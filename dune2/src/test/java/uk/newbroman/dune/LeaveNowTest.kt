package uk.newbroman.dune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveNowTest {
    @Test fun asking() {
        listOf("when do I need to leave", "When should I leave for the dentist?", "what time do I need to set off").forEach {
            assertTrue(it, Assistant.interpret(it) is LeaveWhen)
        }
    }
    @Test fun places() {
        assertTrue(LeaveNow.looksLikePlace("12 High Street, Menai Bridge LL59 5EE"))
        assertTrue(LeaveNow.looksLikePlace("Ysbyty Gwynedd"))
        assertFalse(LeaveNow.looksLikePlace("https://zoom.us/j/123"))
        assertFalse(LeaveNow.looksLikePlace("Microsoft Teams Meeting"))
        assertFalse(LeaveNow.looksLikePlace("Phone"))
    }
}
