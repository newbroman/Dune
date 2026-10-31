package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetTest {
    @Test fun hostsInNetwork() {
        val h = Lan.hosts("192.168.1.37", 24)
        assertEquals(253, h.size)                         // .1–.254 minus this phone
        assertEquals("192.168.1.1", h.first()); assertEquals("192.168.1.254", h.last())
        assertFalse("192.168.1.37" in h)
        assertEquals(253, Lan.hosts("10.0.5.9", 16).size)  // big networks are capped to the /24 around the phone
        assertEquals(13, Lan.hosts("10.1.1.20", 28).size)  // .17–.30 minus this phone
    }
    @Test fun guessesDevices() {
        assertEquals("printer", Lan.kind(setOf(80, 631), emptySet(), null))
        assertEquals("speaker", Lan.kind(setOf(8009), emptySet(), null))
        assertEquals("speaker", Lan.kind(emptySet(), setOf("_googlecast:Kitchen speaker"), null))
        assertEquals("computer", Lan.kind(setOf(22), emptySet(), "john-thinkpad-l14"))
        assertEquals("printer", Lan.kind(emptySet(), emptySet(), "HP-LaserJet-M15w"))
        assertEquals("device", Lan.kind(emptySet(), emptySet(), null))
    }
    @Test fun signal() {
        assertEquals(4, Lan.bars(-50)); assertEquals(2, Lan.bars(-70)); assertEquals(0, Lan.bars(-95))
        assertEquals("5 GHz", Lan.band(5180)); assertEquals("2.4 GHz", Lan.band(2437))
        assertEquals(80.0, Lan.mbps(10_000_000, 1000), 0.01)
    }
}
