@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One of your Claude projects: its claude.ai link, and optionally the GitHub repo it works on. */
data class ClaudeProject(val name: String, val link: String, val repo: String?)

/** A repo's latest commit and build, from GitHub's public API. */
data class RepoState(val lastCommit: String?, val whenMs: Long, val build: String?)

object ClaudeProjects {
    /** One per line:  Name | claude.ai project link (or its id) | owner/repo (optional). */
    fun parse(text: String): List<ClaudeProject> = text.lines().mapNotNull { l ->
        val p = l.split('|').map { it.trim() }
        if (p.isEmpty() || p[0].isEmpty() || l.trim().startsWith("#")) return@mapNotNull null
        val raw = p.getOrNull(1).orEmpty()
        val link = when {
            raw.startsWith("http") -> raw
            Regex("^[0-9a-f-]{36}$").matches(raw) -> "https://claude.ai/project/$raw"
            else -> ""
        }
        val repo = p.getOrNull(2)?.removePrefix("https://github.com/")?.trimEnd('/')?.takeIf { Regex("^[\\w.-]+/[\\w.-]+$").matches(it) }
        ClaudeProject(p[0], link, repo)
    }

    /** Latest commit and CI result. Unauthenticated: GitHub allows 60 requests an hour, so results are cached for 10 minutes. */
    private val cache = HashMap<String, Pair<Long, RepoState?>>()
    suspend fun repo(r: String): RepoState? = withContext(Dispatchers.IO) {
        cache[r]?.let { (t, s) -> if (System.currentTimeMillis() - t < 600_000) return@withContext s }
        val s = runCatching {
            fun get(u: String) = (URL(u).openConnection() as HttpURLConnection).run {
                connectTimeout = 6000; readTimeout = 6000; setRequestProperty("Accept", "application/vnd.github+json")
                inputStream.bufferedReader().readText()
            }
            val c = JSONArray(get("https://api.github.com/repos/$r/commits?per_page=1")).optJSONObject(0)
            val msg = c?.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull()
            val date = c?.optJSONObject("commit")?.optJSONObject("committer")?.optString("date")
            val whenMs = runCatching { java.time.Instant.parse(date).toEpochMilli() }.getOrDefault(0L)
            val run = runCatching { JSONObject(get("https://api.github.com/repos/$r/actions/runs?per_page=1")).optJSONArray("workflow_runs")?.optJSONObject(0) }.getOrNull()
            val build = run?.let { if (it.optString("status") != "completed") "building…" else when (it.optString("conclusion")) { "success" -> "✓ build passed"; "failure" -> "✗ build failed"; "" -> null; else -> it.optString("conclusion") } }
            RepoState(msg, whenMs, build)
        }.getOrNull()
        cache[r] = System.currentTimeMillis() to s
        s
    }
}

/** Your Claude projects in one place: open one in the Claude app, see how its code is doing, start a chat about it. */
object ClaudeHub : Hub {
    override val id = "claude"; override val title = "Claude"; override val glyph = "✳"

    @Composable override fun summary(vm: LauncherVM): String? {
        val n = ClaudeProjects.parse(vm.store.claudeProjects.value).size
        return if (n == 0) null else "$n Claude project" + (if (n == 1) "" else "s")
    }

    override fun search(ctx: Context, vm: LauncherVM, q: String) =
        ClaudeProjects.parse(vm.store.claudeProjects.value).filter { Find.matches(it.name, q) }.take(4).map { p ->
            ("✳ " + p.name) to { open(ctx, p) }
        }

    fun open(ctx: Context, p: ClaudeProject) {
        // claude.ai links open in the Claude app when it's installed.
        if (p.link.isNotBlank()) ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(p.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else ctx.toast("Add the project's claude.ai link (Edit)")
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        val s = vm.store
        val projects = ClaudeProjects.parse(s.claudeProjects.value)
        var editing by rememberSaveable { mutableStateOf(false) }
        var q by rememberSaveable { mutableStateOf("") }
        var openOne by remember { mutableStateOf<String?>(null) }

        HubPage("Claude", gestures, trailing = {
            Text(if (editing) "done" else "edit", color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable { editing = !editing }.padding(8.dp))
        }) {
            item {
                FlowRow {
                    Chip("✳ Claude") { if (!act.isInstalled(CLAUDE)) act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new"))) else launchPkg(vm, CLAUDE) }
                    Chip("new chat") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Chip("projects") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/projects")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Chip("Claude Code") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/code")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                // Ask Claude straight away (opens a new chat in the Claude app with this text).
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Faint, RoundedCornerShape(22.dp)).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val send = { if (q.isNotBlank()) { Assistant.askClaude(act, q.trim()); q = "" } }
                    BasicTextField(q, { q = it }, textStyle = TextStyle(color = Fg, fontSize = 16.sp), cursorBrush = SolidColor(Sand),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }), modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        decorationBox = { inner -> Box { if (q.isEmpty()) Text("Ask Claude…", color = Faint, fontSize = 16.sp); inner() } })
                    Text("🎤", fontSize = 18.sp, modifier = Modifier.combinedClickable { act.listen("Ask Claude") { it?.let { t -> Assistant.askClaude(act, t) } } }.padding(horizontal = 10.dp, vertical = 8.dp))
                }
                if (editing) {
                    Note("One project per line:  Name | claude.ai project link | GitHub owner/repo (optional). " +
                        "Copy a project's link from claude.ai (open the project › share or the address bar).")
                    OutlinedTextField(s.claudeProjects.value, { s.claudeProjects.set(it) }, minLines = 6,
                        textStyle = TextStyle(fontSize = 13.sp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                } else if (projects.isEmpty()) Note("No projects yet – tap edit to add them.")
                Text("PROJECTS", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            }
            projects.forEachIndexed { i, p -> item(key = "p$i") { ProjectRow(vm, p, openOne == p.name) { openOne = if (openOne == p.name) null else p.name } } }
        }
    }
}

@Composable
private fun ProjectRow(vm: LauncherVM, p: ClaudeProject, open: Boolean, toggle: () -> Unit) {
    val act = LocalContext.current as MainActivity
    val state by produceState<RepoState?>(null, p.repo) { value = p.repo?.let { ClaudeProjects.repo(it) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = toggle, onClick = { ClaudeHub.open(act, p) }), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Fg, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(p.repo, state?.build, state?.whenMs?.takeIf { it > 0 }?.let { "pushed " + ago(it) }).joinToString(" · ")
                if (line.isNotBlank()) Text(line, color = if (state?.build?.startsWith("✗") == true) androidx.compose.ui.graphics.Color(0xFFFF7A6A) else Dim,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("⋯", color = Dim, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = toggle).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (open) Column(Modifier.padding(top = 6.dp).border(1.dp, Faint, RoundedCornerShape(12.dp)).padding(10.dp)) {
            state?.lastCommit?.let { Text("Last commit: $it", color = Dim, fontSize = 13.sp) }
            FlowRow(Modifier.padding(top = 4.dp)) {
                Chip("open in Claude") { ClaudeHub.open(act, p) }
                Chip("ask about it") {
                    act.listen("Ask about ${p.name}") { it?.let { t -> Assistant.askClaude(act, "About my project \"${p.name}\"" + (p.repo?.let { r -> " (github.com/$r)" } ?: "") + ": $t") } }
                }
                p.repo?.let { r ->
                    Chip("GitHub") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$r"))) }
                    Chip("builds") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$r/actions"))) }
                }
                Chip("copy link") {
                    act.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("link", p.link)); act.toast("Copied")
                }
            }
        }
    }
}
