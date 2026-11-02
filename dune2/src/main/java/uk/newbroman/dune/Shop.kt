@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A shop Dune can search: its search address, with {q} where the words go. */
data class ShopSite(val name: String, val group: String, val url: String) {
    val id get() = name.lowercase().filter { it.isLetterOrDigit() }
    fun search(q: String) = url.replace("{q}", Uri.encode(q.trim()))
    val host get() = runCatching { Uri.parse(url.replace("{q}", "x")).host?.removePrefix("www.") }.getOrNull() ?: ""
}

object Shops {
    /** Default list: name | group | search address. Shops without a public search address go through Google, limited to their site. */
    const val DEFAULT = """eBay | Second-hand & new | https://www.ebay.co.uk/sch/i.html?_nkw={q}
eBay sold prices | Second-hand & new | https://www.ebay.co.uk/sch/i.html?_nkw={q}&LH_Sold=1&LH_Complete=1
Facebook Marketplace | Second-hand & new | https://www.facebook.com/marketplace/search/?query={q}
CeX | Second-hand & new | https://uk.webuy.com/search?stext={q}
Gumtree | Second-hand & new | https://www.gumtree.com/search?search_category=all&q={q}
Vinted | Second-hand & new | https://www.vinted.co.uk/catalog?search_text={q}
Amazon | Shops & price comparison | https://www.amazon.co.uk/s?k={q}
Google Shopping | Shops & price comparison | https://www.google.com/search?udm=28&q={q}
PriceSpy | Shops & price comparison | https://pricespy.co.uk/search?search={q}
Screwfix | Building & DIY | https://www.screwfix.com/search?search={q}
Toolstation | Building & DIY | https://www.toolstation.com/search?q={q}
Huws Gray | Building & DIY | https://www.google.com/search?q=site%3Ahuwsgray.co.uk+{q}
B&Q | Building & DIY | https://www.diy.com/search?term={q}
TradePoint | Building & DIY | https://www.trade-point.co.uk/search?term={q}
Wickes | Building & DIY | https://www.wickes.co.uk/search?text={q}
Travis Perkins | Building & DIY | https://www.travisperkins.co.uk/search?text={q}
Jewson | Building & DIY | https://www.jewson.co.uk/search?text={q}
Selco | Building & DIY | https://www.selcobw.com/catalogsearch/results/shop-by/?q={q}
Builder Depot | Building & DIY | https://www.google.com/search?q=site%3Abuilderdepot.co.uk+{q}
Machine Mart | Building & DIY | https://www.google.com/search?q=site%3Amachinemart.co.uk+{q}
CEF electrical | Building & DIY | https://www.google.com/search?q=site%3Acef.co.uk+{q}
Homebase | Building & DIY | https://www.google.com/search?q=site%3Ahomebase.co.uk+{q}"""

    fun parse(text: String): List<ShopSite> = text.lines().mapNotNull { l ->
        val p = l.split('|').map { it.trim() }
        if (p.size >= 3 && p[0].isNotEmpty() && p[2].contains("{q}") && !l.trim().startsWith("#")) ShopSite(p[0], p[1].ifEmpty { "Other" }, p[2]) else null
    }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dune", 0)
    fun list(ctx: Context) = parse(prefs(ctx).getString("shops", DEFAULT)!!)
    fun setList(ctx: Context, text: String) = prefs(ctx).edit().putString("shops", text).apply()
    fun text(ctx: Context) = prefs(ctx).getString("shops", DEFAULT)!!
    /** Which shops a search covers (ids); a sensible start. */
    fun picked(ctx: Context): Set<String> = prefs(ctx).getString("shopPicked", null)?.split(',')?.filter { it.isNotBlank() }?.toSet()
        ?: setOf("ebay", "ebaysoldprices", "facebookmarketplace", "cex", "amazon", "googleshopping")
    fun setPicked(ctx: Context, s: Set<String>) = prefs(ctx).edit().putString("shopPicked", s.joinToString(",")).apply()

    fun recent(ctx: Context): List<String> = prefs(ctx).getString("shopRecent", "")!!.split('\n').filter { it.isNotBlank() }
    fun remember(ctx: Context, q: String) = prefs(ctx).edit().putString("shopRecent", (listOf(q.trim()) + recent(ctx).filter { !it.equals(q.trim(), true) }).take(12).joinToString("\n")).apply()

    data class Saved(val title: String, val url: String, val shop: String, val at: Long)
    fun saved(ctx: Context): List<Saved> = runCatching {
        val a = JSONArray(prefs(ctx).getString("shopSaved", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Saved(it.getString("t"), it.getString("u"), it.optString("s"), it.optLong("at")) }
    }.getOrDefault(emptyList())
    fun save(ctx: Context, list: List<Saved>) = prefs(ctx).edit().putString("shopSaved", JSONArray(list.map {
        JSONObject().put("t", it.title).put("u", it.url).put("s", it.shop).put("at", it.at) }).toString()).apply()
}

/** Shop: one search across marketplaces, shops and builders' merchants, compared side by side. */
object ShopHub : Hub {
    override val id = "shop"; override val title = "Shop"; override val glyph = "£"
    private val APPS = listOf("com.ebay.mobile", "com.amazon.mShop.android.shopping", "com.facebook.katana", "uk.co.cex.app",
        "com.gumtree.android", "fr.vinted", "com.screwfix.screwfix", "com.toolstation.toolstation", "com.kingfisher.diy")
    private val WORDS = listOf("ebay", "amazon", "cex", "gumtree", "vinted", "screwfix", "toolstation", "b&q", "wickes", "argos", "shop")

    override fun search(ctx: Context, vm: LauncherVM, q: String) =
        if (q.length >= 3) listOf(("£ Search shops for “$q”") to { vm.shopQuery = q; vm.screen = Screen.SHOP }) else emptyList()

    @Composable override fun summary(vm: LauncherVM): String? {
        val ctx = LocalContext.current
        val n = remember(vm.resumeTick) { Shops.saved(ctx).size }
        val last = remember(vm.resumeTick) { Shops.recent(ctx).firstOrNull() }
        return listOfNotNull(last?.let { "last search: $it" }, if (n > 0) "$n saved" else null).joinToString(" · ").ifBlank { null }
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        var q by rememberSaveable { mutableStateOf("") }
        var picked by remember { mutableStateOf(Shops.picked(act)) }
        var stores by remember { mutableStateOf(Shops.list(act)) }
        var editing by remember { mutableStateOf(false) }
        var tick by remember { mutableStateOf(0) }
        val recent = remember(tick, vm.resumeTick) { Shops.recent(act) }
        val saved = remember(tick, vm.resumeTick) { Shops.saved(act) }
        fun go(text: String = q) {
            if (text.isBlank()) return
            if (stores.none { it.id in picked }) { act.toast("Pick at least one shop"); return }
            Shops.remember(act, text); tick++; vm.shopQuery = text.trim(); vm.screen = Screen.SHOP
        }
        HubPage("Shop", gestures) {
            item {
                AppsRow(vm, id, APPS, WORDS)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Faint, RoundedCornerShape(24.dp)).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Fg, fontSize = 17.sp), cursorBrush = SolidColor(Sand),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
                        modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                        decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search shops for…", color = Faint, fontSize = 17.sp); inner() } })
                    Text("🎤", fontSize = 20.sp, modifier = Modifier.combinedClickable { act.listen("What are you looking for?") { it?.let { t -> q = t; go(t) } } }.padding(10.dp))
                    Text("compare", color = Sand, fontSize = 15.sp, modifier = Modifier.combinedClickable { go() }.padding(end = 16.dp, top = 10.dp, bottom = 10.dp))
                }
                if (recent.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp)) { recent.take(8).forEach { r -> Chip(r) { q = r; go(r) } } }
            }
            // Which shops a search covers, by group.
            stores.groupBy { it.group }.forEach { (g, list) ->
                item(key = "g$g") {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                        val all = list.all { it.id in picked }
                        Text(if (all) "none" else "all", color = Faint, fontSize = 12.sp, modifier = Modifier.combinedClickable {
                            picked = if (all) picked - list.map { it.id }.toSet() else picked + list.map { it.id }; Shops.setPicked(act, picked)
                        }.padding(4.dp))
                    }
                    FlowRow {
                        list.forEach { s ->
                            val on = s.id in picked
                            Chip((if (on) "● " else "") + s.name, onLong = { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(s.search(q.ifBlank { " " }).replace("+%20", "")))) }) {
                                picked = if (on) picked - s.id else picked + s.id; Shops.setPicked(act, picked)
                            }
                        }
                    }
                }
            }
            item {
                Note("Tap shops to include them, then “compare”: each shop's results open side by side – swipe between them. Long-press a shop to open it on its own.")
                if (saved.isNotEmpty()) {
                    Text("SAVED", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    saved.forEach { sv ->
                        Column(Modifier.fillMaxWidth().combinedClickable(onClick = { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(sv.url))) },
                            onLongClick = { Shops.save(act, Shops.saved(act).filter { it.url != sv.url }); tick++; act.toast("Removed") }).padding(vertical = 6.dp)) {
                            Text(sv.title, color = Fg, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(sv.shop + " · " + ago(sv.at), color = Faint, fontSize = 11.sp)
                        }
                    }
                    Note("Long-press a saved item to remove it.")
                }
                Text(if (editing) "done editing shops" else "edit shops", color = Faint, fontSize = 12.sp,
                    modifier = Modifier.combinedClickable { if (editing) stores = Shops.list(act); editing = !editing }.padding(vertical = 10.dp))
                if (editing) {
                    var text by remember { mutableStateOf(Shops.text(act)) }
                    Note("One per line:  Name | group | search address with {q} for the words. For a shop without a search page, use Google:  https://www.google.com/search?q=site%3Ashop.co.uk+{q}")
                    OutlinedTextField(text, { text = it; Shops.setList(act, it) }, minLines = 8, textStyle = TextStyle(fontSize = 12.sp, color = Fg), modifier = Modifier.fillMaxWidth())
                    Row {
                        Chip("restore the default list") { text = Shops.DEFAULT; Shops.setList(act, text) }
                    }
                }
            }
        }
    }
}

/** The comparison: one tab per shop, each a web page of its results; swipe or tap a tab. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShopScreen(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    val q = vm.shopQuery
    val stores = remember(q) { Shops.list(act).filter { it.id in Shops.picked(act) } }
    val pager = rememberPagerState { stores.size }
    val scope = rememberCoroutineScope()
    val views = remember(q) { HashMap<String, WebView>() }
    DisposableEffect(q) { onDispose { views.values.forEach { it.destroy() }; views.clear() } }
    fun web(s: ShopSite): WebView = views.getOrPut(s.id) {
        WebView(act).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true; settings.useWideViewPort = true
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    // Web pages stay here; app links (intent:, market:, fb:…) go to the app.
                    if (u.scheme == "http" || u.scheme == "https") return false
                    runCatching {
                        val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                        act.tryStart(i)
                    }
                    return true
                }
            }
            loadUrl(s.search(q))
        }
    }
    LaunchedEffect(q) { if (q.isBlank()) vm.screen = Screen.HOME }
    if (stores.isEmpty()) { LaunchedEffect(Unit) { act.toast("Pick some shops first"); vm.screen = Screen.HOME }; return }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Dim, fontSize = 20.sp, modifier = Modifier.combinedClickable {
                val w = views[stores[pager.currentPage].id]
                if (w?.canGoBack() == true) w.goBack() else { vm.jumpHub = ShopHub.id; vm.goHome() }
            }.padding(10.dp))
            Text("“$q”", color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("★", color = Sand, fontSize = 18.sp, modifier = Modifier.combinedClickable {
                val s = stores[pager.currentPage]; val w = views[s.id]
                val url = w?.url ?: s.search(q)
                Shops.save(act, listOf(Shops.Saved(w?.title?.takeIf { it.isNotBlank() } ?: "$q – ${s.name}", url, s.name, System.currentTimeMillis())) + Shops.saved(act).filter { it.url != url })
                act.toast("Saved – it's on the Shop page")
            }.padding(10.dp))
            Text("↗", color = Dim, fontSize = 18.sp, modifier = Modifier.combinedClickable {
                val s = stores[pager.currentPage]
                act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(views[s.id]?.url ?: s.search(q))))
            }.padding(10.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            stores.forEachIndexed { i, s ->
                val on = pager.currentPage == i
                Text(s.name, color = if (on) Sand else Dim, fontSize = 13.sp,
                    modifier = Modifier.combinedClickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
        // Move between shops with the tabs or ‹ › below; swipes inside a page scroll the page.
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), beyondViewportPageCount = 1, userScrollEnabled = false, key = { stores[it].id }) { page ->
            val s = stores[page]
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { web(s).also { (it.parent as? android.view.ViewGroup)?.removeView(it) } }, modifier = Modifier.fillMaxSize())
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ ${stores.getOrNull(pager.currentPage - 1)?.name ?: ""}", color = Dim, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.weight(1f).combinedClickable { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }.padding(10.dp))
            Text("${pager.currentPage + 1} / ${stores.size}", color = Faint, fontSize = 12.sp)
            Text("${stores.getOrNull(pager.currentPage + 1)?.name ?: ""} ›", color = Dim, fontSize = 13.sp, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f).combinedClickable { if (pager.currentPage < stores.size - 1) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }.padding(10.dp))
        }
    }
}
