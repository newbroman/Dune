@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Dune 2 is built from hubs. A hub is one area of life – messages, tasks, maps – with:
 *  - its own page (Dune's live view, plus the apps that do the job in full, handed off to),
 *  - optionally a one-line summary for the Home hub,
 *  - optionally results for the command bar and background for Dune AI.
 * You choose which hubs you have and their order (Settings › Hubs).
 */
interface Hub {
    val id: String
    val title: String
    /** One character for the hub bar. */
    val glyph: String
    /** The hub's page. [active] is true while it's on screen. */
    @Composable fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection)
    /** A short line for the Home hub, or null for none. */
    @Composable fun summary(vm: LauncherVM): String? = null
    /** Command bar matches: label and what tapping it does. */
    fun search(ctx: Context, vm: LauncherVM, q: String): List<Pair<String, () -> Unit>> = emptyList()
}

object Hubs {
    val ALL: List<Hub> = listOf(HomeHub, CommsHub, TasksHub, MapHub, WeatherHub, AppsHub, WidgetsHub, RozmowaHub)
    fun byId(id: String) = ALL.firstOrNull { it.id == id }
    /** The hubs switched on, in order, Home always first. */
    fun enabled(s: Store): List<Hub> =
        (listOf("home") + s.hubs.value.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "home" })
            .distinct().mapNotNull { byId(it) }
    fun setEnabled(s: Store, ids: List<String>) = s.hubs.set((listOf("home") + ids.filter { it != "home" }).distinct().joinToString(","))
}

// ---------------------------------------------------------------- the hub pager (Dune 2's home screen)

@Composable
fun HubScreen(vm: LauncherVM) {
    val ctx = LocalContext.current
    val s = vm.store
    val hubs = Hubs.enabled(s)
    val pager = rememberPagerState { hubs.size }
    val scope = rememberCoroutineScope()
    val gestures = rememberOverscrollGestures(up = { vm.screen = Screen.DRAWER }, down = { Actions.expandNotifications(ctx) })

    if (s.showSky.value) {
        val heading by rememberHeading(s.skyCompass.value && vm.screen == Screen.HOME && vm.currentHub == "home", vm.skyLat, vm.skyLon)
        SkyBackground(vm.skyLat, vm.skyLon, 0.9f, weather = if (s.skyWeather.value) vm.weather else null, heading = heading)
    }
    LaunchedEffect(vm.homeTick) { if (vm.screen == Screen.HOME && pager.currentPage != 0) pager.animateScrollToPage(0) }
    LaunchedEffect(pager.currentPage, hubs.size) { vm.currentHub = hubs.getOrNull(pager.currentPage)?.id ?: "home"; vm.homePage = if (pager.currentPage == 0) HOME_PAGE else -1 }
    LaunchedEffect(vm.jumpHub) {
        val id = vm.jumpHub ?: return@LaunchedEffect
        vm.jumpHub = null   // clear first, so a cut-short jump can't leave the next tap ignored
        hubs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) }
            ?: ctx.toast("Add the ${Hubs.byId(id)?.title ?: id} hub in Settings › Hubs")
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1, key = { hubs[it].id }) { page ->
            hubs[page].Page(vm, active = pager.currentPage == page && vm.screen == Screen.HOME, gestures = gestures)
        }
        // The hub bar: one glyph per hub, tap to jump.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            hubs.forEachIndexed { i, h ->
                val on = pager.currentPage == i
                Column(Modifier.combinedClickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.glyph, color = if (on) Sand else Dim, fontSize = 18.sp)
                    Text(h.title.lowercase(), color = if (on) Sand else Faint, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("settings", color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable { vm.screen = Screen.SETTINGS }.padding(vertical = 12.dp))
            Spacer(Modifier.weight(1f))
            Text("drive", color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable { Drive.set(ctx, true); vm.screen = Screen.DRIVE }.padding(vertical = 12.dp))
            Spacer(Modifier.weight(1f))
            Text("apps  ⌃", color = Fg, fontSize = 17.sp, modifier = Modifier.combinedClickable { vm.screen = Screen.DRAWER }.padding(12.dp))
        }
    }
}

/** A hub page with a title and a scrolling list, the usual shape. */
@Composable
fun HubPage(title: String, gestures: NestedScrollConnection, trailing: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().nestedScroll(gestures).padding(horizontal = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Fg, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light, modifier = Modifier.weight(1f))
                trailing()
            }
        }
        content()
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ---------------------------------------------------------------- hand-off to the apps that do the job in full

data class HubApp(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Installed apps for a hub: known packages first, then any whose name contains one of [words]. */
fun hubApps(ctx: Context, vm: LauncherVM, packages: List<String>, words: List<String>): List<HubApp> {
    val pm = ctx.packageManager
    val installed = vm.apps.map { it.pkg }.toSet()
    val byName = vm.apps.filter { a -> val l = a.label.lowercase(); words.any { l.contains(it) } }.map { it.pkg }
    return (packages.filter { it in installed } + byName).distinct().map { p ->
        val icon = runCatching {
            val d = pm.getApplicationIcon(p)
            android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c)
            }.asImageBitmap()
        }.getOrNull()
        HubApp(p, vm.labelFor(p), icon)
    }
}

/** "↗ last app" plus "apps ▾" – the same hand-off row on every hub. */
@Composable
fun AppsRow(vm: LauncherVM, hub: String, packages: List<String>, words: List<String>, open: (HubApp) -> Unit = { launchPkg(vm, it.pkg) }) {
    val ctx = LocalContext.current
    val lastMap = vm.store.hubLastApp.value.lines().mapNotNull { l -> l.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    val lastPkg = lastMap[hub]
    var list by remember { mutableStateOf<List<HubApp>?>(null) }
    val scope = rememberCoroutineScope()
    fun remember(p: String) = vm.store.hubLastApp.set((lastMap + (hub to p)).entries.joinToString("\n") { "${it.key}=${it.value}" })
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (lastPkg != null && vm.apps.any { it.pkg == lastPkg }) Chip("↗ " + vm.labelFor(lastPkg).take(14)) {
            open(HubApp(lastPkg, vm.labelFor(lastPkg), null))
        }
        Box {
            // icons are drawn off the main thread; the menu opens when they're ready
            Chip("apps ▾") { scope.launch { list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { hubApps(ctx, vm, packages, words) } } }
            DropdownMenu(list != null, onDismissRequest = { list = null }) {
                if (list?.isEmpty() == true) DropdownMenuItem(text = { Text("No apps found for this hub") }, onClick = { list = null })
                list?.forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) },
                        leadingIcon = { a.icon?.let { Image(it, null, Modifier.size(28.dp)) } },
                        onClick = { list = null; remember(a.pkg); open(a) })
                }
            }
        }
    }
}

fun launchPkg(vm: LauncherVM, pkg: String) {
    vm.apps.firstOrNull { it.pkg == pkg }?.let { vm.launch(it) }
}

// ---------------------------------------------------------------- Home hub

object HomeHub : Hub {
    override val id = "home"; override val title = "Home"; override val glyph = "◉"
    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val ctx = LocalContext.current
        val s = vm.store
        val list = rememberLazyListState()
        LaunchedEffect(vm.homeTick) { list.scrollToItem(0) }
        val isDefault = remember(vm.resumeTick) { Actions.isDefaultHome(ctx) }
        LazyColumn(Modifier.fillMaxSize().nestedScroll(gestures).padding(horizontal = 24.dp), state = list) {
            if (!isDefault) item {
                Text("Make Dune 2 your home app  →", color = Sand, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 16.dp).combinedClickable { Actions.homeSettings(ctx) })
            }
            item { ClockBlock(vm) }
            if (s.showAssistant.value) item { AssistantBlock(vm) }
            // One line from each hub; tap to go there.
            item {
                Column(Modifier.padding(top = 18.dp)) {
                    Hubs.enabled(s).filter { it.id != "home" }.forEach { h -> androidx.compose.runtime.key(h.id) {
                        val line = h.summary(vm)
                        if (line != null) Row(Modifier.fillMaxWidth().combinedClickable { vm.jumpHub = h.id }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(h.glyph, color = Sand, fontSize = 16.sp, modifier = Modifier.padding(end = 12.dp))
                            Text(line, color = Fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } }
                }
            }
            if (s.showPhrase.value) item { PhraseBlock(vm) }
            if (s.showWeather.value) item { WeatherBlock(vm) }
            if (s.showFavs.value) item { FavouritesBlock(vm) }
            if (s.showWorkspaces.value) item { WorkspacesBlock(vm) }
            if (s.showCoinbase.value && vm.coinbaseKey != null) item { CoinbaseBlock(vm) }
            if (s.showTermux.value) item { TermuxBlock(vm) }
            if (s.showL14.value) item { L14Block(vm) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ---------------------------------------------------------------- v1 pages, offered as hubs

object MapHub : Hub {
    override val id = "map"; override val title = "Map"; override val glyph = "⌖"
    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) = MapPage(vm, active)
}

object WeatherHub : Hub {
    override val id = "weather"; override val title = "Weather"; override val glyph = "☁"
    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) = WeatherPage(vm, gestures)
    @Composable override fun summary(vm: LauncherVM): String? = vm.weather?.let { w ->
        "${w.temp}° ${w.summary}" + (w.rainChance?.let { " · rain $it%" } ?: "") + " · wind ${w.wind} mph"
    }
}

object AppsHub : Hub {
    override val id = "apps"; override val title = "Groups"; override val glyph = "▦"
    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) = SidePage("Groups", gestures) {
        item { GroupsBlock(vm, title = false) }
    }
}

object WidgetsHub : Hub {
    override val id = "widgets"; override val title = "Widgets"; override val glyph = "▤"
    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) = SidePage("Widgets", gestures) {
        item { WidgetsBlock(vm) }
    }
}

object RozmowaHub : Hub {
    override val id = "rozmowa"; override val title = "Rozmowa"; override val glyph = "ł"
    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) = RozmowaPage(vm)
}

// ---------------------------------------------------------------- Settings › Hubs

@Composable
fun HubSettings(vm: LauncherVM) {
    val s = vm.store
    val on = Hubs.enabled(s).map { it.id }
    Note("Choose your hubs and their order. Home is always first; swipe between hubs or tap the bar at the bottom.")
    // Enabled hubs in order, then the rest.
    (on.mapNotNull { Hubs.byId(it) } + Hubs.ALL.filter { it.id !in on }).forEach { h ->
        val enabled = h.id in on
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(h.glyph, color = if (enabled) Sand else Faint, fontSize = 18.sp, modifier = Modifier.padding(end = 12.dp))
            Text(h.title, color = if (enabled) Fg else Dim, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (enabled && h.id != "home") {
                val i = on.indexOf(h.id)
                if (i > 1) Text("↑", color = Dim, fontSize = 18.sp, modifier = Modifier.combinedClickable {
                    Hubs.setEnabled(s, on.toMutableList().also { java.util.Collections.swap(it, i, i - 1) })
                }.padding(horizontal = 10.dp, vertical = 6.dp))
                if (i < on.size - 1) Text("↓", color = Dim, fontSize = 18.sp, modifier = Modifier.combinedClickable {
                    Hubs.setEnabled(s, on.toMutableList().also { java.util.Collections.swap(it, i, i + 1) })
                }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (h.id != "home") Switch(checked = enabled, onCheckedChange = { want ->
                Hubs.setEnabled(s, if (want) on + h.id else on - h.id)
            })
        }
    }
}

