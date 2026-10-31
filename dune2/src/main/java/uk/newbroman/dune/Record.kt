@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- where captures go

/** Photos, videos and recordings Dune makes go into their own "Dune" folders, so every gallery app sees them. */
object Captures {
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val sub = Environment.DIRECTORY_PICTURES + "/Dune"
    val movies = Environment.DIRECTORY_MOVIES + "/Dune"
    val audio get() = (if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC) + "/Dune"

    fun photoValues() = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Dune_${stamp()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, sub)
    }
    fun videoValues() = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Dune_${stamp()}.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, movies)
    }

    /** Last known position, for geotagging photos (null if Dune may not know it). */
    @SuppressLint("MissingPermission")
    fun where(ctx: Context): android.location.Location? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
}

fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------- the camera

/** Dune's own camera: photo or video, saved to Pictures/Dune or Movies/Dune, photos geotagged. */
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    var granted by remember { mutableStateOf(hasPerm(act, Manifest.permission.CAMERA)) }
    LaunchedEffect(Unit) {
        // One request for everything the camera needs (two at once would cancel each other).
        val want = if (vm.cameraVideo && !hasPerm(act, Manifest.permission.RECORD_AUDIO)) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                   else arrayOf(Manifest.permission.CAMERA)
        if (!granted) act.requestPerms(want) { r -> granted = r[Manifest.permission.CAMERA] == true || hasPerm(act, Manifest.permission.CAMERA); if (!granted) { vm.goHome(); vm.jumpHub = "record" } }
        else if (want.size > 1) act.requestPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) { }
    }
    if (!granted) { Box(Modifier.fillMaxSize().background(Color.Black)); return }

    var video by rememberSaveable { mutableStateOf(vm.cameraVideo) }
    var back by rememberSaveable { mutableStateOf(true) }
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recStart by remember { mutableLongStateOf(0L) }
    var last by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }

    val preview = remember { PreviewView(act).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember {
        VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build())
    }
    imageCapture.flashMode = flash

    // Keep photos and videos the right way up (Dune handles rotation itself, so CameraX isn't told).
    DisposableEffect(Unit) {
        val l = object : android.view.OrientationEventListener(act) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) { in 45..134 -> android.view.Surface.ROTATION_270; in 135..224 -> android.view.Surface.ROTATION_180
                    in 225..314 -> android.view.Surface.ROTATION_90; else -> android.view.Surface.ROTATION_0 }
                imageCapture.targetRotation = r; videoCapture.targetRotation = r
            }
        }
        l.enable(); onDispose { l.disable() }
    }
    DisposableEffect(back, video) {
        var disposed = false
        val future = ProcessCameraProvider.getInstance(act)
        future.addListener({
            if (disposed) return@addListener
            runCatching {
                val p = future.get()
                p.unbindAll()
                val pv = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val sel = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if (video) p.bindToLifecycle(act, sel, pv, videoCapture) else p.bindToLifecycle(act, sel, pv, imageCapture)
            }.onFailure { act.toast("Camera unavailable: ${it.message}") }
        }, ContextCompat.getMainExecutor(act))
        onDispose { disposed = true; if (future.isDone) runCatching { future.get().unbindAll() } }
    }
    DisposableEffect(Unit) { onDispose { recording?.stop() } }

    val shoot: () -> Unit = {
        if (!video && !busy) {
            busy = true
            val meta = ImageCapture.Metadata().apply { location = Captures.where(act); isReversedHorizontal = !back }
            val out = ImageCapture.OutputFileOptions.Builder(act.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Captures.photoValues())
                .setMetadata(meta).build()
            imageCapture.takePicture(out, ContextCompat.getMainExecutor(act), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) { busy = false; last = r.savedUri; Gallery.changed.value++ }
                override fun onError(e: ImageCaptureException) { busy = false; act.toast("Couldn't save the photo: ${e.message}") }
            })
        } else if (video) {
            val r = recording
            if (r != null) { r.stop(); recording = null }
            else {
                val opts = MediaStoreOutputOptions.Builder(act.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(Captures.videoValues()).setLocation(Captures.where(act)).build()
                var pending = videoCapture.output.prepareRecording(act, opts)
                if (hasPerm(act, Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
                recStart = System.currentTimeMillis()
                recording = pending.start(ContextCompat.getMainExecutor(act)) { e ->
                    if (e is VideoRecordEvent.Finalize) {
                        recording = null
                        if (e.hasError()) act.toast("Video not saved (${e.error})") else { last = e.outputResults.outputUri; Gallery.changed.value++ }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", color = Color.White, fontSize = 22.sp, modifier = Modifier.combinedClickable { vm.goHome(); vm.jumpHub = "record" }.padding(8.dp))
                Spacer(Modifier.weight(1f))
                if (!video) Text(when (flash) { ImageCapture.FLASH_MODE_ON -> "⚡ on"; ImageCapture.FLASH_MODE_OFF -> "⚡ off"; else -> "⚡ auto" },
                    color = Color.White, fontSize = 15.sp, modifier = Modifier.combinedClickable {
                        flash = when (flash) { ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF; else -> ImageCapture.FLASH_MODE_AUTO }
                    }.padding(8.dp))
                if (recording != null) {
                    val now by produceState(System.currentTimeMillis()) { while (true) { delay(500); value = System.currentTimeMillis() } }
                    val s = (now - recStart) / 1000
                    Text("● %d:%02d".format(s / 60, s % 60), color = Color(0xFFFF5A5A), fontSize = 16.sp, modifier = Modifier.padding(8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
                listOf(false to "PHOTO", true to "VIDEO").forEach { (v, l) ->
                    Text(l, color = if (video == v) Sand else Color.White, fontSize = 14.sp, modifier = Modifier
                        .combinedClickable { if (recording == null) video = v }.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 28.dp, start = 28.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                // last capture: tap to view
                Box(Modifier.size(54.dp).border(2.dp, Color.White, RoundedCornerShape(10.dp)).combinedClickable { last?.let { Gallery.view(act, it) } }) {
                    val thumb by produceState<ImageBitmap?>(null, last) { value = last?.let { Gallery.thumb(act, it) } }
                    thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                    .background(if (video) Color(0xFFE53935) else Color.White, if (recording != null) RoundedCornerShape(10.dp) else CircleShape)
                    .combinedClickable(onClick = shoot))
                Spacer(Modifier.weight(1f))
                Text("⟲", color = Color.White, fontSize = 30.sp, modifier = Modifier.size(54.dp).combinedClickable { if (recording == null) back = !back }
                    .padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------- the audio recorder

/** Records sound to Recordings/Dune, carrying on with the screen off. */
object AudioRec {
    val recording = MutableStateFlow(false)
    val started = MutableStateFlow(0L)
    /** 0–1, for the level meter. */
    val level = MutableStateFlow(0f)
    fun start(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, AudioRecService::class.java)) }
        .onFailure { ctx.toast("Couldn't start recording: ${it.message}") }
    fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AudioRecService::class.java))
}

class AudioRecService : Service() {
    private var rec: MediaRecorder? = null
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private val main = Handler(Looper.getMainLooper())
    private val meter = object : Runnable {
        override fun run() { rec?.let { r -> AudioRec.level.value = (runCatching { r.maxAmplitude }.getOrDefault(0) / 32767f).coerceIn(0f, 1f); main.postDelayed(this, 150) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Audio recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java).putExtra("hub", "record")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "rec").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording audio").setContentText("Open Dune's Record hub to stop").setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(80, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(80, n)
        WakeWord.pause(this)   // the wake word listener lets go of the microphone
        try {
            val name = "Recording " + SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date()) + ".m4a"
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000); r.setAudioSamplingRate(44_100)
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Captures.audio); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                pfd = contentResolver.openFileDescriptor(uri!!, "w")
                r.setOutputFile(pfd!!.fileDescriptor)
            } else {
                r.setOutputFile(File(File(filesDir, "recordings").apply { mkdirs() }, name).absolutePath)
            }
            r.prepare(); r.start()
            rec = r
            AudioRec.recording.value = true; AudioRec.started.value = System.currentTimeMillis()
            main.post(meter)
        } catch (e: Exception) {
            toast("Couldn't record: ${e.message}")
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            stopSelf()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        val ok = rec?.let { r -> val s = runCatching { r.stop() }.isSuccess; r.release(); s } ?: false
        rec = null
        runCatching { pfd?.close() }
        uri?.let { u ->
            // stop() fails on a recording of under a second: don't leave a broken file behind
            if (ok) runCatching { contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
            else { runCatching { contentResolver.delete(u, null, null) }; toast("Too short to keep") }
        }
        AudioRec.recording.value = false; AudioRec.level.value = 0f
        Gallery.changed.value++
        WakeWord.resume(this)
        super.onDestroy()
    }
}

// ---------------------------------------------------------------- the gallery

data class MediaItem(val uri: Uri, val name: String, val time: Long, val durationMs: Long, val video: Boolean)

object Gallery {
    val changed = MutableStateFlow(0)

    fun readPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun canRead(ctx: Context) = readPermissions().all { hasPerm(ctx, it) } ||
        (Build.VERSION.SDK_INT >= 34 && hasPerm(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))

    /** Newest photos and videos (or only Dune's own when [duneOnly]). */
    fun visual(ctx: Context, duneOnly: Boolean, kind: String, limit: Int = 90): List<MediaItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val types = when (kind) { "photo" -> "1"; "video" -> "3"; else -> "1,3" }
        var sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($types)"
        if (duneOnly && Build.VERSION.SDK_INT >= 29) sel += " AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE '%/Dune/%'"
        val out = ArrayList<MediaItem>()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, "duration"),
                sel, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val video = c.getInt(1) == 3
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += MediaItem(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(2) ?: "", c.getLong(3) * 1000, if (video) c.getLong(4) else 0, video)
                }
            }
        }
        return out
    }

    /** Dune's audio recordings, newest first. */
    fun recordings(ctx: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE '%/Dune/%'", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) out += MediaItem(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1) ?: "", c.getLong(2) * 1000, c.getLong(3), false)
            }
        } else (File(ctx.filesDir, "recordings").listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }.forEach { f ->
            out += MediaItem(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f), f.name, f.lastModified(), 0, false)
        }
        return out
    }

    suspend fun thumb(ctx: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(240, 240), null).asImageBitmap()
            else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, ContentUris.parseId(uri),
                MediaStore.Images.Thumbnails.MINI_KIND, null)?.asImageBitmap()
        }.getOrNull()
    }

    fun view(ctx: Context, uri: Uri) = ctx.tryStart(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    fun share(ctx: Context, uri: Uri, mime: String) = ctx.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** One sound playing at a time, from the Record hub. */
object Player {
    private var mp: MediaPlayer? = null
    val playing = MutableStateFlow<Uri?>(null)
    fun toggle(ctx: Context, uri: Uri) {
        val was = playing.value
        stop()
        if (was == uri) return
        mp = runCatching { MediaPlayer.create(ctx, uri) }.getOrNull()?.apply {
            setOnCompletionListener { stop() }; start()
        }
        playing.value = if (mp != null) uri else null
        if (mp == null) ctx.toast("Can't play that recording")
    }
    fun stop() { mp?.let { runCatching { it.stop() }; it.release() }; mp = null; playing.value = null }
}

// ---------------------------------------------------------------- the hub

object RecordHub : Hub {
    override val id = "record"; override val title = "Record"; override val glyph = "◎"
    private val WORDS = listOf("camera", "recorder", "voice rec", "gallery", "photos", "scanner", "lens")

    @Composable override fun summary(vm: LauncherVM): String? {
        val ctx = LocalContext.current
        val rec by AudioRec.recording.collectAsState()
        val track by Tracks.recording.collectAsState()
        val tick by Gallery.changed.collectAsState()
        val today by produceState(-1, tick) {
            value = withContext(Dispatchers.IO) {
                val midnight = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (Gallery.canRead(ctx)) Gallery.visual(ctx, false, "all", 200).count { it.time >= midnight } else -1
            }
        }
        return when {
            rec -> "● Recording audio"
            track -> "● Recording a track"
            today > 0 -> "$today photo" + (if (today == 1) "" else "s") + " and videos today"
            else -> null
        }
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        val cameraApps = remember {
            listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_IMAGE_CAPTURE).flatMap { a ->
                act.packageManager.queryIntentActivities(Intent(a), 0).map { it.activityInfo.packageName }
            }.distinct().filter { it != act.packageName }
        }
        var tab by rememberSaveable { mutableStateOf("photos") }
        var duneOnly by rememberSaveable { mutableStateOf(false) }
        var canRead by remember { mutableStateOf(Gallery.canRead(act)) }
        val tick by Gallery.changed.collectAsState()
        val savedTracks by Tracks.saved.collectAsState()
        val items by produceState(emptyList<MediaItem>(), tab, duneOnly, tick, canRead, active) {
            if (!active) return@produceState
            value = withContext(Dispatchers.IO) {
                when (tab) {
                    "photos" -> if (canRead || duneOnly) Gallery.visual(act, duneOnly || !canRead, "photo") else emptyList()
                    "videos" -> if (canRead || duneOnly) Gallery.visual(act, duneOnly || !canRead, "video") else emptyList()
                    "audio" -> Gallery.recordings(act)
                    else -> emptyList()
                }
            }
        }
        val tracks by produceState(emptyList<SavedTrack>(), savedTracks, tab) {
            if (tab == "tracks") value = withContext(Dispatchers.IO) { Tracks.list(act) }
        }

        HubPage("Record", gestures) {
            item {
                AppsRow(vm, id, cameraApps, WORDS)
                // Capture
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigAction("📷", "Photo") { vm.cameraVideo = false; vm.screen = Screen.CAMERA }
                    BigAction("🎥", "Video") { vm.cameraVideo = true; vm.screen = Screen.CAMERA }
                    AudioButton()
                    val trk by Tracks.recording.collectAsState()
                    BigAction(if (trk) "■" else "⌖", if (trk) "Stop track" else "Track") {
                        if (trk) Tracks.stop(act) else vm.jumpHub = "map"
                    }
                }
                AudioMeter()
                // Gallery tabs
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    listOf("photos" to "Photos", "videos" to "Videos", "audio" to "Audio", "tracks" to "Tracks").forEach { (k, l) ->
                        Text(l, color = if (tab == k) Sand else Dim, fontSize = 15.sp, modifier = Modifier.combinedClickable { tab = k }.padding(end = 16.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
                if (tab == "photos" || tab == "videos") Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (duneOnly || !canRead) "● Dune's only" else "○ Dune's only", color = Dim, fontSize = 13.sp,
                        modifier = Modifier.combinedClickable { duneOnly = !duneOnly }.padding(vertical = 4.dp))
                    if (!canRead) Text("   Allow access to see all your photos", color = Sand, fontSize = 13.sp, modifier = Modifier.combinedClickable {
                        act.requestPerms(Gallery.readPermissions()) { canRead = Gallery.canRead(act) }
                    }.padding(vertical = 4.dp))
                }
            }
            when (tab) {
                "photos", "videos" -> items.chunked(3).forEachIndexed { r, row ->
                    item(key = "g$tab$r") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { m -> Thumb(m, Modifier.weight(1f)) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                "audio" -> {
                    if (items.isEmpty()) item { Note("Your recordings will appear here.") }
                    items.forEach { m -> item(key = "a" + m.uri) { AudioRow(m) } }
                }
                else -> {
                    if (tracks.isEmpty()) item { Note("Record a track from the Map hub (● track) and it will appear here.") }
                    tracks.forEach { t -> item(key = "t" + t.file.name) { TrackRow(vm, t) } }
                }
            }
            if ((tab == "photos" || tab == "videos") && items.isEmpty()) item {
                Note(if (duneOnly || !canRead) "Nothing taken with Dune yet." else "No ${tab} found.")
            }
        }
    }
}

@Composable
private fun BigAction(icon: String, label: String, onClick: () -> Unit) {
    Column(Modifier.width(74.dp).border(1.dp, Faint, RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 24.sp, color = Fg)
        Text(label, fontSize = 12.sp, color = Dim, maxLines = 1)
    }
}

@Composable
private fun AudioButton() {
    val act = LocalContext.current as MainActivity
    val rec by AudioRec.recording.collectAsState()
    val started by AudioRec.started.collectAsState()
    val now by produceState(System.currentTimeMillis(), rec) { while (rec) { delay(500); value = System.currentTimeMillis() } }
    val s = ((now - started) / 1000).coerceAtLeast(0)
    BigAction(if (rec) "■" else "🎙", if (rec) "%d:%02d".format(s / 60, s % 60) else "Audio") {
        if (rec) AudioRec.stop(act)
        else act.requestPerm(Manifest.permission.RECORD_AUDIO) { ok ->
            if (ok) { act.requestPerm(Manifest.permission.POST_NOTIFICATIONS) { }; AudioRec.start(act) } else act.toast("Recording needs the microphone")
        }
    }
}

@Composable
private fun AudioMeter() {
    val rec by AudioRec.recording.collectAsState()
    val lvl by AudioRec.level.collectAsState()
    if (!rec) return
    Box(Modifier.fillMaxWidth().height(6.dp).background(Faint, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(lvl.coerceAtLeast(.02f)).height(6.dp).background(Color(0xFFE53935), RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun Thumb(m: MediaItem, modifier: Modifier) {
    val act = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, m.uri) { value = Gallery.thumb(act, m.uri) }
    var menu by remember { mutableStateOf(false) }
    Box(modifier.aspectRatio(1f).background(Color(0xFF15140F), RoundedCornerShape(6.dp))
        .combinedClickable(onLongClick = { menu = true }, onClick = { Gallery.view(act, m.uri) })) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (m.video) Text("▶ " + dur(m.durationMs), color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp))
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; Gallery.view(act, m.uri) })
            DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; Gallery.share(act, m.uri, if (m.video) "video/*" else "image/*") })
        }
    }
}

@Composable
private fun AudioRow(m: MediaItem) {
    val act = LocalContext.current
    val playing by Player.playing.collectAsState()
    val on = playing == m.uri
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "■" else "▶", color = Sand, fontSize = 20.sp, modifier = Modifier.combinedClickable { Player.toggle(act, m.uri) }.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name.removeSuffix(".m4a"), color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ago(m.time) + (if (m.durationMs > 0) " · " + dur(m.durationMs) else ""), color = Dim, fontSize = 12.sp)
        }
        Text("share", color = Dim, fontSize = 13.sp, modifier = Modifier.combinedClickable { Gallery.share(act, m.uri, "audio/mp4") }.padding(8.dp))
    }
}

@Composable
private fun TrackRow(vm: LauncherVM, t: SavedTrack) {
    val act = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).combinedClickable { vm.mapTrackRequest = t.file.absolutePath; vm.jumpHub = "map" }) {
            Text(t.name.removePrefix("Track "), color = Fg, fontSize = 15.sp, maxLines = 1)
            Text(String.format(Locale.UK, "%.2f km · %d min", t.km, t.minutes), color = Dim, fontSize = 12.sp)
        }
        Text("map", color = Dim, fontSize = 13.sp, modifier = Modifier.combinedClickable { vm.mapTrackRequest = t.file.absolutePath; vm.jumpHub = "map" }.padding(8.dp))
        Text("share", color = Dim, fontSize = 13.sp, modifier = Modifier.combinedClickable { Tracks.share(act, t.file) }.padding(8.dp))
    }
}

private fun dur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

