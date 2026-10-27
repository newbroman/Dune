package uk.newbroman.dune

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String, val pkg: String, val cls: String, val user: UserHandle, val serial: Long,
    val category: Int, val info: LauncherActivityInfo,
) {
    val key get() = "$pkg/$cls/$serial"
}

enum class Screen { HOME, DRAWER, SETTINGS, GROUP }

val DEFAULT_GROUPS = listOf("Comms", "Work", "Media", "Travel", "News", "Games", "Tools")

/** First guess at a group, from Android's app category plus name keywords. */
fun autoGroup(a: AppEntry): String {
    val p = a.pkg.lowercase(); val l = a.label.lowercase()
    fun has(vararg k: String) = k.any { p.contains(it) || l.contains(it) }
    return when {
        has("termux", "termius", "claude", "docs", "sheets", "slides", "drive", "office", "word", "excel",
            "keep", "notion", "calendar", "github", "pdf", "scanner", "calculator", "tasks") -> "Work"
        has("dialer", "phone", "contacts", "messag", "mms", "sms", "mail", "whatsapp", "telegram", "signal",
            "zoom", "teams", "slack", "discord", "meet") -> "Comms"
        a.category == ApplicationInfo.CATEGORY_SOCIAL -> "Comms"
        a.category == ApplicationInfo.CATEGORY_GAME -> "Games"
        a.category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Work"
        a.category == ApplicationInfo.CATEGORY_MAPS ||
            has("maps", "transit", "train", "rail", "uber", "bolt", "flight", "booking", "waze", "citymapper") -> "Travel"
        a.category in listOf(ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE) ||
            has("camera", "gallery", "photo", "music", "spotify", "youtube", "video", "podcast", "radio", "player", "iplayer", "netflix") -> "Media"
        a.category == ApplicationInfo.CATEGORY_NEWS || has("news", "bbc", "guardian", "weather") -> "News"
        else -> "Tools"
    }
}

class LauncherVM(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val store = Store.get(app)
    private val la = app.getSystemService(LauncherApps::class.java)
    private val um = app.getSystemService(UserManager::class.java)

    var screen by mutableStateOf(Screen.HOME)
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var weather by mutableStateOf<Weather?>(null)
        private set
    var weatherMsg by mutableStateOf<String?>(null)
        private set
    /** Where the sky is drawn for: the weather place/location once known (Newborough until then). */
    var skyLat by mutableStateOf(53.16)
        private set
    var skyLon by mutableStateOf(-4.37)
        private set
    var l14Online by mutableStateOf<Boolean?>(null)
        private set
    /** Bumped on resume / home press so screens can re-read system state and scroll to top. */
    var resumeTick by mutableIntStateOf(0)
        private set

    private var appsJob: Job? = null
    private var weatherJob: Job? = null
    private var l14Job: Job? = null
    private var weatherAt = 0L
    private var weatherKey = ""

    private val cb = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String?, u: UserHandle?) = loadApps()
        override fun onPackageAdded(p: String?, u: UserHandle?) = loadApps()
        override fun onPackageChanged(p: String?, u: UserHandle?) = loadApps()
        override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = loadApps()
        override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = loadApps()
    }

    init {
        la.registerCallback(cb, Handler(Looper.getMainLooper()))
        loadApps()
    }

    override fun onCleared() { la.unregisterCallback(cb) }

    fun loadApps() {
        appsJob?.cancel()
        appsJob = viewModelScope.launch {
            apps = withContext(Dispatchers.Default) {
                um.userProfiles.flatMap { u ->
                    val serial = um.getSerialNumberForUser(u)
                    la.getActivityList(null, u).map {
                        AppEntry(
                            it.label.toString(), it.componentName.packageName, it.componentName.className, u, serial,
                            it.applicationInfo.category, it,
                        )
                    }
                }.filter { it.pkg != ctx.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            icons.clear(); iconLoading.clear()
        }
    }

    // ---------- groups and icons

    var openGroup by mutableStateOf<String?>(null)
    /** App whose "move to group" picker is showing. */
    var moving by mutableStateOf<AppEntry?>(null)

    val groupNames: List<String> get() = DEFAULT_GROUPS + store.customGroups
    fun groupOf(a: AppEntry) = store.groupOverrides[a.key]?.takeIf { it in groupNames } ?: autoGroup(a)
    fun appsIn(g: String) = apps.filter { it.key !in store.hidden && groupOf(it) == g }

    fun showGroup(g: String) { openGroup = g; screen = Screen.GROUP }

    private val icons = mutableStateMapOf<String, ImageBitmap>()
    private val iconLoading = HashSet<String>()

    /** Icon for [a], or null while it loads (the caller recomposes when it arrives). */
    fun icon(a: AppEntry): ImageBitmap? {
        icons[a.key]?.let { return it }
        if (iconLoading.add(a.key)) viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                runCatching { a.info.getBadgedIcon(0).toBitmap(144, 144).asImageBitmap() }.getOrNull()
            }
            if (bmp != null) icons[a.key] = bmp
        }
        return null
    }

    // ---------- shortcuts (Termux:Widget and other apps' long-press shortcuts)

    val termuxPinned: List<Pinned> get() = store.pinned.filter { it.pkg == TERMUX || it.pkg == "com.termux.widget" }
    /** The Termux shortcut the L14 SSH button uses: one whose name mentions L14 or ssh. */
    val l14Shortcut: Pinned? get() = termuxPinned.firstOrNull {
        it.label.contains("l14", true) || it.label.contains("ssh", true)
    }

    fun launchPinned(s: Pinned) {
        try {
            if (s.uri.isNotEmpty()) {
                ctx.startActivity(android.content.Intent.parseUri(s.uri, 0).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } else la.startShortcut(s.pkg, s.id, null, null, android.os.Process.myUserHandle())
        } catch (e: Exception) { ctx.toast("Couldn't open ${s.label} – it may have been removed") }
    }

    fun appShortcuts(a: AppEntry): List<android.content.pm.ShortcutInfo> = try {
        if (!la.hasShortcutHostPermission()) emptyList() else {
            val q = LauncherApps.ShortcutQuery().setPackage(a.pkg).setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            (la.getShortcuts(q, a.user) ?: emptyList()).filter { it.isEnabled }.sortedBy { it.rank }.take(6)
        }
    } catch (e: Exception) { emptyList() }

    fun launchShortcutInfo(s: android.content.pm.ShortcutInfo) {
        try { la.startShortcut(s, null, null) } catch (e: Exception) { ctx.toast("Couldn't open that shortcut") }
    }

    /** Accept a pin request (from an app, or returned by its create-shortcut screen). */
    fun acceptPin(intent: android.content.Intent?): Boolean {
        if (intent == null) return false
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        if (req != null && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            val info = req.shortcutInfo ?: return false
            if (!req.isValid || !req.accept()) return false
            store.addPinned(Pinned(info.`package`, info.id, (info.shortLabel ?: info.longLabel ?: info.id).toString()))
            ctx.toast("Added “${info.shortLabel}” to Dune")
            return true
        }
        // Old-style shortcut result
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_SHORTCUT_INTENT) ?: return false
        @Suppress("DEPRECATION")
        val name = intent.getStringExtra(android.content.Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        store.addPinned(Pinned(target.`package` ?: target.component?.packageName ?: TERMUX, "legacy:" + name, name, target.toUri(0)))
        ctx.toast("Added “$name” to Dune")
        return true
    }

    fun appByKey(key: String) = apps.firstOrNull { it.key == key }
    fun labelFor(pkg: String) = apps.firstOrNull { it.pkg == pkg }?.label
        ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun launch(a: AppEntry) {
        try {
            la.startMainActivity(ComponentName(a.pkg, a.cls), a.user, null, null)
            if (screen == Screen.DRAWER) viewModelScope.launch { kotlinx.coroutines.delay(300); screen = Screen.HOME }
        } catch (e: Exception) { ctx.toast("Couldn't open ${a.label}") }
    }

    fun appInfo(a: AppEntry) {
        try { la.startAppDetailsActivity(ComponentName(a.pkg, a.cls), a.user, null, null) } catch (_: Exception) { }
    }

    fun goHome() {
        if (screen == Screen.SETTINGS) afterSettings()
        screen = Screen.HOME
        resumeTick++
    }

    /** Back button: never leaves the launcher. */
    fun back() { if (screen != Screen.HOME) goHome() }

    fun onResume() {
        resumeTick++
        refreshWeather()
        checkL14()
    }

    private fun afterSettings() {
        refreshWeather()
        checkL14()
    }

    fun refreshWeather(force: Boolean = false) {
        if (!store.showWeather.value) return
        val place = store.weatherPlace.value.trim()
        val stale = System.currentTimeMillis() - weatherAt > 30 * 60_000L
        if (!force && !stale && weather != null && place == weatherKey) return
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            if (weather == null || place != weatherKey) weatherMsg = "Loading weather…"
            try {
                val p = if (place.isNotEmpty()) Geo.lookup(place) ?: run {
                    weatherMsg = "Can't find \"$place\" – check the place in settings"; return@launch
                } else Geo.current(ctx) ?: run {
                    weatherMsg = if (Geo.hasPermission(ctx)) "No location fix yet – tap to retry"
                    else "Tap to use your location for weather"
                    return@launch
                }
                skyLat = p.lat; skyLon = p.lon
                weather = Weather.fetch(p.lat, p.lon, p.name)
                weatherAt = System.currentTimeMillis(); weatherKey = place; weatherMsg = null
            } catch (e: Exception) {
                weatherMsg = "Weather unavailable – tap to retry"
            }
        }
    }

    fun checkL14() {
        val host = store.l14Host.value.trim()
        if (!store.showL14.value || host.isEmpty()) { l14Online = null; return }
        val port = store.l14Port.value.trim().toIntOrNull() ?: 22
        l14Job?.cancel()
        l14Job = viewModelScope.launch {
            l14Online = Actions.reachable(host, port)
        }
    }

    fun wakeL14() {
        viewModelScope.launch {
            val ok = Actions.wake(store.l14Mac.value)
            ctx.toast(if (ok) "Wake signal sent to the L14" else "Couldn't send wake signal – check the MAC address")
            if (ok) { kotlinx.coroutines.delay(25_000); checkL14() }
        }
    }
}
