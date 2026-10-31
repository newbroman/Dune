package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GisTest {
    @Test fun formatsFromNames() {
        assertEquals("gpkg", Gis.fmtOf("Newborough.GPKG"))
        assertEquals("geojson", Gis.fmtOf("wells.json"))
        assertEquals("zip", Gis.fmtOf("dunes.zip"))
        assertEquals("kmz", Gis.fmtOf("NEW WELLS.kmz"))
        assertEquals("csv", Gis.fmtOf("well_metadata.csv"))
        assertNull(Gis.fmtOf("photo.jpg"))
        assertNull(Gis.fmtOf("README"))
    }
    @Test fun presetsAreComplete() {
        WMS_PRESETS.forEach { p -> assert(p.url.startsWith("https://") && p.layers.isNotBlank() && p.attr.isNotBlank()) { p.label } }
    }
}
