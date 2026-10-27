package uk.newbroman.dune

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Task(val id: Long, val text: String, val done: Boolean)

/** A "Label | command" line from settings. */
data class Shortcut(val label: String, val command: String)

fun parseShortcuts(text: String): List<Shortcut> = text.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { line ->
        val i = line.indexOf('|')
        if (i < 0) Shortcut(line, line) else Shortcut(line.substring(0, i).trim(), line.substring(i + 1).trim())
    }
    .filter { it.label.isNotEmpty() }

/** All settings and user data, persisted to SharedPreferences and observable by Compose. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("dune", Context.MODE_PRIVATE)

    inner class Str(private val key: String, def: String) {
        var value by mutableStateOf(p.getString(key, def) ?: def)
            private set
        fun set(v: String) { value = v; p.edit().putString(key, v).apply() }
    }

    inner class Flag(private val key: String, def: Boolean) {
        var value by mutableStateOf(p.getBoolean(key, def))
            private set
        fun set(v: Boolean) { value = v; p.edit().putBoolean(key, v).apply() }
    }

    // Look and behaviour
    val clock24 = Flag("clock24", true)
    val autoKeyboard = Flag("autoKeyboard", true)
    val showWeather = Flag("showWeather", true)
    val showNotifs = Flag("showNotifs", true)
    val showTasks = Flag("showTasks", true)
    val showFavs = Flag("showFavs", true)
    val showTermux = Flag("showTermux", true)
    val showL14 = Flag("showL14", true)
    val showGroups = Flag("showGroups", true)
    val showSky = Flag("showSky", true)

    // Groups: user-made group names, and per-app group choices (app key -> group)
    var customGroups by mutableStateOf(readList("customGroups"))
        private set
    fun addGroup(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n !in customGroups) { customGroups = customGroups + n; writeList("customGroups", customGroups) }
    }
    fun removeGroup(name: String) {
        customGroups = customGroups - name; writeList("customGroups", customGroups)
        saveOverrides(groupOverrides.filterValues { it != name })
    }
    var groupOverrides by mutableStateOf(readOverrides())
        private set
    fun setGroup(key: String, group: String) = saveOverrides(groupOverrides + (key to group))
    fun resetGroups() = saveOverrides(emptyMap())
    private fun saveOverrides(m: Map<String, String>) {
        groupOverrides = m
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString("groupOverrides", o.toString()).apply()
    }
    private fun readOverrides(): Map<String, String> = try {
        val o = JSONObject(p.getString("groupOverrides", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    // Weather: blank = phone location
    val weatherPlace = Str("weatherPlace", "")

    // Termux
    val termuxLines = Str(
        "termuxLines",
        """
        Termux |
        Update | pkg upgrade -y
        """.trimIndent()
    )

    // L14
    val l14User = Str("l14User", "john")
    val l14Host = Str("l14Host", "")
    val l14Port = Str("l14Port", "22")
    val l14Mac = Str("l14Mac", "")
    val l14Lines = Str(
        "l14Lines",
        """
        Uptime | uptime
        Disk space | df -h /
        """.trimIndent()
    )

    // Favourites (ordered app keys) and hidden apps
    var favourites by mutableStateOf(readList("fav"))
        private set
    var hidden by mutableStateOf(readList("hidden").toSet())
        private set

    fun saveFavourites(l: List<String>) { favourites = l; writeList("fav", l) }
    fun toggleFavourite(key: String) =
        saveFavourites(if (key in favourites) favourites - key else favourites + key)
    fun moveFavourite(key: String, by: Int) {
        val l = favourites.toMutableList()
        val i = l.indexOf(key); val j = i + by
        if (i < 0 || j !in l.indices) return
        l.removeAt(i); l.add(j, key); saveFavourites(l)
    }
    fun setHidden(key: String, h: Boolean) {
        hidden = if (h) hidden + key else hidden - key
        writeList("hidden", hidden.toList())
    }

    // Tasks
    var tasks by mutableStateOf(readTasks())
        private set
    private fun saveTasks(l: List<Task>) {
        tasks = l
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        p.edit().putString("tasks", a.toString()).apply()
    }
    fun addTask(text: String) { if (text.isNotBlank()) saveTasks(tasks + Task(System.currentTimeMillis(), text.trim(), false)) }
    fun toggleTask(id: Long) = saveTasks(tasks.map { if (it.id == id) it.copy(done = !it.done) else it })
    fun deleteTask(id: Long) = saveTasks(tasks.filterNot { it.id == id })
    fun clearDone() = saveTasks(tasks.filterNot { it.done })

    private fun readTasks(): List<Task> = try {
        val a = JSONArray(p.getString("tasks", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Task(it.getLong("id"), it.getString("text"), it.getBoolean("done")) }
    } catch (e: Exception) { emptyList() }

    private fun readList(k: String) = (p.getString(k, "") ?: "").split('\n').filter { it.isNotBlank() }
    private fun writeList(k: String, l: List<String>) = p.edit().putString(k, l.joinToString("\n")).apply()
}
