@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Złoty exchange rate (European Central Bank figures via frankfurter.app), kept for a few hours. */
object Zloty {
    private const val KEY = "plnRate"; private const val AT = "plnRateAt"
    fun cached(ctx: Context): Float? = ctx.getSharedPreferences("dune", 0).getFloat(KEY, 0f).takeIf { it > 0f }
    /** £1 in złoty. Fetches when the saved rate is over 6 hours old; falls back to the saved one offline. */
    fun rate(ctx: Context): Float? {
        val p = ctx.getSharedPreferences("dune", 0)
        if (System.currentTimeMillis() - p.getLong(AT, 0) < 6 * 3_600_000L) cached(ctx)?.let { return it }
        return runCatching {
            val r = JSONObject(get("https://api.frankfurter.app/latest?from=GBP&to=PLN")).getJSONObject("rates").getDouble("PLN").toFloat()
            p.edit().putFloat(KEY, r).putLong(AT, System.currentTimeMillis()).apply(); r
        }.getOrElse { cached(ctx) }
    }
    fun fmt(v: Double) = String.format(Locale.UK, if (v >= 100) "%,.0f" else "%,.2f", v)
}

/** Poland: Google Translate, the Polish apps on the phone, złoty, time in Warsaw, and the Polish learning tools. */
object PolskaHub : Hub {
    override val id = "polska"; override val title = "Polska"; override val glyph = "ż"
    const val TRANSLATE = "com.google.android.apps.translate"
    /** Apps you'd use in Poland: shopping, government ID, travel, tickets, parcels, money, maps. */
    private val APPS = listOf(TRANSLATE, "pl.allegro", "pl.nask.mobywatel", "pl.mobiltek.mobilet", "com.mobiltek.mobilet",
        "com.citynav.jakdojade.pl.android", "pl.koleo", "pl.intercity.app", "pl.tablica", "pl.inpost.inmobile", "pl.zabka.apb2c",
        "pl.biedronka.app", "com.lidl.eci.lidlplus", "ee.mtakso.client", "com.ubercab", "pl.mbank", "pl.pkobp.iko", "com.revolut.revolut",
        "cz.seznam.mapy", "pl.mapaturystyczna.app", "pl.orlen.mobile", "com.booking", "com.flightradar24free", "pl.lot.mobile")
    /** Names that mark an app as Polish (or for Poland). */
    private val WORDS = listOf("allegro", "mobilet", "mobywatel", "jakdojade", "koleo", "pkp", "intercity", "inpost", "olx", "żabka", "zabka",
        "biedronka", "orlen", "blik", "mbank", "poland", "polska", "polski", "polsk", "polish", "warszaw", "kraków", "krakow", "wrocław",
        "gdańsk", "gdansk", "tatry", "szlak", "turyst", "hiking", "mapa", "bilet", "mpk", "ztm", "translate", "tłumacz")
    private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

    @Composable override fun summary(vm: LauncherVM): String {
        val ctx = LocalContext.current
        val t = ZonedDateTime.now(WARSAW).format(DateTimeFormatter.ofPattern("HH:mm"))
        val r = remember(vm.resumeTick) { Zloty.cached(ctx) }
        return "Warszawa $t" + (r?.let { " · £1 = ${Zloty.fmt(it.toDouble())} zł" } ?: "") + " · Translate"
    }

    private fun translate(ctx: Context, text: String, from: String, to: String) {
        val url = "https://translate.google.com/?sl=$from&tl=$to&op=translate&text=" + Uri.encode(text)
        // the Translate app takes the text directly; otherwise the website
        if (text.isNotBlank() && ctx.isInstalled(TRANSLATE) &&
            ctx.tryStart(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(TRANSLATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        if (text.isBlank() && ctx.isInstalled(TRANSLATE)) { ctx.packageManager.getLaunchIntentForPackage(TRANSLATE)?.let { ctx.tryStart(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } }
        ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        var q by remember { mutableStateOf("") }
        var gbp by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf<List<HubApp>?>(null) }
        val polishPkgs = remember(vm.apps) { vm.apps.map { it.pkg }.filter { it.startsWith("pl.") } }
        val loaded by produceState<List<HubApp>?>(null, vm.apps, vm.resumeTick, vm.store.hubAppsAdded.value, vm.store.hubAppsRemoved.value) {
            value = withContext(Dispatchers.Default) { hubApps(act, vm, APPS + polishPkgs, WORDS, id) }
        }
        apps = loaded
        val rate by produceState(Zloty.cached(act), active) { if (active) value = withContext(Dispatchers.IO) { Zloty.rate(act) } }
        val now by produceState(ZonedDateTime.now(WARSAW), active) { while (active) { value = ZonedDateTime.now(WARSAW); kotlinx.coroutines.delay(20_000) } }

        HubPage("Polska", gestures) {
            // ---- Translate
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, Faint, RoundedCornerShape(24.dp)).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Fg, fontSize = 17.sp), cursorBrush = SolidColor(Sand),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { translate(act, q, "en", "pl") }),
                        modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                        decorationBox = { inner -> Box { if (q.isEmpty()) Text("Translate…", color = Faint, fontSize = 17.sp); inner() } })
                    Text("🎤", fontSize = 20.sp, modifier = Modifier.combinedClickable { act.listen("Say it in English") { it?.let { t -> q = t; translate(act, t, "en", "pl") } } }.padding(10.dp))
                }
                FlowRow(Modifier.padding(top = 6.dp)) {
                    Chip("EN → PL") { translate(act, q, "en", "pl") }
                    Chip("PL → EN") { translate(act, q, "pl", "en") }
                    Chip("🎤 po polsku") { act.listen("Powiedz po polsku", "pl-PL") { it?.let { t -> q = t; translate(act, t, "pl", "en") } } }
                    Chip("Translate app") { translate(act, "", "en", "pl") }
                }
            }
            // ---- the Polish apps on this phone
            item {
                Section("Apps")
                val l = apps
                if (l == null) Note("…")
                else if (l.isEmpty()) Note("No Polish apps found yet – add them with “apps ▾ › + add an app”.")
                else FlowRow {
                    l.forEach { a ->
                        Column(Modifier.width(76.dp).combinedClickable(onClick = { launchPkg(vm, a.pkg) },
                            onLongClick = { vm.apps.firstOrNull { it.pkg == a.pkg }?.let { vm.appInfo(it) } }).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            a.icon?.let { Image(it, null, Modifier.size(44.dp)) }
                            Text(a.label, color = Fg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
                AppsRow(vm, id, APPS + polishPkgs, WORDS)
                Note("Polish apps are found by name (and any app whose package starts with pl.). Use apps ▾ to add or take one off.")
            }
            // ---- money and time
            item {
                Section("Złoty")
                val r = rate
                if (r == null) Note("No exchange rate yet (needs the internet once).")
                else {
                    Text("£1 = ${Zloty.fmt(r.toDouble())} zł   ·   10 zł = £${Zloty.fmt(10 / r.toDouble())}", color = Fg, fontSize = 17.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(gbp, { gbp = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, singleLine = true,
                            textStyle = TextStyle(color = Fg, fontSize = 17.sp), cursorBrush = SolidColor(Sand),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(110.dp).border(1.dp, Faint, RoundedCornerShape(12.dp)).padding(10.dp),
                            decorationBox = { inner -> Box { if (gbp.isEmpty()) Text("amount", color = Faint, fontSize = 17.sp); inner() } })
                        val v = gbp.replace(',', '.').toDoubleOrNull()
                        if (v != null) Text("  £${Zloty.fmt(v)} = ${Zloty.fmt(v * r)} zł\n  ${Zloty.fmt(v)} zł = £${Zloty.fmt(v / r)}", color = Dim, fontSize = 14.sp)
                    }
                    Note("European Central Bank rate; card and cash rates differ a little.")
                }
                Section("Czas")
                Text("Warszawa " + now.format(DateTimeFormatter.ofPattern("HH:mm, EEEE d MMMM", Locale.forLanguageTag("pl"))), color = Fg, fontSize = 17.sp)
                Note("Poland is an hour ahead of the UK. Emergency: 112.")
            }
            // ---- learning Polish
            item {
                Section("Mówię po polsku")
                FlowRow {
                    Chip("Rozmowa") { vm.jumpHub = "rozmowa" }
                    Chip("Tongues (Polish)") { vm.screen = Screen.CLOZE }
                    Chip("Polish phrases") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(Phrases.APP_URL))) }
                    Chip("time in Polish") { Speaker.speak(act, PolishTime.spoken(java.time.LocalDateTime.now(WARSAW)), PolishTime.PL); act.toast(PolishTime.spoken(java.time.LocalDateTime.now(WARSAW))) }
                }
            }
        }
    }

    @Composable private fun Section(t: String) = Text(t.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}
