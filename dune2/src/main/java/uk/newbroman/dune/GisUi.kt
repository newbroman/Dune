@file:OptIn(ExperimentalFoundationApi::class)

package uk.newbroman.dune

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Web map services checked to work (EPSG:3857, PNG, no key) – mostly for Wales and Newborough. */
private const val NRW = "https://datamap.gov.wales/geoserver/inspire-nrw/wms"
private const val DMW = "https://datamap.gov.wales/geoserver/geonode/wms"
private const val NRW_ATTR = "© Natural Resources Wales and Database Right. Contains OS data © Crown copyright (AC0000849444)"
private const val BGS625 = "https://ogc.bgs.ac.uk/cgi-bin/BGS_Bedrock_and_Superficial_Geology/wms"
private const val BGS50 = "https://map.bgs.ac.uk/arcgis/services/BGS_Detailed_Geology/MapServer/WMSServer"
val WMS_PRESETS = listOf(
    Gis.WmsPreset("SSSI (NRW)", NRW, "NRW_SSSI", NRW_ATTR, 0.6),
    Gis.WmsPreset("Special Areas of Conservation (NRW)", NRW, "NRW_SAC", NRW_ATTR, 0.8),
    Gis.WmsPreset("National Nature Reserves (NRW)", NRW, "NRW_NNR", NRW_ATTR, 0.5),
    Gis.WmsPreset("Special Protection Areas (NRW)", NRW, "NRW_SPA", NRW_ATTR, 0.6),
    Gis.WmsPreset("Flood Map for Planning – zones 2 and 3 (NRW)", NRW, "FloodMapforPlanningFloodZones2and3", NRW_ATTR, 0.6),
    Gis.WmsPreset("Flood risk from the sea (NRW)", NRW, "NRW_FLOOD_RISK_FROM_SEA", NRW_ATTR, 0.6),
    Gis.WmsPreset("Phase 1 habitat survey (NRW)", DMW, "nrw_terrestrial_phase_1_habitat_survey", "© Natural Resources Wales", 0.7),
    Gis.WmsPreset("Phase 2 sand dune vegetation (NRW)", DMW, "nrw_phase_2_sand_dunes", "© Natural Resources Wales", 0.75),
    Gis.WmsPreset("Geology 1:50k – superficial (BGS, zoom in)", BGS50, "BGS.50k.Superficial.deposits", "Contains British Geological Survey materials © UKRI", 0.6),
    Gis.WmsPreset("Geology 1:50k – bedrock (BGS, zoom in)", BGS50, "BGS.50k.Bedrock", "Contains British Geological Survey materials © UKRI", 0.6),
    Gis.WmsPreset("Geology 1:625k – superficial (BGS)", BGS625, "GBR_BGS_625k_SLT", "British Geological Survey (BGS)", 0.6),
    Gis.WmsPreset("Geology 1:625k – bedrock (BGS)", BGS625, "GBR_BGS_625k_BLT", "British Geological Survey (BGS)", 0.6),
    Gis.WmsPreset("LiDAR hillshade 1 m (Environment Agency, England only)",
        "https://environment.data.gov.uk/spatialdata/lidar-composite-digital-terrain-model-dtm-1m/wms",
        "Lidar_Composite_Hillshade_DTM_1m", "© Environment Agency copyright and/or database right", 0.75),
)

private val Panel = Color(0xFF15140F)

@Composable
private fun Sheet(title: String, onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(14.dp).background(Panel, RoundedCornerShape(14.dp)).padding(16.dp).heightIn(max = 620.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Fg, fontSize = 19.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                Text("done", color = Sand, fontSize = 15.sp, modifier = Modifier.combinedClickable(onClick = onClose).padding(8.dp))
            }
            content()
        }
    }
}

/** The map's layer list: tick to show, tap a name to zoom to it, ⋯ for colour, QField, sharing and removing. */
@Composable
fun LayersSheet(
    layers: List<Gis.Layer>, status: Map<String, String>, onChange: (List<Gis.Layer>) -> Unit,
    onZoom: (Gis.Layer) -> Unit, onAddFiles: () -> Unit, onClose: () -> Unit,
) {
    val act = LocalContext.current as MainActivity
    var addingWeb by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    Sheet("Layers", onClose) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            if (layers.isEmpty()) item { Note("No layers yet.") }
            items(layers, key = { it.id }) { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val c = runCatching { Color(android.graphics.Color.parseColor(l.color)) }.getOrDefault(Sand)
                    Box(Modifier.size(22.dp).combinedClickable { onChange(layers.map { if (it.id == l.id) it.copy(on = !it.on) else it }) }.padding(3.dp)
                        .background(if (l.on) c else Color.Transparent, CircleShape)
                        .then(if (l.on) Modifier else Modifier.background(Color(0x33FFFFFF), CircleShape)))
                    Column(Modifier.weight(1f).combinedClickable { if (!l.on) onChange(layers.map { if (it.id == l.id) it.copy(on = true) else it }); onZoom(l) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(l.name, color = if (l.on) Fg else Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(when (l.kind) { "wms" -> "web map"; "wells" -> "built in"; else -> l.fmt?.uppercase() }, status[l.id]).joinToString(" · "),
                            color = if (status[l.id]?.startsWith("⚠") == true) Color(0xFFFF8A80) else Dim, fontSize = 12.sp, maxLines = 2)
                    }
                    Box {
                        Text("⋯", color = Dim, fontSize = 20.sp, modifier = Modifier.combinedClickable { menu = l.id }.padding(horizontal = 10.dp, vertical = 4.dp))
                        androidx.compose.material3.DropdownMenu(menu == l.id, onDismissRequest = { menu = null }) {
                            @Composable fun item(t: String, f: () -> Unit) = androidx.compose.material3.DropdownMenuItem(text = { Text(t) }, onClick = { menu = null; f() })
                            item("Next colour") { onChange(layers.map { if (it.id == l.id) it.copy(color = Gis.COLORS[(Gis.COLORS.indexOf(l.color) + 1) % Gis.COLORS.size]) else it }) }
                            if (l.isWeb) {
                                item("More see-through") { onChange(layers.map { if (it.id == l.id) it.copy(opacity = (it.opacity - 0.15).coerceAtLeast(0.15)) else it }) }
                                item("Less see-through") { onChange(layers.map { if (it.id == l.id) it.copy(opacity = (it.opacity + 0.15).coerceAtMost(1.0)) else it }) }
                            }
                            if (l.kind == "file") {
                                item("Open in QField") { Gis.fileUri(act, l)?.let { Gis.sendToQField(act, it, Gis.mimeOf(l.fmt)) } }
                                item("Share the file") { Gis.fileUri(act, l)?.let { Gis.share(act, it, Gis.mimeOf(l.fmt), "Share ${l.name}") } }
                            }
                            val i = layers.indexOf(l)
                            if (i > 0) item("Move up") { onChange(layers.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                            if (i < layers.size - 1) item("Move down") { onChange(layers.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                            if (l.kind != "wells") item("Remove") { Gis.delete(act, l); onChange(layers.filter { it.id != l.id }) }
                        }
                    }
                }
            }
        }
        LinkRow("Add a file") { onAddFiles() }
        Note("GeoPackage, shapefile (pick the .shp, .dbf and .prj together, or a .zip), GeoJSON, KML/KMZ, GPX or CSV. British National Grid is converted. You can also open these files with Dune from Files or Drive.")
        LinkRow("Add a web map (WMS)") { addingWeb = true }
        LinkRow(if (Gis.hasQField(act)) "Open QField (QGIS on Android)" else "Get QField (QGIS on Android)") { Gis.openQField(act) }
    }
    if (addingWeb) WmsSheet(onAdd = { name, url, layer, attr, op ->
        onChange(layers + Gis.Layer("w" + System.currentTimeMillis().toString(36), name, "wms", wmsUrl = url, wmsLayers = layer, attr = attr, opacity = op))
        addingWeb = false
    }, onClose = { addingWeb = false })
}

@Composable
private fun WmsSheet(onAdd: (String, String, String, String?, Double) -> Unit, onClose: () -> Unit) {
    val act = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    Sheet("Add a web map", onClose) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item { Note("Ready to use (Wales and Britain):") }
            items(WMS_PRESETS) { p -> LinkRow(p.label) { onAdd(p.label, p.url, p.layers, p.attr, p.opacity) } }
            item {
                Note("Or any WMS address – Dune reads the list of its layers:")
                OutlinedTextField(url, { url = it; found = null }, singleLine = true, placeholder = { Text("https://…/wms") }, modifier = Modifier.fillMaxWidth())
                LinkRow("Find its layers") {
                    msg = "Asking the server…"; found = null
                    scope.launch {
                        runCatching { Gis.wmsLayers(url) }.onSuccess { found = it; msg = if (it.isEmpty()) "No drawable layers found there" else null }
                            .onFailure { msg = "Couldn't read that service: ${it.message}" }
                    }
                }
                msg?.let { Note(it) }
            }
            found?.let { list -> items(list) { (n, t) -> LinkRow(t) { onAdd(t, url.substringBefore('?').trim(), n, url.substringAfter("//").substringBefore('/'), 0.7) } } }
        }
    }
}

/** The survey menu: points, measuring, the grid reference, and exporting for QGIS. */
@Composable
fun SurveyMenu(open: Boolean, count: Int, grid: Boolean, onDismiss: () -> Unit, js: (String) -> Unit,
               onGrid: () -> Unit, onExport: (fmt: String, qfield: Boolean) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    androidx.compose.material3.DropdownMenu(open, onDismissRequest = { onDismiss(); confirm = false }) {
        @Composable fun item(t: String, f: () -> Unit) = androidx.compose.material3.DropdownMenuItem(text = { Text(t) }, onClick = { onDismiss(); f() })
        item("Add a point at my position") { js("gisAddPoint('gps')") }
        item("Add a point at the map centre") { js("gisAddPoint('centre')") }
        item("Measure a distance") { js("gisMeasure('line')") }
        item("Measure an area") { js("gisMeasure('area')") }
        item(if (grid) "Hide the grid reference" else "Show the grid reference") { onGrid() }
        if (count > 0) {
            item("Save survey as GeoPackage (QGIS)") { onExport("gpkg", false) }
            item("Save survey as GeoJSON") { onExport("geojson", false) }
            item("Send survey to QField") { onExport("gpkg", true) }
            androidx.compose.material3.DropdownMenuItem(text = { Text(if (confirm) "Tap again to delete all $count" else "Clear survey ($count)", color = if (confirm) Color(0xFFFF8A80) else Color.Unspecified) },
                onClick = { if (confirm) { confirm = false; onDismiss(); js("gisClearSurvey()") } else confirm = true })
        }
        androidx.compose.material3.DropdownMenuItem(text = { Text("Long-press the map for a spot's grid reference", color = Dim, fontSize = 12.sp) }, onClick = { onDismiss() })
    }
}

// ---- The Newborough well logger.

/** Opens the well logger: in Dune by default; [installed] opens the copy installed from Chrome instead. */
fun openWellLogger(vm: LauncherVM, act: MainActivity, well: String? = null, installed: Boolean = false) {
    if (installed) {
        val app = vm.apps.firstOrNull { it.label.contains("well logger", true) }
        if (app != null) { vm.launch(app); return }
        act.toast("The well logger isn't installed from Chrome – opening it in Dune")
    }
    vm.screen = Screen.WELLS
    if (well != null) act.wells().runWhenLoaded("try{switchTab('log');selectWell(${org.json.JSONObject.quote(well.lowercase())})}catch(e){}")
}

/** The well logger full screen; back returns to the map. */
@Composable
fun WellsFull(vm: LauncherVM) {
    val act = LocalContext.current as MainActivity
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← map", color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable { vm.back() }.padding(10.dp))
            Text("Well logger", color = Fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("↻", color = Dim, fontSize = 16.sp, modifier = Modifier.combinedClickable { act.wells().view.reload() }.padding(10.dp))
        }
        AndroidView(factory = { act.wells().detached() }, modifier = Modifier.fillMaxSize())
    }
}
