@file:OptIn(ExperimentalFoundationApi::class)

package uk.newbroman.dune

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Folded Home heading: how many there are. */
fun bookmarksHint(vm: LauncherVM): String? {
    val n = Bookmarks.list.size + vm.linkPinned.size
    return if (n == 0) null else "$n"
}

/** Dune's search: matching bookmarks and home-screen links. */
fun bookmarkSearch(ctx: Context, vm: LauncherVM, q: String): List<Pair<String, () -> Unit>> {
    Bookmarks.ensure(ctx)
    val pins = vm.linkPinned.filter { Find.matches(it.label, q) }.take(3).map { p -> ("☆ " + p.label) to { vm.launchPinned(p) } }
    val marks = Bookmarks.search(Bookmarks.list, q, 5).map { b -> ("☆ " + b.title) to { Bookmarks.open(ctx, b.url); Unit } }
    return pins + marks
}

/**
 * Bookmarks on Home: pages added to the home screen from a browser, pages shared to Dune, and imported
 * bookmarks files. Tap opens; long-press to star into Favourites, copy, share or delete.
 */
@Composable
fun BookmarksBlock(vm: LauncherVM) {
    val ctx = LocalContext.current
    val act = ctx as? MainActivity
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var all by rememberSaveable { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }
    val marks = Bookmarks.list
    val pins = vm.linkPinned

    Header("Bookmarks", "import file") {
        act?.pickFiles(multiple = false) { uris ->
            val u = uris.firstOrNull() ?: return@pickFiles
            scope.launch { msg = withContext(Dispatchers.IO) { Bookmarks.importFile(ctx, u) } }
        }
    }
    msg?.let { Note(it) }

    if (marks.size + pins.size > 8) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Fg, fontSize = 16.sp),
            cursorBrush = SolidColor(Sand), modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search bookmarks…", color = Faint, fontSize = 16.sp); inner() } })
        if (query.isNotEmpty()) Text("✕", color = Dim, fontSize = 15.sp, modifier = Modifier.combinedClickable { query = "" }.padding(8.dp))
    }

    val q = query.trim()
    val shownPins = if (q.isEmpty()) pins else pins.filter { Find.matches(it.label, q) }
    val limit = if (all || q.isNotEmpty()) 60 else (8 - shownPins.size).coerceAtLeast(3)
    val found = Bookmarks.search(marks, q, limit)

    Column(Modifier.fillMaxWidth()) {
        shownPins.forEach { p -> PinRow(vm, p) }
        found.forEach { b -> BookmarkRow(vm, b) }
        if (q.isNotEmpty() && shownPins.isEmpty() && found.isEmpty()) Note("Nothing matches “$q”")
        val more = (if (q.isEmpty()) marks.size else marks.count { Bookmarks.matches(it, q) }) - found.size
        if (more > 0) Text(if (q.isEmpty()) "all ${marks.size}  →" else "$more more – narrow the search", color = Dim, fontSize = 13.sp,
            modifier = Modifier.combinedClickable { if (q.isEmpty()) all = true }.padding(vertical = 8.dp))
        if (all && q.isEmpty() && marks.size > 60) Note("Showing the newest 60 – search to find the rest")
    }

    val showHelp = help || (marks.isEmpty() && pins.isEmpty())
    Text(if (showHelp) "how to add  ▴" else "how to add  ▾", color = Faint, fontSize = 13.sp,
        modifier = Modifier.combinedClickable { help = !help }.padding(vertical = 6.dp))
    if (showHelp) {
        Note("From Firefox or Chrome on the phone: ⋮ › Share › Dune bookmark saves the page you're on.")
        Note("⋮ › Add to Home screen also works – the page appears here and can be starred into Favourites.")
        Note("All your Firefox bookmarks: export them on the L14 (Firefox Sync brings the phone's over too), " +
            "copy the file to the phone, then tap import file. Firefox's bookmarks.html or .json backup and Chrome's export all work.")
        Note("Long-press a bookmark to star it into Favourites, copy, share or delete it.")
    }
}

@Composable
private fun BookmarkRow(vm: LauncherVM, b: Bookmark) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val key = "web:" + b.url
    val fav = key in vm.store.favourites
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { Bookmarks.open(ctx, b.url) }.padding(vertical = 7.dp)) {
            Text((if (fav) "★ " else "") + b.title, color = Fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Bookmarks.host(b.url) + (if (b.folder.isNotEmpty()) "  ·  " + b.folder.substringAfterLast(" › ") else ""),
                color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            Text(b.url, color = Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(text = { Text(if (fav) "Remove from favourites" else "Add to favourites") },
                onClick = { menu = false; vm.toggleFavouriteUrl(b.url) })
            DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                menu = false
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(b.title, b.url))
            })
            DropdownMenuItem(text = { Text("Share") }, onClick = {
                menu = false
                ctx.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, b.title).putExtra(Intent.EXTRA_TEXT, b.url), "Share ${b.title}"))
            })
            DropdownMenuItem(text = { Text("Delete bookmark") }, onClick = {
                menu = false
                if (fav) vm.store.toggleFavourite(key)
                Bookmarks.remove(ctx, b)
            })
        }
    }
}

@Composable
private fun PinRow(vm: LauncherVM, p: Pinned) {
    var menu by remember { mutableStateOf(false) }
    val key = vm.pinKey(p)
    val fav = key in vm.store.favourites
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { vm.launchPinned(p) }.padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LinkIcon(vm, p, 22.dp)
            Column(Modifier.padding(start = 10.dp)) {
                Text((if (fav) "★ " else "") + p.label, color = Fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("on the home screen", color = Dim, fontSize = 12.sp)
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (fav) "Remove from favourites" else "Add to favourites") },
                onClick = { menu = false; vm.store.toggleFavourite(key) })
            DropdownMenuItem(text = { Text("Remove link") }, onClick = {
                menu = false
                if (fav) vm.store.toggleFavourite(key)
                vm.store.removePinned(p)
            })
        }
    }
}
