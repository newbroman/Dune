package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    @Test fun search() {
        val r = Routes.parseSearch(res("photon.json"))
        assertTrue(r.isNotEmpty())
        assertEquals("Newborough Forest", r[0].name)
        assertTrue(r[0].lat in 53.0..53.3 && r[0].lon in -4.5..-4.2)
    }
    @Test fun route() {
        val r = Routes.parseRoute(res("route.json"))!!
        assertTrue(r.points.size > 5)
        assertEquals(5.1, r.km, 0.1)
        assertEquals(69, r.minutes)
    }
}
