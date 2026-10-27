package uk.newbroman.dune

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap

/** Saved widget: its id and the height it was given on the home screen. */
data class PlacedWidget(val id: Int, val heightDp: Int)

const val WIDGET_HOST_ID = 4711

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetsBlock(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val s = vm.store
    var editing by remember { mutableStateOf(false) }
    val mgr = remember { AppWidgetManager.getInstance(act) }
    val placed = s.widgets
    if (placed.isEmpty() && !editing) {
        Text(
            "+ add a widget (e.g. Google Translate)", color = Faint, fontSize = 14.sp,
            modifier = Modifier.padding(top = 20.dp).combinedClickable { vm.screen = Screen.WIDGET_PICK }.padding(vertical = 6.dp)
        )
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("WIDGETS", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        if (editing) Text("+ add", color = Sand, fontSize = 12.sp,
            modifier = Modifier.combinedClickable { vm.screen = Screen.WIDGET_PICK }.padding(4.dp))
        Spacer(Modifier.width(12.dp))
        Text(if (editing) "done" else "edit", color = Dim, fontSize = 12.sp,
            modifier = Modifier.combinedClickable { editing = !editing }.padding(4.dp))
    }
    placed.forEach { w -> key(w.id) {
        val info = remember(w.id) { mgr.getAppWidgetInfo(w.id) }
        if (info == null) {
            if (editing) Text("(widget no longer available – remove)", color = Faint, fontSize = 13.sp,
                modifier = Modifier.combinedClickable { act.removeWidget(w.id) }.padding(6.dp))
        } else {
        if (editing) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info.loadLabel(act.packageManager), color = Fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
            listOf("↑" to { s.moveWidget(w.id, -1) }, "↓" to { s.moveWidget(w.id, 1) },
                "−" to { s.resizeWidget(w.id, -40) }, "+" to { s.resizeWidget(w.id, 40) },
                "✕" to { act.removeWidget(w.id) }).forEach { (l, f) ->
                Text(l, color = Sand, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = f).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        HostedWidget(act, w.id, info, w.heightDp)
        }
    } }
}

/** Default height for a new widget, from what the provider asks for. */
fun defaultWidgetHeight(ctx: Context, info: AppWidgetProviderInfo): Int {
    val dp = (info.minHeight / ctx.resources.displayMetrics.density).toInt()
    return dp.coerceIn(60, 400).let { if (it < 100) 110 else it }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetPickerScreen(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val pm = act.packageManager
    // provider -> (widget label, app label), worked out once
    val providers = remember {
        AppWidgetManager.getInstance(act).installedProviders.map { p ->
            Triple(p, p.loadLabel(pm), runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() }.getOrDefault(p.provider.packageName))
        }.sortedWith(compareBy({ it.third.lowercase() }, { it.second.lowercase() }))
            .let { all ->
                // Choosing the weather widget: show only Weawow's widgets (if it has any).
                if (vm.widgetTarget == "weather") all.filter { it.first.provider.packageName == WEAWOW || it.third.equals("weawow", true) }.ifEmpty { all }
                else all
            }
    }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Text(if (vm.widgetTarget == "weather") "←  Choose a weather widget" else "←  Add a widget", color = Fg, fontSize = 22.sp,
            modifier = Modifier.combinedClickable { vm.goHome() }.padding(top = 20.dp, bottom = 12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search, e.g. translate") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        val q = query.trim().lowercase()
        LazyColumn(Modifier.weight(1f)) {
            items(providers.filter { (_, wl, al) ->
                q.isEmpty() || wl.lowercase().contains(q) || al.lowercase().contains(q)
            }, key = { it.first.provider.flattenToString() }) { (p, widgetLabel, appLabel) ->
                val icon = remember(p) { runCatching { pm.getApplicationIcon(p.provider.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                Row(Modifier.fillMaxWidth().combinedClickable { act.addWidget(p) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) Image(icon, null, Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(widgetLabel, color = Fg, fontSize = 17.sp)
                        Text(appLabel, color = Dim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** One app widget, sized to the screen width and [heightDp]. */
@Composable
fun HostedWidget(act: MainActivity, id: Int, info: AppWidgetProviderInfo, heightDp: Int) {
    AndroidView(
        factory = { c -> act.widgetHost.createView(c, id, info).apply { setAppWidget(id, info) } },
        update = { v ->
            val wDp = (act.resources.displayMetrics.widthPixels / act.resources.displayMetrics.density - 48).toInt()
            if (android.os.Build.VERSION.SDK_INT >= 31) v.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp.toFloat(), heightDp.toFloat())))
            else @Suppress("DEPRECATION") v.updateAppWidgetSize(null, wDp, heightDp, wDp, heightDp)
        },
        modifier = Modifier.fillMaxWidth().height(heightDp.dp).padding(vertical = 4.dp),
    )
}

const val WEAWOW = "com.weawow"
