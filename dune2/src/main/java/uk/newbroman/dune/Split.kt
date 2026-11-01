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
        SplitService.instance?.let { it.split(la, lb); return }
        if (!ctx.getSharedPreferences("dune", 0).getBoolean("splitAsked", false)) {
            ctx.getSharedPreferences("dune", 0).edit().putBoolean("splitAsked", true).apply()
            ctx.toast("For dependable split screen, turn on “Dune split screen” in Accessibility (Settings › Workspaces)")
        }
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
 * "Dune split screen": an accessibility service that does one thing – presses Android's own
 * split-screen button for a workspace. It reads nothing on screen. Opening the first app, asking
 * the system to split, then opening the second app beside it works on phones where an app
 * asking for its neighbour on its own doesn't.
 */
class SplitService : android.accessibilityservice.AccessibilityService() {
    companion object { @Volatile var instance: SplitService? = null; private set
        fun enabled() = instance != null
        fun settings(ctx: Context) { ctx.tryStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onAccessibilityEvent(e: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun split(a: Intent, b: Intent) {
        main.removeCallbacksAndMessages(null)
        runCatching { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        main.postDelayed({
            @Suppress("DEPRECATION")
            val ok = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            main.postDelayed({
                runCatching { startActivity(Intent(b).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) }
                if (!ok) toast("This phone wouldn't split the screen for that app")
            }, 900)
        }, 900)
    }
}
