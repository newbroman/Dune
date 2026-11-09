@file:OptIn(ExperimentalFoundationApi::class)

package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * The phone's own camera app (night mode, pro mode, 200 MP… whatever it has).
 * Opened the way its home-screen icon opens it, not with the "still image camera" intent: some
 * makers' camera apps (seen on the Ulefone) start in a restricted mode from that intent and shut
 * after a minute complaining about security permissions. The intent is only the fallback.
 */
object PhoneCamera {
    /** The package that answers "take a photo" – the phone's default camera app. */
    fun pkg(ctx: Context): String? {
        val pm = ctx.packageManager
        val intents = listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_IMAGE_CAPTURE)
        intents.forEach { a ->
            pm.resolveActivity(android.content.Intent(a), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeIf { it != "android" && it != ctx.packageName && pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        }
        return intents.flatMap { a -> pm.queryIntentActivities(android.content.Intent(a), 0).map { it.activityInfo.packageName } }
            .firstOrNull { it != ctx.packageName && pm.getLaunchIntentForPackage(it) != null }
    }

    fun open(ctx: Context, video: Boolean): Boolean {
        pkg(ctx)?.let { p ->
            ctx.packageManager.getLaunchIntentForPackage(p)?.let { i ->
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (ctx.tryStart(i)) return true
            }
        }
        return ctx.tryStart(android.content.Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** One of the phone's rear cameras, as CameraX sees it. */
data class Lens(val id: String, val fovLong: Double, val minFocusDiopters: Float, val focalMm: Float?, val sensorLongMm: Float?)

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
object Lenses {
    fun of(info: CameraInfo): Lens? = runCatching {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val s = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val long = s?.let { max(it.width, it.height) }
        val fov = if (focal != null && long != null && focal > 0) Math.toDegrees(2 * atan(long / 2.0 / focal)) else 70.0
        Lens(c.cameraId, fov, c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f, focal, long)
    }.getOrNull()

    fun back(p: ProcessCameraProvider): List<Lens> = p.availableCameraInfos
        .filter { runCatching { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }.getOrDefault(false) }
        .mapNotNull { of(it) }.distinctBy { it.id }

    fun selector(id: String): CameraSelector = CameraSelector.Builder().addCameraFilter { infos ->
        infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId == id }.getOrDefault(false) }
    }.build()

    /** "0.6×", "1×", "2×" relative to the main camera. */
    fun label(l: Lens, main: Lens): String {
        val z = tan(Math.toRadians(main.fovLong / 2)) / tan(Math.toRadians(l.fovLong / 2))
        return if (abs(z - 1) < 0.08) "1×" else if (z < 1) String.format(java.util.Locale.UK, "%.1f×", z) else "${"%.1f".format(z).removeSuffix(".0")}×"
    }

    /** A dedicated close-up camera, if the phone lets apps use one: focuses far closer than the main camera. */
    fun macro(all: List<Lens>, main: Lens?): Lens? = all.filter { it.id != main?.id && it.minFocusDiopters >= 15f &&
        it.minFocusDiopters > (main?.minFocusDiopters ?: 0f) * 1.5f }.maxByOrNull { it.minFocusDiopters }

    /** Camera2 settings for this session: close-focus mode, and exposure / white balance locks (panoramas). */
    fun apply(cam: Camera?, macroAf: Boolean, lock: Boolean) {
        cam ?: return
        runCatching {
            val b = CaptureRequestOptions.Builder()
            if (macroAf) b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
            if (lock) { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true); b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true) }
            Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = b.build()
        }
    }
    fun hasMacroAf(cam: Camera?): Boolean = cam != null && runCatching {
        Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) == true
    }.getOrDefault(false)
}

private const val LAPSE = "lapse"
private const val PHOTO = "photo"; private const val VIDEO = "video"; private const val PANO = "pano"; private const val SPHERE = "360"

/** Holds a running time-lapse between the camera screen and its frame thread. */
class LapseRun {
    @Volatile var enc: TimeLapseEncoder? = null
    @Volatile var nextAt = 0L
    var running by mutableStateOf(false)
    var frames by mutableIntStateOf(0)
    var since by mutableLongStateOf(0L)
}

/** Dune's own camera: photo, video, time-lapse, panorama and 360 photo sphere; lenses, zoom, macro, self-timer, tap to focus. */
@SuppressLint("MissingPermission", "ClickableViewAccessibility")
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

    val prefs = remember { act.getSharedPreferences("dune", 0) }
    var mode by rememberSaveable { mutableStateOf(vm.cameraMode ?: if (vm.cameraVideo) VIDEO else PHOTO) }
    val video = mode == VIDEO; val pano = mode == PANO || mode == SPHERE; val lapseMode = mode == LAPSE
    var back by rememberSaveable { mutableStateOf(true) }
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var timer by remember { mutableIntStateOf(prefs.getInt("camTimer", 0)) }
    var macro by rememberSaveable { mutableStateOf(false) }
    var lensId by rememberSaveable { mutableStateOf<String?>(null) }     // null = the phone's main rear camera
    var lenses by remember { mutableStateOf<List<Lens>>(emptyList()) }
    var cam by remember { mutableStateOf<Camera?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recStart by remember { mutableLongStateOf(0L) }
    var last by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var countJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var every by remember { mutableIntStateOf(prefs.getInt("lapseEvery", 2)) }
    val lapse = remember { LapseRun() }
    val rot = remember { intArrayOf(Surface.ROTATION_0) }
    var zoom by remember { mutableFloatStateOf(1f) }; var zMin by remember { mutableFloatStateOf(1f) }; var zMax by remember { mutableFloatStateOf(1f) }

    val mainLens = lenses.firstOrNull()
    val macroLens = remember(lenses) { Lenses.macro(lenses, mainLens) }
    val usingBack = back || pano

    val preview = remember { PreviewView(act).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember { VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()) }
    imageCapture.flashMode = flash

    // ---- panorama state
    val pv = remember { PanoRun() }
    val analysis = remember {
        ImageAnalysis.Builder().setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
            .setTargetRotation(Surface.ROTATION_0)   // frames come upright for the phone held in portrait
            .build()
    }
    val worker = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }

    // Keep photos and videos the right way up (Dune handles rotation itself, so CameraX isn't told).
    DisposableEffect(Unit) {
        val l = object : android.view.OrientationEventListener(act) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                imageCapture.targetRotation = r; videoCapture.targetRotation = r; rot[0] = r
            }
        }
        l.enable(); onDispose { l.disable() }
    }
    DisposableEffect(usingBack, mode, lensId) {
        var disposed = false
        val future = ProcessCameraProvider.getInstance(act)
        future.addListener({
            if (disposed) return@addListener
            runCatching {
                val p = future.get()
                if (lenses.isEmpty()) lenses = Lenses.back(p)
                p.unbindAll()
                val pre = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val sel = when { !usingBack -> CameraSelector.DEFAULT_FRONT_CAMERA; lensId != null -> Lenses.selector(lensId!!); else -> CameraSelector.DEFAULT_BACK_CAMERA }
                cam = when { video -> p.bindToLifecycle(act, sel, pre, videoCapture); pano || lapseMode -> p.bindToLifecycle(act, sel, pre, analysis)
                             else -> p.bindToLifecycle(act, sel, pre, imageCapture) }
            }.onFailure { act.toast("Camera unavailable: ${it.message}"); if (lensId != null) { lensId = null; macro = false } }
        }, ContextCompat.getMainExecutor(act))
        onDispose { disposed = true; cam = null; if (future.isDone) runCatching { future.get().unbindAll() } }
    }
    // close-focus on the main camera when there's no separate macro camera
    LaunchedEffect(cam, macro, pv.locked) { Lenses.apply(cam, macro && macroLens == null && !pano, pano && pv.locked) }
    DisposableEffect(Unit) { onDispose { recording?.stop() } }
    // the zoom the camera is at, and how far it goes
    DisposableEffect(cam) {
        val c = cam ?: return@DisposableEffect onDispose { }
        val obs = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoom = it.zoomRatio; zMin = it.minZoomRatio; zMax = it.maxZoomRatio }
        c.cameraInfo.zoomState.observe(act, obs)
        onDispose { c.cameraInfo.zoomState.removeObserver(obs) }
    }

    // tap to focus, pinch to zoom
    LaunchedEffect(Unit) {
        val sg = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = cam ?: return true
                val z = c.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                c.cameraControl.setZoomRatio(z * d.scaleFactor); return true
            }
        })
        preview.setOnTouchListener { _, e ->
            sg.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !sg.isInProgress && e.eventTime - e.downTime < 350 && e.pointerCount == 1) {
                cam?.let { c -> runCatching { c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(e.x, e.y)).build()) } }
            }
            true
        }
    }

    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 70) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    val shoot: () -> Unit = {
        if (mode == PHOTO && !busy) {
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

    // ---- panoramas: sensors, frames, stitching
    val lensForPano = (lensId?.let { id -> lenses.firstOrNull { it.id == id } } ?: mainLens)
    // the lens width measured by earlier panoramas beats what the phone reports
    val fKey = "panoFocal_" + (lensForPano?.id ?: "main")
    val fLong = prefs.getFloat(fKey, 0f).takeIf { it > 0f }?.toDouble() ?: Pano.focalOfLong(lensForPano?.focalMm, lensForPano?.sensorLongMm)
    val finishPano: () -> Unit = {
        val shots = pv.shots.toList()
        if (shots.size < 2) { act.toast("Take at least two frames first"); }
        else {
            pv.active = false
            pv.stitching = 0f
            val sphere = mode == SPHERE
            scope.launch {
                val u = runCatching { Pano.stitch(act, shots, sphere, pv.startAz, fLong) { p -> pv.stitching = p } }
                    .onFailure { act.toast("Couldn't stitch: ${it.message ?: it.javaClass.simpleName}") }.getOrNull()
                pv.stitching = -1f
                Pano.lastFocal?.let { f -> prefs.edit().putFloat(fKey, f.toFloat()).apply() }
                if (u != null) { last = u; Gallery.changed.value++; act.toast(if (sphere) "360 photo saved – Google Photos shows it as a sphere" else "Panorama saved") }
                pv.reset(act)
            }
        }
    }
    DisposableEffect(pano) {
        if (!pano) return@DisposableEffect onDispose { }
        analysis.targetRotation = Surface.ROTATION_0
        val before = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val r = FloatArray(9); SensorManager.getRotationMatrixFromVector(r, e.values)
                pv.onRotation(r, e.timestamp)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (s == null) act.toast("This phone has no rotation sensor, so Dune can't guide a panorama")
        else sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
        analysis.setAnalyzer(worker) { img ->
            val want = pv.pending.get()
            if (want >= 0 && pv.pending.compareAndSet(want, -2)) {
                val r = pv.r.copyOf()
                runCatching {
                    var bm = img.toBitmap()
                    val deg = img.imageInfo.rotationDegrees
                    if (deg != 0) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                    val f = File(pv.dir(act), "f${pv.shots.size}_$want.jpg")
                    f.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    bm.recycle()
                    act.runOnUiThread { pv.taken(want, Pano.Shot(f, r)); runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 80) } }
                }.onFailure { pv.pending.set(-1) }
            }
            img.close()
        }
        onDispose {
            sm.unregisterListener(l); analysis.clearAnalyzer()
            act.requestedOrientation = before
            if (pv.stitching < 0) pv.reset(act)
        }
    }
    LaunchedEffect(pv.shots.size, pv.targets.size) {
        if (pv.active && pv.targets.isNotEmpty() && pv.shots.size >= pv.targets.size) finishPano()
    }

    // ---- time-lapse: a frame every few seconds, straight into a video
    val stopLapse: () -> Unit = {
        val e = lapse.enc
        lapse.enc = null; lapse.running = false
        if (e != null) worker.execute {
            val f = runCatching { e.finish() }.getOrNull()
            val u = f?.let { runCatching { TimeLapse.save(act, it) }.getOrNull() }
            act.runOnUiThread {
                if (u != null) { last = u; Gallery.changed.value++; act.toast("Time-lapse saved – ${e.frames} frames") }
                else act.toast("Time-lapse not saved (no frames)")
            }
        }
    }
    DisposableEffect(lapseMode) {
        if (!lapseMode) return@DisposableEffect onDispose { }
        analysis.setAnalyzer(worker) { img ->
            val e = lapse.enc
            val now = System.currentTimeMillis()
            if (e != null && now >= lapse.nextAt) {
                lapse.nextAt = maxOf(lapse.nextAt + every * 1000L, now + every * 500L)
                runCatching {
                    var bm = img.toBitmap()
                    val deg = img.imageInfo.rotationDegrees
                    if (deg != 0) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                    e.add(bm); bm.recycle()
                    val n = e.frames
                    act.runOnUiThread { lapse.frames = n }
                }.onFailure { act.runOnUiThread { act.toast("Time-lapse frame failed: ${it.message}") } }
            }
            img.close()
        }
        onDispose { analysis.clearAnalyzer(); if (lapse.enc != null) stopLapse() }
    }
    LaunchedEffect(lapse.running) {
        if (lapse.running) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    val startLapse: () -> Unit = {
        val r = rot[0]
        analysis.targetRotation = r          // frames come upright for the way the phone is held now
        val portrait = r == Surface.ROTATION_0 || r == Surface.ROTATION_180
        lapse.running = true; lapse.frames = 0; lapse.since = System.currentTimeMillis()
        worker.execute {
            runCatching { TimeLapseEncoder.open(act, portrait) }
                .onSuccess { lapse.nextAt = System.currentTimeMillis(); lapse.enc = it }
                .onFailure { act.runOnUiThread { lapse.running = false; act.toast("Can't record a time-lapse: ${it.message}") } }
        }
    }

    val press: () -> Unit = {
        when {
            lapseMode -> if (lapse.running) stopLapse() else if (countJob == null && timer == 0) startLapse() else if (countJob != null) { countJob?.cancel(); countJob = null; countdown = 0 }
                else countJob = scope.launch {
                    for (s in timer downTo 1) { countdown = s; runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }; delay(1000) }
                    countdown = 0; countJob = null; startLapse()
                }
            pano -> when {
                pv.stitching >= 0 -> {}
                !pv.active -> {
                    val lfw = 3.0; val lfh = 4.0; val f = fLong * lfh
                    pv.start(mode == SPHERE, Pano.fov(lfw, f), Pano.fov(lfh, f))
                }
                else -> finishPano()
            }
            countJob != null -> { countJob?.cancel(); countJob = null; countdown = 0 }
            timer > 0 && recording == null && (mode == PHOTO || video) -> countJob = scope.launch {
                for (s in timer downTo 1) { countdown = s; runCatching { tone?.startTone(if (s <= 2) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120) }; delay(1000) }
                countdown = 0; countJob = null; shoot()
            }
            else -> shoot()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        if (pano) PanoOverlay(pv, fLong)
        if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 120.sp, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", color = Color.White, fontSize = 22.sp, modifier = Modifier.combinedClickable {
                    if (pv.active) pv.reset(act) else { vm.goHome(); vm.jumpHub = "record" }
                }.padding(8.dp))
                Spacer(Modifier.weight(1f))
                if (mode == PHOTO || video) TopChip("phone cam", false) { if (recording == null) PhoneCamera.open(act, video) }
                if (!pano) {
                    TopChip("⏱ " + if (timer == 0) "off" else "${timer}s", timer > 0) {
                        timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }; prefs.edit().putInt("camTimer", timer).apply()
                    }
                    if (usingBack && (macroLens != null || Lenses.hasMacroAf(cam) || macro)) TopChip("✿ macro", macro) {
                        macro = !macro
                        if (macroLens != null) lensId = if (macro) macroLens.id else null
                    }
                }
                if (mode == PHOTO) TopChip(when (flash) { ImageCapture.FLASH_MODE_ON -> "⚡ on"; ImageCapture.FLASH_MODE_OFF -> "⚡ off"; else -> "⚡ auto" }, false) {
                    flash = when (flash) { ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF; else -> ImageCapture.FLASH_MODE_AUTO }
                }
                if (lapseMode && !lapse.running) TopChip("every ${if (every < 60) "${every}s" else "1 min"}", true) {
                    every = TimeLapse.INTERVALS[(TimeLapse.INTERVALS.indexOf(every) + 1) % TimeLapse.INTERVALS.size]; prefs.edit().putInt("lapseEvery", every).apply()
                }
                if (lapse.running) {
                    val now by produceState(System.currentTimeMillis()) { while (true) { delay(500); value = System.currentTimeMillis() } }
                    val s = (now - lapse.since) / 1000; val v = lapse.frames / TimeLapseEncoder.FPS
                    Text("● %d:%02d · %d frames → %d:%02d".format(s / 60, s % 60, lapse.frames, v / 60, v % 60), color = Color(0xFFFF5A5A), fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                }
                if (recording != null) {
                    val now by produceState(System.currentTimeMillis()) { while (true) { delay(500); value = System.currentTimeMillis() } }
                    val s = (now - recStart) / 1000
                    Text("● %d:%02d".format(s / 60, s % 60), color = Color(0xFFFF5A5A), fontSize = 16.sp, modifier = Modifier.padding(8.dp))
                }
            }
            if (pano) PanoStatus(pv, mode == SPHERE)
            Spacer(Modifier.weight(1f))
            // lenses (rear cameras the phone lets apps use) and zoom steps; pinch for anything in between
            if (!pano) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun pill(t: String, on: Boolean, click: () -> Unit) = Text(t, color = if (on) Sand else Color.White, fontSize = 13.sp, modifier = Modifier
                        .padding(horizontal = 3.dp).background(Color(0x66000000), CircleShape).combinedClickable(onClick = click).padding(horizontal = 10.dp, vertical = 6.dp))
                    if (usingBack && mainLens != null && lenses.size > 1 && !macro) {
                        lenses.filter { it.id != macroLens?.id && it.id != mainLens.id }.sortedBy { -it.fovLong }.forEach { l ->
                            pill(Lenses.label(l, mainLens), lensId == l.id) { if (recording == null && !lapse.running) lensId = l.id }
                        }
                    }
                    val steps = (listOfNotNull(zMin.takeIf { it < 0.95f }) + listOf(1f, 2f, 5f, 10f, 20f)).filter { it <= zMax + 0.01f }
                    val match = steps.minByOrNull { abs(it - zoom) }?.takeIf { abs(it - zoom) < 0.05f }
                    steps.forEach { z ->
                        pill(fmtZoom(z), z == match) {
                            if (lensId != null && !macro) lensId = null
                            cam?.cameraControl?.setZoomRatio(z)
                        }
                    }
                    if (match == null) Text(fmtZoom(zoom), color = Sand, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
                listOf(PHOTO to "PHOTO", VIDEO to "VIDEO", LAPSE to "LAPSE", PANO to "PANO", SPHERE to "360").forEach { (m, l) ->
                    Text(l, color = if (mode == m) Sand else Color.White, fontSize = 14.sp, modifier = Modifier
                        .combinedClickable { if (recording == null && !lapse.running && !pv.active && pv.stitching < 0) { mode = m; countJob?.cancel(); countJob = null; countdown = 0 } }
                        .padding(horizontal = 9.dp, vertical = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 28.dp, start = 28.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                // last capture: tap to view
                Box(Modifier.size(54.dp).border(2.dp, Color.White, RoundedCornerShape(10.dp)).combinedClickable { last?.let { Gallery.view(act, it) } }) {
                    val thumb by produceState<ImageBitmap?>(null, last) { value = last?.let { Gallery.thumb(act, it) } }
                    thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.weight(1f))
                val shutter = when { video || lapseMode -> Color(0xFFE53935); pano && pv.active -> Sand; else -> Color.White }
                Box(Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                    .background(shutter, if (recording != null || lapse.running || (pano && pv.active)) RoundedCornerShape(10.dp) else CircleShape)
                    .combinedClickable(onClick = press))
                Spacer(Modifier.weight(1f))
                Text(if (pano) "" else "⟲", color = Color.White, fontSize = 30.sp, modifier = Modifier.size(54.dp)
                    .combinedClickable { if (recording == null && !lapse.running && !pano) { back = !back; macro = false; lensId = null } }
                    .padding(top = 6.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun fmtZoom(z: Float) = if (z < 0.95f || abs(z - Math.round(z)) > 0.05f) String.format(java.util.Locale.UK, "%.1f×", z) else "${Math.round(z)}×"

@Composable
private fun TopChip(text: String, on: Boolean, onClick: () -> Unit) =
    Text(text, color = if (on) Sand else Color.White, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp))

/** A panorama in progress: where to point next, what's been taken, and the latest orientation. */
class PanoRun {
    var active by mutableStateOf(false)
    var sphere = false
    var startAz = 0.0
    var targets by mutableStateOf<List<Pano.Target>>(emptyList())
    val shots = mutableStateListOf<Pano.Shot>()
    val done = mutableStateListOf<Int>()
    var stitching by mutableFloatStateOf(-1f)      // < 0: not stitching
    var locked by mutableStateOf(false)            // exposure and white balance held after the first frame
    var tick by mutableLongStateOf(0L)             // bumps with each sensor reading, to redraw the guide
    val r = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    val pending = AtomicInteger(-1)                // target waiting for a frame (-1 none, -2 being saved)
    private val history = ArrayDeque<Pair<Long, DoubleArray>>()
    private var hasR = false

    fun dir(ctx: Context) = File(ctx.cacheDir, "pano").apply { mkdirs() }

    fun start(sphere: Boolean, hfov: Double, vfov: Double) {
        if (!hasR) return
        this.sphere = sphere
        startAz = Pano.azimuth(Pano.forward(r))
        targets = Pano.targets(sphere, hfov, vfov)
        shots.clear(); done.clear(); locked = false; pending.set(-1)
        active = true
    }
    fun reset(ctx: Context) {
        active = false; targets = emptyList(); shots.clear(); done.clear(); locked = false; pending.set(-1)
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
    fun taken(i: Int, s: Pano.Shot) { if (active) { shots.add(s); done.add(i); locked = true }; pending.set(-1) }

    fun targetDir(t: Pano.Target) = Pano.dir(startAz + t.yaw, t.pitch)

    /** The target to aim at next: the closest one not yet taken (ties go round the ring in order). */
    fun next(): Int? {
        val f = Pano.forward(r)
        return targets.indices.filter { it !in done }.minByOrNull { Pano.angle(f, targetDir(targets[it])) + it * 0.01 }
    }

    fun onRotation(m: FloatArray, t: Long) {
        System.arraycopy(m, 0, r, 0, 9); hasR = true
        val f = Pano.forward(m)
        history.addLast(t to f); while (history.size > 2 && t - history.first().first > 350_000_000L) history.removeFirst()
        tick = t
        if (!active || pending.get() != -1) return
        val i = next() ?: return
        val tol = if (abs(targets[i].pitch) > 80) 6.0 else 3.5
        val steady = history.size > 3 && t - history.first().first > 250_000_000L && Pano.angle(history.first().second, f) < 0.8
        if (steady && Pano.angle(f, targetDir(targets[i])) < tol) pending.compareAndSet(-1, i)
    }
}

/** The guide: a ring in the middle, the dot to point it at, and where the taken frames are. */
@Composable
private fun PanoOverlay(pv: PanoRun, fLong: Double) {
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val t = pv.tick
        val cx = size.width / 2; val cy = size.height / 2
        val fs = fLong * max(size.height.toDouble(), size.width * 4.0 / 3)   // preview fills the screen from a 3:4 frame
        val ring = 30.dp.toPx()
        drawCircle(Color.White, ring, Offset(cx, cy), style = Stroke(2.dp.toPx()))
        if (!pv.active) return@Canvas
        fun project(w: DoubleArray): Pair<Offset, Boolean> {
            val d = Pano.toDevice(pv.r, w)
            if (d[2] >= -0.05) return Offset((d[0] * 1e4).toFloat(), (-d[1] * 1e4).toFloat()) to false
            return Offset((cx + fs * d[0] / -d[2]).toFloat(), (cy - fs * d[1] / -d[2]).toFloat()) to true
        }
        pv.done.forEach { i -> val (p, ok) = project(pv.targetDir(pv.targets[i])); if (ok) drawCircle(Color(0xFF7BD88F), 6.dp.toPx(), p) }
        val n = pv.next() ?: return@Canvas
        val (p, front) = project(pv.targetDir(pv.targets[n]))
        val onScreen = front && p.x in 0f..size.width && p.y in 0f..size.height
        if (onScreen) {
            drawCircle(Sand, 14.dp.toPx(), p)
            drawLine(Color(0x88FFFFFF), Offset(cx, cy), p, 2.dp.toPx())
        } else {
            // off screen: an arrow at the edge pointing the way to turn
            val dx = if (front) p.x - cx else p.x; val dy = if (front) p.y - cy else p.y
            val len = hypot(dx, dy).coerceAtLeast(1f); val ux = dx / len; val uy = dy / len
            val k = min((size.width / 2 - 40.dp.toPx()) / abs(ux).coerceAtLeast(1e-3f), (size.height / 2 - 120.dp.toPx()) / abs(uy).coerceAtLeast(1e-3f))
            val tip = Offset(cx + ux * k, cy + uy * k); val a = 22.dp.toPx()
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - ux * a - uy * a * .6f, tip.y - uy * a + ux * a * .6f)
                lineTo(tip.x - ux * a + uy * a * .6f, tip.y - uy * a - ux * a * .6f); close()
            }
            drawPath(path, Sand)
        }
    }
}

@Composable
private fun PanoStatus(pv: PanoRun, sphere: Boolean) {
    val msg = when {
        pv.stitching >= 0 -> "Stitching… ${(pv.stitching * 100).toInt()}%"
        !pv.active -> if (sphere) "360 photo sphere: stand still, tap ● and turn on the spot to each dot – all round, then up and down."
                      else "Panorama: tap ● and turn slowly to each dot. Tap ■ when you've gone far enough."
        else -> "Point the ring at the dot and hold still · ${pv.shots.size} / ${pv.targets.size}" +
            (if (sphere) "" else " · ■ to finish")
    }
    Text(msg, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        .padding(horizontal = 24.dp).background(Color(0x88000000), RoundedCornerShape(12.dp)).padding(10.dp))
}
