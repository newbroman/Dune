package uk.newbroman.dune

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Task(val id: Long, val text: String, val done: Boolean)
data class Note(val id: Long, val text: String, val time: Long)

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
/** A shortcut pinned to Dune: a real ShortcutInfo (pkg + id) or an old-style intent URI. */
data class Pinned(val pkg: String, val id: String, val label: String, val uri: String = "")

class Store private constructor(ctx: Context) {
    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }
    }

    private val p = ctx.getSharedPreferences("dune", Context.MODE_PRIVATE)

    var pinned by mutableStateOf(readPinned())
        private set
    fun addPinned(s: Pinned) = savePinned(pinned.filterNot { it.pkg == s.pkg && it.id == s.id } + s)
    fun removePinned(s: Pinned) = savePinned(pinned - s)
    private fun savePinned(l: List<Pinned>) {
        pinned = l
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("pkg", it.pkg).put("id", it.id).put("label", it.label).put("uri", it.uri)) }
        p.edit().putString("pinned", a.toString()).apply()
    }
    private fun readPinned(): List<Pinned> = try {
        val a = JSONArray(p.getString("pinned", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Pinned(it.getString("pkg"), it.getString("id"), it.getString("label"), it.optString("uri")) }
    } catch (e: Exception) { emptyList() }

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
    /** Behind the home hubs: your phone's wallpaper (darkened a little so text stays readable), or Dune's sky. */
    val useWallpaper = Flag("useWallpaper", false)
    /** The hub to the left of Home ("" for none): Groups by default, as in Dune 1. */
    val leftHub = Str("leftHub", "apps")
    /** A still picture of the sky on the lock screen (SkyWallpaper reads the same key). */
    val skyLock = Flag("skyLock", false)
    val skyCompass = Flag("skyCompass", true)
    val skyWeather = Flag("skyWeather", true)
    val groupRules = Str("groupRules", DEFAULT_GROUP_RULES)

    // Polish and assistant
    val polishClock = Flag("polishClock", true)
    val showPhrase = Flag("showPhrase", true)
    val showAssistant = Flag("showAssistant", true)
    val showWidgets = Flag("showWidgets", true)
    val showCoinbase = Flag("showCoinbase", true)
    /** Widget shown in place of Dune's own weather, as "id:heightDp" (blank = Dune's weather). */
    val weatherWidget = Str("weatherWidget", "")
    /** Widgets on the weather page (four swipes left), "id:heightDp" separated by commas. */
    val weatherPage = Str("weatherPage", "")

    // Dock: the few apps used most, always at the bottom of the home screen
    var dock by mutableStateOf(readList("dock"))
        private set
    val dockSeeded = Flag("dockSeeded", false)
    val favSeeded2 = Flag("favSeeded2", false)
    val favSeeded3 = Flag("favSeeded3", false)
    fun saveDock(l: List<String>) { dock = l.distinct().take(5); writeList("dock", dock) }
    fun toggleDock(key: String) = saveDock(if (key in dock) dock - key else dock + key)
    fun moveDock(key: String, by: Int) {
        val l = dock.toMutableList(); val i = l.indexOf(key); val j = i + by
        if (i < 0 || j !in l.indices) return
        l.removeAt(i); l.add(j, key); saveDock(l)
    }
    val hideBalances = Flag("hideBalances", false)

    // Workspaces: two apps side by side, one per line "Name | first app | second app"
    val showWorkspaces = Flag("showWorkspaces", true)
    val workspaces = Str("workspaces", "Maps + music | Maps | Spotify\nTranslate + chat | Translate | WhatsApp")

    // Drive mode
    val driveAuto = Flag("driveAuto", true)
    val driveReadAloud = Flag("driveReadAloud", true)
    val driveLandscape = Flag("driveLandscape", true)
    val homeAddress = Str("homeAddress", "")
    var carDevices by mutableStateOf(readList("carDevices").toSet())
        private set
    fun setCar(address: String, on: Boolean) {
        carDevices = if (on) carDevices + address else carDevices - address
        writeList("carDevices", carDevices.toList())
    }

    // Widgets placed on the home screen, in order
    var widgets by mutableStateOf(readWidgets())
        private set
    private fun saveWidgets(l: List<PlacedWidget>) {
        widgets = l; writeList("widgets", l.map { "${it.id}:${it.heightDp}" })
    }
    private fun readWidgets() = readList("widgets").mapNotNull {
        val (i, h) = it.split(':').let { p -> p.getOrNull(0)?.toIntOrNull() to p.getOrNull(1)?.toIntOrNull() }
        if (i != null) PlacedWidget(i, h ?: 120) else null
    }
    fun addWidget(id: Int, heightDp: Int) = saveWidgets(widgets.filterNot { it.id == id } + PlacedWidget(id, heightDp))
    fun dropWidget(id: Int) = saveWidgets(widgets.filterNot { it.id == id })
    fun moveWidget(id: Int, by: Int) {
        val l = widgets.toMutableList(); val i = l.indexOfFirst { it.id == id }; val j = i + by
        if (i < 0 || j !in l.indices) return
        val w = l.removeAt(i); l.add(j, w); saveWidgets(l)
    }
    fun resizeWidget(id: Int, byDp: Int) =
        saveWidgets(widgets.map { if (it.id == id) it.copy(heightDp = (it.heightDp + byDp).coerceIn(60, 600)) else it })

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
        saveCopies(groupCopies.mapValues { it.value - name }.filterValues { it.isNotEmpty() })
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

    // Copies: an app can also appear in other groups as well as its own (app key -> extra groups)
    var groupCopies by mutableStateOf(readCopies())
        private set
    fun toggleCopy(key: String, group: String) {
        val now = groupCopies[key].orEmpty()
        val next = if (group in now) now - group else now + group
        saveCopies(if (next.isEmpty()) groupCopies - key else groupCopies + (key to next))
    }
    private fun saveCopies(m: Map<String, Set<String>>) {
        groupCopies = m
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, org.json.JSONArray(v.toList())) }
        p.edit().putString("groupCopies", o.toString()).apply()
    }
    private fun readCopies(): Map<String, Set<String>> = try {
        val o = JSONObject(p.getString("groupCopies", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map(a::getString).toSet() } }
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
    /** l14-status.py on the L14: its port and token (`l14-status.py token`). Blank token = no warnings. */
    val l14StatusPort = Str("l14StatusPort", "8714")
    val l14StatusToken = Str("l14StatusToken", "")
    /** NRG task list on the home screen: expanded or folded to one line. */
    val l14TasksOpen = Flag("l14TasksOpen", true)
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

    // Notes (Tasks hub)
    var notes by mutableStateOf(readNotes())
        private set
    private fun saveNotes(l: List<Note>) {
        notes = l
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("t", it.time)) }
        p.edit().putString("notes", a.toString()).apply()
    }
    private fun readNotes(): List<Note> = try {
        val a = JSONArray(p.getString("notes", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Note(o.getLong("id"), o.getString("text"), o.optLong("t")) } }
    } catch (e: Exception) { emptyList() }
    fun saveNote(id: Long?, text: String) {
        val t = text.trim()
        if (id == null) { if (t.isNotEmpty()) saveNotes(listOf(Note(System.currentTimeMillis(), t, System.currentTimeMillis())) + notes); return }
        saveNotes(if (t.isEmpty()) notes.filterNot { it.id == id } else notes.map { if (it.id == id) Note(id, t, System.currentTimeMillis()) else it })
    }
    fun deleteNote(id: Long) = saveNotes(notes.filterNot { it.id == id })

    // Hubs: which are switched on, in order ("home" is always first)
    val hubs = Str("hubs", "home,comms,tasks,claude,learn,music,record,connect,map,weather")
    /** Claude hub: "Name | claude.ai project link | owner/repo" per line. */
    val claudeProjects = Str("claudeProjects", "Dune | | newbroman/Dune")
    /** Hubs this phone has already been offered (so a new one can be switched on once). */
    val hubsKnown = Str("hubsKnown", "")
    /** Last app used from each hub's "apps ▾" list, "hub=package" per line. */
    val hubLastApp = Str("hubLastApp", "")
    /** Apps you've added to / removed from each hub's list. */
    val hubAppsAdded = Str("hubAppsAdded", "")
    val hubAppsRemoved = Str("hubAppsRemoved", "")

    private fun readTasks(): List<Task> = try {
        val a = JSONArray(p.getString("tasks", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Task(it.getLong("id"), it.getString("text"), it.getBoolean("done")) }
    } catch (e: Exception) { emptyList() }

    private fun readList(k: String) = (p.getString(k, "") ?: "").split('\n').filter { it.isNotBlank() }
    private fun writeList(k: String, l: List<String>) = p.edit().putString(k, l.joinToString("\n")).apply()
}
