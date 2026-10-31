package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    @Test fun parsesWorkspaces() {
        val w = Workspaces.parse("Maps + music | Maps | Spotify\nbad line\n | Translate | WhatsApp\nOnly | one |")
        assertEquals(2, w.size)
        assertEquals(Workspace("Maps + music", "Maps", "Spotify"), w[0])
        assertEquals("Translate + WhatsApp", w[1].name)
    }
    @Test fun findsAppsByName() {
        val apps = listOf("Google Maps", "Maps.me", "Spotify", "Translate")
        assertEquals("Spotify", Workspaces.find(apps, "spotify") { it })
        assertEquals("Maps.me", Workspaces.find(apps, "maps") { it })          // starts-with beats contains
        assertEquals("Google Maps", Workspaces.find(apps, "google maps") { it })
        assertNull(Workspaces.find(apps, "chrome") { it })
    }
    @Test fun whatsappNumbers() {
        assertEquals("447700900123", Find.whatsappNumber("07700 900123"))
        assertEquals("447700900123", Find.whatsappNumber("+44 7700 900123"))
        assertEquals("48601234567", Find.whatsappNumber("0048 601 234 567"))
        assertEquals("447700900123", Find.whatsappNumber("+44 (0)7700 900123"))
    }
    @Test fun wordMatching() {
        assertTrue(Find.matches("Tom: see you at the station at 6", "tom station"))
        assertFalse(Find.matches("Tom: see you later", "tom station"))
        assertFalse(Find.matches("anything", "a"))
    }
    @Test fun kinds() {
        assertEquals("email", Kind.of("com.google.android.gm", null, false))
        assertEquals("email", Kind.of("x", "email", false))
        assertEquals("message", Kind.of("com.whatsapp", "msg", true))
        assertEquals("other", Kind.of("com.bbc.news", null, false))
    }
    @Test fun describesCommands() {
        assertEquals("Add task: Buy screws", Find.describe(Assistant.interpret("add task buy screws")))
        assertEquals("Navigate to Asda", Find.describe(Assistant.interpret("navigate to Asda")))
        assertNull(Find.describe(Assistant.interpret("when is Tom coming")))
    }
    @Test fun loginCodesStayOnThePhone() {
        assertEquals("Your code is [code]", Brain.redact("Your code is 482913"))
        assertEquals("G-[code] is your Google code", Brain.redact("G-123456 is your Google code"))
        assertEquals("See you at 18:30 on 2026-11-02, £1200.50", Brain.redact("See you at 18:30 on 2026-11-02, £1200.50"))
        assertEquals("Meet at 7", Brain.redact("Meet at 7"))
    }
}
