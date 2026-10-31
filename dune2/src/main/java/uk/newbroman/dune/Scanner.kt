package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An open port, what usually listens there, and anything it said about itself. */
data class OpenPort(val port: Int, val service: String, val banner: String?, val risk: String?)

/** A saved network scan, for history and "what's new since last time". */
data class ScanRecord(val time: Long, val network: String, val hosts: List<LanHost>)

/** Port knowledge, kept free of Android so it can be unit-tested. */
object Ports {
    val SERVICES = mapOf(
        20 to "FTP data", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP mail", 53 to "DNS", 67 to "DHCP", 69 to "TFTP",
        80 to "web (HTTP)", 81 to "web (alt)", 88 to "Kerberos", 110 to "POP3 mail", 111 to "RPC", 119 to "NNTP", 123 to "NTP time",
        135 to "Windows RPC", 137 to "NetBIOS", 139 to "NetBIOS/SMB", 143 to "IMAP mail", 161 to "SNMP", 389 to "LDAP", 443 to "web (HTTPS)",
        445 to "Windows file sharing (SMB)", 465 to "SMTP (SSL)", 500 to "VPN (IKE)", 515 to "printing (LPD)", 548 to "Apple file sharing",
        554 to "video stream (RTSP)", 587 to "SMTP submission", 631 to "printing (IPP)", 873 to "rsync", 993 to "IMAP (SSL)", 995 to "POP3 (SSL)",
        1080 to "SOCKS proxy", 1194 to "OpenVPN", 1400 to "Sonos", 1433 to "SQL Server", 1723 to "VPN (PPTP)", 1883 to "MQTT (smart home)",
        1900 to "UPnP", 2049 to "NFS", 2375 to "Docker (unsecured)", 3000 to "web app", 3128 to "proxy", 3306 to "MySQL", 3389 to "Remote Desktop",
        3689 to "iTunes sharing", 4433 to "web (alt)", 5000 to "web / UPnP", 5001 to "web (Synology)", 5353 to "mDNS", 5432 to "PostgreSQL",
        5555 to "Android debugging (ADB)", 5900 to "VNC remote screen", 5985 to "Windows remote management", 6379 to "Redis",
        7000 to "AirPlay", 7100 to "AirPlay", 8000 to "web (alt)", 8008 to "Cast", 8009 to "Chromecast", 8080 to "web (8080)",
        8081 to "web (alt)", 8123 to "Home Assistant", 8443 to "web (HTTPS alt)", 8883 to "MQTT (SSL)", 8888 to "web (alt)",
        9000 to "web (alt)", 9100 to "printing (raw)", 9090 to "web admin", 10000 to "Webmin", 27017 to "MongoDB", 32400 to "Plex",
        41641 to "Tailscale", 49152 to "UPnP", 62078 to "iPhone sync",
    )

    /** Why an open port is worth a look, or null if it's ordinary. */
    fun risk(port: Int, outside: Boolean): String? = when (port) {
        23 -> "Telnet sends passwords unencrypted – turn it off"
        21 -> "FTP sends passwords unencrypted"
        445, 139 -> if (outside) "Windows file sharing open to the internet – close this on the router" else "File sharing: make sure it needs a password"
        3389 -> if (outside) "Remote Desktop open to the internet – a common way in for attackers" else "Remote Desktop is on"
        5900 -> "VNC remote screen – check it has a strong password"
        5555 -> "Android debugging over the network – anyone on the network could control that device"
        2375 -> "Docker without security – full control of that machine"
        1900, 5000, 49152 -> if (outside) "UPnP reachable from the internet – turn UPnP off on the router" else null
        3306, 5432, 1433, 27017, 6379 -> "A database is listening on the network"
        161 -> "SNMP can leak device details"
        80, 8080, 81, 8000, 8888, 9000 -> if (outside) "A web page is reachable from the internet (router admin?)" else null
        22 -> if (outside) "SSH reachable from the internet – keys only, no passwords" else null
        else -> null
    }

    /** The commonest ports, for the outside check (whole-range scans over mobile data are slow and unkind). */
    val COMMON = SERVICES.keys.sorted()

    fun report(network: String, hosts: List<LanHost>, ports: Map<String, List<OpenPort>>): String = buildString {
        appendLine("Network scan – $network – " + SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.UK).format(Date()))
        appendLine("${hosts.size} devices\n")
        hosts.forEach { h ->
            appendLine("${h.ip}  ${h.name ?: ""}  [${h.kind}]")
            val ps = ports[h.ip]
            if (ps != null) ps.forEach { p -> appendLine("   ${p.port}  ${p.service}" + (p.banner?.let { "  – $it" } ?: "") + (p.risk?.let { "  ⚠ $it" } ?: "")) }
            else if (h.ports.isNotEmpty()) appendLine("   open: " + h.ports.sorted().joinToString(", ") { "$it ${SERVICES[it] ?: ""}".trim() })
        }
    }
}

object Scanner {
    /** Every port 1–65535 on one device in the network, about a minute. Results arrive as they're found. */
    suspend fun allPorts(host: String, onOpen: (Int) -> Unit, progress: (Int) -> Unit): List<OpenPort> = coroutineScope {
        val gate = Semaphore(256)
        val open = java.util.concurrent.ConcurrentSkipListSet<Int>()
        var done = 0
        val chunk = 512
        (1..65535).chunked(chunk).forEach { block ->
            block.map { port ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (connects(host, port, 250)) { open += port; withContext(Dispatchers.Main) { onOpen(port) } }
                    }
                }
            }.awaitAll()
            done += block.size
            withContext(Dispatchers.Main) { progress(done * 100 / 65535) }
        }
        describe(host, open.toList(), outside = false)
    }

    /** The common ports on your own public address – what someone on the internet would see. */
    suspend fun outside(publicIp: String, progress: (Int) -> Unit): List<OpenPort> = coroutineScope {
        val gate = Semaphore(64)
        var done = 0
        val open = Ports.COMMON.map { port ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val ok = connects(publicIp, port, 1500)
                    synchronized(this@coroutineScope) { done++ }
                    withContext(Dispatchers.Main) { progress(done * 100 / Ports.COMMON.size) }
                    if (ok) port else null
                }
            }
        }.awaitAll().filterNotNull()
        describe(publicIp, open, outside = true)
    }

    private suspend fun describe(host: String, ports: List<Int>, outside: Boolean): List<OpenPort> = coroutineScope {
        ports.sorted().map { p -> async(Dispatchers.IO) { OpenPort(p, Ports.SERVICES[p] ?: "unknown", banner(host, p), Ports.risk(p, outside)) } }.awaitAll()
    }

    private fun connects(host: String, port: Int, ms: Int) = try { Socket().use { it.connect(InetSocketAddress(host, port), ms) }; true } catch (e: Exception) { false }

    /** What the service says first (SSH/FTP/mail greet you), or the web server's name. */
    private fun banner(host: String, port: Int): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 800); s.soTimeout = 900
            if (port in listOf(80, 81, 8000, 8008, 8080, 8081, 8123, 8888, 9000, 3000, 5000, 32400)) {
                s.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
                val head = s.getInputStream().bufferedReader().readText().take(2000)
                Regex("(?im)^server:\\s*(.+)$").find(head)?.groupValues?.get(1)?.trim()
                    ?: head.lineSequence().firstOrNull()?.trim()
            } else {
                val buf = ByteArray(160); val n = s.getInputStream().read(buf)
                if (n > 0) String(buf, 0, n).lineSequence().first().filter { it.isLetterOrDigit() || it in " ._-/()" }.trim().ifBlank { null } else null
            }
        }
    }.getOrNull()?.take(70)

    // ---- history

    private fun file(ctx: Context) = File(ctx.filesDir, "scans.json")

    fun history(ctx: Context): List<ScanRecord> = runCatching {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val hs = o.getJSONArray("h")
            ScanRecord(o.getLong("t"), o.getString("n"), (0 until hs.length()).map { j ->
                val h = hs.getJSONObject(j); val ps = h.getJSONArray("p")
                LanHost(h.getString("ip"), h.optString("name").ifBlank { null }, (0 until ps.length()).map { ps.getInt(it) }.toSet())
            })
        }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, network: String, hosts: List<LanHost>) {
        val l = (listOf(ScanRecord(System.currentTimeMillis(), network, hosts)) + history(ctx)).take(30)
        val a = JSONArray()
        l.forEach { r ->
            val hs = JSONArray()
            r.hosts.forEach { h -> hs.put(JSONObject().put("ip", h.ip).put("name", h.name ?: "").put("p", JSONArray(h.ports.toList()))) }
            a.put(JSONObject().put("t", r.time).put("n", r.network).put("h", hs))
        }
        runCatching { file(ctx).writeText(a.toString()) }
    }

    /** Devices in [now] not seen in the last scan of the same network. */
    fun newSince(prev: ScanRecord?, now: List<LanHost>): Set<String> =
        if (prev == null) emptySet() else now.map { it.ip }.toSet() - prev.hosts.map { it.ip }.toSet()

    fun shareReport(ctx: Context, text: String) = ctx.tryStart(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Network scan").putExtra(Intent.EXTRA_TEXT, text), "Share report")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
