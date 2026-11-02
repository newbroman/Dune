package uk.newbroman.dune
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
class BackupTest {
    @Test fun sealedRoundTrip() {
        val plain = """{"ai_openai":"sk-test","tado_refresh":"r1"}""".toByteArray()
        val s = Backup.seal(plain, "horse battery")
        assertArrayEquals(plain, Backup.open(s, "horse battery"))
        val wrong = runCatching { Backup.open(s, "wrong") }
        assert(wrong.isFailure)
    }
    @Test fun prefsTyped() {
        val o = Backup.prefsJson(mapOf("a" to true, "b" to 3, "c" to 4L, "d" to "x", "e" to setOf("p", "q")))
        assertEquals("b", o.getJSONObject("a").getString("t")); assertEquals(3, o.getJSONObject("b").getInt("v"))
        assertEquals("l", o.getJSONObject("c").getString("t")); assertEquals("ss", o.getJSONObject("e").getString("t"))
    }
}
