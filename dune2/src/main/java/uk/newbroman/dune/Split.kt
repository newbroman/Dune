package uk.newbroman.dune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** A saved pair of apps to open side by side: "Name | first app | second app". */
data class Workspace(val name: String, val first: String, val second: String)

object Workspaces {
    fun parse(lines: String): List<Workspace> = lines.lines().mapNotNull { l ->
        val p = l.split('|').map { it.trim() }
        if (p.size >= 3 && p[1].isNotEmpty() && p[2].isNotEmpty()) Workspace(p[0].ifEmpty { "${p[1]} + ${p[2]}" }, p[1], p[2]) else null
    }

    /** Best match for a name the user typed: exact label, then starts-with, then contains. */
    fun <T> find(items: List<T>, name: String, label: (T) -> String): T? {
        val n = name.trim().lowercase()
        return items.firstOrNull { label(it).lowercase() == n }
            ?: items.firstOrNull { label(it).lowercase().startsWith(n) }
            ?: items.firstOrNull { label(it).lowercase().contains(n) }
    }

    fun open(ctx: Context, apps: List<AppEntry>, w: Workspace) {
        val a = find(apps, w.first) { it.label }; val b = find(apps, w.second) { it.label }
        if (a == null || b == null) { ctx.toast("Can't find “${if (a == null) w.first else w.second}”"); return }
        val la = launchIntent(ctx, a) ?: return; val lb = launchIntent(ctx, b) ?: return
        // The reliable way: Android's own split-screen action, which only an accessibility service may use.
        // Dune (the home app) starts both apps itself – Android lets the home app do that – and the
        // accessibility service only presses the split-screen button in between.
        SplitService.instance?.let { it.split(ctx, la, lb); return }
        ctx.toast("“Dune split screen” is off – turn it on in Settings › Workspaces, or the apps can't share the screen")
        ctx.startActivity(Intent(ctx, SplitActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra("a", la).putExtra("b", lb))
    }

    private fun launchIntent(ctx: Context, a: AppEntry): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.apply { component = android.content.ComponentName(a.pkg, a.cls) }
            ?: run { ctx.toast("Can't open ${a.label}"); null }
}

/**
 * Opens two apps in split screen. Android only splits when an ordinary app (not the home
 * screen) asks for its neighbour, so this invisible stand-in does the asking: it requests the
 * second app beside itself, then hands its own half to the first app and closes.
 */
class SplitActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }   // recreated by the split: already done
        @Suppress("DEPRECATION") val a = intent.getParcelableExtra<Intent>("a")
        @Suppress("DEPRECATION") val b = intent.getParcelableExtra<Intent>("b")
        if (a == null || b == null) { finish(); return }
        runCatching {
            startActivity(Intent(b).addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        main.postDelayed({
            if (!isFinishing && !isDestroyed) runCatching { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            finish()
        }, 700)
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}

/**
 * "Dune split screen": an accessibility service used only while a workspace opens. Android on many
 * phones won't split the screen when an app asks, so this does what you would by hand: open Recent
 * apps, tap the first app's icon, tap "Split screen", then tap the second app. It looks at the
 * screen only during those few seconds. If a step fails it keeps a short log of what it saw.
 */
class SplitService : android.accessibilityservice.AccessibilityService() {
    companion object { @Volatile var instance: SplitService? = null; private set
        fun enabled() = instance != null
        fun settings(ctx: Context) { ctx.tryStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        fun log(ctx: Context): String? = ctx.getSharedPreferences("dune", 0).getString("splitLog", null)
        private val SPLIT_WORDS = Regex("(?i)^\\s*(split( screen| top| left| right| bottom)?|multi.?window|split.?view)\\s*$")
    }
    private val main = Handler(Looper.getMainLooper())
    private var run = 0
    private val trace = StringBuilder()

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onAccessibilityEvent(e: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun split(from: Context, a: Intent, b: Intent) {
        main.removeCallbacksAndMessages(null)
        val me = ++run
        trace.setLength(0); skip = emptySet()
        val la = label(a.component?.packageName ?: a.`package` ?: ""); val lb = label(b.component?.packageName ?: b.`package` ?: "")
        note("Workspace: $la + $lb · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val first = runCatching { from.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (first.isFailure) { toast("Couldn't open $la"); return }
        main.postDelayed({
            if (me != run) return@postDelayed
            // Some phones still honour the direct split-screen action; try it first.
            @Suppress("DEPRECATION")
            if (performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) {
                note("direct split: accepted")
                main.postDelayed({ startSecond(from, b) }, 1100); return@postDelayed
            }
            note("direct split: refused")
            // 2. The simple way (worked for some pairs in 2.18): open the second app "beside" the first.
            val pa = a.component?.packageName ?: ""; val pb = b.component?.packageName ?: ""
            runCatching { startActivity(Intent(b).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) }
            main.postDelayed({
                if (me != run) return@postDelayed
                val showing = allRoots().mapNotNull { it.packageName?.toString() }.toSet()
                if (pa in showing && pb in showing) { note("beside: split ✓"); return@postDelayed }
                note("beside: not split (showing ${showing.filter { it != "com.android.systemui" }}) – using Recent apps")
                viaRecents(me, from, a, b, la, lb)
            }, 1600)
        }, 1300)
    }

    /** 3. The long way: Recent apps › the first app's card › Split screen › the second app's card. */
    private fun viaRecents(me: Int, from: Context, a: Intent, b: Intent, la: String, lb: String) {
            // Only the Recent apps screen counts – not the app itself, the status bar or the navigation bar.
            skip = setOf(a.component?.packageName ?: "", "com.android.systemui", packageName)
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            waitFor(me, "Recent apps", { root -> root.takeIf { recentsWindow(it) } }, quick = true, onMiss = {
                // Android ignored the request: press the navigation bar's Overview button instead.
                val btn = allRoots().firstNotNullOfOrNull { r -> find(r) { n -> (n.viewIdResourceName ?: "").endsWith("recent_apps") || n.contentDescription?.toString().equals("Overview", true) } }
                note(if (btn != null) "pressing Overview" else "no Overview button either")
                btn?.let { click(it) }
                waitFor(me, "Recent apps (after Overview)", { root -> root.takeIf { recentsWindow(it) } }) { pickFirst(me, from, la, lb, b) }
            }) { pickFirst(me, from, la, lb, b) }
    }

    private var skip: Set<String> = emptySet()
    private fun recentsWindow(r: android.view.accessibility.AccessibilityNodeInfo) = (r.packageName?.toString() ?: "") !in skip

    private fun pickFirst(me: Int, from: Context, la: String, lb: String, b: Intent) {
        note("Recent apps: " + roots().joinToString { it.packageName ?: "?" })
        waitFor(me, "the $la card", { root -> findCard(root, la) }) { card ->
            note("$la card actions: " + card.actionList.mapNotNull { it.label?.toString() }.joinToString(" | "))
            // Cards usually offer their menu items (Split screen, App info…) as accessibility actions.
            val act = card.actionList.firstOrNull { it.label?.toString()?.contains("split", true) == true }
            if (act != null && card.performAction(act.id)) { note("used “${act.label}”"); pickSecond(me, from, lb, b); return@waitFor }
            // Otherwise tap the app icon at the top of the card, and choose Split screen from its menu.
            val r = android.graphics.Rect(); card.getBoundsInScreen(r)
            tap(r.exactCenterX(), r.top + 28 * resources.displayMetrics.density)
            waitFor(me, "Split screen in the menu", { root -> find(root) { n -> SPLIT_WORDS.matches(n.text?.toString() ?: n.contentDescription?.toString() ?: "") } }) { opt ->
                click(opt); pickSecond(me, from, lb, b)
            }
        }
    }

    /** The second app's card, scrolling through Recent apps if need be; or open it beside if it isn't there. */
    private fun pickSecond(me: Int, from: Context, lb: String, b: Intent, scrolls: Int = 0) {
        waitFor(me, "the $lb card", { root -> findCard(root, lb) }, quick = true, onMiss = {
            val list = roots().firstNotNullOfOrNull { r -> find(r) { it.isScrollable } }
            if (list != null && scrolls < 6 && (list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                    list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))) {
                note("scrolling for $lb"); main.postDelayed({ pickSecond(me, from, lb, b, scrolls + 1) }, 500)
            } else { note("$lb not in Recent apps – opening it beside"); dump(); startSecond(from, b) }
        }) { card -> click(card); note("done") }
    }

    /** A task card in Recent apps: the node named after the app, preferring Launcher's "task" views. */
    private fun findCard(root: android.view.accessibility.AccessibilityNodeInfo, label: String): android.view.accessibility.AccessibilityNodeInfo? {
        val l = label.lowercase()
        val hits = walk(root).filter { n -> n.isVisibleToUser && n.contentDescription?.toString()?.lowercase()?.let { it == l || it.startsWith("$l ") || it.startsWith("$l,") } == true }
        return hits.firstOrNull { (it.viewIdResourceName ?: "").endsWith("/task") } ?: hits.maxByOrNull { area(it) }
    }

    private fun tap(x: Float, y: Float) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    private fun startSecond(from: Context, b: Intent) {
        val i = Intent(b).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching { from.startActivity(i) }.recoverCatching { startActivity(i) }
    }

    /** Looks for something on screen every quarter second for up to 3 s, then gives up (with a log). */
    private fun waitFor(me: Int, what: String, find: (android.view.accessibility.AccessibilityNodeInfo) -> android.view.accessibility.AccessibilityNodeInfo?,
                        quick: Boolean = false, onMiss: (() -> Unit)? = null, then: (android.view.accessibility.AccessibilityNodeInfo) -> Unit) {
        var tries = 0
        val step = object : Runnable { override fun run() {
            if (me != run) return
            val hit = roots().firstNotNullOfOrNull { find(it) }
            if (hit != null) { note("found $what"); main.postDelayed({ if (me == run) then(hit) }, 350); return }
            if (++tries < (if (quick) 6 else 12)) { main.postDelayed(this, 250); return }
            note("NOT found: $what"); if (!quick) dump()
            if (onMiss != null) onMiss() else toast("Split screen: couldn't find $what (log in Settings › Workspaces)")
        } }
        main.postDelayed(step, 400)
    }

    private fun allRoots(): List<android.view.accessibility.AccessibilityNodeInfo> =
        (runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(rootInActiveWindow)).distinct()
    /** The windows worth searching: everything except the first app, the system bars and Dune's own. */
    private fun roots() = allRoots().filter { (it.packageName?.toString() ?: "") !in skip }

    private fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0, out: MutableList<android.view.accessibility.AccessibilityNodeInfo> = ArrayList()): List<android.view.accessibility.AccessibilityNodeInfo> {
        if (n == null || depth > 40 || out.size > 3000) return out
        out += n
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, out)
        return out
    }
    private fun find(root: android.view.accessibility.AccessibilityNodeInfo, test: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean) =
        walk(root).firstOrNull { it.isVisibleToUser && test(it) }

    private fun area(n: android.view.accessibility.AccessibilityNodeInfo) = android.graphics.Rect().also { n.getBoundsInScreen(it) }.let { it.width().toLong() * it.height() }

    /** A node naming the app: on its icon (small) or its whole card (large). */
    private fun findApp(root: android.view.accessibility.AccessibilityNodeInfo, label: String, preferIcon: Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        val l = label.lowercase()
        val hits = walk(root).filter { n ->
            n.isVisibleToUser && listOfNotNull(n.contentDescription, n.text).any { t -> t.toString().lowercase().let { it == l || it.startsWith("$l ") || it.startsWith("$l,") || it.startsWith("$l.") } }
        }
        if (hits.isEmpty()) return null
        val icon = hits.filter { (it.viewIdResourceName ?: "").contains("icon", true) }
        return if (preferIcon) (icon.ifEmpty { hits }).minByOrNull { area(it) } else hits.maxByOrNull { area(it) }
    }

    private fun click(n: android.view.accessibility.AccessibilityNodeInfo) {
        var c: android.view.accessibility.AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
        // Nothing clickable said yes: tap its centre instead.
        val r = android.graphics.Rect(); n.getBoundsInScreen(r)
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    private fun note(s: String) { trace.appendLine(s); save() }
    /** What was on screen when a step failed: text, descriptions and view ids only. */
    private fun dump() {
        allRoots().filter { (it.packageName?.toString() ?: "") != "com.android.systemui" || it.childCount < 8 }.forEachIndexed { wi, r ->
            trace.appendLine("— window $wi (${r.packageName})")
            walk(r).filter { it.text != null || it.contentDescription != null || it.viewIdResourceName != null }.take(200).forEach { n ->
                trace.appendLine("  " + listOfNotNull(n.viewIdResourceName?.substringAfter(":id/")?.let { "#$it" },
                    n.text?.let { "\"${it.toString().take(40)}\"" }, n.contentDescription?.let { "[${it.toString().take(40)}]" },
                    if (n.isClickable) "clickable" else null,
                    n.actionList.mapNotNull { it.label?.toString() }.takeIf { it.isNotEmpty() }?.joinToString("|", "{", "}")).joinToString(" "))
            }
        }
        save()
    }
    private fun save() { getSharedPreferences("dune", 0).edit().putString("splitLog", trace.toString().take(12000)).apply() }
}
