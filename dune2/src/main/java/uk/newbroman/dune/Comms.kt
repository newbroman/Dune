@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One person (or group chat) and what they've sent in the last week, across all apps. */
data class Convo(
    val who: String,
    val entries: List<InboxLog.Entry>,   // oldest first
    val live: List<NotifItem>,            // still in the notification shade: can be replied to
    val last: Long,
    val pkg: String,
    val kind: String,
) {
    val replyable get() = live.firstOrNull { it.reply != null }
    val preview get() = live.firstOrNull()?.text ?: entries.lastOrNull()?.text.orEmpty()
}

object Convos {
    /** Group messages and emails by who sent them, newest conversation first. Pure, so it can be tested. */
    fun build(log: List<InboxLog.Entry>, live: List<NotifItem>): List<Convo> {
        fun key(s: String) = s.trim().lowercase()
        val people = (log.map { key(it.title) } + live.filter { it.kind != "other" }.map { key(it.title) }).filter { it.isNotEmpty() }.distinct()
        return people.map { k ->
            val e = log.filter { key(it.title) == k }.sortedBy { it.time }
            val l = live.filter { it.kind != "other" && key(it.title) == k }.sortedByDescending { it.time }
            val newest = maxOf(e.lastOrNull()?.time ?: 0L, l.firstOrNull()?.time ?: 0L)
            val pkg = l.firstOrNull()?.pkg ?: e.last().pkg
            val kind = l.firstOrNull()?.kind ?: e.last().kind
            Convo((l.firstOrNull()?.title ?: e.last().title).trim(), e, l, newest, pkg, kind)
        }.sortedByDescending { it.last }
    }
}

object CommsHub : Hub {
    override val id = "comms"; override val title = "Comms"; override val glyph = "✉"
    private val APPS = listOf("com.whatsapp", "com.google.android.apps.messaging", "com.google.android.gm", "org.thoughtcrime.securesms",
        "org.telegram.messenger", "com.facebook.orca", "com.microsoft.office.outlook", "com.google.android.dialer", "com.samsung.android.messaging")
    private val WORDS = listOf("mail", "messag", "chat", "whatsapp", "signal", "telegram", "phone", "contacts")

    @Composable override fun summary(vm: LauncherVM): String {
        val items by NotifService.items.collectAsState()
        val people = items.filter { it.kind != "other" }
        if (people.isEmpty()) return "No new messages"
        val names = people.map { it.title.ifBlank { vm.labelFor(it.pkg) } }.distinct()
        return "${people.size} new · " + names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        val items by NotifService.items.collectAsState()
        // The week's log is read off the main thread, and again whenever new notifications arrive.
        val convos by produceState(emptyList<Convo>(), items) {
            value = withContext(Dispatchers.IO) { Convos.build(InboxLog.all(act), items) }
        }
        var filter by rememberSaveable { mutableStateOf("all") }
        var open by remember { mutableStateOf<String?>(null) }
        var showOther by remember { mutableStateOf(false) }
        val other = items.filter { it.kind == "other" }
        val shown = convos.filter { filter == "all" || it.kind == filter }

        HubPage("Comms", gestures) {
            item {
                AppsRow(vm, id, APPS, WORDS)
                FlowRow(Modifier.padding(top = 4.dp)) {
                    listOf("all" to "Everyone", "message" to "Messages", "email" to "Email").forEach { (k, l) ->
                        Text(l, color = if (filter == k) Sand else Dim, fontSize = 14.sp,
                            modifier = Modifier.combinedClickable { filter = k }.padding(end = 16.dp, bottom = 6.dp))
                    }
                }
                if (Brain.ready(act)) Chip(if (vm.aiBusy) "…" else "✦ Catch me up") {
                    vm.askAi("Catch me up: summarise my messages and emails from the last day – who wants what, and anything I need to reply to or do. Group by person.",
                        onAnswer = { vm.inboxAnswer = it })
                }
                vm.inboxAnswer?.let {
                    Text(it, color = Fg, fontSize = 15.sp, modifier = Modifier.fillMaxWidth()
                        .combinedClickable { vm.inboxAnswer = null }.padding(vertical = 8.dp))
                }
                if (NotifService.instance == null) LinkRow("Allow notification access so messages appear here") { Actions.notificationAccess(act) }
                else if (shown.isEmpty()) Note("No messages in the last week yet. They'll gather here as they arrive.")
            }
            shown.forEach { c ->
                item(key = "c:" + c.who.lowercase()) {
                    ConvoRow(vm, c, open == c.who) { open = if (open == c.who) null else c.who }
                }
            }
            if (other.isNotEmpty()) item {
                Text((if (showOther) "▾ " else "▸ ") + "Other notifications · ${other.size}", color = Dim, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().combinedClickable { showOther = !showOther }.padding(top = 18.dp, bottom = 6.dp))
            }
            if (showOther) other.forEach { n -> item(key = "o:" + n.key) { InboxRow(vm, n, false) {} } }
        }
    }
}

@Composable
private fun ConvoRow(vm: LauncherVM, c: Convo, open: Boolean, toggle: () -> Unit) {
    val act = LocalContext.current as MainActivity
    val isNew = c.live.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(if (isNew) Sand else Faint, CircleShape), contentAlignment = Alignment.Center) {
                Text(c.who.take(1).uppercase(), color = if (isNew) Bg else Fg, fontSize = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.who, color = Fg, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ${vm.labelFor(c.pkg)} · ${ago(c.last)}", color = if (isNew) Sand else Faint, fontSize = 12.sp, maxLines = 1)
                }
                Text(c.preview, color = Dim, fontSize = 14.sp, maxLines = if (open) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (open) Column(Modifier.padding(start = 50.dp, top = 6.dp)) {
            c.entries.takeLast(8).forEach { e ->
                Text(ago(e.time) + "  " + e.text, color = Fg, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
            val person by produceState<Person?>(null, c.who) { value = withContext(Dispatchers.IO) { Find.people(act, c.who, 1).firstOrNull() } }
            FlowRow(Modifier.padding(top = 6.dp)) {
                c.live.firstOrNull()?.let { n -> Chip("open") { Actions.openNotification(act, n) } }
                    ?: Chip("open ${vm.labelFor(c.pkg)}") { launchPkg(vm, c.pkg) }
                person?.let { p ->
                    Chip("call") { Find.call(act, p) }
                    Chip("text") { Find.text(act, p) }
                    Chip("WhatsApp") { Find.whatsapp(act, p) }
                }
                c.live.forEach { n -> Chip("✕ clear") { NotifService.instance?.cancelNotification(n.key) } }
            }
            c.replyable?.let { n -> ReplyBox(n) }
        }
    }
}

@Composable
private fun ReplyBox(n: NotifItem) {
    val act = LocalContext.current as MainActivity
    var text by remember(n.key) { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            if (NotifService.sendReply(act, n, text.trim())) { act.toast("Sent"); text = "" } else act.toast("Couldn't send – open the app instead")
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Faint, RoundedCornerShape(20.dp)).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(text, { text = it }, textStyle = TextStyle(color = Fg, fontSize = 16.sp), cursorBrush = SolidColor(Sand),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            decorationBox = { inner -> Box { if (text.isEmpty()) Text("Reply to ${n.title}", color = Faint, fontSize = 16.sp); inner() } })
        Text("🎤", fontSize = 18.sp, modifier = Modifier.combinedClickable { act.listen("Reply to ${n.title}") { it?.let { t -> text = t } } }
            .padding(horizontal = 8.dp, vertical = 8.dp))
        Text("send", color = Sand, fontSize = 15.sp, modifier = Modifier.combinedClickable { send() }.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
