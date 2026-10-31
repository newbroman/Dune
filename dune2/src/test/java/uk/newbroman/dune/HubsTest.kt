package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HubsTest {
    private fun e(t: Long, who: String, text: String, pkg: String = "com.whatsapp", kind: String = "message") =
        InboxLog.Entry(t, pkg, "WhatsApp", who, text, kind)
    private fun n(t: Long, who: String, text: String, kind: String = "message") =
        NotifItem("k$t", "com.whatsapp", who, text, t, null, true, isMessage = kind == "message", kind = kind)

    @Test fun groupsByPersonNewestFirst() {
        val c = Convos.build(
            listOf(e(1, "Tom", "hi"), e(2, "Mum", "dinner?"), e(3, "tom ", "on my way"), e(4, "Gmail Bob", "invoice", "com.google.android.gm", "email")),
            listOf(n(5, "Mum", "call me"), n(6, "BBC News", "headline", kind = "other")),
        )
        assertEquals(listOf("Mum", "Gmail Bob", "tom"), c.map { it.who.lowercase().let { w -> if (w == "mum") "Mum" else if (w.startsWith("gmail")) "Gmail Bob" else w } })
        assertEquals(2, c.first { it.who.equals("tom", true) }.entries.size)
        assertEquals("call me", c.first().preview)            // a live notification is the latest word
        assertEquals("email", c.first { it.who.startsWith("Gmail") }.kind)
    }
    @Test fun otherNotificationsAreNotPeople() {
        assertEquals(0, Convos.build(emptyList(), listOf(n(1, "Weather alert", "rain", kind = "other"))).size)
    }
    @Test fun hubRegistry() {
        assertNotNull(Hubs.byId("comms")); assertNotNull(Hubs.byId("tasks")); assertNull(Hubs.byId("nope"))
        assertEquals(Hubs.ALL.size, Hubs.ALL.map { it.id }.distinct().size)
    }
}
