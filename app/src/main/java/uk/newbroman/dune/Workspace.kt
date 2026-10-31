@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.Manifest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- Inbox

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 24 * 60 -> "${m / 60}h"; else -> "${m / 1440}d" }
}

/** Everything that has come in – messages, email, the rest – in one stream, newest first. */
@Composable
fun InboxPage(vm: LauncherVM, gestures: NestedScrollConnection) {
    val act = LocalContext.current as MainActivity
    val items by NotifService.items.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var replying by remember { mutableStateOf<String?>(null) }
    val shown = items.filter { filter == "all" || it.kind == filter }
    SidePage("Inbox", gestures) {
        item {
            FlowRow {
                listOf("all" to "All ${items.size}", "message" to "Messages", "email" to "Email", "other" to "Other").forEach { (k, l) ->
                    Text(l, color = if (filter == k) Sand else Dim, fontSize = 14.sp,
                        modifier = Modifier.combinedClickable { filter = k }.padding(end = 16.dp, bottom = 8.dp))
                }
            }
            if (Brain.ready(act)) Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(if (vm.aiBusy) "…" else "✦ Catch me up") {
                    vm.askAi("Catch me up: summarise my messages and emails from the last day – who wants what, and anything I need to reply to or do. Group by person.",
                        onAnswer = { vm.inboxAnswer = it })
                }
                if (items.isNotEmpty()) Chip("clear all") { NotifService.instance?.cancelAllNotifications() }
            } else if (items.isNotEmpty()) Chip("clear all") { NotifService.instance?.cancelAllNotifications() }
            vm.inboxAnswer?.let {
                Text(it, color = Fg, fontSize = 15.sp, modifier = Modifier.fillMaxWidth()
                    .combinedClickable { vm.inboxAnswer = null }.padding(vertical = 8.dp))
            }
            if (shown.isEmpty()) Text(if (NotifService.instance == null) "Allow notification access in settings to see them here" else "Nothing here",
                color = Faint, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        shown.forEach { n -> item(key = n.key) { InboxRow(vm, n, replying == n.key) { replying = if (replying == n.key) null else n.key } } }
    }
}

@Composable
private fun InboxRow(vm: LauncherVM, n: NotifItem, open: Boolean, toggleReply: () -> Unit) {
    val act = LocalContext.current as MainActivity
    var text by remember(n.key) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vm.labelFor(n.pkg), color = if (n.kind == "other") Dim else Sand, fontSize = 12.sp)
            Text("  ·  " + ago(n.time), color = Faint, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (n.reply != null) Text(if (open) "cancel" else "reply", color = Dim, fontSize = 13.sp,
                modifier = Modifier.combinedClickable(onClick = toggleReply).padding(horizontal = 8.dp, vertical = 2.dp))
            Text("✕", color = Dim, fontSize = 14.sp,
                modifier = Modifier.combinedClickable { NotifService.instance?.cancelNotification(n.key) }.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Column(Modifier.fillMaxWidth().combinedClickable { Actions.openNotification(act, n) }) {
            if (n.title.isNotBlank()) Text(n.title, color = Fg, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.text.isNotBlank()) Text(n.text, color = Dim, fontSize = 14.sp, maxLines = if (open) 8 else 3, overflow = TextOverflow.Ellipsis)
        }
        if (open) Row(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Faint, RoundedCornerShape(20.dp))
            .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val send = {
                if (text.isNotBlank()) {
                    if (NotifService.sendReply(act, n, text.trim())) { act.toast("Sent"); text = ""; toggleReply() } else act.toast("Couldn't send – open the app instead")
                }
            }
            BasicTextField(text, { text = it }, textStyle = TextStyle(color = Fg, fontSize = 16.sp), cursorBrush = SolidColor(Sand),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text("Reply to ${n.title}", color = Faint, fontSize = 16.sp); inner() } })
            Text("🎤", fontSize = 18.sp, modifier = Modifier.combinedClickable {
                act.listen("Reply to ${n.title}") { it?.let { t -> text = t } }
            }.padding(horizontal = 8.dp, vertical = 8.dp))
            Text("send", color = Sand, fontSize = 15.sp, modifier = Modifier.combinedClickable { send() }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- Command bar results

/** People and messages matching the query – looked up off the main thread (see [rememberLookups]). */
data class Lookups(val people: List<Person> = emptyList(), val messages: List<InboxLog.Entry> = emptyList())

@Composable
fun rememberLookups(query: String): Lookups {
    val ctx = LocalContext.current
    val q = query.trim()
    val r by androidx.compose.runtime.produceState(Lookups(), q) {
        if (q.length < 2) { value = Lookups(); return@produceState }
        kotlinx.coroutines.delay(180)   // wait for a pause in typing
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Lookups(Find.people(ctx, q), InboxLog.all(ctx).asReversed().filter { Find.matches(it.title + " " + it.text, q) }.take(5))
        }
    }
    return r
}

/** Extra results under the app matches: a command to run, people, tasks, messages, web apps, the AI. */
fun LazyListScope.commandResults(vm: LauncherVM, act: MainActivity, query: String, found: Lookups, aiReady: Boolean, clear: () -> Unit) {
    val q = query.trim()
    if (q.length < 2) return
    val cmd = Assistant.interpret(q)
    Find.describe(cmd)?.let { d ->
        item(key = "cmd") { Result("▸ $d", Sand) { act.handleAsk(q); clear(); vm.goHome() } }
    }
    val people = found.people
    if (people.isNotEmpty()) {
        item(key = "ppl-h") { Section("People") }
        people.forEachIndexed { i, p ->
            item(key = "p$i") {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(p.name, color = Fg, fontSize = 18.sp)
                    Row {
                        listOf<Pair<String, () -> Unit>>("call" to { Find.call(act, p) }, "text" to { Find.text(act, p) }, "WhatsApp" to { Find.whatsapp(act, p) })
                            .forEach { (l, f) -> Text(l, color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = f).padding(end = 20.dp, top = 4.dp, bottom = 4.dp)) }
                    }
                }
            }
        }
    }
    val tasks = vm.store.tasks.filter { Find.matches(it.text, q) }.take(4)
    if (tasks.isNotEmpty()) {
        item(key = "t-h") { Section("Tasks") }
        tasks.forEach { t -> item(key = "t${t.id}") { Result((if (t.done) "✓ " else "○ ") + t.text, Fg) { vm.store.toggleTask(t.id) } } }
    }
    val web = vm.webApps.filter { Find.matches(it.title, q) }.take(4)
    if (web.isNotEmpty()) {
        item(key = "w-h") { Section("Your web apps") }
        web.forEachIndexed { i, w -> item(key = "w$i") { Result(w.label, Fg) { vm.openWeb(w); clear() } } }
    }
    val msgs = found.messages
    if (msgs.isNotEmpty()) {
        item(key = "m-h") { Section("Messages") }
        msgs.forEachIndexed { i, m ->
            item(key = "m$i") {
                Column(Modifier.fillMaxWidth().combinedClickable { act.packageManager.getLaunchIntentForPackage(m.pkg)?.let { act.tryStart(it) } }.padding(vertical = 6.dp)) {
                    Text("${m.app} · ${m.title} · ${ago(m.time)}", color = Dim, fontSize = 12.sp)
                    Text(m.text, color = Fg, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (aiReady) item(key = "ai") {
        Result("✦ Ask Dune AI: “$q”", Sand) { vm.askAi(q); clear(); vm.goHome() }
    }
}

@Composable private fun Section(t: String) = Text(t.uppercase(), color = Dim, fontSize = 11.sp, letterSpacing = 2.sp,
    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

@Composable private fun Result(t: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) = Text(
    t, color = color, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(vertical = 10.dp))

// ---------------------------------------------------------------- Workspaces

@Composable
fun WorkspacesBlock(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val list = Workspaces.parse(vm.store.workspaces.value)
    if (list.isEmpty()) return
    Header("Workspaces", "edit") { vm.screen = Screen.SETTINGS }
    FlowRow { list.forEach { w -> Chip("⧉ " + w.name) { Workspaces.open(act, vm.apps, w) } } }
}

// ---------------------------------------------------------------- AI settings

@Composable
fun AiSettings(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    var tick by remember { mutableIntStateOf(0) }
    val p = remember(tick) { Brain.provider(act) }
    val hasKey = remember(tick) { Brain.ready(act) }
    var key by rememberSaveable { mutableStateOf("") }
    var model by remember(tick) { mutableStateOf(Brain.model(act)) }
    Note("Ask Dune anything it can't do itself – “when is Tom coming?”, “what's on this week?”, “catch me up” – and an AI " +
        "answers using your messages, tasks and calendar. It uses your own key, stored encrypted on this phone. " +
        "Nothing is sent until you ask, and then only what's ticked below.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Brain.PROVIDERS.forEach { pr ->
            Text(pr.name, color = if (pr.id == p.id) Sand else Dim, fontSize = 16.sp,
                modifier = Modifier.combinedClickable { Brain.setProvider(act, pr.id); key = ""; tick++ }.padding(end = 20.dp, top = 8.dp, bottom = 8.dp))
        }
    }
    if (hasKey) {
        Note("✓ ${p.name} key saved")
        LinkRow("Remove the ${p.name} key") { Brain.setKey(act, null); tick++ }
    } else {
        OutlinedTextField(key, { key = it }, label = { Text("${p.name} key  (${p.keyHint})") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        LinkRow("Save the key") { if (key.isNotBlank()) { Brain.setKey(act, key); key = ""; tick++; act.toast("Saved") } }
    }
    OutlinedTextField(model, { model = it; Brain.setModel(act, it) }, label = { Text("Model (blank = ${p.defaultModel})") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
    if (p.id == "openrouter") Note("Free OpenRouter models (ending :free) may keep what you send. Use a paid model for your messages.")
    ShareToggle("Let it read my messages and emails", "aiMessages", Brain.shareMessages(act))
    ShareToggle("Let it read my tasks", "aiTasks", Brain.shareTasks(act))
    ShareToggle("Let it read my calendar", "aiCalendar", Brain.shareCalendar(act))
    val cal = remember(tick) { Brain.calendar(act) != null }
    if (!cal) LinkRow("Allow calendar access") { act.requestPerm(Manifest.permission.READ_CALENDAR) { tick++ } }
    val contacts = remember(tick) { Find.canReadContacts(act) }
    if (!contacts) LinkRow("Allow contacts (to find people from the app list)") { act.requestPerm(Manifest.permission.READ_CONTACTS) { tick++ } }
    LinkRow("Forget the saved message history (${InboxLog.all(act).size})") { InboxLog.clear(act); tick++ }
}

@Composable
private fun ShareToggle(label: String, pref: String, initial: Boolean) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().combinedClickable { on = !on; Brain.setShare(ctx, pref, on) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Fg, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

