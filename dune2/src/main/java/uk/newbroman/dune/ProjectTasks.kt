@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A task that lives in a project's GitHub repo: an open issue, or a checkbox line in a task file. */
data class RepoTask(val project: String, val text: String, val done: Boolean, val source: String, val url: String)

/**
 * Tasks from each Claude project's linked repo: open GitHub Issues, and "- [ ] …" checkbox lines in
 * TASKS.md, TODO.md, .claude/tasks.md or docs/TASKS.md – the file Claude can keep its own task list in.
 * Cached for 30 minutes (GitHub allows 60 calls an hour without a token).
 */
object RepoTasks {
    val FILES = listOf("TASKS.md", "TODO.md", ".claude/tasks.md", ".claude/TASKS.md", "docs/TASKS.md", "docs/TODO.md")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dune", 0)
    fun token(ctx: Context) = Secure.get(ctx, "github_token")
    fun setToken(ctx: Context, t: String?) { Secure.put(ctx, "github_token", t?.trim()); prefs(ctx).edit().remove("repoTasks").apply() }

    /** "- [ ] Do this" → (Do this, false); "* [x] Done" → (Done, true). */
    fun parse(md: String): List<Pair<String, Boolean>> = md.lines().mapNotNull { l ->
        Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.+)$").find(l)?.let { m -> m.groupValues[2].trim() to (m.groupValues[1] != " ") }
    }

    private fun get(ctx: Context, url: String, raw: Boolean = false): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10_000
        c.setRequestProperty("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
        token(ctx)?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return when (c.responseCode) {
            in 200..299 -> c.inputStream.use { it.readBytes().decodeToString() }
            404 -> null
            403, 429 -> throw IllegalStateException("GitHub's hourly limit is used up – add a GitHub token below, or wait")
            401 -> throw IllegalStateException("The GitHub token isn't accepted")
            else -> throw IllegalStateException("GitHub said ${c.responseCode}")
        }
    }

    fun cached(ctx: Context): Pair<Long, List<RepoTask>>? = runCatching {
        val o = JSONObject(prefs(ctx).getString("repoTasks", null) ?: return null)
        val a = o.getJSONArray("t")
        o.getLong("at") to (0 until a.length()).map { a.getJSONObject(it) }.map {
            RepoTask(it.getString("p"), it.getString("x"), it.getBoolean("d"), it.getString("s"), it.getString("u")) }
    }.getOrNull()

    suspend fun load(ctx: Context, projects: List<ClaudeProject>, force: Boolean = false): List<RepoTask> = withContext(Dispatchers.IO) {
        cached(ctx)?.takeIf { !force && System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return@withContext it.second }
        val out = ArrayList<RepoTask>(); var problem: Exception? = null
        projects.filter { !it.repo.isNullOrBlank() }.forEach { p ->
            val r = p.repo!!
            runCatching {
                // Open issues (pull requests are listed as issues too – leave those out).
                get(ctx, "https://api.github.com/repos/$r/issues?state=open&per_page=30")?.let { s ->
                    val a = JSONArray(s)
                    (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.has("pull_request") }.forEach { i ->
                        out += RepoTask(p.name, "#${i.getInt("number")} " + i.getString("title"), false, "issue", i.getString("html_url"))
                    }
                }
                // Task files.
                FILES.forEach { f ->
                    get(ctx, "https://api.github.com/repos/$r/contents/$f", raw = true)?.let { md ->
                        parse(md).forEach { (t, d) -> out += RepoTask(p.name, t, d, f, "https://github.com/$r/blob/HEAD/$f") }
                    }
                }
            }.onFailure { problem = it as? Exception ?: Exception(it) }
        }
        if (out.isEmpty() && problem != null) throw problem!!
        prefs(ctx).edit().putString("repoTasks", JSONObject().put("at", System.currentTimeMillis()).put("t", JSONArray(out.map {
            JSONObject().put("p", it.project).put("x", it.text).put("d", it.done).put("s", it.source).put("u", it.url) })).toString()).apply()
        out
    }
}

/** The Tasks hub's list: your own tasks (each can belong to a project), then each project's tasks from its repo. */
@Composable
fun ProjectTasksBlock(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val s = vm.store
    val projects = remember(s.claudeProjects.value) { ClaudeProjects.parse(s.claudeProjects.value) }
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf(RepoTasks.cached(act)?.second ?: emptyList()) }
    var repoMsg by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }
    var tokenEdit by remember { mutableStateOf(false) }
    fun load(force: Boolean) = scope.launch {
        if (projects.none { !it.repo.isNullOrBlank() }) return@launch
        repoMsg = if (force) "Checking GitHub…" else null
        runCatching { RepoTasks.load(act, projects, force) }.onSuccess { repo = it; repoMsg = null }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; repoMsg = it.message }
    }
    LaunchedEffect(vm.resumeTick, projects) { load(false) }

    // ---- your own tasks, by project
    val groups = (listOf<String?>(null) + projects.map { it.name } + s.tasks.mapNotNull { it.project }).distinct()
    val open = s.tasks.count { !it.done }
    Header(if (open > 0) "My tasks · $open" else "My tasks", if (s.tasks.any { it.done }) "clear done" else null) { s.clearDone() }
    groups.forEach { g ->
        val ts = s.tasks.filter { it.project == g }
        if (ts.isEmpty()) return@forEach
        if (g != null) Text("✳ $g", color = Sand, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
            .combinedClickable { projects.firstOrNull { it.name == g }?.let { ClaudeHub.open(act, it) } })
        ts.forEach { t -> MyTask(vm, t, projects) }
    }
    AddTask(s, projects)

    // ---- from the projects' repos
    val withRepo = projects.filter { !it.repo.isNullOrBlank() }
    if (withRepo.isEmpty()) {
        Note("Link a GitHub repo to a Claude project (Claude hub › Edit) to see its open issues and TASKS.md here.")
        return
    }
    Header("From project repos", "↻") { load(true) }
    repoMsg?.let { Note(it) }
    withRepo.forEach { p ->
        val ts = repo.filter { it.project == p.name && (showDone || !it.done) }
        val left = repo.count { it.project == p.name && !it.done }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✳ ${p.name}", color = Sand, fontSize = 13.sp, modifier = Modifier.weight(1f).combinedClickable { ClaudeHub.open(act, p) })
            Text(if (left == 0) "nothing open" else "$left open", color = Faint, fontSize = 12.sp)
        }
        ts.take(25).forEach { t ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) }, onLongClick = { menu = true })
                    .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (t.done) "●" else if (t.source == "issue") "◇" else "○", color = if (t.done) Faint else Dim, fontSize = 14.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(t.text, color = if (t.done) Faint else Fg, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open on GitHub") }, onClick = { menu = false; act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) })
                    DropdownMenuItem(text = { Text("Ask Claude about this") }, onClick = { menu = false; Assistant.askClaude(act, "${p.name}: ${t.text}") })
                    DropdownMenuItem(text = { Text("Copy to my tasks") }, onClick = { menu = false; s.addTask(t.text, p.name) })
                }
            }
        }
    }
    Row(Modifier.padding(top = 8.dp)) {
        Text(if (showDone) "hide done" else "show done", color = Faint, fontSize = 12.sp, modifier = Modifier.combinedClickable { showDone = !showDone }.padding(end = 16.dp, top = 4.dp, bottom = 4.dp))
        Text(if (RepoTasks.token(act) != null) "GitHub token ✓" else "GitHub token (for private repos)", color = Faint, fontSize = 12.sp,
            modifier = Modifier.combinedClickable { tokenEdit = !tokenEdit }.padding(vertical = 4.dp))
    }
    Note("Tap a repo task to open it; long-press to ask Claude about it or copy it to your list. Claude can keep a project's list in TASKS.md (or .claude/tasks.md) as “- [ ] …” lines.")
    if (tokenEdit) {
        var t by remember { mutableStateOf("") }
        Note("A GitHub fine-grained token with read-only access to Contents and Issues (github.com › Settings › Developer settings › Fine-grained tokens). Kept encrypted on this phone.")
        OutlinedTextField(t, { t = it }, label = { Text("github_pat_…") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row {
            Chip("save") { RepoTasks.setToken(act, t.ifBlank { null }); tokenEdit = false; load(true) }
            if (RepoTasks.token(act) != null) Chip("remove token") { RepoTasks.setToken(act, null); tokenEdit = false; load(true) }
        }
    }
}

@Composable
private fun MyTask(vm: LauncherVM, t: Task, projects: List<ClaudeProject>) {
    val act = LocalContext.current as MainActivity
    val s = vm.store
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }, onClick = { s.toggleTask(t.id) }).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.done) "●" else "○", color = if (t.done) Faint else Sand, fontSize = 16.sp)
            Spacer(Modifier.width(12.dp))
            Text(t.text, fontSize = 17.sp, color = if (t.done) Faint else Fg, textDecoration = if (t.done) TextDecoration.LineThrough else null)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false; moving = false }) {
            if (!moving) {
                DropdownMenuItem(text = { Text(if (t.project == null) "Put in a project ▸" else "Move to another project ▸") }, onClick = { moving = true })
                val p = projects.firstOrNull { it.name == t.project }
                if (p != null) {
                    DropdownMenuItem(text = { Text("Open ${p.name} in Claude") }, onClick = { menu = false; ClaudeHub.open(act, p) })
                    DropdownMenuItem(text = { Text("Ask Claude about this") }, onClick = { menu = false; Assistant.askClaude(act, "${p.name}: ${t.text}") })
                } else DropdownMenuItem(text = { Text("Ask Claude about this") }, onClick = { menu = false; Assistant.askClaude(act, t.text) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; s.deleteTask(t.id) })
            } else {
                DropdownMenuItem(text = { Text("No project") }, onClick = { menu = false; moving = false; s.setTaskProject(t.id, null) })
                projects.forEach { p -> DropdownMenuItem(text = { Text((if (p.name == t.project) "● " else "") + p.name) },
                    onClick = { menu = false; moving = false; s.setTaskProject(t.id, p.name) }) }
            }
        }
    }
}

@Composable
private fun AddTask(s: Store, projects: List<ClaudeProject>) {
    var draft by rememberSaveable { mutableStateOf("") }
    var proj by rememberSaveable { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(draft, { draft = it }, textStyle = TextStyle(color = Fg, fontSize = 17.sp), cursorBrush = SolidColor(Sand), singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { s.addTask(draft, proj); draft = "" }),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+", color = Dim, fontSize = 17.sp); Spacer(Modifier.width(12.dp))
                Box { if (draft.isEmpty()) Text("Add a task", color = Faint, fontSize = 17.sp); inner() }
            } })
        if (projects.isNotEmpty()) Box {
            Text((proj?.let { "✳ " + it.take(14) } ?: "no project") + " ▾", color = if (proj != null) Sand else Faint, fontSize = 12.sp,
                modifier = Modifier.combinedClickable { pick = true }.padding(6.dp))
            DropdownMenu(pick, onDismissRequest = { pick = false }) {
                DropdownMenuItem(text = { Text("No project") }, onClick = { proj = null; pick = false })
                projects.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { proj = p.name; pick = false }) }
            }
        }
    }
}
