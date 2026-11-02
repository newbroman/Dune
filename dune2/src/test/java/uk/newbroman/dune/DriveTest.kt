package uk.newbroman.dune
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class DriveTest {
    @Test fun carNames() {
        listOf("Car Audio", "CAR-KIT", "MyCar", "carplay", "Ford Car Multimedia", "car").forEach { assertTrue(it, Drive.isCarName(it)) }
        listOf("Scarlett 2i2", "Pixel Buds", "Cardiff speaker", "Sony WH-1000", "Oscar's AirPods", null, "").forEach { assertFalse(it ?: "null", Drive.isCarName(it)) }
    }
}
