package uk.newbroman.dune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The L14's own report on itself, from l14-status.py (see l14/ in this repo): backups, services,
 * machine health and NRG jobs. Not HTTP: connect, send the token and a newline, read one JSON
 * document. Over Tailscale the link is encrypted; without the right token the L14 sends nothing.
 */
data class L14Item(val group: String, val name: String, val level: String, val detail: String) {
    val isProblem get() = level == "fail" || level == "warn"
}

/** An open task from NRG's tools/task_register.csv: its check printed [value], it wants [want]. */
data class L14Task(val id: String, val title: String, val gate: String, val value: String, val want: String, val opened: String)

data class L14Tasks(val open: Int, val done: Int, val broken: Int, val checked: String, val rows: List<L14Task>)

data class L14Status(
    val generated: String,
    val fail: Int,
    val warn: Int,
    val items: List<L14Item>,
    /** NRG task list; null until the L14 has run task_lint once (it re-runs every 6 hours). */
    val tasks: L14Tasks? = null,
    /** When the phone received it (ms), so a stale copy can say "as of". */
    val receivedAt: Long = System.currentTimeMillis(),
) {
    /** Problems first (failures before warnings), in the L14's own order within each. */
    val problems get() = items.filter { it.isProblem }.sortedBy { if (it.level == "fail") 0 else 1 }

    companion object {
        fun parse(text: String, now: Long = System.currentTimeMillis()): L14Status {
            val o = JSONObject(text)
            val a = o.getJSONArray("items")
            val items = (0 until a.length()).map { a.getJSONObject(it) }.map {
                L14Item(it.optString("group"), it.optString("name"), it.optString("level"), it.optString("detail"))
            }
            val tasks = o.optJSONObject("tasks")?.let { t ->
                val r = t.optJSONArray("rows")
                val rows = if (r == null) emptyList() else (0 until r.length()).map { r.getJSONObject(it) }.map {
                    L14Task(it.optString("id"), it.optString("title"), it.optString("gate"),
                        it.optString("value"), it.optString("want"), it.optString("opened"))
                }
                L14Tasks(t.optInt("open", rows.size), t.optInt("done"), t.optInt("broken"), t.optString("checked"), rows)
            }
            return L14Status(
                o.optString("generated"),
                o.optInt("fail", items.count { it.level == "fail" }),
                o.optInt("warn", items.count { it.level == "warn" }),
                items, tasks, now,
            )
        }

        /** null with a reason when the L14 doesn't answer, refuses the token or sends garbage. */
        suspend fun fetch(host: String, port: Int, token: String): Result<L14Status> = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 3000)
                    s.soTimeout = 15_000  // the first answer after a quiet spell runs the checks (a few seconds)
                    s.getOutputStream().apply { write((token.trim() + "\n").toByteArray()); flush() }
                    val text = s.getInputStream().bufferedReader().readText().trim()
                    if (text.isEmpty()) error("no answer – check the status token")
                    parse(text)
                }
            }
        }
    }
}

/** "● online · 1 problem, 2 warnings" style summary for the L14 header. */
fun l14Summary(online: Boolean?, st: L14Status?): String = when (online) {
    false -> "○ offline"
    null -> "· checking"
    true -> {
        if (st == null) "● online"
        else {
            val bits = buildList {
                if (st.fail > 0) add("${st.fail} problem" + if (st.fail > 1) "s" else "")
                if (st.warn > 0) add("${st.warn} warning" + if (st.warn > 1) "s" else "")
            }
            "● online · " + (bits.joinToString(", ").ifEmpty { "all OK" })
        }
    }
}
