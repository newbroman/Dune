package uk.newbroman.dune

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Share › "Dune bookmark" from Firefox, Chrome or any app: saves the page to Dune's bookmarks. */
class BookmarkShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = Bookmarks.fromShare(intent?.getStringExtra(Intent.EXTRA_TEXT), intent?.getStringExtra(Intent.EXTRA_SUBJECT), System.currentTimeMillis())
        when {
            b == null -> toast("No web address in what was shared")
            Bookmarks.add(this, listOf(b)) == 0 -> toast("Already in Dune's bookmarks: ${b.title}")
            else -> toast("Saved to Dune's bookmarks: ${b.title}")
        }
        finish()
    }
}
