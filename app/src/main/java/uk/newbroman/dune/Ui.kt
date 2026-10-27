@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val Bg = Color(0xFF000000)
val Fg = Color(0xFFECE8E1)
val Dim = Color(0xFF8C877F)
val Faint = Color(0xFF3A3733)
val Sand = Color(0xFFD9B26A)

@Composable
fun DuneApp(vm: LauncherVM) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sand, onPrimary = Bg, background = Bg, surface = Color(0xFF141311),
            onSurface = Fg, onBackground = Fg, outline = Faint, surfaceVariant = Color(0xFF1C1A17),
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Crossfade(vm.screen, label = "screen") {
                when (it) {
                    Screen.HOME -> HomeScreen(vm)
                    Screen.DRAWER -> DrawerScreen(vm)
                    Screen.SETTINGS -> SettingsScreen(vm)
                    Screen.GROUP -> GroupScreen(vm)
                }
            }
            vm.moving?.let { MoveDialog(vm, it) }
        }
    }
}

/** Turns leftover vertical drags into gestures: [up] past the end, [down] past the top. */
@Composable
private fun rememberOverscrollGestures(up: () -> Unit, down: () -> Unit) = remember {
    object : NestedScrollConnection {
        var acc = 0f
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                if (available.y == 0f || (acc != 0f && Math.signum(available.y) != Math.signum(acc))) acc = 0f
                acc += available.y
                if (acc < -160f) { acc = 0f; up() } else if (acc > 160f) { acc = 0f; down() }
            }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity { acc = 0f; return Velocity.Zero }
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: LauncherVM) {
    val ctx = LocalContext.current
    val s = vm.store
    val listState = rememberLazyListState()
    LaunchedEffect(vm.resumeTick) { if (vm.screen == Screen.HOME) listState.scrollToItem(0) }
    val gestures = rememberOverscrollGestures(
        up = { vm.screen = Screen.DRAWER },
        down = { Actions.expandNotifications(ctx) },
    )
    val isDefault = remember(vm.resumeTick) { Actions.isDefaultHome(ctx) }

    if (s.showSky.value) SkyBackground(vm.skyLat, vm.skyLon, 0.9f)
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().nestedScroll(gestures).padding(horizontal = 24.dp),
            state = listState,
        ) {
            if (!isDefault) item {
                Text(
                    "Make Dune your home app  →", color = Sand, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 16.dp).combinedClickable { Actions.homeSettings(ctx) }
                )
            }
            item { ClockBlock(vm) }
            if (s.showWeather.value) item { WeatherBlock(vm) }
            if (s.showNotifs.value) item { NotificationsBlock(vm) }
            if (s.showTasks.value) item { TasksBlock(s) }
            if (s.showGroups.value) item { GroupsBlock(vm) }
            if (s.showFavs.value) item { FavouritesBlock(vm) }
            if (s.showTermux.value) item { TermuxBlock(vm) }
            if (s.showL14.value) item { L14Block(vm) }
            item { Spacer(Modifier.height(32.dp)) }
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "settings", color = Dim, fontSize = 14.sp,
                modifier = Modifier.combinedClickable { vm.screen = Screen.SETTINGS }.padding(vertical = 12.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                "✦ spheres", color = Dim, fontSize = 14.sp,
                modifier = Modifier.combinedClickable {
                    ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://newbroman.github.io/Music_of_the_Spheres/")))
                }.padding(vertical = 12.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                "apps  ⌃", color = Fg, fontSize = 17.sp,
                modifier = Modifier.combinedClickable { vm.screen = Screen.DRAWER }.padding(12.dp)
            )
        }
    }
}

@Composable
private fun Header(text: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(
            trailing, color = Dim, fontSize = 12.sp,
            modifier = Modifier.combinedClickable { onTrailing?.invoke() }.padding(4.dp)
        )
    }
}

@Composable
private fun Chip(label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp), color = Color.Transparent, border = BorderStroke(1.dp, Faint),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) {
        Text(
            label, color = Fg, fontSize = 15.sp,
            modifier = Modifier.combinedClickable(onLongClick = onLong, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ClockBlock(vm: LauncherVM) {
    val ctx = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(1000L - System.currentTimeMillis() % 1000) }
    }
    val pattern = if (vm.store.clock24.value) "HH:mm" else "h:mm"
    Text(
        now.format(DateTimeFormatter.ofPattern(pattern)), color = Fg, fontSize = 76.sp,
        fontWeight = FontWeight.Thin,
        modifier = Modifier.padding(top = 36.dp).combinedClickable { Actions.openClock(ctx) }
    )
    Text(
        now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)), color = Dim, fontSize = 18.sp,
        modifier = Modifier.combinedClickable { Actions.openCalendar(ctx) }
    )
}

@Composable
private fun WeatherBlock(vm: LauncherVM) {
    val act = LocalContext.current as Activity
    val w = vm.weather
    val tap: () -> Unit = {
        if (vm.store.weatherPlace.value.isBlank() && !Geo.hasPermission(act)) {
            (act as MainActivity).requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION) { ok ->
                if (ok) vm.refreshWeather(true) else { act.toast("Allow Location in Permissions, or set a place in Dune settings"); Actions.appSettings(act) }
            }
        } else vm.refreshWeather(true)
    }
    Column(Modifier.padding(top = 20.dp).combinedClickable(onClick = tap)) {
        if (w != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${w.temp}°", color = Sand, fontSize = 30.sp, fontWeight = FontWeight.Light)
                Text("  ${w.summary}", color = Fg, fontSize = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            val bits = listOfNotNull(
                "H ${w.high}°  L ${w.low}°",
                w.rainChance?.let { "rain $it%" },
                "wind ${w.wind} mph",
                w.place.ifBlank { null },
            )
            Text(bits.joinToString("  ·  "), color = Dim, fontSize = 14.sp)
        }
        vm.weatherMsg?.let { Text(it, color = Dim, fontSize = 14.sp) }
    }
}

@Composable
private fun NotificationsBlock(vm: LauncherVM) {
    val act = LocalContext.current as Activity
    val granted = remember(vm.resumeTick) {
        NotificationManagerCompat.getEnabledListenerPackages(act).contains(act.packageName)
    }
    val items by NotifService.items.collectAsState()
    if (!granted) {
        Header("Notifications")
        Text(
            "Allow notification access to see them here  →", color = Dim, fontSize = 15.sp,
            modifier = Modifier.combinedClickable { Actions.notificationAccess(act) }.padding(vertical = 6.dp)
        )
        Text(
            "Greyed out? App info › ⋮ › Allow restricted settings, then try again  →", color = Faint, fontSize = 13.sp,
            modifier = Modifier.combinedClickable { Actions.appSettings(act) }.padding(vertical = 4.dp)
        )
        return
    }
    if (items.isEmpty()) {
        Header("Notifications")
        Text("Nothing new", color = Faint, fontSize = 15.sp)
        return
    }
    val groups = items.groupBy { it.pkg }.entries.toList()
    Header("Notifications · ${items.size}", "clear all") { NotifService.instance?.cancelAllNotifications() }
    groups.take(6).forEach { (pkg, list) ->
        val latest = list.first()
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onLongClick = { list.forEach { NotifService.instance?.cancelNotification(it.key) } },
                    onClick = { Actions.openNotification(act, latest) },
                )
                .padding(vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.labelFor(pkg), color = Fg, fontSize = 17.sp)
                if (list.size > 1) Text("  ${list.size}", color = Sand, fontSize = 15.sp)
            }
            val line = listOf(latest.title, latest.text).filter { it.isNotBlank() }.joinToString(" – ")
            if (line.isNotBlank()) Text(line, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (groups.size > 6) Text(
        "+ ${groups.size - 6} more apps", color = Dim, fontSize = 14.sp,
        modifier = Modifier.combinedClickable { Actions.expandNotifications(act) }.padding(vertical = 4.dp)
    )
}

@Composable
private fun TasksBlock(s: Store) {
    var draft by rememberSaveable { mutableStateOf("") }
    val tasks = s.tasks
    val open = tasks.count { !it.done }
    Header(
        if (open > 0) "Tasks · $open" else "Tasks",
        if (tasks.any { it.done }) "clear done" else null,
    ) { s.clearDone() }
    tasks.forEach { t ->
        var menu by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onLongClick = { menu = true }, onClick = { s.toggleTask(t.id) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (t.done) "●" else "○", color = if (t.done) Faint else Sand, fontSize = 16.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    t.text, fontSize = 17.sp, color = if (t.done) Faint else Fg,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; s.deleteTask(t.id) })
            }
        }
    }
    BasicTextField(
        value = draft, onValueChange = { draft = it },
        textStyle = TextStyle(color = Fg, fontSize = 17.sp), cursorBrush = SolidColor(Sand), singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { s.addTask(draft); draft = "" }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+", color = Dim, fontSize = 17.sp)
                Spacer(Modifier.width(12.dp))
                Box { if (draft.isEmpty()) Text("Add a task", color = Faint, fontSize = 17.sp); inner() }
            }
        },
    )
}

@Composable
private fun FavouritesBlock(vm: LauncherVM) {
    val s = vm.store
    Header("Favourites")
    val favs = s.favourites.mapNotNull { vm.appByKey(it) }
    if (favs.isEmpty()) {
        Text("Long-press an app in the app list to pin it here", color = Faint, fontSize = 15.sp)
        return
    }
    favs.forEach { a ->
        var menu by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onLongClick = { menu = true }, onClick = { vm.launch(a) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(vm, a, 34.dp)
                Spacer(Modifier.width(14.dp))
                Text(a.label, color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; s.moveFavourite(a.key, -1) })
                DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; s.moveFavourite(a.key, 1) })
                DropdownMenuItem(text = { Text("Remove from favourites") }, onClick = { menu = false; s.toggleFavourite(a.key) })
                DropdownMenuItem(text = { Text("Move to group…") }, onClick = { menu = false; vm.moving = a })
                DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; vm.appInfo(a) })
            }
        }
    }
}

@Composable
private fun TermuxBlock(vm: LauncherVM) {
    val act = LocalContext.current as Activity
    val shortcuts = parseShortcuts(vm.store.termuxLines.value)
    val pinned = vm.termuxPinned
    Header("Termux", "edit") { vm.screen = Screen.SETTINGS }
    FlowRow {
        pinned.forEach { p -> Chip("▸ " + p.label, onLong = { vm.store.removePinned(p) }) { vm.launchPinned(p) } }
        shortcuts.forEach { sc -> Chip(sc.label) { Actions.termux(act, sc.command) } }
        Chip("+") { (act as MainActivity).addShortcutFrom(TERMUX, "com.termux.widget") }
    }
}

@Composable
private fun L14Block(vm: LauncherVM) {
    val act = LocalContext.current as Activity
    val s = vm.store
    val host = s.l14Host.value.trim()
    val status = when {
        host.isEmpty() -> null
        vm.l14Online == true -> "● online"
        vm.l14Online == false -> "○ offline"
        else -> "· checking"
    }
    Header("L14", status) { vm.checkL14() }
    if (host.isEmpty()) {
        Text(
            "Set the L14's address in settings  →", color = Dim, fontSize = 15.sp,
            modifier = Modifier.combinedClickable { vm.screen = Screen.SETTINGS }.padding(vertical = 6.dp)
        )
        return
    }
    val hasTermius = remember(vm.resumeTick) { act.isInstalled(TERMIUS) }
    FlowRow {
        val sc = vm.l14Shortcut
        Chip("SSH") { if (sc != null) vm.launchPinned(sc) else Actions.termux(act, Actions.sshCommand(s)) }
        if (hasTermius) Chip("Termius") { act.launchPackage(TERMIUS) }
        if (s.l14Mac.value.isNotBlank()) Chip("Wake") { vm.wakeL14() }
        parseShortcuts(s.l14Lines.value).forEach { sc ->
            Chip(sc.label) { Actions.termux(act, Actions.sshCommand(s, sc.command)) }
        }
    }
}

// ---------------------------------------------------------------- App drawer

@Composable
fun DrawerScreen(vm: LauncherVM) {
    val ctx = LocalContext.current
    val s = vm.store
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (s.autoKeyboard.value) { delay(150); runCatching { focus.requestFocus() }; keyboard?.show() }
    }
    val q = query.trim().lowercase()
    val visible = remember(vm.apps, s.hidden, q) {
        val base = vm.apps.filter { it.key !in s.hidden }
        if (q.isEmpty()) base else {
            fun initials(l: String) = l.split(' ', '-', '.').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.lowercase()
            base.filter { a -> a.label.lowercase().contains(q) || initials(a.label).startsWith(q) }
                .sortedBy { a -> if (a.label.lowercase().startsWith(q)) 0 else 1 }
        }
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    val gestures = rememberOverscrollGestures(up = {}, down = { vm.goHome() })

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        BasicTextField(
            value = query, onValueChange = { query = it },
            textStyle = TextStyle(color = Fg, fontSize = 22.sp), cursorBrush = SolidColor(Sand), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = {
                if (q.isNotEmpty()) visible.firstOrNull()?.let { vm.launch(it); query = "" } ?: Actions.webSearch(ctx, query)
            }),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box { if (query.isEmpty()) Text("Search apps", color = Faint, fontSize = 22.sp); inner() }
            },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(gestures)) {
            items(visible, key = { it.key }) { a -> AppRow(vm, a) }
            if (q.isNotEmpty()) item {
                Text(
                    "Search the web for “${query.trim()}”", color = Dim, fontSize = 17.sp,
                    modifier = Modifier.fillMaxWidth().combinedClickable { Actions.webSearch(ctx, query.trim()) }
                        .padding(vertical = 12.dp)
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppRow(vm: LauncherVM, a: AppEntry) {
    val ctx = LocalContext.current
    val s = vm.store
    var menu by remember { mutableStateOf(false) }
    val fav = a.key in s.favourites
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onLongClick = { menu = true }, onClick = { vm.launch(a) })
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(a.label, color = Fg, fontSize = 20.sp)
            if (fav) Text("  •", color = Sand, fontSize = 20.sp)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (fav) "Remove from favourites" else "Add to favourites") },
                onClick = { menu = false; s.toggleFavourite(a.key) })
            vm.appShortcuts(a).forEach { sc ->
                DropdownMenuItem(text = { Text("› " + (sc.shortLabel ?: "")) }, onClick = { menu = false; vm.launchShortcutInfo(sc) })
            }
            DropdownMenuItem(text = { Text("Move to group…  (${vm.groupOf(a)})") }, onClick = { menu = false; vm.moving = a })
            DropdownMenuItem(text = { Text("Hide") }, onClick = { menu = false; s.setHidden(a.key, true) })
            DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; vm.appInfo(a) })
            DropdownMenuItem(text = { Text("Uninstall") }, onClick = {
                menu = false
                ctx.tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
            })
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(vm: LauncherVM) {
    val act = LocalContext.current as Activity
    val s = vm.store

    LazyColumn(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        item {
            Text(
                "←  Settings", color = Fg, fontSize = 22.sp,
                modifier = Modifier.combinedClickable { vm.goHome() }.padding(top = 20.dp, bottom = 8.dp)
            )
        }

        item { Header("Set up") }
        item { LinkRow("Make Dune the home app") { Actions.homeSettings(act) } }
        item { LinkRow("Notification access") { Actions.notificationAccess(act) } }
        item {
            Note("If Dune is greyed out there: open App info below › ⋮ menu › Allow restricted settings.")
            LinkRow("App info (permissions, restricted settings)") { Actions.appSettings(act) }
        }
        item {
            LinkRow("Location for weather") {
                (act as MainActivity).requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION) { ok ->
                    if (ok) vm.refreshWeather(true) else { act.toast("Allow Location in Permissions"); Actions.appSettings(act) }
                }
            }
        }
        item {
            LinkRow("Allow running Termux commands") {
                (act as MainActivity).requestPerm(TERMUX_PERMISSION) {
                    if (it) act.toast("Allowed. In Termux also set allow-external-apps = true in ~/.termux/termux.properties")
                    else { act.toast("Allow \"Run commands in Termux environment\" for Dune"); Actions.appSettings(act) }
                }
            }
        }

        item { Header("Home screen") }
        item { Toggle("24-hour clock", s.clock24) }
        item { Toggle("Weather", s.showWeather) }
        item { Toggle("Notifications", s.showNotifs) }
        item { Toggle("Tasks", s.showTasks) }
        item { Toggle("Sky background", s.showSky) }
        item { Toggle("App groups", s.showGroups) }
        item { Toggle("Favourites", s.showFavs) }
        item { Toggle("Termux shortcuts", s.showTermux) }
        item { Toggle("L14", s.showL14) }
        item { Toggle("Open keyboard with app list", s.autoKeyboard) }

        item { Header("Weather") }
        item { Field("Place (blank = phone location)", s.weatherPlace) }

        item { Header("Termux shortcuts") }
        item {
            Note("One-tap: put a script in Termux's ~/.shortcuts, then tap “+” in the Termux row and pick it. " +
                "A shortcut named L14 (or ssh…) is used by the L14 SSH button. Long-press a ▸ chip to remove it.")
            Note("Fallback lines (copied for you to paste):  Label | command")
            Field("Shortcuts", s.termuxLines, multi = true)
        }

        item { Header("L14") }
        item { Field("Address (IP or hostname)", s.l14Host) }
        item { Field("User", s.l14User) }
        item { Field("SSH port", s.l14Port, number = true) }
        item { Field("MAC address for Wake (optional)", s.l14Mac) }
        item {
            Note("Commands run on the L14 over SSH, one per line:  Label | command")
            Field("L14 commands", s.l14Lines, multi = true)
        }

        item { Header("Groups") }
        item {
            Note("Apps are sorted automatically; long-press any app › Move to group to change one. " +
                "Rules, one group per line:  Name | keywords. First match wins. Keywords match the start of a word in the app's name; " +
                "=name matches exactly; a.b.c is a package; @web:site matches installed web apps; @cat:game uses Android's category.")
            Field("Group rules", s.groupRules, multi = true)
            LinkRow("Restore the suggested rules") { s.groupRules.set(DEFAULT_GROUP_RULES) }
            LinkRow("Put every app back in its automatic group") { s.resetGroups(); act.toast("Groups reset") }
        }

        val hidden = vm.apps.filter { it.key in s.hidden }
        if (hidden.isNotEmpty()) {
            item { Header("Hidden apps – tap to show") }
            items(hidden, key = { "h" + it.key }) { a -> LinkRow(a.label) { s.setHidden(a.key, false) } }
        }
        item {
            Text("Dune 1.6", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 32.dp))
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) = Text(
    "$label  →", color = Fg, fontSize = 17.sp,
    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(vertical = 10.dp)
)

@Composable
private fun Note(text: String) = Text(text, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun Toggle(label: String, f: Store.Flag) {
    Row(
        Modifier.fillMaxWidth().combinedClickable { f.set(!f.value) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Fg, fontSize = 17.sp)
        Switch(checked = f.value, onCheckedChange = { f.set(it) })
    }
}

@Composable
private fun Field(label: String, v: Store.Str, multi: Boolean = false, number: Boolean = false) {
    OutlinedTextField(
        value = v.value, onValueChange = { v.set(it) },
        label = { Text(label) }, singleLine = !multi, minLines = if (multi) 3 else 1,
        textStyle = TextStyle(fontSize = 16.sp, color = Fg),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text, autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

// ---------------------------------------------------------------- Groups and icons

@Composable
fun AppIcon(vm: LauncherVM, a: AppEntry, size: Dp) {
    val bmp = vm.icon(a)
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size).clip(CircleShape).background(Faint))
}

@Composable
private fun GroupsBlock(vm: LauncherVM) {
    val groups = vm.groupNames.map { it to vm.appsIn(it) }.filter { it.second.isNotEmpty() }
    if (groups.isEmpty()) return
    Header("Groups")
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        groups.forEach { (name, list) -> GroupTile(vm, name, list) }
        // keep the last row left-aligned
        repeat((4 - groups.size % 4) % 4) { Spacer(Modifier.width(72.dp)) }
    }
}

@Composable
private fun GroupTile(vm: LauncherVM, name: String, list: List<AppEntry>) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.width(72.dp).padding(bottom = 12.dp)
                .combinedClickable(onLongClick = { menu = true }, onClick = { vm.showGroup(name) }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF1C1A17)).padding(7.dp)
            ) {
                Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                    list.take(4).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            row.forEach { AppIcon(vm, it, 21.dp) }
                            if (row.size == 1) Spacer(Modifier.size(21.dp))
                        }
                    }
                }
            }
            Text(name, color = Dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
        }
        if (name in vm.store.customGroups) DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Delete group (apps go back to automatic)") },
                onClick = { menu = false; vm.store.removeGroup(name) })
        }
    }
}

@Composable
fun GroupScreen(vm: LauncherVM) {
    val name = vm.openGroup ?: return
    val list = vm.appsIn(name)
    val gestures = rememberOverscrollGestures(up = {}, down = { vm.goHome() })
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Text(
            "←  $name", color = Fg, fontSize = 22.sp,
            modifier = Modifier.combinedClickable { vm.goHome() }.padding(start = 8.dp, top = 20.dp, bottom = 16.dp)
        )
        if (name.equals("Web", true)) {
            val ctx = LocalContext.current
            var q by rememberSaveable { mutableStateOf("") }
            BasicTextField(
                value = q, onValueChange = { q = it },
                textStyle = TextStyle(color = Fg, fontSize = 20.sp), cursorBrush = SolidColor(Sand), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) { Actions.webSearch(ctx, q.trim()); q = "" } }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    .border(1.dp, Faint, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search the web", color = Faint, fontSize = 20.sp); inner() } },
            )
            Spacer(Modifier.height(12.dp))
        }
        if (list.isEmpty()) Text("No apps here yet", color = Dim, modifier = Modifier.padding(8.dp))
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).nestedScroll(gestures)) {
            gridItems(list, key = { it.key }) { a -> GridApp(vm, a) }
        }
    }
}

@Composable
private fun GridApp(vm: LauncherVM, a: AppEntry) {
    val ctx = LocalContext.current
    val s = vm.store
    var menu by remember { mutableStateOf(false) }
    val fav = a.key in s.favourites
    Box {
        Column(
            Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }, onClick = { vm.launch(a) })
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(vm, a, 52.dp)
            Text(
                a.label, color = Fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (menu) vm.appShortcuts(a).forEach { sc ->
                DropdownMenuItem(text = { Text("› " + (sc.shortLabel ?: "")) }, onClick = { menu = false; vm.launchShortcutInfo(sc) })
            }
            DropdownMenuItem(text = { Text("Move to group…") }, onClick = { menu = false; vm.moving = a })
            DropdownMenuItem(
                text = { Text(if (fav) "Remove from favourites" else "Add to favourites") },
                onClick = { menu = false; s.toggleFavourite(a.key) })
            DropdownMenuItem(text = { Text("Hide") }, onClick = { menu = false; s.setHidden(a.key, true) })
            DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; vm.appInfo(a) })
            DropdownMenuItem(text = { Text("Uninstall") }, onClick = {
                menu = false; ctx.tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
            })
        }
    }
}

@Composable
private fun MoveDialog(vm: LauncherVM, a: AppEntry) {
    var newName by remember { mutableStateOf("") }
    val current = vm.groupOf(a)
    val done = { vm.moving = null }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("Move ${a.label} to") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.groupNames.forEach { g ->
                    Text(
                        if (g == current) "●  $g" else "○  $g",
                        color = if (g == current) Sand else Fg, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable { vm.store.setGroup(a.key, g); done() }
                            .padding(vertical = 9.dp)
                    )
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text("or a new group") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) { vm.store.addGroup(newName); vm.store.setGroup(a.key, newName.trim()); done() }
                    }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) { vm.store.addGroup(newName); vm.store.setGroup(a.key, newName.trim()) }
                done()
            }) { Text(if (newName.isNotBlank()) "Create" else "Close") }
        },
    )
}
