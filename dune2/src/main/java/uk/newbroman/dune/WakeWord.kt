package uk.newbroman.dune

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * "OK Computer": while Drive mode is on, Dune listens for its wake word with Vosk, a small
 * offline recogniser (nothing leaves the phone while it waits). On hearing it, Dune comes to the
 * front and opens the normal Speak prompt; afterwards it goes back to listening.
 */
object WakeWord {
    const val PHRASE = "OK Computer"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    var status by mutableStateOf("")

    fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk-en")
    fun hasModel(ctx: Context) = File(modelDir(ctx), "am").exists() || File(modelDir(ctx), "conf").exists()
    fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    /** Needed so Dune may come to the front (and open Maps etc.) while another app is showing. */
    fun canPopUp(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun enabled(ctx: Context) = ctx.getSharedPreferences("dune", 0).getBoolean("wakeWord", false)

    /** Download and unpack the ~40 MB English model, once. */
    suspend fun download(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val dir = modelDir(ctx); val tmp = File(ctx.cacheDir, "vosk.zip")
        try {
            status = "Downloading the speech model…"
            val c = URL(MODEL_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val total = c.contentLengthLong
            c.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var n: Int; var done = 0L; var last = -1L
                while (inp.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n); done += n
                    val pct = if (total > 0) done * 100 / total else -1
                    if (pct != last) { last = pct; status = if (pct >= 0) "Downloading the speech model… $pct%" else "Downloading…" }
                }
            } }
            status = "Unpacking…"
            dir.deleteRecursively(); dir.mkdirs()
            ZipInputStream(tmp.inputStream().buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    // strip the top folder (vosk-model-small-en-us-0.15/...)
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isNotEmpty() && !rel.contains("..")) {
                        val f = File(dir, rel)
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                    e = z.nextEntry
                }
            }
            tmp.delete()
            status = "Ready"
            true
        } catch (e: Exception) {
            tmp.delete(); dir.deleteRecursively()
            status = "Download failed: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** Start listening if Drive mode is on and everything is set up; otherwise make sure it's off. */
    fun sync(ctx: Context) {
        val want = Drive.active.value && enabled(ctx) && hasModel(ctx) && hasMic(ctx)
        val i = Intent(ctx, WakeService::class.java)
        if (want) runCatching { ctx.startForegroundService(i) } else ctx.stopService(i)
    }

    /** After the spoken command has been handled, listen for the wake word again. */
    fun resume(ctx: Context) {
        if (WakeService.running) runCatching { ctx.startService(Intent(ctx, WakeService::class.java).setAction(WakeService.RESUME)) }
    }

    fun pause(ctx: Context) {
        if (WakeService.running) runCatching { ctx.startService(Intent(ctx, WakeService::class.java).setAction(WakeService.PAUSE)) }
    }

    /** "ok computer" / "okay computer" anywhere in what Vosk heard. */
    fun isWake(json: String): Boolean {
        val text = runCatching { JSONObject(json).optString("text") }.getOrDefault("")
        return Regex("\\b(ok|okay)\\s+computer\\b").containsMatchIn(text)
    }
}

class WakeService : Service(), RecognitionListener {
    companion object {
        const val RESUME = "uk.newbroman.dune.WAKE_RESUME"
        const val PAUSE = "uk.newbroman.dune.WAKE_PAUSE"
        var running = false; private set
        private const val CHANNEL = "wake"
        private const val NOTIF_ID = 78
    }

    private var model: Model? = null
    private var speech: SpeechService? = null
    private val main = Handler(Looper.getMainLooper())
    private val resumeLater = Runnable { startListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getActivity(this, 2, Drive.intent(this), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for “${WakeWord.PHRASE}”")
            .setContentText("Drive mode · offline, nothing is sent anywhere")
            .setContentIntent(stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
        Thread {
            val m = runCatching { Model(WakeWord.modelDir(this).absolutePath) }.getOrNull()
            main.post {
                if (m == null) { WakeWord.status = "The speech model couldn't be loaded – set it up again"; stopSelf() }
                else { model = m; startListening() }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RESUME -> { main.removeCallbacks(resumeLater); main.postDelayed(resumeLater, 600) }
            // Speak was tapped: hand the microphone over, and take it back if nobody returns it.
            PAUSE -> { stopListening(); main.removeCallbacks(resumeLater); main.postDelayed(resumeLater, 20_000) }
        }
        return START_NOT_STICKY
    }

    private fun startListening() {
        val m = model ?: return
        if (speech != null) return
        try {
            val grammar = "[\"ok computer\", \"okay computer\", \"computer\", \"[unk]\"]"
            speech = SpeechService(Recognizer(m, 16000f, grammar), 16000f).also { it.startListening(this) }
        } catch (e: Exception) {
            WakeWord.status = "Microphone busy – will retry"
            main.postDelayed(resumeLater, 3000)
        }
    }

    private fun stopListening() { speech?.let { runCatching { it.stop(); it.shutdown() } }; speech = null }

    private fun heard(json: String?) {
        if (json == null || !WakeWord.isWake(json)) return
        stopListening()   // free the microphone for Android's recogniser
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("drive", true).putExtra("wake", true))
        // If the prompt never reports back (e.g. it couldn't open), listen again anyway.
        main.removeCallbacks(resumeLater); main.postDelayed(resumeLater, 20_000)
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onResult(hypothesis: String?) = heard(hypothesis)
    override fun onFinalResult(hypothesis: String?) = heard(hypothesis)
    override fun onError(exception: Exception?) { stopListening(); main.postDelayed(resumeLater, 3000) }
    override fun onTimeout() { stopListening(); main.post(resumeLater) }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopListening()
        model?.close(); model = null
        super.onDestroy()
    }
}
