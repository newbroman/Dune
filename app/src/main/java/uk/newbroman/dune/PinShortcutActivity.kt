package uk.newbroman.dune

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle

/** Receives "add to home screen" requests from apps (e.g. Termux shortcuts) while Dune is the launcher. */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        val info = req?.shortcutInfo
        if (req != null && info != null && req.isValid && req.accept()) {
            Store.get(this).addPinned(Pinned(info.`package`, info.id, (info.shortLabel ?: info.id).toString()))
            toast("Added “${info.shortLabel}” to Dune")
        }
        finish()
    }
}
