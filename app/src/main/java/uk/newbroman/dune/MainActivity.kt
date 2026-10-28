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
        listenCallback = cb
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try { speechLauncher.launch(i) } catch (e: Exception) { listenCallback = null; toast("Speech recognition isn't available") }
    }

    /** Run a typed or spoken request; in Drive mode the answer is spoken too. */
    fun handleAsk(text: String, speak: Boolean = vm.screen == Screen.DRIVE) {
        vm.assistantFallback = null
        val cmd = Assistant.interpret(text)
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

    override fun onDestroy() { rozmowaWeb?.destroy(); rozmowaWeb = null; super.onDestroy() }

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
        } else vm.store.addWidget(id, defaultWidgetHeight(this, info))
        vm.widgetTarget = null
        pendingWidget = null
        vm.goHome()
    }

    fun removeWidget(id: Int) { widgetHost.deleteAppWidgetId(id); vm.store.dropWidget(id) }

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

    fun requestPerm(permission: String, cb: (Boolean) -> Unit) {
        permCallback = cb
        permLauncher.launch(permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        if (savedInstanceState == null && intent?.getBooleanExtra("drive", false) == true) {
            intent.removeExtra("drive"); Drive.set(this, true); vm.screen = Screen.DRIVE
        }
        onBackPressedDispatcher.addCallback(this) { vm.back() }
        setContent { DuneApp(vm) }
    }

    /** Pressing Home while already home returns to the top of the home screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("drive", false)) { intent.removeExtra("drive"); Drive.set(this, true); vm.screen = Screen.DRIVE }
        else vm.goHome()
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
