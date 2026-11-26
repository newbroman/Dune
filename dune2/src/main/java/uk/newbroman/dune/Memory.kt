package uk.newbroman.dune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Things Martin asks Dune to remember – "remember that my boiler is a Worcester Greenstar", "remember Anna's
 * birthday is 4 March" – kept on the phone across days and given to Dune AI with every question, so
 * "when's Anna's birthday?" or "what boiler have I got?" just works. Read and removed in Settings › Dune AI,
 * or "what do you remember", "forget that my boiler…".
 */
object Memory {
    data class Fact(val id: Long, val text: String, val time: Long)

    private const val PREFS = "dune_memory"
    const val MAX = 300

    sealed class M : Cmd()
    data class Remember(val text: String) : M()
    object Recall : M()
    data class Forget(val text: String) : M()

    fun load(ctx: Context): List<Fact> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, 0).getString("facts", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Fact(it.getLong("id"), it.getString("text"), it.getLong("time")) }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, l: List<Fact>) = ctx.getSharedPreferences(PREFS, 0).edit()
        .putString("facts", JSONArray(l.map { JSONObject().put("id", it.id).put("text", it.text).put("time", it.time) }).toString()).apply()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Keep [text]; the same fact again replaces the old copy. */
    fun add(ctx: Context, text: String): Fact {
        val t = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        val f = Fact(System.currentTimeMillis(), t, System.currentTimeMillis())
        save(ctx, (load(ctx).filter { norm(it.text) != norm(t) } + f).takeLast(MAX))
        return f
    }

    fun remove(ctx: Context, id: Long) = save(ctx, load(ctx).filter { it.id != id })
    fun clear(ctx: Context) = save(ctx, emptyList())

    /** Facts that share the telling words of [what] ("my boiler" finds "My boiler is a Worcester"). */
    fun matching(facts: List<Fact>, what: String): List<Fact> {
        val words = norm(what).split(' ').filter { it.length > 2 && it !in setOf("that", "the", "about", "you", "and", "remember", "what", "know") }
        if (words.isEmpty()) return emptyList()
        val all = facts.filter { f -> val n = norm(f.text); words.all { n.contains(it) } }
        return all.ifEmpty { facts.filter { f -> val n = norm(f.text); words.count { n.contains(it) } * 2 > words.size } }
    }

    fun forget(ctx: Context, what: String): List<Fact> {
        val hit = matching(load(ctx), what)
        if (hit.isNotEmpty()) save(ctx, load(ctx).filter { f -> hit.none { it.id == f.id } })
        return hit
    }

    /** The part of Dune AI's background that holds them. */
    fun background(ctx: Context): String {
        val f = load(ctx)
        if (f.isEmpty()) return ""
        val d = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
        return "\nTHINGS MARTIN HAS ASKED YOU TO REMEMBER (oldest first; a later one overrides an earlier one):\n" +
            f.joinToString("\n") { "- ${it.text} (noted ${d.format(java.util.Date(it.time))})" } + "\n"
    }

    /** "remember that…", "what do you remember", "forget that my…". "Remember to…" is a task or reminder, not this. */
    fun parse(s: String): M? {
        if (Regex("^(?:what do you (?:remember|know)(?: about me)?|what have you remembered|what have i asked you to remember|what are you remembering)$").matches(s)) return Recall
        Regex("^(?:please )?(?:remember|note|make a note|bear in mind|don'?t forget|keep in mind)(?: that)?[:,]? (.+)$").find(s)?.let { m ->
            val t = m.groupValues[1]
            if (Regex("^(?:to |this$|that$|it$|me\\b)").containsMatchIn(t)) return null
            if (s.startsWith("note") && !s.startsWith("note that")) return null        // "note …" is the notes hub's
            if (s.startsWith("don't forget") || s.startsWith("dont forget")) return null // "don't forget to…" reads as a reminder
            return Remember(t)
        }
        Regex("^(?:forget|stop remembering|you can forget|delete the note|remove the fact)(?: that| about| what i said about)? (.+)$").find(s)?.let { m ->
            val t = m.groupValues[1]
            // "forget that", "forget it", "forget everything" are about the conversation (a new chat)
            if (Regex("^(?:that|it|this|everything|our conversation|the conversation)$").matches(t)) return null
            return Forget(t)
        }
        return null
    }

    fun run(ctx: Context, c: M): String = when (c) {
        is Remember -> { add(ctx, c.text); "I'll remember: “${c.text.trim().trimEnd('.').replaceFirstChar { it.uppercase() }}”." }
        Recall -> load(ctx).let { f ->
            if (f.isEmpty()) "Nothing yet. Say “remember that …” and I'll keep it."
            else "I'm remembering ${f.size} thing${if (f.size == 1) "" else "s"}: " + f.takeLast(12).joinToString("; ") { it.text } +
                (if (f.size > 12) "; and ${f.size - 12} more (Settings › Dune AI)." else ".")
        }
        is Forget -> forget(ctx, c.text).let { h ->
            if (h.isEmpty()) "I don't have anything about “${c.text}”." else "Forgotten: " + h.joinToString("; ") { "“${it.text}”" }
        }
    }
}
