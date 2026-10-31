package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test

/** Positions checked against astropy for Newborough, 2026-10-27 07:50 UTC. */
class SkyTest {
    private val jd = julian(1793087400000L)
    private val ls = lst(jd, -4.37)
    private val bodies = solarSystem(jd).first.associateBy { it.name }

    private fun check(name: String, alt: Double, az: Double) {
        val b = bodies.getValue(name)
        val (a, z) = altAz(b.ra, b.dec, 53.16, ls)
        assertEquals("$name altitude", alt, a, 1.5)
        assertEquals("$name azimuth", az, z, 1.5)
    }

    @Test fun planets() {
        check("Venus", 5.4, 127.2); check("Mars", 53.5, 195.6)
        check("Jupiter", 51.0, 183.9); check("Saturn", -25.7, 313.2)
    }
    @Test fun sun() = check("Sun", 5.1, 119.4)
    @Test fun moon() = check("Moon", 9.3, 293.9)
    @Test fun moonPhase() = assertEquals(195.4, solarSystem(jd).second, 3.0)
}
