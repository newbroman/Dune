package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Locale

/**
 * A web app living on one of Dune's pages (Rozmowa). Android's WebView has no Web Speech API,
 * so Dune supplies SpeechRecognition and speechSynthesis itself, backed by Android's own
 * recogniser and text-to-speech, through a small JavaScript shim.
 */
class SpeechWebView(private val act: MainActivity, private val home: String) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayList<Runnable>()

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(act).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addJavascriptInterface(Bridge(), "DuneBridge")
        val origin = Uri.parse(home).let { "${it.scheme}://${it.host}" }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(this, SHIM, setOf(origin))
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                // Stay on the app's own site; anything else (e.g. the Gemini key page) opens in the browser.
                if (req.url.host == Uri.parse(home).host) return false
                act.tryStart(Intent(Intent.ACTION_VIEW, req.url)); return true
            }
            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Older WebViews: inject as early as we can.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(SHIM, null)
            }
        }
        loadUrl(home)
        // Android's WebView can keep a page laid out at a stale height after a resize (typically
        // after the keyboard closes), leaving the app squashed into the top of the view. After any
        // size change, compare the page's idea of its height with the view's real height and, if
        // they disagree, nudge it, then reload as a last resort (the web apps keep their state).
        addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                main.removeCallbacks(heightCheck)
                main.postDelayed(heightCheck, 450)
            }
        }
    }

    private var lastReload = 0L
    private val heightCheck = Runnable { checkHeight(nudged = false) }

    private fun checkHeight(nudged: Boolean) {
        val v = view
        if (v.height < 200 || v.parent == null) return
        val density = v.resources.displayMetrics.density
        v.evaluateJavascript("window.innerHeight") { res ->
            val inner = res?.trim('"')?.toFloatOrNull() ?: return@evaluateJavascript
            val real = v.height / density
            if (kotlin.math.abs(inner - real) / real < 0.12f) return@evaluateJavascript
            if (!nudged) {
                v.requestLayout(); v.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
                main.postDelayed({ checkHeight(nudged = true) }, 400)
            } else if (System.currentTimeMillis() - lastReload > 5_000) {
                lastReload = System.currentTimeMillis(); v.reload()
            }
        }
    }


    /** Lift the WebView out of its old parent so Compose can re-attach it after a page change. */
    fun detached(): WebView {
        (view.parent as? ViewGroup)?.removeView(view)
        // Fill whatever space Compose gives it; without this a WebView can measure itself to its
        // content's height and the page collapses.
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return view
    }

    private fun js(code: String) { main.post { view.evaluateJavascript(code, null) } }
    private fun q(s: String) = JSONObject.quote(s)

    inner class Bridge {
        @JavascriptInterface fun recStart(id: Int, lang: String, interim: Boolean) = main.post { startRec(id, lang, interim) }
        @JavascriptInterface fun recStop(id: Int) = main.post { recognizer?.stopListening() }
        @JavascriptInterface fun speak(id: Int, text: String, lang: String, rate: Float, pitch: Float) = main.post { say(id, text, lang, rate, pitch) }
        @JavascriptInterface fun ttsStop() = main.post { tts?.stop() }
    }

    private fun startRec(id: Int, lang: String, interim: Boolean) {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPerm(Manifest.permission.RECORD_AUDIO) { ok ->
                if (ok) startRec(id, lang, interim)
                else { js("window.__duneSR($id,'error','not-allowed',false)"); js("window.__duneSR($id,'end','',false)") }
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            js("window.__duneSR($id,'error','service-not-allowed',false)"); js("window.__duneSR($id,'end','',false)"); return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(act); recognizer = r
        var ended = false
        fun end() { if (!ended) { ended = true; js("window.__duneSR($id,'end','',false)") } }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = js("window.__duneSR($id,'start','',false)")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() = js("window.__duneSR($id,'speechend','',false)")
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (t.isNotBlank()) js("window.__duneSR($id,'result',${q(t)},false)")
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) js("window.__duneSR($id,'result',${q(t)},true)")
                end()
            }
            override fun onError(e: Int) {
                val name = when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_CLIENT -> "aborted"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "network"
                    SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                    else -> "aborted"
                }
                js("window.__duneSR($id,'error','$name',false)"); end()
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim))
    }

    private fun say(id: Int, text: String, lang: String, rate: Float, pitch: Float) {
        val run = Runnable {
            val t = tts ?: return@Runnable
            val loc = Locale.forLanguageTag(lang.ifBlank { "pl-PL" })
            val res = t.setLanguage(loc)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                act.toast("Install the Polish voice: Settings › Text-to-speech › Install voice data › Polski")
                js("window.__duneTTS($id,'error')"); return@Runnable
            }
            t.setSpeechRate(rate.coerceIn(0.3f, 2f)); t.setPitch(pitch.coerceIn(0.5f, 2f))
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString())
        }
        if (tts == null) {
            pendingSpeech += run
            tts = TextToSpeech(act.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(u: String) = js("window.__duneTTS($u,'start')")
                    override fun onDone(u: String) = js("window.__duneTTS($u,'end')")
                    @Deprecated("old API") override fun onError(u: String) = js("window.__duneTTS($u,'error')")
                    override fun onStop(u: String, interrupted: Boolean) = js("window.__duneTTS($u,'end')")
                })
                main.post { if (ttsReady) pendingSpeech.forEach { it.run() } else js("window.__duneTTS($id,'error')"); pendingSpeech.clear() }
            }
        } else if (!ttsReady) pendingSpeech += run else run.run()
    }

    fun destroy() { recognizer?.destroy(); tts?.shutdown(); view.destroy() }

    companion object {
        const val ROZMOWA = "https://newbroman.github.io/rozmowa/"
        const val CLOZE = "https://newbroman.github.io/cloze/"

        /** Web Speech API stand-ins that talk to Android through window.DuneBridge. */
        val SHIM = """
(function(){
 if (window.__duneShim || !window.DuneBridge) return; window.__duneShim = 1;
 var B = window.DuneBridge, recs = {}, n = 0;
 function SR(){ this.lang='pl-PL'; this.interimResults=false; this.continuous=false; this.maxAlternatives=1; this._id=++n; recs[this._id]=this; }
 SR.prototype.start = function(){ B.recStart(this._id, this.lang||'pl-PL', !!this.interimResults); };
 SR.prototype.stop = function(){ B.recStop(this._id); };
 SR.prototype.abort = function(){ B.recStop(this._id); };
 SR.prototype.addEventListener = function(t,f){ this['on'+t] = f; };
 window.__duneSR = function(id, type, text, fin){
   var r = recs[id]; if (!r) return;
   if (type==='result'){ var res=[{transcript:text, confidence:0.9}]; res.isFinal=!!fin; res.item=function(i){return res[i]};
     var rl=[res]; rl.item=function(i){return rl[i]}; r.onresult && r.onresult({resultIndex:0, results:rl}); }
   else if (type==='error') r.onerror && r.onerror({error:text});
   else if (type==='start'){ r.onstart && r.onstart({}); r.onaudiostart && r.onaudiostart({}); }
   else if (type==='speechend') r.onspeechend && r.onspeechend({});
   else if (type==='end') r.onend && r.onend({});
 };
 window.SpeechRecognition = SR; window.webkitSpeechRecognition = SR;

 var utts = {}, un = 0;
 var voice = {name:'Android – Polski', lang:'pl-PL', localService:true, 'default':true, voiceURI:'android-pl'};
 window.SpeechSynthesisUtterance = function(t){ this.text=t||''; this.lang='pl-PL'; this.rate=1; this.pitch=1; this.volume=1; this.voice=null; };
 var ss = { speaking:false, pending:false, paused:false, onvoiceschanged:null,
   getVoices: function(){ return [voice]; },
   speak: function(u){ var id=++un; utts[id]=u; ss.speaking=true; B.speak(id, u.text||'', (u.voice&&u.voice.lang)||u.lang||'pl-PL', u.rate||1, u.pitch||1); },
   cancel: function(){ B.ttsStop(); ss.speaking=false; for (var k in utts){ var u=utts[k]; delete utts[k]; u.onerror && u.onerror({error:'interrupted'}); } },
   pause: function(){}, resume: function(){},
   addEventListener: function(t,f){ if (t==='voiceschanged') setTimeout(f,0); } };
 try { Object.defineProperty(window, 'speechSynthesis', {value: ss, configurable: true}); } catch(e) { window.speechSynthesis = ss; }
 setTimeout(function(){ ss.onvoiceschanged && ss.onvoiceschanged(); }, 60);
 window.__duneTTS = function(id, type){
   var u = utts[id]; if (!u) return;
   if (type==='start') u.onstart && u.onstart({});
   else { ss.speaking=false; delete utts[id]; if (type==='end') u.onend && u.onend({}); else u.onerror && u.onerror({error:'synthesis-failed'}); }
 };
})();
""".trimIndent()
    }
}
