package uk.newbroman.dune
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
class KlapLiveTest {
    @Test fun againstFakePlug() {
        Assume.assumeTrue(System.getenv("KLAP_FAKE") == "1")
        val s = Klap.connect("127.0.0.1", "me@example.com", "p4ss!")!!
        assertEquals(true, s.smart)
        assertEquals(false, s.send("""{"method":"get_device_info"}""").getJSONObject("result").getBoolean("device_on"))
        s.send("""{"method":"set_device_info","params":{"device_on":true}}""")
        assertEquals(true, s.send("""{"method":"get_device_info"}""").getJSONObject("result").getBoolean("device_on"))
        try { Klap.connect("127.0.0.1", "me@example.com", "wrong"); throw AssertionError("should fail") } catch (e: Klap.LoginFailed) {}
    }
}

class WizLiveTest {
    @Test fun againstFakeBulb() {
        Assume.assumeTrue(System.getenv("KLAP_FAKE") == "1")
        Wiz.ask("127.0.0.1", """{"method":"setPilot","params":{"state":true,"dimming":70}}""")
        val r = Wiz.ask("127.0.0.1", """{"method":"getPilot","params":{}}""").getJSONObject("result")
        assertEquals(true, r.getBoolean("state")); assertEquals(70, r.getInt("dimming"))
        val found = Wiz.discover("127.0.0.1", 8, seconds = 2)
        assertEquals("a8bb50aabbcc", found.values.first().getString("mac"))
    }
}
