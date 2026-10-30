package uk.newbroman.dune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test fun hearsTheWakeWord() {
        assertTrue(WakeWord.isWake("""{"text" : "okay computer"}"""))
        assertTrue(WakeWord.isWake("""{"text" : "ok computer"}"""))
        assertTrue(WakeWord.isWake("""{"text" : "[unk] okay computer"}"""))
    }
    @Test fun ignoresOtherSpeech() {
        assertFalse(WakeWord.isWake("""{"text" : "computer"}"""))
        assertFalse(WakeWord.isWake("""{"text" : ""}"""))
        assertFalse(WakeWord.isWake("""{"text" : "okay"}"""))
        assertFalse(WakeWord.isWake("not json"))
    }
}
