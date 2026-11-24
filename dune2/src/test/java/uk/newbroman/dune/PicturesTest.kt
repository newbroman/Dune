package uk.newbroman.dune

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PicturesTest {
    private fun c(s: String) = Assistant.interpret(s)

    @Test fun asking() {
        assertEquals(Cmd.Pictures("Pluto"), c("Can you show me a picture of Pluto?"))
        assertEquals(Cmd.Pictures("the Eiffel Tower"), c("show me some photos of the Eiffel Tower please"))
        assertEquals(Cmd.Pictures("a puffin"), c("What does a puffin look like"))
        assertEquals(Cmd.Pictures("Kraków"), c("pictures of Kraków"))
        assertEquals(Cmd.Pictures("it"), c("show me a picture of it"))
        assertTrue(Pictures.needsResolving("it"))
        assertTrue(c("what does the weather look like tomorrow") !is Cmd.Pictures)
        assertTrue(c("what does my day look like") !is Cmd.Pictures)
        assertTrue(c("take a picture") !is Cmd.Pictures)
    }

    @Test fun wikipedia() {
        val j = JSONObject("""{"query":{"pages":[{"title":"Pluto","fullurl":"https://en.wikipedia.org/wiki/Pluto",
            "thumbnail":{"source":"https://upload.wikimedia.org/a/Pluto.jpg"},"extract":"Pluto is a dwarf planet. It is in the Kuiper belt."}]}}""")
        val (title, pic, rest) = Pictures.parseWiki(j)!!
        assertEquals("Pluto", title); assertEquals("https://upload.wikimedia.org/a/Pluto.jpg", pic!!.url)
        assertEquals("https://en.wikipedia.org/wiki/Pluto", rest.second)
    }

    @Test fun commons() {
        val j = JSONObject("""{"query":{"pages":[
            {"index":2,"title":"File:Pluto_b.png","imageinfo":[{"thumburl":"https://t/b.png","descriptionurl":"https://c/b","mime":"image/png"}]},
            {"index":1,"title":"File:Pluto_a.jpg","imageinfo":[{"thumburl":"https://t/a.jpg","descriptionurl":"https://c/a","mime":"image/jpeg"}]},
            {"index":3,"title":"File:Pluto.tif","imageinfo":[{"thumburl":"https://t/c.jpg","descriptionurl":"https://c/c","mime":"image/tiff"}]}]}}""")
        val p = Pictures.parseCommons(j)
        assertEquals(listOf("Pluto a", "Pluto b"), p.map { it.title })
    }
}

class PicturesSummaryTest {
    @Test fun summary() {
        val j = JSONObject("""{"type":"standard","title":"Pluto","extract":"Pluto is a dwarf planet. It orbits far out. Third sentence.",
            "thumbnail":{"source":"https://upload.wikimedia.org/thumb/a/ab/P.jpg/330px-P.jpg","width":330},
            "originalimage":{"source":"https://upload.wikimedia.org/a/ab/P.jpg","width":4000},
            "content_urls":{"mobile":{"page":"https://en.m.wikipedia.org/wiki/Pluto"}}}""")
        val (t, pic, rest) = Pictures.parseSummary(j)!!
        assertEquals("Pluto", t)
        assertEquals("https://upload.wikimedia.org/thumb/a/ab/P.jpg/1280px-P.jpg", pic!!.url)
        assertEquals("Pluto is a dwarf planet. It orbits far out.", rest.first)
    }
}
