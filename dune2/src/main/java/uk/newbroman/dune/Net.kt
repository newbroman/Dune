package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** A device found on the local network. */
data class LanHost(val ip: String, val name: String?, val ports: Set<Int>, val services: Set<String> = emptySet()) {
    val kind get() = Lan.kind(ports, services, name)
}

/** Network maths and device guessing, free of Android so they can be unit-tested. */
object Lan {
    val PORTS = listOf(80, 443, 22, 445, 139, 631, 9100, 515, 8009, 8008, 554, 3389, 5000, 8080, 1400, 7000, 53, 23)
    val PORT_NAMES = mapOf(80 to "web", 443 to "https", 22 to "SSH", 445 to "files (SMB)", 139 to "NetBIOS", 631 to "printing (IPP)",
        9100 to "printing (raw)", 515 to "printing (LPD)", 8009 to "Chromecast", 8008 to "Cast", 554 to "video stream (RTSP)",
        3389 to "Remote Desktop", 5000 to "web/UPnP", 8080 to "web (8080)", 1400 to "Sonos", 7000 to "AirPlay", 53 to "DNS", 23 to "Telnet")

    /** Every host address in [ip]'s network, capped to the /24 around it (bigger networks would take minutes). */
    fun hosts(ip: String, prefix: Int): List<String> {
        val p = ip.split('.').map { it.toInt() }
        val n = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
        val bits = prefix.coerceIn(24, 30)
        val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
        val net = n and mask; val size = 1 shl (32 - bits)
        return (1 until size - 1).map { net + it }.filter { it != n }
            .map { "${it ushr 24 and 255}.${it ushr 16 and 255}.${it ushr 8 and 255}.${it and 255}" }
    }

    /** What sort of device it probably is, from its open ports, mDNS services and name. */
    fun kind(ports: Set<Int>, services: Set<String>, name: String?): String {
        val n = name?.lowercase().orEmpty(); val s = services.joinToString(" ")
        return when {
            "_ipp" in s || "_printer" in s || "_pdl" in s || 631 in ports || 9100 in ports || 515 in ports ||
                listOf("print", "hp", "epson", "canon", "brother", "laserjet").any { n.contains(it) } -> "printer"
            "_googlecast" in s || "_spotify" in s || "_raop" in s || "_airplay" in s || "_sonos" in s || 8009 in ports || 1400 in ports ||
                listOf("chromecast", "nest", "sonos", "echo", "speaker", "homepod").any { n.contains(it) } -> "speaker"
            554 in ports || listOf("cam", "ring", "doorbell").any { n.contains(it) } -> "camera"
            22 in ports || 445 in ports || 3389 in ports || "_ssh" in s || "_smb" in s || listOf("thinkpad", "laptop", "desktop", "pc", "mac").any { n.contains(it) } -> "computer"
            listOf("android", "iphone", "galaxy", "pixel", "phone").any { n.contains(it) } -> "phone"
            53 in ports -> "router"
            else -> "device"
        }
    }
    fun icon(kind: String) = when (kind) {
        "printer" -> "🖨"; "speaker" -> "🔊"; "camera" -> "📷"; "computer" -> "💻"; "phone" -> "📱"; "router" -> "🌐"; else -> "◦"
    }

    /** Signal bars (0–4) from dBm. */
    fun bars(rssi: Int) = when { rssi >= -55 -> 4; rssi >= -66 -> 3; rssi >= -77 -> 2; rssi >= -88 -> 1; else -> 0 }
    fun band(mhz: Int) = when { mhz in 2400..2500 -> "2.4 GHz"; mhz in 4900..5900 -> "5 GHz"; mhz in 5925..7125 -> "6 GHz"; else -> "" }
    fun mbps(bytes: Long, ms: Long) = if (ms <= 0) 0.0 else bytes * 8.0 / ms / 1000.0
}

data class WifiNow(val ssid: String?, val rssi: Int, val linkMbps: Int, val mhz: Int, val ip: String?, val prefix: Int, val gateway: String?, val connected: Boolean)
data class BtDevice(val name: String, val address: String, val connected: Boolean, val kind: String)
data class Nearby(val ssid: String, val rssi: Int, val secure: Boolean, val mhz: Int)

object Net {
    @SuppressLint("MissingPermission")
    fun wifi(ctx: Context): WifiNow {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val nw = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(nw)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val lp = cm.getLinkProperties(nw)
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        @Suppress("DEPRECATION")
        // NetworkCapabilities hides the network name; WifiManager still gives it to apps allowed precise location.
        val fromWm = if (hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo else null
        val info: WifiInfo? = fromWm ?: (if (Build.VERSION.SDK_INT >= 31) caps?.transportInfo as? WifiInfo else null)
            ?: ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return WifiNow(ssid, info?.rssi ?: -127, info?.linkSpeed ?: 0, info?.frequency ?: 0,
            v4?.address?.hostAddress, v4?.prefixLength ?: 24, gw, onWifi)
    }

    fun vpnOn(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun nearby(ctx: Context): List<Nearby> = runCatching {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        runCatching { wm.startScan() }   // Android throttles this; results may be a minute old
        wm.scanResults.filter { !it.SSID.isNullOrBlank() }.groupBy { it.SSID }.map { (s, l) ->
            val best = l.maxBy { it.level }
            Nearby(s, best.level, !best.capabilities.contains("[ESS]") || best.capabilities.contains("WPA") || best.capabilities.contains("WEP") || best.capabilities.contains("SAE"), best.frequency)
        }.sortedByDescending { it.rssi }
    }.getOrDefault(emptyList())

    fun canBt(ctx: Context) = Build.VERSION.SDK_INT < 31 || hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    @SuppressLint("MissingPermission")
    fun bluetooth(ctx: Context): Pair<Boolean, List<BtDevice>> {
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false to emptyList()
        if (!canBt(ctx)) return ad.isEnabled to emptyList()
        val devs = runCatching { ad.bondedDevices }.getOrNull().orEmpty().map { d ->
            // isConnected is hidden but has worked on every Android since 4.4
            val on = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
            val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
            val kind = when (major) {
                android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "🎧"
                android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "💻"
                android.bluetooth.BluetoothClass.Device.Major.PHONE -> "📱"
                android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "⌚"
                android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "⌨"
                android.bluetooth.BluetoothClass.Device.Major.IMAGING -> "🖨"
                else -> "◦"
            }
            BtDevice(runCatching { d.name }.getOrNull() ?: d.address, d.address, on, kind)
        }.sortedWith(compareBy({ !it.connected }, { it.name.lowercase() }))
        return ad.isEnabled to devs
    }

    /** The internet as seen from outside: public IP and provider. */
    suspend fun publicIp(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://ipinfo.io/json").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            val j = org.json.JSONObject(c.inputStream.bufferedReader().readText())
            j.optString("ip") + (j.optString("org").takeIf { it.isNotBlank() }?.let { " · " + it.substringAfter(' ') } ?: "")
        }.getOrNull()
    }

    /** Download speed (Mbps) and latency (ms), using Cloudflare's speed-test server. */
    suspend fun speed(onProgress: (String) -> Unit): Pair<Double, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val addr = InetSocketAddress(InetAddress.getByName("speed.cloudflare.com"), 443)   // look up the name first, so ping is just the trip
            val t0 = System.currentTimeMillis()
            Socket().use { it.connect(addr, 4000) }
            val ping = System.currentTimeMillis() - t0
            val c = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 15000
            val start = System.currentTimeMillis(); var got = 0L
            c.inputStream.use { inp ->
                val buf = ByteArray(64 * 1024); var n: Int; var last = 0L
                while (inp.read(buf).also { n = it } > 0) {
                    got += n
                    val el = System.currentTimeMillis() - start
                    if (el - last > 400) { last = el; withContext(Dispatchers.Main) { onProgress("%.0f Mbps…".format(Lan.mbps(got, el))) } }
                    if (el > 12_000) break
                }
            }
            Lan.mbps(got, System.currentTimeMillis() - start) to ping
        }.getOrNull()
    }

    /** Knock on each address: a reply *or a refusal* means something is there. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scanIo = Dispatchers.IO.limitedParallelism(64)   // its own threads, so the rest of Dune isn't starved

    suspend fun scan(ip: String, prefix: Int, progress: (Int, Int) -> Unit): List<LanHost> = coroutineScope {
        val targets = Lan.hosts(ip, prefix)
        val gate = Semaphore(64)
        var done = 0
        val found = targets.map { host ->
            async(scanIo) {
                gate.withPermit {
                    var alive = false; val open = HashSet<Int>()
                    // Quick check on a few ports first; only look closer at hosts that answer.
                    for (port in listOf(80, 443, 22, 445, 8009, 631, 9100, 53, 7000, 1400)) {
                        when (knock(host, port, 220)) { 1 -> { alive = true; open += port }; 0 -> alive = true }
                    }
                    if (alive) Lan.PORTS.filter { it !in open }.forEach { if (knock(host, it, 250) == 1) open += it }
                    synchronized(this@coroutineScope) { done++; progress(done, targets.size) }
                    if (alive) LanHost(host, null, open) else null
                }
            }
        }.awaitAll().filterNotNull()
        // Names, from the router's DNS where it has them.
        // A blocking lookup can't be cancelled, so each runs on its own and we stop waiting after 1.5 s.
        val lookups = found.map { h -> h to async(scanIo) { runCatching { InetAddress.getByName(h.ip).canonicalHostName }.getOrNull() } }
        val deadline = System.currentTimeMillis() + 1500
        lookups.map { (h, d) ->
            val n = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1)) { d.await() }
            if (n == null) d.cancel()
            h.copy(name = n?.takeIf { it != h.ip }?.removeSuffix(".lan")?.removeSuffix(".home")?.removeSuffix(".local"))
        }
    }

    /** 1 open, 0 refused (but alive), -1 nothing there. */
    private fun knock(host: String, port: Int, ms: Int): Int = try {
        Socket().use { it.connect(InetSocketAddress(host, port), ms) }; 1
    } catch (e: ConnectException) { if (e.message?.contains("ECONNREFUSED") == true) 0 else -1 } catch (e: Exception) { -1 }

    /** Devices that announce themselves (printers, Cast speakers, AirPlay, Spotify Connect, SSH…), by IP. */
    suspend fun announce(ctx: Context, seconds: Long = 6): Map<String, Set<String>> {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return emptyMap()
        val types = listOf("_ipp._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_googlecast._tcp", "_spotify-connect._tcp",
            "_airplay._tcp", "_raop._tcp", "_sonos._tcp", "_ssh._tcp", "_smb._tcp", "_http._tcp")
        val found = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
        val pending = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listeners = types.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) { pending.offer(s) }
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }.also { l -> runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) } }
        }
        // Resolve one at a time (Android only allows one resolve in flight on older versions).
        try { withContext(Dispatchers.IO) {
            val end = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < end && kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false) {
                val s = pending.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val lock = java.util.concurrent.CountDownLatch(1)
                val rl = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, e: Int) { lock.countDown() }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val v4 = (if (Build.VERSION.SDK_INT >= 34) si.hostAddresses else listOfNotNull(si.host)).firstOrNull { it is Inet4Address }
                        val ip = v4?.hostAddress
                        if (ip != null) found.getOrPut(ip) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                            .add(si.serviceType.trim('.').substringBefore("._tcp") + ":" + si.serviceName)
                        lock.countDown()
                    }
                }
                @Suppress("DEPRECATION") runCatching { nsd.resolveService(s, rl) }.onFailure { lock.countDown() }
                if (!lock.await(2, java.util.concurrent.TimeUnit.SECONDS) && Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(rl) }
            }
        } } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }   // also when the page goes away mid-scan
        }
        return found
    }

}
