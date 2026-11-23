package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksTest {
    // what l14/firefox_bookmarks.py writes
    private val l14Export = """
<!DOCTYPE NETSCAPE-Bookmark-file-1>
<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
<TITLE>Bookmarks</TITLE>
<H1>Bookmarks</H1>
<DL><p>
    <DT><H3>Bookmarks Menu</H3>
    <DL><p>
        <DT><A HREF="https://www.bbc.co.uk/weather" ADD_DATE="1700000000">BBC Weather</A>
    </DL><p>
    <DT><H3>Mobile Bookmarks</H3>
    <DL><p>
        <DT><H3>Polska</H3>
        <DL><p>
            <DT><A HREF="https://allegro.pl/?q=a&amp;b=c" ADD_DATE="1700000001">Allegro – kupuj &amp; sprzedawaj</A>
        </DL><p>
        <DT><A HREF="https://arctic.example/ice" ADD_DATE="0">Arctic &lt;Ice&gt; Watch</A>
    </DL><p>
</DL><p>
""".trimIndent()

    // Chrome / Firefox desktop's own export: lower-case tags, attributes, icons, a place: query and an empty title
    private val chrome = """
<!DOCTYPE NETSCAPE-Bookmark-file-1>
<DL><p>
<DT><H3 ADD_DATE="1" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
<DL><p>
<DT><a href="https://screwfix.com/" add_date="1690000000" icon="data:image/png;base64,AAA=">Screwfix</a>
<DT><A HREF="place:sort=8&maxResults=10">Recent tags</A>
<DT><A HREF="https://toolstation.com/">  </A>
<DT><A HREF="javascript:alert(1)">bookmarklet</A>
</DL><p>
<DT><A HREF="http://example.org/top">Top &#x142;&#243;d&#378;</A>
</DL><p>
""".trimIndent()

    @Test fun readsTheL14Export() {
        val b = Bookmarks.parse(l14Export)
        assertEquals(3, b.size)
        assertEquals(Bookmark("https://www.bbc.co.uk/weather", "BBC Weather", "Bookmarks Menu", 1_700_000_000_000), b[0])
        assertEquals("https://allegro.pl/?q=a&b=c", b[1].url)
        assertEquals("Allegro – kupuj & sprzedawaj", b[1].title)
        assertEquals("Mobile Bookmarks › Polska", b[1].folder)
        assertEquals("Arctic <Ice> Watch", b[2].title)
        assertEquals("Mobile Bookmarks", b[2].folder)     // back out of Polska
    }

    @Test fun readsChromeStyleExport() {
        val b = Bookmarks.parse(chrome)
        assertEquals(listOf("https://screwfix.com/", "https://toolstation.com/", "http://example.org/top"), b.map { it.url })
        assertEquals("Bookmarks bar", b[0].folder)
        assertEquals("toolstation.com", b[1].title)       // no title: the site
        assertEquals("Top łódź", b[2].title)
        assertEquals("", b[2].folder)
    }

    @Test fun readsFirefoxJsonBackup() {
        val json = """{"guid":"root________","title":"","root":"placesRoot","type":"text/x-moz-place-container","children":[
            {"title":"menu","root":"bookmarksMenuFolder","type":"text/x-moz-place-container","children":[
              {"title":"Weawow","type":"text/x-moz-place","uri":"https://weawow.com/","dateAdded":1700000000000000},
              {"title":"Sub","type":"text/x-moz-place-container","children":[
                {"title":"","type":"text/x-moz-place","uri":"https://sub.example/a"},
                {"type":"text/x-moz-place-separator"}]}]},
            {"title":"mobile","root":"mobileFolder","type":"text/x-moz-place-container","children":[
              {"title":"Tongues","type":"text/x-moz-place","uri":"https://newbroman.github.io/cloze/"},
              {"title":"q","type":"text/x-moz-place","uri":"place:type=6"}]}]}"""
        val b = Bookmarks.parse(json)
        assertEquals(listOf("Weawow", "sub.example", "Tongues"), b.map { it.title })
        assertEquals(listOf("Bookmarks Menu", "Bookmarks Menu › Sub", "Mobile Bookmarks"), b.map { it.folder })
        assertEquals(1_700_000_000_000, b[0].added)
    }

    @Test fun nothingFromJunk() {
        assertTrue(Bookmarks.parse("hello").isEmpty())
        assertTrue(Bookmarks.parse("{ not json").isEmpty())
    }

    @Test fun fromShare() {
        // Firefox: just the address, title as subject
        assertEquals(Bookmark("https://bbc.co.uk/news/x", "BBC News", "Shared", 5), Bookmarks.fromShare("https://bbc.co.uk/news/x", "BBC News", 5))
        // Chrome / apps: title and address in one text
        assertEquals("Arctic Ice Watch", Bookmarks.fromShare("Arctic Ice Watch - https://ice.example/watch.", null)!!.title)
        assertEquals("https://ice.example/watch", Bookmarks.fromShare("Arctic Ice Watch - https://ice.example/watch.", null)!!.url)
        // only an address
        assertEquals("allegro.pl", Bookmarks.fromShare("https://www.allegro.pl/oferta", "https://www.allegro.pl/oferta")!!.title)
        assertNull(Bookmarks.fromShare("no link here", "x"))
        assertNull(Bookmarks.fromShare(null, null))
    }

    @Test fun mergeKeepsOneOfEachPage() {
        val old = listOf(Bookmark("https://a.com/", "A"), Bookmark("https://b.com", "B"))
        val m = Bookmarks.merge(old, listOf(Bookmark("http://www.a.com", "A again"), Bookmark("https://c.com", "C", added = 2),
            Bookmark("https://d.com", "D", added = 9), Bookmark("https://c.com/", "C twice")))
        assertEquals(listOf("D", "C", "A", "B"), m.map { it.title })
    }

    @Test fun search() {
        val l = Bookmarks.parse(l14Export)
        assertEquals(listOf("Allegro – kupuj & sprzedawaj"), Bookmarks.search(l, "polska allegro").map { it.title })
        assertEquals(listOf("BBC Weather"), Bookmarks.search(l, "BBC.co").map { it.title })
        assertEquals(3, Bookmarks.search(l, "  ").size)
    }

    @Test fun jsonRoundTrip() {
        val l = Bookmarks.parse(l14Export)
        assertEquals(l, Bookmarks.fromJson(Bookmarks.toJson(l)))
    }
}
