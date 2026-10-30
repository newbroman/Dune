package uk.newbroman.dune

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Drive-mode state, shared by the launcher, the car receiver and the notification listener. */
object Drive {
    val active = MutableStateFlow(false)
    private const val CHANNEL = "drive"
    private const val NOTIF_ID = 77

    fun init(ctx: Context) {
        active.value = ctx.getSharedPreferences("dune", Context.MODE_PRIVATE).getBoolean("driveActive", false)
    }

    fun set(ctx: Context, on: Boolean) {
        active.value = on
        ctx.getSharedPreferences("dune", Context.MODE_PRIVATE).edit().putBoolean("driveActive", on).apply()
        WakeWord.sync(ctx)
        if (!on) {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            Speaker.stop()
        }
    }

    fun intent(ctx: Context) = Intent(ctx, MainActivity::class.java)
        .putExtra("drive", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Car connected: switch on, then open the Drive screen (or offer it as a notification). */
    fun carConnected(ctx: Context) {
        set(ctx, true)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Drive mode", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 1, intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Drive mode")
            .setContentText("Car connected – tap to open Drive mode")
            .setContentIntent(pi).setAutoCancel(true).setCategory(android.app.Notification.CATEGORY_NAVIGATION)
            .build()
        if (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            nm.notify(NOTIF_ID, n)
        // Works whenever Dune is on screen (it usually is when you get in); otherwise the notification does it.
        runCatching { ctx.startActivity(intent(ctx)) }
    }

    fun readMessages(ctx: Context) {
        val msgs = NotifService.items.value.filter { it.isMessage }.take(5)
        if (msgs.isEmpty()) { Speaker.speak(ctx, "No new messages"); return }
        msgs.forEachIndexed { i, n ->
            Speaker.speak(ctx, "From ${n.title}. ${n.text}", flush = i == 0)
        }
    }

    fun hasBtPermission(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<Pair<String, String>> {
        if (!hasBtPermission(ctx)) return emptyList()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.map { (it.name ?: it.address) to it.address } }.getOrDefault(emptyList())
            .sortedBy { it.first.lowercase() }
    }
}

/** Starts and stops Drive mode when one of your chosen car Bluetooth devices connects. */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val addr = dev?.address ?: return
        val store = Store.get(ctx)
        if (!store.driveAuto.value || addr !in store.carDevices) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> Drive.carConnected(ctx)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> Drive.set(ctx, false)
        }
    }
}

/** What's playing, via the media sessions Dune can see through its notification access. */
class MediaWatcher(private val ctx: Context) {
    var title by mutableStateOf<String?>(null); private set
    var artist by mutableStateOf<String?>(null); private set
    var appPkg by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set

    private var controller: MediaController? = null
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, NotifService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onSessionDestroyed() { pick() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick() }
    private var started = false

    fun start() {
        if (started) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
        } catch (e: SecurityException) { return }
        pick()
    }

    fun stop() {
        if (!started) return
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(cb); controller = null; started = false
    }

    /** Look at the sessions once without registering a listener (used outside Drive mode). */
    private fun pickOnce() {
        val sessions = try { msm.getActiveSessions(component) } catch (e: SecurityException) { emptyList() }
        controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        update()
    }

    private fun pick() {
        val sessions = try { msm.getActiveSessions(component) } catch (e: SecurityException) { emptyList() }
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb, handler)
        }
        update()
    }

    private fun update() {
        val c = controller
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        appPkg = c?.packageName
        playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    /** play, pause, toggle, next, previous. False when there's no media session to control. */
    fun control(action: String): Boolean {
        if (!started) { start(); if (!started) pickOnce() } else pick()
        val t = controller?.transportControls ?: return false
        when (action) {
            "play" -> t.play(); "pause" -> t.pause(); "next" -> t.skipToNext(); "previous" -> t.skipToPrevious()
            "toggle" -> if (playing) t.pause() else t.play()
        }
        handler.postDelayed({ update() }, 400)
        return true
    }
}

// ---------------------------------------------------------------- Drive screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveScreen(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val s = vm.store
    val media = vm.media
    val items by NotifService.items.collectAsState()
    val messages = items.filter { it.isMessage }
    var showMessages by remember { mutableStateOf(false) }
    val landscapeWanted = s.driveLandscape.value

    DisposableEffect(landscapeWanted) {
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Lock to landscape (either way up, so it suits the cradle) while driving.
        act.requestedOrientation = if (landscapeWanted) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        media.start()
        WakeWord.sync(act)   // starting the microphone needs Dune on screen, so (re)try here
        onDispose {
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            media.stop()
        }
    }
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(1000L - System.currentTimeMillis() % 1000) }
    }
    val time = now.format(DateTimeFormatter.ofPattern(if (s.clock24.value) "HH:mm" else "h:mm"))

    val tiles: List<TileSpec> = listOf(
        TileSpec("Home", "navigate") { act.runCmd(Cmd.Navigate(null)) },
        TileSpec("Navigate…", "say where") { act.listen("Where to?") { it?.let { d -> act.runCmd(Cmd.Navigate(d)) } } },
        TileSpec("Messages", if (messages.isEmpty()) "none new" else "${messages.size} new", messages.isNotEmpty()) { showMessages = true },
        TileSpec("Spotify", null) { act.runCmd(Cmd.Spotify(null)) },
        TileSpec("BBC Sounds", null) {
            if (!act.launchPackage(BBC_SOUNDS)) act.tryStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.bbc.co.uk/sounds")))
        },
        TileSpec("Spheres", "music of the spheres") { Actions.openSpheres(act) },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(12.dp)) {
        if (maxWidth > maxHeight) {
            // Landscape: controls on the left, big buttons (or messages) on the right.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.42f).fillMaxHeight().padding(end = 10.dp)) {
                    TopBar(vm, time, compact = true)
                    Spacer(Modifier.height(6.dp))
                    NowPlaying(vm, act, compact = true)
                    Spacer(Modifier.weight(1f))
                    vm.assistantReply?.let { Text(it, color = Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 4.dp)) }
                    SpeakButton(act, Modifier.fillMaxWidth().height(76.dp))
                }
                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                    if (showMessages) MessagesPanel(vm, messages) { showMessages = false }
                    else TileGrid(tiles, columns = 3, fill = true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, time, compact = false)
                Spacer(Modifier.height(10.dp))
                NowPlaying(vm, act, compact = false)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f)) {
                    if (showMessages) MessagesPanel(vm, messages) { showMessages = false }
                    else TileGrid(tiles, columns = 2, fill = false)
                }
                vm.assistantReply?.let { Text(it, color = Dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp)) }
                SpeakButton(act, Modifier.fillMaxWidth().height(80.dp))
            }
        }
    }

    vm.pendingReply?.let { ReplyConfirm(vm, it) }
}

private class TileSpec(val title: String, val sub: String?, val highlight: Boolean = false, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(vm: LauncherVM, time: String, compact: Boolean) {
    val act = LocalContext.current as MainActivity
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = Fg, fontSize = if (compact) 36.sp else 44.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(12.dp))
        Text("DRIVE", color = Sand, fontSize = 13.sp, letterSpacing = 3.sp)
        Spacer(Modifier.weight(1f))
        Text("exit ✕", color = Dim, fontSize = 18.sp, modifier = Modifier
            .combinedClickable { Drive.set(act, false); vm.goHome() }.padding(12.dp))
    }
}

@Composable
private fun NowPlaying(vm: LauncherVM, act: MainActivity, compact: Boolean) {
    val media = vm.media
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF15140F), border = BorderStroke(1.dp, Faint),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp)) {
            val app = media.appPkg?.let { vm.labelFor(it) }
            Text(media.title ?: "Nothing playing", color = Fg, fontSize = if (compact) 19.sp else 22.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(media.artist, app).joinToString("  ·  ").ifEmpty { " " }, color = Dim, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigIcon("⏮") { media.control("previous") }
                BigIcon(if (media.playing) "⏸" else "▶") {
                    if (!media.control("toggle")) act.toast("No player found – start Spotify or BBC Sounds, or re-allow Dune's notification access")
                }
                BigIcon("⏭") { media.control("next") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeakButton(act: MainActivity, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(40.dp), color = Sand, modifier = modifier
        .combinedClickable(onLongClick = { act.tryStart(Intent(Intent.ACTION_VOICE_COMMAND)) }) {
            act.listen("Say a command") { it?.let { t -> act.handleAsk(t, speak = true) } }
        }) {
        Box(contentAlignment = Alignment.Center) {
            FitText("🎤  Speak", Bg, 26.sp, FontWeight.Medium)
        }
    }
}

/** Big buttons in a grid. With [fill] the rows share the available height (landscape). */
@Composable
private fun TileGrid(tiles: List<TileSpec>, columns: Int, fill: Boolean) {
    val rows = tiles.chunked(columns)
    Column(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        rows.forEach { row ->
            Row(if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()) {
                row.forEach { t -> Tile(t.title, t.sub, t.highlight, fill, t.onClick) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One line of text that shrinks until it fits, rather than wrapping (e.g. "Message" / "s"). */
@Composable
private fun FitText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight? = null,
                    modifier: Modifier = Modifier) {
    var scale by remember(text, size) { mutableFloatStateOf(1f) }
    var ready by remember(text, size) { mutableStateOf(false) }
    Text(
        text, color = color, fontSize = size * scale, fontWeight = weight, maxLines = 1, softWrap = false,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r ->
            if (r.didOverflowWidth && scale > 0.45f) scale *= 0.9f else ready = true
        },
    )
}

@Composable
private fun BigIcon(label: String, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).background(Color(0xFF221F1A), RoundedCornerShape(36.dp))
        .combinedClickableCompat(onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Fg, fontSize = 28.sp)
    }
}

@Composable
private fun RowScope.Tile(title: String, sub: String?, highlight: Boolean = false, fill: Boolean = false, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF15140F),
        border = BorderStroke(1.dp, if (highlight) Sand else Faint),
        modifier = Modifier.weight(1f).then(if (fill) Modifier.fillMaxHeight() else Modifier.height(104.dp))
            .padding(5.dp).combinedClickableCompat(onClick)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.Center) {
            FitText(title, Fg, 22.sp)
            if (sub != null) FitText(sub, if (highlight) Sand else Dim, 14.sp)
        }
    }
}

@Composable
private fun MessagesPanel(vm: LauncherVM, messages: List<NotifItem>, close: () -> Unit) {
    val act = LocalContext.current as MainActivity
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Messages", color = Fg, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            Text("read all", color = Sand, fontSize = 16.sp, modifier = Modifier.combinedClickableCompat { Drive.readMessages(act) }.padding(10.dp))
            Text("back", color = Dim, fontSize = 16.sp, modifier = Modifier.combinedClickableCompat(close).padding(10.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (messages.isEmpty()) Text("No new messages", color = Dim, fontSize = 18.sp, modifier = Modifier.padding(12.dp))
            messages.take(8).forEach { n ->
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF15140F), border = BorderStroke(1.dp, Faint),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${n.title}  ·  ${vm.labelFor(n.pkg)}", color = Fg, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.text, color = Dim, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 8.dp)) {
                            Pill("Read") { Speaker.speak(act, "From ${n.title}. ${n.text}") }
                            if (n.reply != null) Pill("Reply") {
                                act.listen("Reply to ${n.title}") { it?.let { t -> vm.pendingReply = PendingReply(n, t) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF221F1A), modifier = Modifier.padding(end = 10.dp)
        .combinedClickableCompat(onClick)) {
        FitText(label, Fg, 18.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

/** Read the reply back, then send it after a few seconds unless cancelled. */
@Composable
private fun ReplyConfirm(vm: LauncherVM, r: PendingReply) {
    val act = LocalContext.current as MainActivity
    var left by remember(r) { mutableIntStateOf(6) }
    LaunchedEffect(r) {
        Speaker.speak(act, "Replying to ${r.to.title}: ${r.text}. Sending in a few seconds.")
        while (left > 0) { delay(1000); left = maxOf(0, left - 1) }
        val ok = NotifService.sendReply(act, r.to, r.text)
        Speaker.speak(act, if (ok) "Sent" else "Sorry, that didn't send")
        vm.pendingReply = null
    }
    Box(Modifier.fillMaxSize().background(Color(0xEE000000)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("To ${r.to.title}", color = Dim, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text("“${r.text}”", color = Fg, fontSize = 26.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Text("Sending in $left…", color = Sand, fontSize = 18.sp)
            Spacer(Modifier.height(24.dp))
            Row {
                Pill("Cancel") { vm.pendingReply = null; Speaker.speak(act, "Cancelled") }
                Pill("Send now") { left = 0 }
            }
        }
    }
}

// Small helpers so this file reads cleanly.
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)
