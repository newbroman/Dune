package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test

class LearnTest {
    @Test fun readsTheWebAppsSummary() {
        // evaluateJavascript hands back a JSON-encoded string of the stored JSON
        val raw = "\"{\\\"streak\\\":3,\\\"today\\\":24,\\\"langs\\\":{\\\"pol\\\":{\\\"due\\\":12,\\\"mastered\\\":40,\\\"seen\\\":90},\\\"cym\\\":{\\\"due\\\":2,\\\"mastered\\\":0,\\\"seen\\\":5}}}\""
        val s = ClozeStats.parse(raw)!!
        assertEquals(3, s.streak); assertEquals(24, s.today)
        assertEquals(12, s.due["pol"]); assertEquals(14, s.due.values.sum())
    }
}
