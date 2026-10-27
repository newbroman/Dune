package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses a saved copy of newbroman.github.io (src/test/resources/portfolio.html). */
class WebAppsTest {
    private val list = WebApps.parse(javaClass.classLoader!!.getResource("portfolio.html")!!.readText())

    @Test fun findsEveryApp() {
        val titles = list.map { it.title }
        assertTrue("got ${list.size}", list.size >= 18)
        for (t in listOf("Polish Phrase Master", "Source Check", "Hnefatafl", "Jukebox of Names", "Welsh Number Trainer"))
            assertTrue("missing $t", t in titles)
        assertTrue("Dune itself excluded", titles.none { it == "Dune" })
        assertTrue(list.all { it.url.startsWith("https://") })
    }

    @Test fun shelves() {
        val ppm = list.first { it.title == "Polish Phrase Master" }
        assertTrue(ppm.isLanguage)
        assertEquals(false, list.first { it.title == "Source Check" }.isLanguage)
        assertTrue(list.first { it.title == "Welsh Number Trainer" }.isLanguage)
    }
}
