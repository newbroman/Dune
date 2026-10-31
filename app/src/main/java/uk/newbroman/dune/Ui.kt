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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.ui.viewinterop.AndroidView
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
                    Screen.DRIVE -> DriveScreen(vm)
                    Screen.WIDGET_PICK -> WidgetPickerScreen(vm)
                    Screen.ROZMOWA -> RozmowaFull()
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

    if (s.showSky.value) {
        val heading by rememberHeading(s.skyCompass.value && vm.screen == Screen.HOME, vm.skyLat, vm.skyLon)
        SkyBackground(vm.skyLat, vm.skyLon, 0.9f, weather = if (s.skyWeather.value) vm.weather else null, heading = heading)
    }
    // Three pages: swipe right for Groups, left for Widgets; home in the middle.
    // Pages: 0 Rozmowa, 1 Groups, 2 home, 3 Widgets, 4 Inbox, 5 Map.
    val pager = rememberPagerState(initialPage = HOME_PAGE) { 6 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.resumeTick) { if (vm.screen == Screen.HOME && pager.currentPage != HOME_PAGE) pager.animateScrollToPage(HOME_PAGE) }
    LaunchedEffect(pager.currentPage) { vm.homePage = pager.currentPage }
    LaunchedEffect(vm.jumpTo) { vm.jumpTo?.let { pager.animateScrollToPage(it); vm.jumpTo = null } }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
      HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
       when (page) {
        0 -> RozmowaPage(vm)
        1 -> SidePage("Groups", gestures) {
            item { GroupsBlock(vm, title = false) }
            item {
                Text("Long-press any app › Move or copy to group to change where it goes. Rules are in settings.",
                    color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        3 -> SidePage("Widgets", gestures) {
            item { WidgetsBlock(vm) }
        }
        4 -> InboxPage(vm, gestures)
        5 -> MapPage(vm, active = pager.currentPage == 5 && vm.screen == Screen.HOME)
        else -> LazyColumn(
            Modifier.fillMaxSize().nestedScroll(gestures).padding(horizontal = 24.dp),
            state = listState,
        ) {
            if (!isDefault) item {
                Text(
                    "Make Dune your home app  →", color = Sand, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 16.dp).combinedClickable { Actions.homeSettings(ctx) }
                )
            }
            item { ClockBlock(vm) }
            if (s.showAssistant.value) item { AssistantBlock(vm) }
            if (s.showPhrase.value) item { PhraseBlock(vm) }
            if (s.showWeather.value) item { WeatherBlock(vm) }
            if (s.showCoinbase.value && vm.coinbaseKey != null) item { CoinbaseBlock(vm) }
            if (s.showNotifs.value) item { NotificationsBlock(vm) }
            if (s.showTasks.value) item { TasksBlock(s) }
            if (s.showFavs.value) item { FavouritesBlock(vm) }
            if (s.showWorkspaces.value) item { WorkspacesBlock(vm) }
            if (s.showTermux.value) item { TermuxBlock(vm) }
            if (s.showL14.value) item { L14Block(vm) }
            item { Spacer(Modifier.height(32.dp)) }
        }
       }
      }
        // Page marker: groups · home · widgets (tap to jump)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center) {
            listOf("rozmowa", "groups", "•", "widgets", "inbox", "map").forEachIndexed { i, l ->
                Text(l, color = if (pager.currentPage == i) Sand else Faint, fontSize = 12.sp,
                    modifier = Modifier.combinedClickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
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
                "drive", color = Dim, fontSize = 14.sp,
                modifier = Modifier.combinedClickable { Drive.set(ctx, true); vm.screen = Screen.DRIVE }.padding(vertical = 12.dp)
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
internal fun Header(text: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
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
internal fun Chip(label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
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
    Row(Modifier.fillMaxWidth().padding(top = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            now.format(DateTimeFormatter.ofPattern(pattern)), color = Fg, fontSize = 76.sp,
            fontWeight = FontWeight.Thin,
            modifier = Modifier.combinedClickable { Actions.openClock(ctx) }
        )
        Spacer(Modifier.weight(1f))
        // Claude, one tap from the clock
        vm.claudeApp?.let { c ->
            Box(Modifier.clip(CircleShape).combinedClickable { vm.launch(c) }.padding(10.dp)) { AppIcon(vm, c, 44.dp) }
        }
    }
    if (vm.store.polishClock.value) {
        Text(
            PolishTime.timeWords(now.hour, now.minute), color = Sand.copy(alpha = 0.9f), fontSize = 18.sp,
            modifier = Modifier.combinedClickable {
                Speaker.speak(ctx, PolishTime.spoken(LocalDateTime.now()), PolishTime.PL)
            }
        )
        Text(
            PolishTime.dateWords(now.toLocalDate()), color = Dim, fontSize = 17.sp,
            modifier = Modifier.padding(top = 2.dp).combinedClickable(onLongClick = { Actions.openCalendar(ctx) }) {
                Speaker.speak(ctx, PolishTime.spoken(LocalDateTime.now()), PolishTime.PL)
            }
        )
    } else Text(
        now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)), color = Dim, fontSize = 18.sp,
        modifier = Modifier.combinedClickable { Actions.openCalendar(ctx) }
    )
}

@Composable
private fun PhraseBlock(vm: LauncherVM) {
    val ctx = LocalContext.current
    val p = vm.phrase ?: return
    Header("Phrase of the day", "another") { vm.nextPhrase() }
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            onLongClick = { ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(Phrases.APP_URL))) },
            onClick = { Speaker.speak(ctx, p.pl, PolishTime.PL) },
        ).padding(vertical = 4.dp)
    ) {
        Text((if (p.emoji.isNotBlank()) p.emoji + "  " else "") + p.pl, color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Light)
        Text(p.en, color = Dim, fontSize = 15.sp)
        if (p.note.isNotBlank()) Text(p.note, color = Dim.copy(alpha = 0.7f), fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AssistantBlock(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    var q by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { if (q.isNotBlank()) { act.handleAsk(q.trim()); q = ""; keyboard?.hide() } }
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp)
            .border(1.dp, Faint, RoundedCornerShape(26.dp)).padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = q, onValueChange = { q = it },
            textStyle = TextStyle(color = Fg, fontSize = 17.sp), cursorBrush = SolidColor(Sand), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            decorationBox = { inner -> Box { if (q.isEmpty()) Text("Ask Dune…", color = Faint, fontSize = 17.sp); inner() } },
        )
        Text(
            "🎤", fontSize = 22.sp,
            modifier = Modifier.combinedClickable(onLongClick = { act.tryStart(Intent(Intent.ACTION_VOICE_COMMAND)) }) {
                act.listen("Ask Dune") { it?.let { t -> act.handleAsk(t) } }
            }.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
    vm.assistantReply?.let {
        Text(it, color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            .combinedClickable { vm.assistantReply = null; vm.assistantFallback = null })
    }
    if (!Brain.ready(act)) vm.assistantFallback?.let { f ->
        FlowRow(Modifier.padding(top = 6.dp)) {
            Chip("Ask Claude") { Assistant.askClaude(act, f); vm.assistantFallback = null }
            Chip("Ask Gemini") { Assistant.askGemini(act, f); vm.assistantFallback = null }
            Chip("Google") { Assistant.askGoogle(act, f); vm.assistantFallback = null }
            Chip("Translate") { Actions.translate(act, f); vm.assistantFallback = null }
        }
    }
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
    val weawow = vm.weatherApp
    val open: () -> Unit = { if (weawow != null) vm.launch(weawow) else tap() }

    // A Weawow (or other) widget chosen for this spot replaces Dune's own weather.
    val placed = vm.store.weatherWidget.value.split(':')
    val wid = placed.getOrNull(0)?.toIntOrNull()
    val info = remember(wid) { wid?.let { android.appwidget.AppWidgetManager.getInstance(act).getAppWidgetInfo(it) } }
    if (wid != null && info != null) {
        Box(Modifier.padding(top = 16.dp)) {
            HostedWidget(act as MainActivity, wid, info, placed.getOrNull(1)?.toIntOrNull() ?: 140)
        }
        return
    }

    Column(Modifier.padding(top = 20.dp).combinedClickable(onLongClick = tap, onClick = open)) {
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
    if (weawow != null) Text(
        "use a Weawow widget here  →", color = Faint, fontSize = 13.sp,
        modifier = Modifier.combinedClickable { vm.chooseWeatherWidget() }.padding(vertical = 4.dp)
    )
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
    // Everything now lives in the Inbox page; the home screen just says what's waiting.
    val people = items.count { it.kind != "other" }
    val other = items.size - people
    Header("Inbox", "open") { vm.jumpTo = 4 }
    Text(
        when {
            items.isEmpty() -> "Nothing new"
            people == 0 -> "$other other notification" + (if (other == 1) "" else "s")
            else -> "$people message" + (if (people == 1) "" else "s") + (if (other > 0) "  ·  $other other" else "") +
                "  ·  " + items.filter { it.kind != "other" }.map { it.title.ifBlank { vm.labelFor(it.pkg) } }.distinct().take(3).joinToString(", ")
        },
        color = if (people > 0) Fg else Faint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().combinedClickable { vm.jumpTo = 4 }.padding(vertical = 6.dp)
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
    // apps and web pages ("web:<url>") side by side
    val favs: List<Any> = s.favourites.mapNotNull { k -> vm.appByKey(k) ?: vm.favWeb(k) }
    if (favs.isEmpty()) {
        Header("Favourites")
        Text("Long-press an app in the app list to pin it here", color = Faint, fontSize = 15.sp)
        return
    }
    // Icons only, five to a row
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        favs.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { f -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (f is AppEntry) FavIcon(vm, f) else FavWebIcon(vm, f as WebApp)
                } }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FavIcon(vm: LauncherVM, a: AppEntry) {
    val s = vm.store
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .combinedClickable(onLongClick = { menu = true }, onClick = { vm.launch(a) })
                .padding(8.dp)
        ) { AppIcon(vm, a, 50.dp) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            Text(a.label, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; s.moveFavourite(a.key, -1) })
            DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; s.moveFavourite(a.key, 1) })
            DropdownMenuItem(text = { Text("Remove from favourites") }, onClick = { menu = false; s.toggleFavourite(a.key) })
            DropdownMenuItem(text = { Text("Move or copy to group…") }, onClick = { menu = false; vm.moving = a })
            DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; vm.appInfo(a) })
        }
    }
}

@Composable
private fun FavWebIcon(vm: LauncherVM, w: WebApp) {
    val s = vm.store
    val key = "web:" + w.url
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .combinedClickable(onLongClick = { menu = true }, onClick = { vm.openWeb(w) })
                .padding(8.dp)
        ) { WebIcon(vm, w, 50.dp) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            Text(w.label, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; s.moveFavourite(key, -1) })
            DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; s.moveFavourite(key, 1) })
            DropdownMenuItem(text = { Text("Remove from favourites") }, onClick = { menu = false; s.toggleFavourite(key) })
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
    val status = if (host.isEmpty()) null else l14Summary(vm.l14Online, vm.l14Status)
    Header("L14", status) { vm.checkL14() }
    if (host.isEmpty()) {
        Text(
            "Set the L14's address in settings  →", color = Dim, fontSize = 15.sp,
            modifier = Modifier.combinedClickable { vm.screen = Screen.SETTINGS }.padding(vertical = 6.dp)
        )
        return
    }
    L14Warnings(vm)
    NrgTasks(vm)
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

private val L14Fail = Color(0xFFE0806A)

/** The L14's problems under its header; tap to show every check, tap again to hide them. */
@Composable
private fun L14Warnings(vm: LauncherVM) {
    val st = vm.l14Status
    vm.l14StatusErr?.let { Text(it, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
    if (st == null) return
    val stale = vm.l14Online != true
    val asOf = remember(st.receivedAt) {
        java.text.SimpleDateFormat("EEE HH:mm", Locale.UK).format(java.util.Date(st.receivedAt))
    }
    val shown = if (vm.l14ShowAll) st.items else st.problems
    Column(Modifier.combinedClickable { vm.l14ShowAll = !vm.l14ShowAll }.padding(bottom = 8.dp)) {
        if (stale) Text("Last report $asOf:", color = Dim, fontSize = 13.sp)
        if (shown.isEmpty()) {
            Text("All checks OK  ·  tap for details", color = Dim, fontSize = 13.sp)
        }
        var group = ""
        shown.forEach { i ->
            if (vm.l14ShowAll && i.group != group) {
                group = i.group
                Text(group, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            val (mark, colour) = when (i.level) {
                "fail" -> "✕" to L14Fail
                "warn" -> "!" to Sand
                "ok" -> "✓" to Dim
                else -> "·" to Dim
            }
            Text(
                "$mark  ${i.name}: ${i.detail}",
                color = if (stale && i.isProblem) colour.copy(alpha = 0.6f) else colour,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

/** Open tasks from NRG's task register, as last checked on the L14. Tap the title line to fold. */
@Composable
private fun NrgTasks(vm: LauncherVM) {
    val t = vm.l14Status?.tasks ?: return
    val s = vm.store
    val at = t.checked.substringAfter('T', "").take(5)
    val head = "NRG tasks: ${t.open} open" + (if (t.broken > 0) ", ${t.broken} broken" else "") +
        (if (at.isNotEmpty()) "  ·  checked $at" else "") + if (s.l14TasksOpen.value) "  ▾" else "  ▸"
    Text(
        head, color = Dim, fontSize = 13.sp,
        modifier = Modifier.combinedClickable { s.l14TasksOpen.set(!s.l14TasksOpen.value) }.padding(vertical = 4.dp)
    )
    if (!s.l14TasksOpen.value) return
    Column(Modifier.padding(bottom = 8.dp)) {
        t.rows.forEach { r ->
            Text(
                "${r.id}  ${r.title}", color = Fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        if (t.rows.isEmpty()) Text("Nothing open", color = Dim, fontSize = 13.sp)
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
    val found = rememberLookups(query)
    val aiReady = remember { Brain.ready(ctx) }

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
                Box { if (query.isEmpty()) Text("Search or ask…", color = Faint, fontSize = 22.sp); inner() }
            },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(gestures)) {
            items(visible, key = { it.key }) { a -> AppRow(vm, a) }
            commandResults(vm, ctx as MainActivity, query, found, aiReady) { query = "" }
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
            DropdownMenuItem(text = { Text("Move or copy to group…  (${vm.groupOf(a)})") }, onClick = { menu = false; vm.moving = a })
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
        item { Toggle("Date and time in Polish", s.polishClock) }
        item { Toggle("Ask bar (assistant)", s.showAssistant) }
        item { Toggle("Polish phrase of the day", s.showPhrase) }
        item { Toggle("Widgets", s.showWidgets) }
        item { Toggle("Coinbase holdings", s.showCoinbase) }
        item { Toggle("Weather", s.showWeather) }
        item { Toggle("Inbox summary", s.showNotifs) }
        item { Toggle("Tasks", s.showTasks) }
        item { Toggle("Sky background", s.showSky) }
        item { Toggle("Weather on the sky", s.skyWeather) }
        item { Toggle("Turn the sky with the compass", s.skyCompass) }
        item { Toggle("App groups", s.showGroups) }
        item { Toggle("Favourites", s.showFavs) }
        item { Toggle("Termux shortcuts", s.showTermux) }
        item { Toggle("L14", s.showL14) }
        item { Toggle("Open keyboard with app list", s.autoKeyboard) }

        item { Header("Dune AI") }
        item { AiSettings(vm) }

        item { Header("Workspaces (two apps side by side)") }
        item {
            Note("One per line:  Name | first app | second app. Names can be the start of an app's name. " +
                "Tap a workspace on the home screen to open both in split screen.")
            Field("Workspaces", s.workspaces, multi = true)
        }
        item { Toggle("Show workspaces on the home screen", s.showWorkspaces) }

        item { Header("Coinbase") }
        item { CoinbaseSettings(vm) }

        item { Header("Drive mode") }
        item {
            Note("Drive mode opens when your phone connects to a car below, and closes when it disconnects. " +
                "It reads new messages aloud and lets you reply by voice.")
            Field("Home address (for “navigate home”)", s.homeAddress)
        }
        item { Toggle("Start automatically in the car", s.driveAuto) }
        item { Toggle("Read new messages aloud", s.driveReadAloud) }
        item { Toggle("Landscape Drive screen", s.driveLandscape) }
        item { WakeWordSettings(vm) }
        item { CarPicker(vm) }
        item { LinkRow("Start Drive mode now") { Drive.set(act, true); vm.screen = Screen.DRIVE } }

        item { Header("Weather") }
        item {
            if (s.weatherWidget.value.isNotBlank()) {
                Note("The weather spot shows a widget.")
                LinkRow("Change the weather widget") { vm.chooseWeatherWidget() }
                LinkRow("Make it taller") { (act as MainActivity).resizeWeatherWidget(40) }
                LinkRow("Make it shorter") { (act as MainActivity).resizeWeatherWidget(-40) }
                LinkRow("Use Dune's own weather instead") { (act as MainActivity).clearWeatherWidget() }
            } else LinkRow("Use a Weawow widget for the weather") { vm.chooseWeatherWidget() }
        }
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
            Note("Warnings (backups, services, disk, NRG jobs) come from l14-status on the L14. " +
                "Get its token on the L14 with:  l14-status.py token   Leave the token blank to turn warnings off.")
            Field("Status port", s.l14StatusPort, number = true)
            Field("Status token", s.l14StatusToken)
        }
        item {
            Note("Commands run on the L14 over SSH, one per line:  Label | command")
            Field("L14 commands", s.l14Lines, multi = true)
        }

        item { Header("Groups") }
        item {
            Note("Apps are sorted automatically; long-press any app › Move or copy to group to change one. " +
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
            Text("Dune 1.16", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 32.dp))
        }
    }
}

@Composable
internal fun LinkRow(label: String, onClick: () -> Unit) = Text(
    "$label  →", color = Fg, fontSize = 17.sp,
    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(vertical = 10.dp)
)

@Composable
internal fun Note(text: String) = Text(text, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))

/** "OK Computer": setting up the offline wake word for Drive mode. */
@Composable
private fun WakeWordSettings(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.resumeTick) { tick++ }
    val p = act.getSharedPreferences("dune", 0)
    var on by remember { mutableStateOf(WakeWord.enabled(act)) }
    val model = remember(tick, WakeWord.status) { WakeWord.hasModel(act) }
    val mic = remember(tick) { WakeWord.hasMic(act) }
    val popUp = remember(tick) { WakeWord.canPopUp(act) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable {
            on = !on; p.edit().putBoolean("wakeWord", on).apply(); WakeWord.sync(act)
        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Wake word “${WakeWord.PHRASE}” in Drive mode", color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null)
        }
        if (on) {
            Note("Say “OK Computer”, then your command, as if you'd tapped Speak. It listens offline and only in Drive mode.")
            if (!model) LinkRow("1. Download the speech model (about 40 MB)") {
                scope.launch { WakeWord.download(act); tick++; WakeWord.sync(act) }
            } else Note("✓ Speech model ready")
            if (!mic) LinkRow("2. Allow the microphone") { act.requestPerm(android.Manifest.permission.RECORD_AUDIO) { tick++; WakeWord.sync(act) } }
            else Note("✓ Microphone allowed")
            if (!popUp) LinkRow("3. Allow “Display over other apps” (so it works over Maps)") {
                act.tryStart(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
            } else Note("✓ Can open over other apps")
            if (WakeWord.status.isNotBlank() && WakeWord.status != "Ready") Note(WakeWord.status)
        }
    }
}

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
private fun GroupsBlock(vm: LauncherVM, title: Boolean = true) {
    val groups = vm.groupNames.map { it to vm.appsIn(it) }
        .filter { (n, l) -> l.isNotEmpty() || n.equals("Links", true) || vm.webLinksFor(n).isNotEmpty() }
    if (groups.isEmpty()) return
    if (title) Header("Groups")
    groups.forEach { (name, list) -> GroupRow(vm, name, list) }
}

/** One category on the groups page: just its name and how many things are in it. */
@Composable
private fun GroupRow(vm: LauncherVM, name: String, list: List<AppEntry>) {
    var menu by remember { mutableStateOf(false) }
    val count = list.size + vm.webLinksFor(name).size + (if (name.equals("Links", true)) vm.linkPinned.size else 0)
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onLongClick = { menu = true }, onClick = { vm.showGroup(name) })
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            if (count > 0) Text("$count", color = Dim, fontSize = 11.sp)
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
        val web = vm.webLinksFor(name)
        if (list.isEmpty() && web.isEmpty()) Text("No apps here yet", color = Dim, modifier = Modifier.padding(8.dp))
        val isLinks = name.equals("Links", true)
        if (isLinks) Text(
            "To add a page: open it in Chrome › ⋮ › Add to home screen. Long-press a link to remove it.",
            color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        LazyVerticalGrid(GridCells.Fixed(5), Modifier.weight(1f).nestedScroll(gestures)) {
            if (isLinks) gridItems(vm.linkPinned, key = { "l" + it.pkg + it.id }) { p -> GridLink(vm, p) }
            gridItems(list, key = { it.key }) { a -> GridApp(vm, a) }
            gridItems(web, key = { "w" + it.url }) { w -> GridWeb(vm, w) }
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
                .padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(vm, a, 40.dp)
            Text(
                a.label, color = Fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp)
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (menu) vm.appShortcuts(a).forEach { sc ->
                DropdownMenuItem(text = { Text("› " + (sc.shortLabel ?: "")) }, onClick = { menu = false; vm.launchShortcutInfo(sc) })
            }
            DropdownMenuItem(text = { Text("Move or copy to group…") }, onClick = { menu = false; vm.moving = a })
            vm.openGroup?.let { g -> if (vm.isCopyIn(a, g)) DropdownMenuItem(text = { Text("Remove from this group") },
                onClick = { menu = false; vm.store.toggleCopy(a.key, g) }) }
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
        title = { Text(a.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Tap a group to move it there; tap “copy” to show it in that group too.",
                    color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                val copies = vm.store.groupCopies[a.key].orEmpty()
                vm.groupNames.forEach { g ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (g == current) "●  $g" else "○  $g",
                            color = if (g == current) Sand else Fg, fontSize = 17.sp,
                            modifier = Modifier.weight(1f)
                                .combinedClickable { vm.store.setGroup(a.key, g); done() }
                                .padding(vertical = 9.dp)
                        )
                        if (g != current) {
                            val on = g in copies
                            Text(if (on) "✓ copy" else "+ copy", color = if (on) Sand else Dim, fontSize = 14.sp,
                                modifier = Modifier.combinedClickable { vm.store.toggleCopy(a.key, g) }.padding(9.dp))
                        }
                    }
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
            }) { Text(if (newName.isNotBlank()) "Create" else "Done") }
        },
    )
}

@Composable
fun LinkIcon(vm: LauncherVM, p: Pinned, size: Dp) {
    val bmp = vm.shortcutIcon(p)
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(Faint), contentAlignment = Alignment.Center) {
        Text(p.label.take(1).uppercase(), color = Fg, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
private fun GridLink(vm: LauncherVM, p: Pinned) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }, onClick = { vm.launchPinned(p) })
                .padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinkIcon(vm, p, 40.dp)
            Text(
                p.label, color = Fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp)
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove link") }, onClick = { menu = false; vm.store.removePinned(p) })
        }
    }
}

@Composable
private fun CarPicker(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val s = vm.store
    var tick by remember { mutableIntStateOf(0) }
    val devices = remember(tick) { Drive.pairedDevices(act) }
    if (!Drive.hasBtPermission(act) || devices.isEmpty()) {
        LinkRow("Choose your car's Bluetooth") {
            val after: () -> Unit = {
                tick++
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    act.requestPerm(Manifest.permission.POST_NOTIFICATIONS) { }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) act.requestPerm(Manifest.permission.BLUETOOTH_CONNECT) { ok ->
                if (ok) after() else { act.toast("Allow Nearby devices so Dune can see your car"); Actions.appSettings(act) }
            } else after()
        }
        if (Drive.hasBtPermission(act) && devices.isEmpty()) Note("No paired Bluetooth devices – pair the car in Android settings first.")
        return
    }
    Note("Tick your car(s):")
    devices.forEach { (name, addr) ->
        val on = addr in s.carDevices
        Text(
            (if (on) "☑  " else "☐  ") + name, color = if (on) Sand else Fg, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().combinedClickable { s.setCar(addr, !on) }.padding(vertical = 8.dp)
        )
    }
}

private fun money(v: Double) = "£" + String.format(java.util.Locale.UK, "%,.2f", v)
private fun amount(v: Double) = when {
    v >= 1000 -> String.format(java.util.Locale.UK, "%,.0f", v)
    v >= 1 -> String.format(java.util.Locale.UK, "%,.4f", v).trimEnd('0').trimEnd('.')
    else -> String.format(java.util.Locale.UK, "%.8f", v).trimEnd('0').trimEnd('.')
}

@Composable
private fun CoinbaseBlock(vm: LauncherVM) {
    val ctx = LocalContext.current
    val s = vm.store
    val p = vm.portfolio
    val hidden = s.hideBalances.value
    Header("Coinbase", if (hidden) "show" else "hide") { s.hideBalances.set(!hidden) }
    Column(Modifier.fillMaxWidth().combinedClickable(
        onLongClick = { vm.refreshCoinbase(true) },
        onClick = { if (!ctx.launchPackage("com.coinbase.android")) ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coinbase.com/home"))) },
    )) {
        if (p != null) {
            Text(if (hidden) "£ • • • • •" else money(p.totalGbp), color = Sand, fontSize = 30.sp, fontWeight = FontWeight.Light)
            if (!hidden) p.holdings.filter { (it.valueGbp ?: 0.0) >= 0.01 || it.valueGbp == null }.take(6).forEach { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(h.currency, color = Fg, fontSize = 16.sp, modifier = Modifier.width(64.dp))
                    Text(amount(h.amount), color = Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(h.valueGbp?.let { money(it) } ?: "–", color = Fg, fontSize = 15.sp)
                }
            }
            val mins = ((System.currentTimeMillis() - p.at) / 60_000).toInt()
            Text(if (mins < 1) "updated just now · long-press to refresh" else "updated $mins min ago · long-press to refresh",
                color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        vm.coinbaseMsg?.let { Text(it, color = Dim, fontSize = 14.sp) }
    }
}

@Composable
private fun CoinbaseSettings(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    if (vm.coinbaseKey != null) {
        Note("Connected with key …" + vm.coinbaseKey!!.name.takeLast(8) + ". Balances refresh every 10 minutes.")
        LinkRow("Refresh now") { vm.refreshCoinbase(true) }
        LinkRow("Remove the Coinbase key") { vm.saveCoinbaseKey(null); act.toast("Coinbase key removed") }
        return
    }
    var name by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    Note("Create a key at portal.cdp.coinbase.com › API keys › Secret API keys: tick View only (no Trade, no Transfer), " +
        "choose ECDSA as the signature algorithm, then download the key file. Dune keeps it encrypted on this phone.")
    LinkRow("Load the downloaded key file") { act.pickCoinbaseKeyFile() }
    Note("…or paste the key name and private key:")
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Key name (organizations/…/apiKeys/…)") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
    OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("Private key (-----BEGIN EC PRIVATE KEY-----…)") },
        minLines = 3, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
    LinkRow("Save and connect") {
        val k = Coinbase.parseKey(name, secret)
        if (k == null) act.toast("Paste both the key name and the private key")
        else { vm.saveCoinbaseKey(k); name = ""; secret = "" }
    }
}

/** A web app's own icon, or a sand-coloured initial while it loads / if it has none. */
@Composable
fun WebIcon(vm: LauncherVM, w: WebApp, size: Dp) {
    val bmp = vm.webIcon(w)
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Color(0xFF2A2620)), contentAlignment = Alignment.Center) {
        Text(w.label.take(1).uppercase(), color = Sand, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
private fun GridWeb(vm: LauncherVM, w: WebApp) {
    val key = "web:" + w.url
    val fav = key in vm.store.favourites
    var menu by remember { mutableStateOf(false) }
    Box {
    DropdownMenu(menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text(if (fav) "Remove from favourites" else "Add to favourites") },
            onClick = { menu = false; vm.store.toggleFavourite(key) })
    }
    Column(
        Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { vm.openWeb(w) }.padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WebIcon(vm, w, 40.dp)
        Text(
            w.label, color = Fg, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp)
        )
    }
    }
}



/** A side page of the home screen: a heading and a scrolling list, over the sky. */
@Composable
internal fun SidePage(title: String, gestures: NestedScrollConnection, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().nestedScroll(gestures).padding(horizontal = 24.dp)) {
        item { Text(title, color = Fg, fontSize = 30.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 36.dp, bottom = 12.dp)) }
        content()
        item { Spacer(Modifier.height(32.dp)) }
    }
}

const val HOME_PAGE = 2

/** Rozmowa on its own, filling the screen like an app: no browser bar, conversation kept. Back returns home. */
@Composable
private fun RozmowaFull() {
    val act = LocalContext.current as MainActivity
    AndroidView(
        factory = { act.rozmowa().detached() },
        modifier = Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding(),
    )
}

/** Rozmowa, Martin's Polish conversation app, running inside Dune with the mic and voice working. */
@Composable
private fun RozmowaPage(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rozmowa", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            Text("reload", color = Dim, fontSize = 13.sp,
                modifier = Modifier.combinedClickable { act.rozmowa().view.reload() }.padding(10.dp))
            Text("full screen", color = Dim, fontSize = 13.sp,
                modifier = Modifier.combinedClickable { vm.screen = Screen.ROZMOWA }.padding(10.dp))
        }
        AndroidView(
            factory = { act.rozmowa().detached() },
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        )
    }
}
