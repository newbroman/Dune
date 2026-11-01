@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * House: heating (tado°), plugs and lights (Tapo, Kasa), scenes, and cameras – on one page. Google
 * Home, Alexa, EZVIZ and Smart Life are a tap away in the apps row; their devices can't be
 * switched by other apps.
 */
object HouseHub : Hub {
    override val id = "house"; override val title = "House"; override val glyph = "⌂"
    private val APPS = listOf("com.google.android.apps.chromecast.app", "com.amazon.dee.app", "com.tado", "com.tplink.iot",
        "com.tplink.kasa_android", "com.tuya.smartlife", "com.tuya.smart", "com.ezviz", "com.videogo",
        "com.wizconnected.wiz2", "com.philips.lighting.hue2", "com.govee.home", "com.lifx.lifx", "com.nest.android",
        "com.ringapp", "com.samsung.android.oneconnect", "com.theswitchbot.switchbot", "com.meross.meross",
        "com.netatmo.netatmo", "com.oceanwing.battery.cam", "com.assaabloy.yale", "uk.co.centrica.hive.v6")
    private val WORDS = listOf("google home", "alexa", "tado", "tapo", "kasa", "smart life", "tuya", "ezviz", "wiz ", "philips hue",
        "govee", "lifx", "nest", "smartthings", "switchbot", "meross", "netatmo", "eufy", "thermostat", "doorbell")
    private const val EZVIZ = "com.ezviz"

    private fun deg(d: Double?) = d?.let { String.format(Locale.UK, "%.1f°", it) } ?: "–"

    @Composable override fun summary(vm: LauncherVM): String? {
        val ctx = LocalContext.current
        val t = remember(vm.resumeTick) { Tado.cached(ctx) }
        val plugs = remember(vm.resumeTick) { Plugs.saved(ctx) }
        val states = remember(vm.resumeTick) { Plugs.lastStates(ctx) }
        if (t == null && plugs.isEmpty()) return null
        val warm = t?.zones?.filter { it.type == "HEATING" && it.inside != null }?.joinToString(" · ") { "${it.name} ${deg(it.inside)}" }
        val on = plugs.count { states[it.ip] == true }
        return listOfNotNull(warm?.ifBlank { null }, if (t?.away == true) "away" else null,
            if (plugs.isNotEmpty()) "$on of ${plugs.size} on" else null).joinToString("  ·  ")
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        val scope = rememberCoroutineScope()
        var tick by remember { mutableIntStateOf(0) }

        // ---- tado
        var tado by remember { mutableStateOf(Tado.cached(act)) }
        var tadoMsg by remember { mutableStateOf<String?>(null) }
        var login by remember { mutableStateOf<Tado.Login?>(null) }
        var linked by remember { mutableStateOf(Tado.linked(act)) }
        fun loadTado(force: Boolean) = scope.launch {
            if (!Tado.linked(act)) return@launch
            runCatching { Tado.state(act, force) }.onSuccess { tado = it; tadoMsg = null }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; tadoMsg = it.message; linked = Tado.linked(act) }
        }
        // ---- plugs
        var plugs by remember { mutableStateOf(Plugs.saved(act)) }
        var plugMsg by remember { mutableStateOf<String?>(null) }
        var hasAccount by remember { mutableStateOf(Plugs.account(act) != null) }
        fun loadPlugs() = scope.launch { if (plugs.isNotEmpty() && hasAccount) plugs = Plugs.readAll(act, plugs) }
        // ---- scenes
        var scenes by remember { mutableStateOf(Scenes.all(act)) }
        var saving by remember { mutableStateOf(false) }
        var sceneMsg by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(active) { if (active) { loadTado(false); loadPlugs() } }
        // tado sign-in: poll until confirmed on tado's page
        LaunchedEffect(login) {
            val l = login ?: return@LaunchedEffect
            var wait = l.interval * 1000L; var misses = 0
            while (System.currentTimeMillis() < l.expires) {
                delay(wait)
                val done = try { Tado.pollLogin(act, l) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Tado.SlowDown) { wait += 5000; false }
                    catch (e: java.io.IOException) { if (++misses > 5) { tadoMsg = "No connection to tado"; login = null; return@LaunchedEffect }; false }
                    catch (e: Exception) { tadoMsg = e.message; login = null; return@LaunchedEffect }
                if (done) { login = null; linked = true; tadoMsg = null; loadTado(true); return@LaunchedEffect }
            }
            login = null; tadoMsg = "The sign-in code ran out – try again"
        }

        HubPage("House", gestures, trailing = {
            Text("↻", color = Dim, fontSize = 18.sp, modifier = Modifier.combinedClickable { loadTado(true); loadPlugs() }.padding(8.dp))
        }) {
            item { HomeApps(vm) }

            // ---------------- scenes
            item {
                Section("Scenes")
                FlowRow {
                    scenes.forEach { s ->
                        Chip("▶ " + s.name, onLong = { scenes = scenes - s; Scenes.save(act, scenes); act.toast("“${s.name}” removed") }) {
                            sceneMsg = "Running “${s.name}”…"
                            scope.launch {
                                val r = Scenes.run(act, s)
                                sceneMsg = r?.let { "Done, except: $it" } ?: "“${s.name}” done"
                                tado = Tado.cached(act); plugs = Plugs.readAll(act, plugs)
                            }
                        }
                    }
                    Chip("＋ save how things are now") { saving = !saving }
                }
                sceneMsg?.let { Note(it) }
                if (scenes.isEmpty() && !saving) Note("Set the heating and plugs how you want them, then save it as a scene – e.g. “Night”, “Away”, “Film”. Long-press a scene to remove it.")
                if (saving) SceneSaver(tado, plugs) { s -> if (s != null) { scenes = scenes.filter { it.name != s.name } + s; Scenes.save(act, scenes) }; saving = false }
            }

            // ---------------- heating
            item {
                Section("Heating · tado°")
                when {
                    login != null -> {
                        val l = login!!
                        Text("Code: ${l.userCode}", color = Sand, fontSize = 22.sp)
                        Note("Confirm on tado's page (it opened in your browser). Dune is waiting…")
                        LinkRow("Open tado's page again") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }
                        LinkRow("Cancel") { login = null }
                    }
                    !linked -> {
                        LinkRow("Sign in to tado°") {
                            scope.launch {
                                runCatching { Tado.startLogin() }.onSuccess { l -> login = l; act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }
                                    .onFailure { tadoMsg = it.message }
                            }
                        }
                        Note("You confirm on tado's own website; Dune never sees your password and keeps only a sign-in token, encrypted on this phone.")
                    }
                    tado == null -> Note(tadoMsg ?: "Loading…")
                    else -> {
                        val t = tado!!
                        t.zones.forEach { z -> ZoneRow(z) { change ->
                            scope.launch {
                                runCatching { change() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; tadoMsg = it.message }
                                tado = Tado.cached(act)
                            }
                        } }
                        FlowRow(Modifier.padding(top = 4.dp)) {
                            Chip(if (t.away == true) "● away" else "away") { scope.launch { runCatching { Tado.presence(act, true) }.onFailure { tadoMsg = it.message }; tado = Tado.cached(act) } }
                            Chip(if (t.away == false) "● home" else "home") { scope.launch { runCatching { Tado.presence(act, false) }.onFailure { tadoMsg = it.message }; tado = Tado.cached(act) } }
                            Chip("auto (geofence)") { scope.launch { runCatching { Tado.presence(act, null) }.onFailure { tadoMsg = it.message }; tado = Tado.cached(act) } }
                        }
                        val mins = ((System.currentTimeMillis() - t.at) / 60_000).toInt()
                        Note(listOfNotNull(if (mins < 1) "updated just now" else "updated $mins min ago", t.callsLeft?.let { "$it tado calls left today" }).joinToString(" · ") +
                            " – pull ↻ to refresh (tado allows about 100 calls a day)")
                        tadoMsg?.let { Note("⚠ $it") }
                        Text("sign out of tado", color = Faint, fontSize = 12.sp, modifier = Modifier.combinedClickable { Tado.unlink(act); linked = false; tado = null }.padding(vertical = 6.dp))
                    }
                }
                if (login == null && (tado == null || !linked)) tadoMsg?.let { Note("⚠ $it") }
            }

            // ---------------- plugs and lights
            item {
                Section("Plugs and lights · Tapo, Kasa")
                if (!hasAccount) {
                    var u by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
                    Note("Dune talks to Tapo and Kasa devices directly over your Wi-Fi. It needs the TP-Link account you use in the Tapo or Kasa app (kept encrypted on this phone; sent only to your own devices).")
                    OutlinedTextField(u, { u = it }, label = { Text("TP-Link email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(p, { p = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    LinkRow("Save and find my devices") {
                        if (u.isBlank() || p.isBlank()) { act.toast("Email and password, please"); return@LinkRow }
                        Plugs.setAccount(act, u, p); hasAccount = true; tick++
                    }
                } else {
                    var finding by remember { mutableStateOf<String?>(null) }
                    fun find() = scope.launch {
                        finding = "Looking across your Wi-Fi…"
                        runCatching { Plugs.discover(act) { d, n -> finding = "Looking across your Wi-Fi… ${d * 100 / n}%" } }
                            .onSuccess { plugs = it; plugMsg = if (it.isEmpty()) "No Tapo or Kasa devices found on this Wi-Fi" else null }
                            .onFailure { plugMsg = it.message }
                        finding = null
                    }
                    LaunchedEffect(tick) { if (tick > 0 && plugs.isEmpty()) find() }
                    plugs.forEach { p -> PlugRow(p, forget = { Plugs.forget(act, p.ip); plugs = plugs.filter { it.ip != p.ip }; act.toast("${p.name} removed") }) { on -> scope.launch { val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Plugs.set(act, p, on) }; plugs = plugs.map { if (it.ip == p.ip) r else it } } } }
                    finding?.let { Note(it) } ?: Row {
                        Chip(if (plugs.isEmpty()) "find my devices" else "find devices again") { find() }
                        Chip("change account") { Plugs.setAccount(act, null, null); hasAccount = false }
                    }
                    plugMsg?.let { Note(it) }
                    if (plugs.isNotEmpty()) Note("Long-press a device's name to remove it from this list.")
                }
            }

            // ---------------- cameras
            item {
                Section("Cameras · EZVIZ")
                LinkRow("Open EZVIZ (live view, doorbell, recordings)") {
                    val pkg = listOf(EZVIZ, "com.videogo").firstOrNull { p -> vm.apps.any { it.pkg == p } }
                    if (pkg != null) launchPkg(vm, pkg) else act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$EZVIZ")), "EZVIZ isn't installed")
                }
                Note("Google Home, Alexa, WiZ and the rest open from the buttons at the top.")
            }
        }
    }

    /** Your smart-home apps as big buttons. Long-press one to take it off; ＋ adds any app. */
    @Composable private fun HomeApps(vm: LauncherVM) {
        val ctx = LocalContext.current
        var edit by remember { mutableIntStateOf(0) }
        var picking by remember { mutableStateOf(false) }
        val apps by androidx.compose.runtime.produceState<List<HubApp>>(emptyList(), vm.apps, edit) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { hubApps(ctx, vm, APPS, WORDS, id) }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            apps.forEach { a ->
                Column(Modifier.width(76.dp).combinedClickable(onClick = { launchPkg(vm, a.pkg) },
                    onLongClick = { HubAppEdits.remove(vm.store, id, a.pkg); edit++; ctx.toast("${a.label} taken off – ＋ to put it back") })
                    .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    a.icon?.let { androidx.compose.foundation.Image(it, a.label, Modifier.size(44.dp)) } ?: Text("⌂", color = Dim, fontSize = 30.sp)
                    Text(a.label, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Column(Modifier.width(76.dp).combinedClickable { picking = true }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("＋", color = Dim, fontSize = 30.sp)
                Text("add app", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (picking) AppPicker(vm, exclude = apps.map { it.pkg }.toSet(), onPick = { HubAppEdits.add(vm.store, id, it); edit++ }, onDismiss = { picking = false })
    }

    @Composable private fun Section(t: String) = Text(t.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))

    /** One room: temperature, humidity, what it's set to; − / + nudge the target (sent after a pause, to save tado calls). */
    @Composable private fun ZoneRow(z: Tado.Zone, run: (suspend () -> Unit) -> Unit) {
        val act = LocalContext.current
        val scope = rememberCoroutineScope()
        var pending by remember(z.id, z.target) { mutableStateOf<Double?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        fun nudge(d: Double) {
            val base = pending ?: z.target ?: 20.0
            pending = ((base + d) * 2).let { Math.round(it) / 2.0 }.coerceIn(5.0, 25.0)
            job?.cancel(); job = scope.launch {
                delay(1800); val v = pending
                if (v != null) run { try { Tado.set(act, z, v) } finally { pending = null } }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(z.name + if (z.type == "HOT_WATER") " (hot water)" else "", color = Fg, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(z.inside?.let { "now ${deg(it)}" }, z.humidity?.let { "${it.toInt()}% humid" },
                        z.heatingPct?.takeIf { it > 0 }?.let { "heating $it%" }, if (z.manual) "manual" else "schedule").joinToString(" · "),
                        color = Dim, fontSize = 12.sp)
                }
                if (z.type != "HOT_WATER") {
                    Text("−", color = Dim, fontSize = 22.sp, modifier = Modifier.combinedClickable { nudge(-0.5) }.padding(horizontal = 10.dp, vertical = 4.dp))
                    Text(if (!z.on && pending == null) "off" else deg(pending ?: z.target), color = if (pending != null) Sand else if (z.on) Fg else Dim,
                        fontSize = 18.sp, modifier = Modifier.padding(horizontal = 2.dp))
                    Text("+", color = Dim, fontSize = 22.sp, modifier = Modifier.combinedClickable { nudge(0.5) }.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Row {
                if (z.on) Text("turn off", color = Faint, fontSize = 12.sp, modifier = Modifier.combinedClickable { run { Tado.set(act, z, null) } }.padding(end = 14.dp, top = 2.dp, bottom = 2.dp))
                else if (z.type == "HOT_WATER") Text("turn on", color = Faint, fontSize = 12.sp, modifier = Modifier.combinedClickable { run { Tado.set(act, z, z.target, on = true) } }.padding(end = 14.dp, top = 2.dp, bottom = 2.dp))
                if (z.manual) Text("back to schedule", color = Faint, fontSize = 12.sp, modifier = Modifier.combinedClickable { run { Tado.resume(act, z) } }.padding(top = 2.dp, bottom = 2.dp))
            }
        }
    }

    @Composable private fun PlugRow(p: Plug, forget: () -> Unit, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = forget)) {
                Text((if (p.bulb) "💡 " else "") + p.name, color = Fg, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.error?.let { "⚠ $it" } ?: listOfNotNull(p.model.ifBlank { null }, p.watts?.let { String.format(Locale.UK, "%.0f W", it) }).joinToString(" · "),
                    color = if (p.error != null) Color(0xFFFF8A80) else Dim, fontSize = 12.sp, maxLines = 1)
            }
            Switch(checked = p.on == true, onCheckedChange = { set(it) }, enabled = p.error == null || p.on != null)
        }
    }

    /** Pick what goes in a new scene; each item is saved as it is now. */
    @Composable private fun SceneSaver(t: Tado.State?, plugs: List<Plug>, done: (Scene?) -> Unit) {
        var name by remember { mutableStateOf("") }
        val picked = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
        val items = buildList {
            t?.zones?.forEach { z -> add(Triple("room:${z.id}", z.name + ": " + when { z.manual && !z.on -> "off"; z.manual && z.target == null -> "on"; z.manual -> deg(z.target); else -> "schedule" },
                SceneAct("room", z.id.toString(), when { z.manual && !z.on -> "off"; z.manual && z.target == null -> "on"; z.manual -> z.target.toString(); else -> "auto" }))) }
            // Only a presence you set by hand (not tado's geofencing) belongs in a scene.
            if (t?.locked == true) t.away?.let { a -> add(Triple("away", "tado: " + if (a) "away" else "home", SceneAct("away", "", if (a) "away" else "home"))) }
            plugs.filter { it.on != null }.forEach { p -> add(Triple("plug:${p.ip}", p.name + ": " + if (p.on == true) "on" else "off", SceneAct("plug", p.ip, if (p.on == true) "on" else "off"))) }
        }
        Column(Modifier.padding(top = 6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Scene name (e.g. Night)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (items.isEmpty()) Note("Nothing to save yet – sign in to tado or find your plugs first.")
            items.forEach { (key, label, _) ->
                Row(Modifier.fillMaxWidth().combinedClickable { picked[key] = !(picked[key] ?: true) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(picked[key] ?: true, { picked[key] = it })
                    Text(label, color = Fg, fontSize = 15.sp)
                }
            }
            Row {
                Chip("save scene") {
                    val acts = items.filter { picked[it.first] ?: true }.map { it.third }
                    if (name.isBlank() || acts.isEmpty()) return@Chip
                    done(Scene(name.trim(), acts))
                }
                Chip("cancel") { done(null) }
            }
        }
    }
}
