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
import androidx.compose.runtime.derivedStateOf
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
    val category: Int, val info: LauncherActivityInfo, val webUrl: String? = null,
) {
    val key get() = "$pkg/$cls/$serial"
}

const val PORTFOLIO = "https://newbroman.github.io/"
val PORTFOLIO_APP = WebApp("My GitHub apps", PORTFOLIO, "")

enum class Screen { HOME, DRAWER, SETTINGS, GROUP, DRIVE, WIDGET_PICK, ROZMOWA, CAMERA, CLOZE }

/** Group rules: "Name | keyword, keyword". First matching group wins; a manual move always wins. */
val DEFAULT_GROUP_RULES = """
Languages | polish, polski, welsh, cymraeg, diarhebion, german, deutsch, vietnam, norwegian, norsk, nynorsk, bokmål, language, duolingo, babbel, memrise, anki, busuu, lingo, phrase, proverb, przysłow, translate, dictionary, słownik
My apps | @web:newbroman.github.io, uk.newbroman, newbroman, spheres, source check, flight checker, newborough
AI | gemini, claude, chatgpt, copilot, perplexity, grok, deepseek, meta ai, le chat, mistral
Web | com.android.chrome, chrome, firefox, =google, browser, duckduckgo, brave, opera, microsoft edge, =edge, kiwi, vivaldi, ecosia, startpage
Links |
Music | music, spotify, radio, podcast, sounds, shazam, soundcloud, tunein, deezer, tidal, bandcamp, audible, tuner, metronome, piano, guitar, synth, @cat:audio
Stars & Geography | stellarium, star walk, starwalk, skyview, sky map, sky guide, night sky, skysafari, planet, astronom, astro, nasa, space, moon, aurora, heavens-above, iss, earth, atlas, globe, geograph, geolog, os maps, ordnance, what3words, geocach, peakfinder, peakvisor, tide
Files | files, file manager, documentsui, explorer, solid explorer, mixplorer, total commander, zarchiver, rar, cx file
Comms | phone, dialer, contacts, messag, sms, mail, outlook, whatsapp, telegram, signal, messenger, zoom, teams, slack, discord, meet, skype, @cat:social
Google | com.google., =youtube, youtube
Shopping | toolstation, screwfix, costa, greggs, gail's, gails, asda, tesco, sainsbury, nectar, clubcard, lidl, aldi, morrisons, co-op, iceland, waitrose, ocado, m&s, boots, superdrug, amazon, ebay, argos, b&q, wickes, homebase, currys, ikea, halfords, travis perkins, jewson, city plumbing, machine mart, robert dyas, primark, vinted, temu, shein, aliexpress, etsy, mcdonald, starbucks, caffè nero, caffe nero, pret a, subway, kfc, domino, just eat, deliveroo, uber eats, wetherspoon, shell, bpme, esso, tim hortons, pets at home, holland & barrett, loyalty, rewards
Work | termux, termius, tailscale, docs, sheets, slides, drive, office, =word, excel, keep, notion, calendar, github, pdf, scanner, calculator, tasks, @cat:productivity
Media | camera, gallery, photos, video, player, iplayer, netflix, prime video, @cat:video, @cat:image
Travel | maps, transit, train, rail, uber, bolt, flight, booking, waze, citymapper, ryanair, easyjet, @cat:maps
News | news, bbc, guardian, weather, @cat:news
Games | @cat:game, game
Tools |
""".trim()

private val CATEGORIES = mapOf(
    "game" to ApplicationInfo.CATEGORY_GAME, "audio" to ApplicationInfo.CATEGORY_AUDIO,
    "video" to ApplicationInfo.CATEGORY_VIDEO, "image" to ApplicationInfo.CATEGORY_IMAGE,
    "social" to ApplicationInfo.CATEGORY_SOCIAL, "news" to ApplicationInfo.CATEGORY_NEWS,
    "maps" to ApplicationInfo.CATEGORY_MAPS, "productivity" to ApplicationInfo.CATEGORY_PRODUCTIVITY,
)

/** What group rules look at. */
data class AppFacts(val label: String, val pkg: String, val category: Int = -1, val webUrl: String? = null)
val AppEntry.facts get() = AppFacts(label, pkg, category, webUrl)

class GroupRule(val name: String, private val tests: List<(AppFacts) -> Boolean>) {
    fun matches(a: AppFacts) = tests.any { it(a) }
}

fun parseGroupRules(text: String): List<GroupRule> = text.lines().map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        val name = line.substringBefore('|').trim().ifEmpty { return@mapNotNull null }
        val kws = if ('|' in line) line.substringAfter('|').split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } else emptyList()
        GroupRule(name, kws.map { k -> keywordTest(k) })
    }
    .distinctBy { it.name.lowercase() }

private fun keywordTest(k: String): (AppFacts) -> Boolean = when {
    k.startsWith("@web:") -> { a -> a.webUrl?.lowercase()?.contains(k.removePrefix("@web:")) == true }
    k.startsWith("@cat:") -> { val c = CATEGORIES[k.removePrefix("@cat:")]; { a -> c != null && a.category == c } }
    k.startsWith("=") -> { a -> a.label.lowercase() == k.substring(1) }
    k.endsWith(".") && ' ' !in k -> { a -> a.pkg.startsWith(k) }
    '.' in k && ' ' !in k -> { a -> a.pkg == k || a.pkg.startsWith("$k.") }
    else -> {
        // label word starting with the keyword; long keywords also match inside package names
        val re = Regex("(^|[^\\p{L}0-9])" + Regex.escape(k))
        val inPkg = k.length >= 5 && ' ' !in k;
        { a -> re.containsMatchIn(a.label.lowercase()) || (inPkg && a.pkg.contains(k)) }
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
    /** Last report from l14-status.py; kept (shown as "as of") while the L14 is offline. */
    var l14Status by mutableStateOf<L14Status?>(null)
        private set
    /** Why the last status fetch failed while the L14 itself was reachable, else null. */
    var l14StatusErr by mutableStateOf<String?>(null)
        private set
    /** Tap the warnings line to show every check, not just the problems. */
    var l14ShowAll by mutableStateOf(false)
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

    // ---------- assistant, Polish, drive

    /** Last thing the assistant said, shown under the ask bar. */
    var assistantReply by mutableStateOf<String?>(null)
    /** A request Dune couldn't handle itself: offer Claude / Gemini / Google. */
    var assistantFallback by mutableStateOf<String?>(null)
    var pendingReply by mutableStateOf<PendingReply?>(null)

    /** Dune's AI (Brain): one question at a time; the answer goes to [onAnswer]. */
    var aiBusy by mutableStateOf(false)
    fun askAi(question: String, speak: Boolean = false, onAnswer: (String) -> Unit = { assistantReply = it }) {
        if (aiBusy) return
        aiBusy = true; onAnswer("Thinking…")
        viewModelScope.launch {
            val a = try { Brain.ask(ctx, store, question, spoken = speak) } catch (e: Exception) { "AI: " + (e.message ?: "something went wrong") }
            aiBusy = false; onAnswer(a)
            if (speak) Speaker.speak(ctx, a)
        }
    }
    var inboxAnswer by mutableStateOf<String?>(null)
    /** Ask the home pager to move to a page (e.g. the inbox). */
    var jumpTo by mutableStateOf<Int?>(null)
    /** Dune 2: ask the hub pager to show a hub by id. */
    var jumpHub by mutableStateOf<String?>(null)
    /** Dune 2: the hub on screen. */
    var currentHub by mutableStateOf("home")
    /** Tasks hub: which tab to show (a notes search result asks for "notes"). */
    var tasksTab by mutableStateOf("tasks")
    /** Record hub: open the camera in video mode. */
    var cameraVideo by mutableStateOf(false)
    /** Show a saved track (GPX path) on the map hub. */
    var mapTrackRequest by mutableStateOf<String?>(null)
    /** Ask the map page to switch to a map (by id), e.g. the rain radar from the weather page. */
    var mapRequest by mutableStateOf<String?>(null)
    /** "weather" while choosing the widget for the weather spot; null for an ordinary widget. */
    var widgetTarget by mutableStateOf<String?>(null)

    // ---------- Coinbase
    var coinbaseKey by mutableStateOf(Coinbase.load(app))
        private set
    var portfolio by mutableStateOf<Portfolio?>(null)
        private set
    var coinbaseMsg by mutableStateOf<String?>(null)
        private set
    private var cbJob: kotlinx.coroutines.Job? = null

    fun saveCoinbaseKey(k: Coinbase.ApiKey?) {
        Coinbase.save(ctx, k); coinbaseKey = k; portfolio = null; coinbaseMsg = null
        if (k != null) refreshCoinbase(true)
    }

    fun refreshCoinbase(force: Boolean = false) {
        val k = coinbaseKey ?: return
        if (!store.showCoinbase.value) return
        val p = portfolio
        if (!force && p != null && System.currentTimeMillis() - p.at < 10 * 60_000L) return
        cbJob?.cancel()
        cbJob = viewModelScope.launch {
            if (portfolio == null) coinbaseMsg = "Loading Coinbase…"
            try { portfolio = Coinbase.fetch(k); coinbaseMsg = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { coinbaseMsg = e.message ?: "Couldn't reach Coinbase" }
        }
    }
    val media = MediaWatcher(app)

    // ---------- web apps from the portfolio page

    var webApps by mutableStateOf<List<WebApp>>(emptyList())
        private set
    private val webIcons = mutableStateMapOf<String, ImageBitmap>()

    /** Bumped when icons arrive on disk, so tiles showing a placeholder look again. */
    private var webIconsVersion by mutableIntStateOf(0)

    fun webIcon(w: WebApp): ImageBitmap? {
        webIconsVersion   // read so composition re-runs when new icons are downloaded
        return webIcons[w.url] ?: WebApps.icon(ctx, w)?.also { webIcons[w.url] = it }
    }

    /** Web pages can be favourites too, stored as "web:<url>". */
    fun favWeb(key: String): WebApp? {
        if (!key.startsWith("web:")) return null
        val url = key.removePrefix("web:")
        return webApps.firstOrNull { sameSite(it.url, url) }
            ?: if (sameSite(url, PORTFOLIO)) PORTFOLIO_APP else WebApp(url.substringAfter("//").trimEnd('/'), url, "")
    }

    private fun sameSite(a: String, b: String) = a.trimEnd('/').equals(b.trimEnd('/'), true)

    /** Portfolio apps shown in [group] (My apps: all; Languages: the language shelves), minus any already installed. */
    fun webLinksFor(group: String): List<WebApp> {
        val list = when {
            group.equals("My apps", true) -> webApps
            group.equals("Languages", true) -> webApps.filter { it.isLanguage }
            else -> return emptyList()
        }
        // Hide a link only when the installed copy of that app is already in this same group.
        return list.filterNot { w ->
            apps.any { a -> ((a.webUrl != null && sameSite(a.webUrl, w.url)) || a.label.equals(w.label, true)) && groupOf(a) == group }
        }
    }

    /** Rozmowa opens full screen inside Dune (with the mic working); other web apps open in the browser. */
    fun openWeb(w: WebApp) {
        if (sameSite(w.url, SpeechWebView.ROZMOWA)) { screen = Screen.ROZMOWA; return }
        ctx.tryStart(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(w.url)))
    }

    private var phrases by mutableStateOf<List<Phrase>>(emptyList())
    /** Moves on with "another", and by itself every third time the phone is woken (kept across restarts). */
    var phraseOffset by mutableIntStateOf(ctx.getSharedPreferences("dune", 0).getInt("phraseOffset", 0))
        private set
    fun nextPhrase() { phraseOffset++; ctx.getSharedPreferences("dune", 0).edit().putInt("phraseOffset", phraseOffset).apply() }
    fun phoneWoken() {
        val p = ctx.getSharedPreferences("dune", 0)
        val n = p.getInt("wakes", 0) + 1
        p.edit().putInt("wakes", n % 3).apply()
        if (n >= 3) nextPhrase()
    }
    val phrase: Phrase? get() = Phrases.forDay(phrases, java.time.LocalDate.now(), phraseOffset)

    init {
        la.registerCallback(cb, Handler(Looper.getMainLooper()))
        loadApps()
        Drive.init(app)
        viewModelScope.launch {
            webApps = WebApps.all(app)
            WebApps.ensureIcons(app, webApps + PORTFOLIO_APP); webIcons.clear(); webIconsVersion++
            if (WebApps.refresh(app)) { webApps = WebApps.all(app); webIcons.clear(); webIconsVersion++ }
        }
        viewModelScope.launch {
            phrases = Phrases.all(app)
            Phrases.refresh(app)
            phrases = Phrases.all(app)
        }
        viewModelScope.launch {
            var first = true
            Drive.active.collect { on ->
                if (on && (!first || screen == Screen.HOME)) screen = Screen.DRIVE
                if (!on && screen == Screen.DRIVE) screen = Screen.HOME
                first = false
            }
        }
    }

    override fun onCleared() { la.unregisterCallback(cb); media.stop() }

    fun loadApps() {
        appsJob?.cancel()
        appsJob = viewModelScope.launch {
            apps = withContext(Dispatchers.Default) {
                um.userProfiles.flatMap { u ->
                    val serial = um.getSerialNumberForUser(u)
                    la.getActivityList(null, u).map {
                        val pkg = it.componentName.packageName
                        // Installed web apps (WebAPKs) record the site they open: used for "@web:" rules.
                        val web = if (pkg.startsWith("org.chromium.webapk")) runCatching {
                            ctx.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.GET_META_DATA)
                                .metaData?.getString("org.chromium.webapk.shell_apk.startUrl")
                        }.getOrNull() else null
                        AppEntry(
                            it.label.toString(), pkg, it.componentName.className, u, serial,
                            it.applicationInfo.category, it, web,
                        )
                    }
                }.filter { it.pkg != ctx.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            icons.clear(); iconLoading.clear()
            seedDock()
            seedCallsAndCamera()
            seedPortfolio()
        }
    }

    // ---------- groups and icons

    var openGroup by mutableStateOf<String?>(null)
    /** App whose "move to group" picker is showing. */
    var moving by mutableStateOf<AppEntry?>(null)

    private val rules by derivedStateOf { parseGroupRules(store.groupRules.value) }
    val groupNames: List<String> by derivedStateOf {
        val names = rules.map { it.name }
        names + store.customGroups.filter { c -> names.none { it.equals(c, true) } }
    }
    /** Every app's group, recomputed only when apps, rules or manual moves change. */
    private val groupMap by derivedStateOf {
        val names = groupNames
        val fallback = names.firstOrNull { it.equals("Tools", true) } ?: names.lastOrNull() ?: "Apps"
        apps.associate { a ->
            a.key to (store.groupOverrides[a.key]?.takeIf { it in names }
                ?: rules.firstOrNull { it.matches(a.facts) }?.name ?: fallback)
        }
    }
    fun groupOf(a: AppEntry) = groupMap[a.key] ?: "Tools"
    /** An app's own group, plus any group it has been copied into. */
    fun appsIn(g: String) = apps.filter { it.key !in store.hidden && (groupOf(it) == g || g in store.groupCopies[it.key].orEmpty()) }
    fun isCopyIn(a: AppEntry, g: String) = groupOf(a) != g && g in store.groupCopies[a.key].orEmpty()

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

    /** Pinned web pages and other non-Termux shortcuts (Chrome › Add to home screen) – shown in Links. */
    val linkPinned: List<Pinned> get() = store.pinned.filter { it.pkg != TERMUX && it.pkg != "com.termux.widget" }

    fun shortcutIcon(p: Pinned): ImageBitmap? {
        val k = "sc:${p.pkg}:${p.id}"
        icons[k]?.let { return it }
        if (iconLoading.add(k)) viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                runCatching {
                    val d = if (p.uri.isEmpty() && la.hasShortcutHostPermission()) {
                        val q = LauncherApps.ShortcutQuery().setPackage(p.pkg).setShortcutIds(listOf(p.id))
                            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                        la.getShortcuts(q, android.os.Process.myUserHandle())?.firstOrNull()
                            ?.let { la.getShortcutIconDrawable(it, ctx.resources.displayMetrics.densityDpi) }
                    } else null
                    (d ?: ctx.packageManager.getApplicationIcon(p.pkg)).toBitmap(144, 144).asImageBitmap()
                }.getOrNull()
            }
            if (bmp != null) icons[k] = bmp
        }
        return null
    }

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

    /**
     * First run of this version: Google News, Weawow and Claude go to the top of the favourites
     * (and anything that was in the short-lived dock moves there too).
     */
    private fun seedDock() {
        if (apps.isEmpty()) return
        if (store.dock.isNotEmpty()) {
            store.saveFavourites(store.dock + store.favourites.filterNot { it in store.dock }); store.saveDock(emptyList())
        }
        if (store.dockSeeded.value) return
        val wanted = listOf(
            listOf("com.google.android.apps.magazines") to "google news",
            listOf("com.weawow") to "weawow",
            listOf(CLAUDE) to "claude",
        )
        val keys = wanted.mapNotNull { (pkgs, label) ->
            (apps.firstOrNull { it.pkg in pkgs } ?: apps.firstOrNull { it.label.equals(label, true) })?.key
        }
        store.saveFavourites(keys + store.favourites.filterNot { it in keys }); store.dockSeeded.set(true)
    }

    /** Once: add the camera, phone and WhatsApp to the end of the favourites. */
    private fun seedCallsAndCamera() {
        if (apps.isEmpty() || store.favSeeded2.value) return
        val pm = ctx.packageManager
        fun handler(i: android.content.Intent) =
            runCatching { pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
                ?.takeIf { it != "android" }
        val pkgs = listOfNotNull(
            handler(android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            handler(android.content.Intent(android.content.Intent.ACTION_DIAL)),
            "com.whatsapp",
        )
        val byLabel = listOf("camera", "phone", "whatsapp")
        val keys = (pkgs.mapNotNull { p -> apps.firstOrNull { it.pkg == p }?.key } +
            byLabel.mapNotNull { l -> apps.firstOrNull { it.label.equals(l, true) }?.key }).distinct()
        store.saveFavourites(store.favourites + keys.filterNot { it in store.favourites })
        store.favSeeded2.set(true)
    }

    /** Once: a link to Martin's GitHub app page among the favourites. */
    private fun seedPortfolio() {
        if (store.favSeeded3.value) return
        val k = "web:$PORTFOLIO"
        if (k !in store.favourites) store.saveFavourites(store.favourites + k)
        store.favSeeded3.set(true)
    }

    /** Weawow if it's installed (tap on the weather), else null. */
    val claudeApp: AppEntry? get() = apps.firstOrNull { it.pkg == CLAUDE }

    val weatherApp: AppEntry? get() = apps.firstOrNull { it.pkg == "com.weawow" || it.label.equals("weawow", true) }

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

    fun chooseWeatherWidget() { widgetTarget = "weather"; screen = Screen.WIDGET_PICK }
    fun addWeatherPageWidget() { widgetTarget = "weatherPage"; screen = Screen.WIDGET_PICK }

    fun goHome() {
        if (screen == Screen.SETTINGS) afterSettings()
        assistantFallback = null
        widgetTarget = null
        // While driving, Home returns to the Drive screen.
        screen = if (Drive.active.value && screen != Screen.DRIVE) Screen.DRIVE else Screen.HOME
        resumeTick++; homeTick++
    }
    /** Bumped by Home and Back only: the pager returns to the middle page. Coming back from an app
     *  (onResume) leaves you on the page you left from – e.g. the map after opening a map app. */
    var homeTick by mutableIntStateOf(0)

    /** Which home page is showing: 0 Rozmowa, 1 groups, 2 home, 3 widgets. */
    var homePage by mutableIntStateOf(HOME_PAGE)

    /** Back button: never leaves the launcher; from a side page it returns to the middle. */
    fun back() { if (screen != Screen.HOME) goHome() else if (homePage != HOME_PAGE) homeTick++ }

    fun onResume() {
        resumeTick++
        refreshCoinbase()
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
            val online = Actions.reachable(host, port)
            l14Online = online
            val token = store.l14StatusToken.value.trim()
            if (!online || token.isEmpty()) { l14StatusErr = null; return@launch }
            val sport = store.l14StatusPort.value.trim().toIntOrNull() ?: 8714
            L14Status.fetch(host, sport, token)
                .onSuccess { l14Status = it; l14StatusErr = null }
                .onFailure { l14StatusErr = "status: " + (it.message ?: "no answer").take(60) }
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
