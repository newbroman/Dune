@file:OptIn(ExperimentalFoundationApi::class)

package uk.newbroman.dune

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The apps you have open, worked out from Android's usage events: every app with a screen you've used in the
 * last three days that hasn't been closed since (swiping it away in Recent apps, or the app finishing, closes
 * it). Needs "Usage access" for Dune.
 */
object OpenApps {
    data class Open(val pkg: String, val lastUsed: Long)
    /** UsageEvents.Event.ACTIVITY_DESTROYED (Android 10+), which the SDK keeps hidden. */
    private const val DESTROYED = 24

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
                   else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
    fun askAccess(ctx: Context) {
        if (!ctx.tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))))
            ctx.tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** Most recently used first. [skip] are packages never to list (Dune, the system UI, other launchers). */
    fun list(ctx: Context, skip: Set<String>, days: Int = 3): List<Open> {
        val um = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val ev = um.queryEvents(now - days * 86_400_000L, now)
        val open = HashMap<String, HashSet<String>>()     // package → activities still alive
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        var sawDestroy = false
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val p = e.packageName ?: continue
            val c = e.className ?: ""
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> { open.getOrPut(p) { HashSet() }.add(c); last[p] = e.timeStamp }
                UsageEvents.Event.ACTIVITY_PAUSED -> { open.getOrPut(p) { HashSet() }.add(c); last[p] = e.timeStamp }
                DESTROYED -> { open[p]?.remove(c); sawDestroy = true }
            }
        }
        // Phones that don't report closed screens: fall back to everything used in the last day.
        return last.filter { (p, t) -> p !in skip && (if (sawDestroy) open[p]?.isNotEmpty() == true else now - t < 86_400_000L) }
            .map { Open(it.key, it.value) }.sortedByDescending { it.lastUsed }
    }

    fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "now"; m < 60 -> "${m} min"; m < 24 * 60 -> "${m / 60} h"; else -> "${m / 1440} d" }
    }
}

/** A screen of the apps you have open: tap to go back to one, long-press for its app info. */
@Composable
fun OpenAppsScreen(vm: LauncherVM) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(OpenApps.hasAccess(ctx)) }
    var list by remember { mutableStateOf<List<OpenApps.Open>?>(null) }
    LaunchedEffect(vm.resumeTick) {
        access = OpenApps.hasAccess(ctx)
        if (!access) return@LaunchedEffect
        val home = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
        val skip = setOf(ctx.packageName, "com.android.systemui", "android") + home
        list = withContext(Dispatchers.IO) { runCatching { OpenApps.list(ctx, skip) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open apps", color = Fg, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light, modifier = Modifier.weight(1f))
            Text("✕", color = Dim, fontSize = 22.sp, modifier = Modifier.combinedClickable { vm.goHome() }.padding(8.dp))
        }
        if (!access) {
            Text("Dune needs Usage access to see which apps are open. It only reads which apps you've used and when – nothing inside them.",
                color = Dim, fontSize = 14.sp)
            Text("Allow Usage access", color = Sand, fontSize = 17.sp, modifier = Modifier.combinedClickable { OpenApps.askAccess(ctx) }.padding(vertical = 16.dp))
        } else {
            val l = list
            when {
                l == null -> Text("…", color = Dim)
                l.isEmpty() -> Text("No apps open.", color = Dim, fontSize = 15.sp)
                else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(l, key = { it.pkg }) { o ->
                        val a = vm.apps.firstOrNull { it.pkg == o.pkg }
                        Column(Modifier.combinedClickable(
                            onClick = { if (a != null) vm.launch(a) else ctx.packageManager.getLaunchIntentForPackage(o.pkg)?.let { ctx.tryStart(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                            onLongClick = { if (a != null) vm.appInfo(a) else ctx.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + o.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        ).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (a != null) AppIcon(vm, a, 48.dp) else Spacer(Modifier.width(48.dp))
                            Text(a?.label ?: vm.labelFor(o.pkg), color = Fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text(OpenApps.ago(o.lastUsed), color = Faint, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(if (l.isNullOrEmpty()) 1f else 0.001f))
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Android's Recent apps", color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable {
                    val s = SplitService.instance
                    if (s != null) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    else ctx.toast("Turn on Dune's accessibility service (Settings › Workspaces) for this")
                }.padding(vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                Text("Long-press: app info · close it there", color = Faint, fontSize = 11.sp)
            }
        }
    }
}
