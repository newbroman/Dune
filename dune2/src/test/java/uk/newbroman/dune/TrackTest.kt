package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTest {
    private val walk = listOf(
        TrackPoint(53.1370, -4.3670, 10.0, 1_700_000_000_000),
        TrackPoint(53.1460, -4.3670, 25.0, 1_700_000_600_000),   // 1 km north, 10 min later
        TrackPoint(53.1460, -4.3520, 21.0, 1_700_001_200_000),   // ~1 km east
    )
    @Test fun distanceAndTime() {
        assertEquals(1.0, TrackMath.metres(walk[0], walk[1]) / 1000, 0.01)
        assertEquals(2.0, TrackMath.km(walk), 0.03)
        assertEquals(20, TrackMath.minutes(walk))
        assertEquals(15.0, TrackMath.climb(walk), 0.01)   // the 4 m dip is under the noise threshold
    }
    @Test fun gpxRoundTrip() {
        val x = TrackMath.gpx("Walk <1> & more", walk)
        val back = TrackMath.parseGpx(x)
        assertEquals(3, back.size)
        assertEquals(walk[1].lat, back[1].lat, 1e-6); assertEquals(walk[2].ele!!, back[2].ele!!, 0.05)
        assertEquals(walk[2].time, back[2].time)
        assert(x.contains("Walk &lt;1&gt; &amp; more"))
    }
}
