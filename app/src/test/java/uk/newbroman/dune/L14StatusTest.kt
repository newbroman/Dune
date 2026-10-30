package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L14StatusTest {
    // Shape written by l14/l14-status.py --json (trimmed).
    private val json = """
        {"host": "john-thinkpad-a475", "generated": "2026-10-30T23:26:53+00:00", "fail": 1, "warn": 2,
         "items": [
          {"group": "Backups", "name": "USB backup (nightly)", "level": "ok", "detail": "last OK 2 h ago"},
          {"group": "Backups", "name": "USB backup drive", "level": "warn", "detail": "not plugged in"},
          {"group": "Services", "name": "Telegram bot (hydrobot)", "level": "fail", "detail": "not running (inactive)"},
          {"group": "Services", "name": "Claude Remote Control", "level": "info", "detail": "no session running"},
          {"group": "Machine", "name": "Updates", "level": "warn", "detail": "12 waiting, 3 security"}
         ]}
    """.trimIndent()

    @Test fun parsesItemsAndCounts() {
        val s = L14Status.parse(json, now = 1L)
        assertEquals(5, s.items.size)
        assertEquals(1, s.fail)
        assertEquals(2, s.warn)
        assertEquals(1L, s.receivedAt)
    }

    @Test fun problemsAreFailuresFirstAndSkipOkAndInfo() {
        val p = L14Status.parse(json).problems
        assertEquals(listOf("Telegram bot (hydrobot)", "USB backup drive", "Updates"), p.map { it.name })
    }

    @Test fun countsFallBackToItemsWhenMissing() {
        val s = L14Status.parse("""{"items":[{"group":"g","name":"n","level":"fail","detail":"d"}]}""")
        assertEquals(1, s.fail)
        assertEquals(0, s.warn)
    }

    @Test fun summaryWording() {
        val s = L14Status.parse(json)
        assertEquals("● online · 1 problem, 2 warnings", l14Summary(true, s))
        assertEquals("○ offline", l14Summary(false, s))
        assertEquals("● online", l14Summary(true, null))
        val clean = L14Status("", 0, 0, emptyList())
        assertTrue(l14Summary(true, clean).endsWith("all OK"))
    }
}
