@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Wi-Fi, Bluetooth, the devices around you (printers, speakers, computers), and the internet. */
object ConnectHub : Hub {
    override val id = "connect"; override val title = "Connect"; override val glyph = "⇄"
    private val APPS = listOf("com.google.android.apps.chromecast.app", "com.tailscale.ipn", "com.overlook.android.fing",
        "com.hp.printercontrol", "epson.print", "jp.co.canon.bsd.ad.pixmaprint", "com.brother.mfc.brprint", "org.mopria.printplugin",
        "com.sonos.acr2", "com.amazon.dee.app", "com.termux", "com.server.auditor.ssh.client", "com.ubnt.usurvey", "com.speedtest.android",
        "org.zwanoo.android.speedtest")
    private val WORDS = listOf("print", "wifi", "wi-fi", "network", "bluetooth", "home", "cast", "speed", "vpn", "tailscale", "router")

    @Composable override fun summary(vm: LauncherVM): String {
        val ctx = LocalContext.current
        val s by produceState("", vm.resumeTick) {
            value = withContext(Dispatchers.IO) {
                val w = Net.wifi(ctx); val (btOn, bt) = Net.bluetooth(ctx)
                val wl = if (w.connected) "Wi-Fi " + (w.ssid ?: "on") else "No Wi-Fi"
                val bl = if (!btOn) "Bluetooth off" else bt.count { it.connected }.let { if (it > 0) "$it Bluetooth connected" else "Bluetooth on" }
                wl + " · " + bl + if (Net.vpnOn(ctx)) " · VPN" else ""
            }
        }
        return s
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        var tick by remember { mutableIntStateOf(0) }
        // Refresh every few seconds while on screen.
        LaunchedEffect(active) { while (active) { delay(4000); tick++ } }
        val wifi by produceState<WifiNow?>(null, tick, vm.resumeTick) { value = withContext(Dispatchers.IO) { Net.wifi(act) } }
        val bt by produceState<Pair<Boolean, List<BtDevice>>>(false to emptyList(), tick, vm.resumeTick) { value = withContext(Dispatchers.IO) { Net.bluetooth(act) } }
        val vpn by produceState(false, tick) { value = withContext(Dispatchers.IO) { Net.vpnOn(act) } }
        val scope = rememberCoroutineScope()
        var showNearby by remember { mutableStateOf(false) }
        var nearby by remember { mutableStateOf<List<Nearby>>(emptyList()) }
        var publicIp by remember { mutableStateOf<String?>(null) }
        var speed by remember { mutableStateOf<String?>(null) }
        var scanState by remember { mutableStateOf<String?>(null) }
        var hosts by remember { mutableStateOf<List<LanHost>>(emptyList()) }
        var openHost by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(active) { if (active && publicIp == null) publicIp = Net.publicIp() ?: "offline?" }

        HubPage("Connect", gestures) {
            item { AppsRow(vm, id, APPS, WORDS) }

            // ---- Wi-Fi
            item {
                Section("Wi-Fi") { act.tryStart(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)) }
                val w = wifi
                if (w == null) Note("…")
                else if (!w.connected) Text("Not connected", color = Dim, fontSize = 16.sp)
                else {
                    Text((w.ssid ?: "Connected") + "   " + "▂▄▆█".take(Lan.bars(w.rssi)).padEnd(4, '·'), color = Fg, fontSize = 18.sp)
                    Text(listOfNotNull("${w.rssi} dBm", w.linkMbps.takeIf { it > 0 }?.let { "$it Mbps link" }, Lan.band(w.mhz).ifBlank { null }).joinToString(" · "), color = Dim, fontSize = 13.sp)
                    Text(listOfNotNull(w.ip?.let { "This phone $it" }, w.gateway?.let { "router $it" }).joinToString(" · "), color = Dim, fontSize = 13.sp)
                    if (w.ssid == null) LinkRow("Allow location to see the network's name") {
                        act.requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { tick++ }
                    }
                    w.gateway?.let { g -> LinkRow("Router settings (http://$g)") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("http://$g"))) } }
                }
                Row {
                    Chip(if (showNearby) "hide nearby networks" else "nearby networks") {
                        showNearby = !showNearby
                        if (showNearby) {
                            val need = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
                                       else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            act.requestPerms(need) { scope.launch { nearby = withContext(Dispatchers.IO) { Net.nearby(act) } } }
                        }
                    }
                    Chip("internet panel") { act.tryStart(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)) }
                    Chip("hotspot") { act.tryStart(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")) || act.tryStart(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                }
                if (showNearby) {
                    if (nearby.isEmpty()) Note("Scanning… (Android limits how often Wi-Fi can be scanned)")
                    nearby.take(15).forEach { n ->
                        Text("▂▄▆█".take(Lan.bars(n.rssi)).padEnd(4, '·') + "  " + n.ssid + (if (n.secure) "  🔒" else "  open") + "  " + Lan.band(n.mhz),
                            color = if (n.ssid == wifi?.ssid) Sand else Fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }

            // ---- Bluetooth
            item {
                Section("Bluetooth") { act.tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                val (on, devs) = bt
                if (!Net.canBt(act)) LinkRow("Allow Dune to see your Bluetooth devices") { act.requestPerm(Manifest.permission.BLUETOOTH_CONNECT) { tick++ } }
                else if (!on) LinkRow("Bluetooth is off – turn it on") {
                    act.tryStart(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) || act.tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                else if (devs.isEmpty()) Note("No paired devices")
                devs.forEach { d ->
                    Row(Modifier.fillMaxWidth().combinedClickable { act.tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(d.kind, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                        Text(d.name, color = if (d.connected) Fg else Dim, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.connected) Text("connected", color = Sand, fontSize = 12.sp)
                        if (d.name in vm.store.carDevices || d.address in vm.store.carDevices) Text("  car", color = Dim, fontSize = 12.sp)
                    }
                }
            }

            // ---- Devices on this network
            item {
                Section("Devices on this network") {}
                val w = wifi
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(scanState ?: if (hosts.isEmpty()) "scan the network" else "scan again") {
                        val ip = w?.ip
                        if (ip == null || w.connected.not()) { act.toast("Join a Wi-Fi network first"); return@Chip }
                        if (scanState != null) return@Chip
                        scope.launch {
                            scanState = "scanning…"
                            val announced = async { Net.announce(act) }
                            val found = Net.scan(ip, w.prefix) { d, n -> scanState = "scanning… ${d * 100 / n}%" }
                            val names = announced.await()
                            // Merge what devices announced about themselves with what answered the knock.
                            val all = (found.map { it.ip } + names.keys).distinct().map { a ->
                                val h = found.firstOrNull { it.ip == a } ?: LanHost(a, null, emptySet())
                                val svc = names[a].orEmpty()
                                val nm = h.name ?: svc.firstOrNull()?.substringAfter(':')
                                h.copy(name = nm, services = svc)
                            }
                            hosts = all.sortedBy { it.ip.split('.').last().toIntOrNull() ?: 0 }
                            scanState = null
                        }
                    }
                }
                if (hosts.isNotEmpty()) Text("${hosts.size} found · " + hosts.groupBy { it.kind }.entries.joinToString(" · ") { "${it.value.size} ${it.key}" + if (it.value.size > 1) "s" else "" },
                    color = Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            hosts.forEach { h ->
                item(key = "h" + h.ip) { HostRow(vm, h, h.ip == wifi?.gateway, openHost == h.ip) { openHost = if (openHost == h.ip) null else h.ip } }
            }

            // ---- Printers and speakers
            item {
                Section("Printers and speakers") {}
                FlowRow {
                    Chip("🖨 add a printer") { act.tryStart(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                    Chip("🔊 set up a speaker") {
                        val home = listOf("com.google.android.apps.chromecast.app", "com.sonos.acr2", "com.amazon.dee.app").firstOrNull { p -> vm.apps.any { it.pkg == p } }
                        if (home != null) launchPkg(vm, home)
                        else act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.chromecast.app")))
                    }
                    Chip("cast settings") { act.tryStart(Intent(Settings.ACTION_CAST_SETTINGS)) }
                }
                Note("Scan the network to find printers and speakers: tap one for its own setup page.")
            }

            // ---- Internet
            item {
                Section("Internet") {}
                Text("Public address " + (publicIp ?: "…"), color = Fg, fontSize = 15.sp)
                Text(if (vpn) "VPN on (Tailscale or another)" else "No VPN", color = Dim, fontSize = 13.sp)
                vm.l14Online?.let { Text("L14 " + if (it) "● reachable" else "○ not reachable", color = if (it) Sand else Dim, fontSize = 13.sp,
                    modifier = Modifier.combinedClickable { vm.checkL14() }.padding(vertical = 2.dp)) }
                Row {
                    Chip(speed ?: "speed test") {
                        if (speed?.endsWith("…") == true) return@Chip
                        speed = "testing…"
                        scope.launch {
                            val r = Net.speed { speed = it }
                            speed = r?.let { (m, p) -> "%.0f Mbps down · %d ms".format(m, p) } ?: "speed test failed"
                        }
                    }
                    Chip("data usage") { act.tryStart(Intent(Settings.ACTION_DATA_USAGE_SETTINGS)) || act.tryStart(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                }
            }
        }
    }
}

@Composable
private fun Section(t: String, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
        if (t == "Wi-Fi" || t == "Bluetooth") Text("settings", color = Dim, fontSize = 12.sp, modifier = Modifier.combinedClickable(onClick = onSettings).padding(4.dp))
    }
}

@Composable
private fun HostRow(vm: LauncherVM, h: LanHost, isRouter: Boolean, open: Boolean, toggle: () -> Unit) {
    val act = LocalContext.current as MainActivity
    val kind = if (isRouter) "router" else h.kind
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = toggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Lan.icon(kind), fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name ?: kind.replaceFirstChar { it.uppercase() }, color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(h.ip + (if (isRouter) " · your router" else ""), color = Dim, fontSize = 12.sp)
            }
        }
        if (open) Column(Modifier.padding(start = 30.dp).border(1.dp, Faint, RoundedCornerShape(12.dp)).padding(10.dp)) {
            if (h.ports.isNotEmpty()) Text("Open: " + h.ports.sorted().joinToString(", ") { Lan.PORT_NAMES[it] ?: "$it" }, color = Dim, fontSize = 12.sp)
            if (h.services.isNotEmpty()) Text("Announces: " + h.services.joinToString(", ") { it.substringBefore(':').removePrefix("_") }, color = Dim, fontSize = 12.sp)
            FlowRow(Modifier.padding(top = 4.dp)) {
                if (80 in h.ports || 8080 in h.ports || 443 in h.ports || 5000 in h.ports || kind == "printer" || isRouter) {
                    val url = when { 443 in h.ports && 80 !in h.ports -> "https://${h.ip}"; 8080 in h.ports && 80 !in h.ports -> "http://${h.ip}:8080"; else -> "http://${h.ip}" }
                    Chip(if (kind == "printer") "printer's setup page" else "open its web page") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
                if (kind == "printer") Chip("add to Android printing") { act.tryStart(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                if (kind == "speaker") Chip("open Google Home") { launchPkg(vm, "com.google.android.apps.chromecast.app") }
                if (22 in h.ports) Chip("SSH in Termux") { Actions.termux(act, "ssh ${h.ip}") }
                Chip("copy address") {
                    (act.getSystemService(android.content.ClipboardManager::class.java))?.setPrimaryClip(android.content.ClipData.newPlainText("ip", h.ip)); act.toast("Copied ${h.ip}")
                }
            }
        }
    }
}
