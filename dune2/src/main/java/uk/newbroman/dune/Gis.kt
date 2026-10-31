package uk.newbroman.dune

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * GIS on Dune's map: layers from files (GeoPackage, shapefile, GeoJSON, KML/KMZ, GPX, CSV) and from
 * web map services (WMS), plus the survey you make with the map's tools. The files live in Dune's
 * own storage (files/gis) and the map page reads them from there; the list is files/gis/layers.json.
 */
object Gis {
    data class Layer(
        val id: String, val name: String, val kind: String,   // "file", "wms" or "wells"
        val file: String? = null, val fmt: String? = null, val color: String = "#e3a35b", val on: Boolean = true,
        val wmsUrl: String? = null, val wmsLayers: String? = null, val opacity: Double = 0.7, val attr: String? = null,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("file", file).put("fmt", fmt)
            .put("color", color).put("on", on).put("wmsUrl", wmsUrl).put("wmsLayers", wmsLayers).put("opacity", opacity).put("attr", attr)
        /** What the map page wants. */
        fun js(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("color", color).put("on", on).apply {
            when (kind) {
                "wms" -> put("wms", JSONObject().put("url", wmsUrl).put("layers", wmsLayers).put("opacity", opacity).put("attr", attr ?: ""))
                "wells" -> { put("url", "wells.geojson"); put("fmt", "geojson") }
                else -> { put("url", "/gis/" + Uri.encode(file)); put("fmt", fmt) }
            }
        }
        val isWeb get() = kind == "wms"
        companion object {
            fun of(o: JSONObject) = Layer(o.getString("id"), o.getString("name"), o.getString("kind"), o.optStr("file"), o.optStr("fmt"),
                o.optString("color", "#e3a35b"), o.optBoolean("on", true), o.optStr("wmsUrl"), o.optStr("wmsLayers"),
                o.optDouble("opacity", 0.7), o.optStr("attr"))
        }
    }
    private fun JSONObject.optStr(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }

    val COLORS = listOf("#e3a35b", "#7fb2ff", "#5fd38a", "#ff7a8a", "#c9a0ff", "#ffd84a", "#4fd1d9", "#ffffff")
    val WELLS = Layer("wells", "Newborough wells", "wells", color = "#4fd1d9", on = false)

    fun dir(ctx: Context) = File(ctx.filesDir, "gis").apply { mkdirs() }
    private fun listFile(ctx: Context) = File(dir(ctx), "layers.json")
    fun surveyFile(ctx: Context) = File(dir(ctx), "survey.geojson")

    fun load(ctx: Context): List<Layer> {
        val f = android.util.AtomicFile(listFile(ctx))
        if (!listFile(ctx).exists()) return listOf(WELLS)
        return runCatching { JSONArray(f.readFully().decodeToString()).let { a -> (0 until a.length()).map { Layer.of(a.getJSONObject(it)) } } }
            .getOrElse {
                // Unreadable list: rebuild it from the files that are there, rather than losing them.
                listOf(WELLS) + (dir(ctx).listFiles() ?: emptyArray()).filter { fmtOf(it.name) != null && it.name != "survey.geojson" && it.name != "layers.json" }
                    .mapIndexed { i, file -> Layer("r$i" + file.name.hashCode().toString(36), file.nameWithoutExtension, "file", file.name, fmtOf(file.name), COLORS[i % COLORS.size], false) }
            }
    }
    fun save(ctx: Context, list: List<Layer>) = writeAtomic(listFile(ctx), JSONArray(list.map { it.json() }).toString())

    private fun writeAtomic(file: File, text: String) = synchronized(this) {
        val f = android.util.AtomicFile(file)
        val out = f.startWrite()
        try { out.write(text.toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
    }
    /** The survey, written whole or not at all (with the previous one kept as a .bak). */
    fun writeSurvey(ctx: Context, json: String) {
        if (runCatching { JSONObject(json).getJSONArray("features") }.isFailure) return
        val f = surveyFile(ctx)
        if (f.exists()) runCatching { f.copyTo(File(dir(ctx), "survey.bak.geojson"), overwrite = true) }
        writeAtomic(f, json)
    }
    fun readSurvey(ctx: Context): String? = runCatching {
        android.util.AtomicFile(surveyFile(ctx)).readFully().decodeToString().also { JSONObject(it).getJSONArray("features") }
    }.getOrNull() ?: runCatching { File(dir(ctx), "survey.bak.geojson").readText().also { JSONObject(it).getJSONArray("features") } }.getOrNull()
    fun toJs(list: List<Layer>): String = JSONArray(list.map { it.js() }).toString()

    /** The format Dune reads a file as, from its name; null if it isn't a map file. */
    fun fmtOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "gpkg" -> "gpkg"; "geojson", "json" -> "geojson"; "kml" -> "kml"; "kmz" -> "kmz"; "gpx" -> "gpx"
        "csv", "txt", "tsv" -> "csv"; "zip" -> "zip"; "shp" -> "shp"
        else -> null
    }
    private val SHP_PARTS = setOf("shp", "shx", "dbf", "prj", "cpg")

    private fun displayName(ctx: Context, u: Uri): String =
        runCatching { ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            ?: u.lastPathSegment?.substringAfterLast('/') ?: "layer"

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)

    /**
     * Copies picked files into Dune and adds them as layers. A shapefile's parts (.shp, .dbf, .prj…)
     * picked together become one zipped layer. Returns the new layers, and names of files it couldn't use.
     */
    suspend fun import(ctx: Context, uris: List<Uri>, existing: List<Layer>): Pair<List<Layer>, List<String>> = withContext(Dispatchers.IO) {
        val d = dir(ctx)
        val named = uris.map { it to displayName(ctx, it) }
        val (parts, others) = named.partition { it.second.substringAfterLast('.', "").lowercase() in SHP_PARTS }
        val out = ArrayList<Layer>(); val bad = ArrayList<String>()
        var ci = existing.size
        fun add(name: String, file: String, fmt: String) {
            val big = File(d, file).length() > 40L * 1024 * 1024   // very large files could exhaust the map page's memory
            out += Layer("f" + System.currentTimeMillis().toString(36) + out.size, name, "file", file, fmt, COLORS[ci++ % COLORS.size], on = !big)
        }
        fun unique(n: String): String { var f = safe(n); var i = 1; while (File(d, f).exists()) f = safe(n).let { it.substringBeforeLast('.') + "-" + i++ + "." + it.substringAfterLast('.') }; return f }
        // shapefiles: group the parts by name
        parts.groupBy { it.second.substringBeforeLast('.') }.forEach { (base, group) ->
            if (group.none { it.second.endsWith(".shp", true) }) { bad += group.map { it.second }; return@forEach }
            val fn = unique("$base.zip")
            runCatching {
                ZipOutputStream(File(d, fn).outputStream()).use { z ->
                    group.forEach { (u, n) -> z.putNextEntry(ZipEntry(safe(n))); ctx.contentResolver.openInputStream(u)!!.use { it.copyTo(z) }; z.closeEntry() }
                }
                add(base, fn, "zip")
            }.onFailure { bad += "$base.shp" }
        }
        others.forEach { (u, n) ->
            val fmt = fmtOf(n)
            if (fmt == null) { bad += n; return@forEach }
            val fn = unique(n)
            runCatching { ctx.contentResolver.openInputStream(u)!!.use { i -> File(d, fn).outputStream().use { i.copyTo(it) } }; add(n.substringBeforeLast('.'), fn, fmt) }
                .onFailure { bad += n }
        }
        out to bad
    }

    fun delete(ctx: Context, l: Layer) { l.file?.let { File(dir(ctx), it).delete() } }

    // ---- Saving what the map exports (the survey): into Downloads/Dune, then offered to share.
    suspend fun saveExport(ctx: Context, name0: String, mime: String, bytes: ByteArray): Uri? = withContext(Dispatchers.IO) {
        val name = safe(File(name0).name).ifBlank { "download" }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Dune")
                }
                val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                ctx.contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
                u
            } else {
                val f = File(File(dir(ctx), "exports").apply { mkdirs() }, name); f.writeBytes(bytes)
                FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            }
        }.getOrNull()
    }
    fun share(ctx: Context, u: Uri, mime: String, title: String) {
        ctx.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), title))
    }
    fun fileUri(ctx: Context, l: Layer): Uri? = l.file?.let { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(dir(ctx), it)) }
    fun mimeOf(fmt: String?) = when (fmt) {
        "gpkg" -> "application/geopackage+sqlite3"; "kml" -> "application/vnd.google-earth.kml+xml"; "kmz" -> "application/vnd.google-earth.kmz"
        "gpx" -> "application/gpx+xml"; "csv" -> "text/csv"; "zip", "shp" -> "application/zip"; else -> "application/geo+json"
    }

    // ---- QField: QGIS on Android.
    const val QFIELD = "ch.opengis.qfield"
    fun hasQField(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(QFIELD, 0); true }.getOrDefault(false)
    fun openQField(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(QFIELD)
        if (i != null) ctx.tryStart(i) else getQField(ctx)
    }
    fun getQField(ctx: Context) {
        if (!ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$QFIELD"))))
            ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$QFIELD")))
    }
    /** Hands a file to QField, which opens or imports it (GeoPackage, GeoJSON, KML, GPX, zipped shapefile, QGIS project). */
    fun sendToQField(ctx: Context, u: Uri, mime: String) {
        if (!hasQField(ctx)) { ctx.toast("QField isn't installed – opening the Play Store"); getQField(ctx); return }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).setPackage(QFIELD).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (ctx.tryStart(view)) return
        if (ctx.tryStart(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).setPackage(QFIELD).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return
        share(ctx, u, mime, "Open with")
    }

    // ---- Web map services.
    data class WmsPreset(val label: String, val url: String, val layers: String, val attr: String, val opacity: Double = 0.7)

    /** Layers a WMS offers, read from its GetCapabilities: name to title. */
    suspend fun wmsLayers(base: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val u = wmsBase(base)
        val c = URL(u + (if (u.contains('?')) "&" else "?") + "service=WMS&request=GetCapabilities").openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Dune (github.com/newbroman/Dune)")
        c.inputStream.use { parseCapabilities(it.readBytes().decodeToString()) }
    }

    /** A WMS address without its own WMS request parameters, but keeping others (e.g. MapServer's map=). */
    fun wmsBase(url: String): String {
        val t = url.trim(); val q = t.substringAfter('?', "")
        val keep = q.split('&').filter { it.isNotBlank() && it.substringBefore('=').lowercase() !in setOf("service", "request", "version", "layers", "format", "srs", "crs", "bbox", "width", "height", "styles", "transparent") }
        return t.substringBefore('?') + if (keep.isEmpty()) "" else "?" + keep.joinToString("&")
    }

    /** The named layers in a WMS capabilities document (the ones that can be drawn), name to title. */
    fun parseCapabilities(xml: String): List<Pair<String, String>> {
        val p = android.util.Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        val out = ArrayList<Pair<String, String>>()
        val stack = ArrayList<Array<String?>>()   // per <Layer>: name, title
        var depth = 0; val layerDepth = ArrayList<Int>()
        var text = StringBuilder(); var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val tag = p.name?.substringAfter(':')
            when (ev) {
                XmlPullParser.START_TAG -> { depth++; if (tag == "Layer") { stack += arrayOfNulls<String>(2); layerDepth += depth }; text = StringBuilder() }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val direct = layerDepth.isNotEmpty() && depth == layerDepth.last() + 1   // a child of the Layer itself, not of its Style
                    when (tag) {
                        "Name" -> if (direct) stack.last().let { if (it[0] == null) it[0] = text.toString().trim() }
                        "Title" -> if (direct) stack.last().let { if (it[1] == null) it[1] = text.toString().trim() }
                        "Layer" -> { layerDepth.removeAt(layerDepth.size - 1); stack.removeAt(stack.size - 1).let { (n, t) -> if (!n.isNullOrBlank()) out += n to (t ?: n) } }
                    }
                    depth--
                }
            }
            ev = p.next()
        }
        return out
    }
}
