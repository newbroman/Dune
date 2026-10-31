@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tasks and notes: Dune's own lists, with your notes and to-do apps one tap away. */
object TasksHub : Hub {
    override val id = "tasks"; override val title = "Tasks"; override val glyph = "✓"
    private val APPS = listOf("com.google.android.keep", "com.google.android.apps.tasks", "com.todoist", "com.microsoft.todos",
        "md.obsidian", "com.samsung.android.app.notes", "com.ticktick.task", "com.evernote")
    private val WORDS = listOf("note", "task", "to do", "todo", "keep", "obsidian")

    @Composable override fun summary(vm: LauncherVM): String {
        val open = vm.store.tasks.filter { !it.done }
        val notes = vm.store.notes.size
        val t = if (open.isEmpty()) "No open tasks" else "${open.size} task" + (if (open.size == 1) "" else "s") + " · next: " + open.first().text
        return if (notes > 0) "$t · $notes note" + (if (notes == 1) "" else "s") else t
    }

    override fun search(ctx: android.content.Context, vm: LauncherVM, q: String) =
        vm.store.notes.filter { Find.matches(it.text, q) }.take(4).map { n ->
            ("✎ " + n.text.lineSequence().first().take(60)) to { vm.tasksTab = "notes"; vm.jumpHub = id; vm.screen = Screen.HOME }
        }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        val s = vm.store
        var tab by remember { mutableStateOf(vm.tasksTab) }
        androidx.compose.runtime.LaunchedEffect(vm.tasksTab) { tab = vm.tasksTab }
        HubPage("Tasks", gestures) {
            item {
                AppsRow(vm, id, APPS, WORDS)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("tasks" to "Tasks · ${s.tasks.count { !it.done }}", "notes" to "Notes · ${s.notes.size}").forEach { (k, l) ->
                        Text(l, color = if (tab == k) Sand else Dim, fontSize = 15.sp,
                            modifier = Modifier.combinedClickable { tab = k; vm.tasksTab = k }.padding(end = 18.dp, top = 4.dp, bottom = 4.dp))
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Text("🎤", fontSize = 20.sp, modifier = Modifier.combinedClickable {
                        act.listen(if (tab == "tasks") "Add a task" else "Take a note") { it?.let { t -> if (tab == "tasks") s.addTask(t.replaceFirstChar { c -> c.uppercase() }) else s.saveNote(null, t) } }
                    }.padding(8.dp))
                }
            }
            if (tab == "tasks") item { TasksBlock(s) }
            else {
                item { NoteEditor(null, "", onSave = { s.saveNote(null, it) }) }
                s.notes.forEach { n -> item(key = "n${n.id}") { NoteCard(vm, n) } }
            }
        }
    }
}

@Composable
private fun NoteCard(vm: LauncherVM, n: Note) {
    val act = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    if (editing) { NoteEditor(n.id, n.text, onSave = { vm.store.saveNote(n.id, it); editing = false }); return }
    Box {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).border(1.dp, Faint, RoundedCornerShape(14.dp))
            .combinedClickable(onLongClick = { menu = true }, onClick = { editing = true }).padding(12.dp)) {
            Text(n.text, color = Fg, fontSize = 15.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Text(ago(n.time), color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Make it a task") }, onClick = { menu = false; vm.store.addTask(n.text.lineSequence().first()) })
            DropdownMenuItem(text = { Text("Share") }, onClick = {
                menu = false; act.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text), "Share note"))
            })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.store.deleteNote(n.id) })
        }
    }
}

@Composable
private fun NoteEditor(id: Long?, initial: String, onSave: (String) -> Unit) {
    var text by remember(id) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).border(1.dp, if (id == null) Faint else Sand, RoundedCornerShape(14.dp)).padding(12.dp)) {
        BasicTextField(text, { text = it }, textStyle = TextStyle(color = Fg, fontSize = 15.sp), cursorBrush = SolidColor(Sand),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner -> Box { if (text.isEmpty()) Text(if (id == null) "New note…" else "Empty note (saving deletes it)", color = Faint, fontSize = 15.sp); inner() } })
        if (text != initial || id != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text("save", color = Sand, fontSize = 15.sp, modifier = Modifier.combinedClickable { onSave(text); if (id == null) text = "" }.padding(6.dp))
        }
    }
}
