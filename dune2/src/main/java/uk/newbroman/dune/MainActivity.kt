package uk.newbroman.dune

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.speech.RecognizerIntent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LauncherVM by viewModels()

    private var permCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val cb = permCallback; permCallback = null; cb?.invoke(it)
    }

    private val shortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && !vm.acceptPin(it.data)) toast("That shortcut couldn't be added")
    }

    /** Open an app's "create shortcut" screen (Termux lists your ~/.shortcuts scripts there). */
    fun addShortcutFrom(vararg pkgs: String) {
        for (pkg in pkgs) {
            val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(pkg), 0)
            val ai = list.firstOrNull()?.activityInfo ?: continue
            try {
                shortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setClassName(ai.packageName, ai.name))
                return
            } catch (e: Exception) { }
        }
        toast("Termux has no shortcut screen – add the widget from Termux instead")
    }

    // ---------- voice input

    private var listenCallback: ((String?) -> Unit)? = null
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val cb = listenCallback; listenCallback = null; cb?.invoke(if (r.resultCode == RESULT_OK) text else null)
    }

    /** Ask Android's speech recogniser for one phrase. */
    fun listen(prompt: String, cb: (String?) -> Unit) {
        WakeWord.pause(this)
        listenCallback = { r -> cb(r); WakeWord.resume(this) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try { speechLauncher.launch(i) } catch (e: Exception) { listenCallback = null; WakeWord.resume(this); toast("Speech recognition isn't available") }
    }

    /** Run a typed or spoken request; in Drive mode the answer is spoken too. */
    fun handleAsk(text: String, speak: Boolean = vm.screen == Screen.DRIVE) {
        vm.assistantFallback = null
        val cmd = Assistant.interpret(text)
        // Anything Dune can't do itself goes to the AI, if one is set up.
        if (cmd is Cmd.Unknown && Brain.ready(this)) { vm.askAi(text, speak); return }
        val reply = Assistant.run(this, vm, cmd)
        vm.assistantReply = reply ?: if (vm.assistantFallback != null) "“${vm.assistantFallback}”" else null
        if (speak) {
            if (reply != null && cmd !is Cmd.TimeInPolish && cmd !is Cmd.PhraseOfDay) Speaker.speak(this, reply)
            if (cmd is Cmd.Unknown) Speaker.speak(this, "Sorry, I can't do that one while you're driving")
        }
    }

    fun runCmd(cmd: Cmd) { Assistant.run(this, vm, cmd)?.let { vm.assistantReply = it; if (vm.screen == Screen.DRIVE) Speaker.speak(this, it) } }

    // ---------- the Rozmowa page (one WebView kept for the life of the activity, so the chat survives swipes)

    private var rozmowaWeb: SpeechWebView? = null
    fun rozmowa(): SpeechWebView = rozmowaWeb ?: SpeechWebView(this, SpeechWebView.ROZMOWA).also { rozmowaWeb = it }
    private var clozeWeb: SpeechWebView? = null
    /** Cloze (the sentence trainer), kept alive like Rozmowa so a round survives swipes. */
    fun cloze(): SpeechWebView = clozeWeb ?: SpeechWebView(this, SpeechWebView.CLOZE).also { clozeWeb = it }
    private var wellsWeb: SpeechWebView? = null
    /** The Newborough well logger, run inside Dune (its readings are kept in Dune's own web storage). */
    fun wells(): SpeechWebView = wellsWeb ?: SpeechWebView(this, SpeechWebView.WELLS).also { wellsWeb = it }

    private var pickCallback: ((List<android.net.Uri>) -> Unit)? = null
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val cb = pickCallback; pickCallback = null; cb?.invoke(uris)
    }
    private val pickOneLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = pickCallback; pickCallback = null; cb?.invoke(listOfNotNull(uri))
    }
    /** Android's file picker (any type); the callback gets an empty list if nothing was chosen. */
    fun pickFiles(multiple: Boolean = true, cb: (List<android.net.Uri>) -> Unit) {
        pickCallback?.invoke(emptyList())
        pickCallback = cb
        runCatching { if (multiple) pickLauncher.launch(arrayOf("*/*")) else pickOneLauncher.launch(arrayOf("*/*")) }
            .onFailure { pickCallback = null; cb(emptyList()); toast("No file picker on this phone") }
    }

    /** Saves a file a web page made into Downloads/Dune, then offers to share it. */
    fun saveDownload(name: String, mime: String, b64: String) {
        lifecycleScope.launch {
            val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
            val u = bytes?.let { Gis.saveExport(this@MainActivity, name, mime, it) }
            if (u == null) toast("Couldn't save $name")
            else { toast("Saved to Downloads/Dune: $name"); Gis.share(this@MainActivity, u, mime, "Share $name") }
        }
    }

    /** A map file opened with Dune from another app (Files, Drive, email): add it to the map's layers. */
    private fun openedFile(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW && intent?.action != Intent.ACTION_SEND) return false
        @Suppress("DEPRECATION")
        val u = intent.data ?: intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return false
        vm.gisImport = listOf(u); vm.jumpHub = "map"
        intent.action = Intent.ACTION_MAIN; intent.data = null
        return true
    }

    override fun onDestroy() { runCatching { unregisterReceiver(wakeReceiver) }; rozmowaWeb?.destroy(); rozmowaWeb = null; clozeWeb?.destroy(); clozeWeb = null; wellsWeb?.destroy(); wellsWeb = null; super.onDestroy() }

    // ---------- widgets

    lateinit var widgetHost: AppWidgetHost
    private var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null
    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = pendingWidget ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) configureOrPlace(p.first, p.second)
        else { widgetHost.deleteAppWidgetId(p.first); pendingWidget = null }
    }

    fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id to info
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.bindAppWidgetIdIfAllowed(id, info.provider)) configureOrPlace(id, info)
        else bindLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider))
    }

    private fun configureOrPlace(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) { /* fall through and place it unconfigured */ }
        }
        place(id, info)
    }

    private fun place(id: Int, info: AppWidgetProviderInfo) {
        if (vm.widgetTarget == "weather") {
            // Replace whatever was in the weather spot.
            vm.store.weatherWidget.value.substringBefore(':').toIntOrNull()?.let { runCatching { widgetHost.deleteAppWidgetId(it) } }
            vm.store.weatherWidget.set("$id:${defaultWidgetHeight(this, info).coerceAtLeast(120)}")
        } else if (vm.widgetTarget == "weatherPage") {
            val list = vm.store.weatherPage.value.split(',').filter { it.isNotBlank() }
            vm.store.weatherPage.set((list + "$id:${defaultWidgetHeight(this, info).coerceAtLeast(200)}").joinToString(","))
        } else vm.store.addWidget(id, defaultWidgetHeight(this, info))
        vm.widgetTarget = null
        pendingWidget = null
        vm.goHome()
    }

    fun removeWidget(id: Int) { widgetHost.deleteAppWidgetId(id); vm.store.dropWidget(id) }

    /** Weather page: remove a widget, or change its height by [by] dp. */
    fun editWeatherPage(id: Int, by: Int?) {
        val list = vm.store.weatherPage.value.split(',').filter { it.isNotBlank() }
        vm.store.weatherPage.set(list.mapNotNull { e ->
            val (i, h) = e.split(':').let { (it[0].toIntOrNull() ?: -1) to (it.getOrNull(1)?.toIntOrNull() ?: 200) }
            when {
                i != id -> e
                by == null -> { runCatching { widgetHost.deleteAppWidgetId(i) }; null }
                else -> "$i:${(h + by).coerceIn(80, 900)}"
            }
        }.joinToString(","))
    }

    /** Back to Dune's own weather. */
    fun clearWeatherWidget() {
        vm.store.weatherWidget.value.substringBefore(':').toIntOrNull()?.let { runCatching { widgetHost.deleteAppWidgetId(it) } }
        vm.store.weatherWidget.set("")
    }

    fun resizeWeatherWidget(by: Int) {
        val (id, h) = vm.store.weatherWidget.value.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return) to (it.getOrNull(1)?.toIntOrNull() ?: 140) }
        vm.store.weatherWidget.set("$id:${(h + by).coerceIn(60, 500)}")
    }

    @Deprecated("Needed for the widget configure screen, which only reports back this way")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIGURE) return
        val p = pendingWidget ?: return
        if (resultCode == RESULT_OK) place(p.first, p.second)
        else { widgetHost.deleteAppWidgetId(p.first); pendingWidget = null }
    }

    override fun onStart() { super.onStart(); runCatching { widgetHost.startListening() } }
    override fun onStop() { super.onStop(); runCatching { widgetHost.stopListening() } }

    companion object { private const val REQ_CONFIGURE = 9001 }

    /** Pick the downloaded Coinbase key file (cdp_api_key.json). */
    private val keyFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val key = text?.let { Coinbase.parseKey("", it) }
        if (key == null) toast("That file doesn't look like a Coinbase API key")
        else { vm.saveCoinbaseKey(key); toast("Coinbase key saved") }
    }
    fun pickCoinbaseKeyFile() = keyFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))

    private var permsCallback: ((Map<String, Boolean>) -> Unit)? = null
    private val permsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cb = permsCallback; permsCallback = null; cb?.invoke(it)
    }
    fun requestPerms(permissions: Array<String>, cb: (Map<String, Boolean>) -> Unit) {
        permsCallback = cb
        permsLauncher.launch(permissions)
    }

    // ---- deleting photos, videos and recordings (Android asks you to confirm; it can be undone from the Bin)
    private var deleteCallback: ((Boolean) -> Unit)? = null
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val cb = deleteCallback; deleteCallback = null; cb?.invoke(it.resultCode == RESULT_OK)
    }
    fun deleteMedia(uris: List<android.net.Uri>, cb: (Boolean) -> Unit) {
        if (uris.isEmpty()) return cb(false)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val pi = android.provider.MediaStore.createDeleteRequest(contentResolver, uris)
                deleteCallback = cb
                deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure { deleteCallback = null; toast("Couldn't delete: ${it.message}"); cb(false) }
            return
        }
        // Older Android: delete directly; on 10, Android may ask first for files another app made.
        var ok = true
        for (u in uris) {
            try { contentResolver.delete(u, null, null) }
            catch (e: SecurityException) {
                val rse = if (android.os.Build.VERSION.SDK_INT >= 29) e as? android.app.RecoverableSecurityException else null
                if (rse != null) { deleteCallback = cb; deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(rse.userAction.actionIntent.intentSender).build()); return }
                ok = false
            }
        }
        cb(ok)
    }

    fun requestPerm(permission: String, cb: (Boolean) -> Unit) {
        permCallback = cb
        permLauncher.launch(permission)
    }

    /** Counts the phone being woken, so the phrase of the day moves on every third time. */
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_ON) vm.phoneWoken() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.core.content.ContextCompat.registerReceiver(this, wakeReceiver,
            android.content.IntentFilter(Intent.ACTION_SCREEN_ON), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        if (savedInstanceState == null) intent?.getStringExtra("hub")?.let { intent.removeExtra("hub"); vm.jumpHub = it }
        if (savedInstanceState == null && intent != null && !voiceIntent(intent) && intent.getBooleanExtra("drive", false)) {
            intent.removeExtra("drive"); Drive.set(this, true); vm.screen = Screen.DRIVE
        }
        if (savedInstanceState == null) openedFile(intent)
        onBackPressedDispatcher.addCallback(this) { vm.back() }
        setContent { DuneApp(vm) }
    }

    /** Pressing Home while already home returns to the top of the home screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (voiceIntent(intent)) return
        if (openedFile(intent)) { vm.goHome(); vm.jumpHub = "map"; return }
        if (intent.getBooleanExtra("drive", false)) { intent.removeExtra("drive"); Drive.set(this, true); vm.screen = Screen.DRIVE }
        else { vm.goHome(); intent.getStringExtra("hub")?.let { intent.removeExtra("hub"); vm.jumpHub = it } }
    }

    /** "OK Computer" was heard, or a car / headset voice button was pressed: open the Speak prompt. */
    private fun voiceIntent(intent: Intent): Boolean {
        val wake = intent.getBooleanExtra("wake", false)
        if (!wake && intent.action != Intent.ACTION_VOICE_COMMAND) return false
        intent.removeExtra("wake"); intent.removeExtra("drive")
        if (wake || Drive.active.value) { Drive.set(this, true); vm.screen = Screen.DRIVE }
        Speaker.stop()
        window.decorView.postDelayed({ listen("Listening…") { it?.let { t -> handleAsk(t, speak = true) } } }, 250)
        return true
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        vm.media.listen()   // notification access may have just been granted
    }
}
