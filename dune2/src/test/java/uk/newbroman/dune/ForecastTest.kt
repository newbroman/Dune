package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {
    @Test fun parsesARealResponse() {
        val json = javaClass.classLoader!!.getResource("forecast.json")!!.readText()
        val f = Forecasts.parse(json, "Newborough")
        assertEquals(24, f.hours.size)
        assertEquals(8, f.days.size)
        assertEquals(8, f.next2h.size)
        assertTrue(f.days.all { it.high >= it.low })
        assertTrue(f.days[0].sunrise.matches(Regex("\\d\\d:\\d\\d")))
        assertTrue(f.temp in -30..45)
    }
    @Test fun nowcast() {
        assertEquals("No rain in the next two hours", Forecasts.nowcast(List(8) { 0.0 }))
        assertEquals("Rain starting in about 30 min", Forecasts.nowcast(listOf(0.0, 0.0, 0.4, 1.0, 0.0, 0.0, 0.0, 0.0)))
        assertEquals("Rain easing in about 45 min", Forecasts.nowcast(listOf(0.5, 0.5, 0.2, 0.0, 0.0, 0.0, 0.0, 0.0)))
        assertEquals("NE", Forecasts.from(40)); assertEquals("N", Forecasts.from(350)); assertEquals("W", Forecasts.from(270))
    }
}
