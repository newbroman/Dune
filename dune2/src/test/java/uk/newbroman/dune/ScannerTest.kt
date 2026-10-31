package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTest {
    @Test fun risks() {
        assertNotNull(Ports.risk(23, false))                 // Telnet is always worth flagging
        assertNull(Ports.risk(80, false)); assertNotNull(Ports.risk(80, true))   // a web page is fine inside, not from the internet
        assertNotNull(Ports.risk(3389, true)); assertNull(Ports.risk(443, true))
    }
    @Test fun newDevices() {
        val before = ScanRecord(0, "Home", listOf(LanHost("192.168.1.1", null, emptySet()), LanHost("192.168.1.20", null, emptySet())))
        assertEquals(setOf("192.168.1.33"), Scanner.newSince(before, listOf(LanHost("192.168.1.1", null, emptySet()), LanHost("192.168.1.33", null, emptySet()))))
        assertTrue(Scanner.newSince(null, listOf(LanHost("1.2.3.4", null, emptySet()))).isEmpty())
    }
    @Test fun report() {
        val r = Ports.report("Home", listOf(LanHost("192.168.1.5", "printer", setOf(631))), mapOf("192.168.1.5" to listOf(OpenPort(23, "Telnet", null, "bad"))))
        assertTrue(r.contains("192.168.1.5") && r.contains("23  Telnet") && r.contains("⚠ bad"))
    }
}
