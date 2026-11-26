package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AssistantPlusTest {
    private val today = LocalDate.of(2026, 11, 26)            // Thursday
    private fun p(s: String) = Reminders.parse(s.lowercase(), today) as? Reminders.Set

    @Test fun memoryWords() {
        assertEquals(Memory.Remember("my boiler is a Worcester Greenstar"), Assistant.interpret("Remember that my boiler is a Worcester Greenstar"))
        assertTrue(Assistant.interpret("what do you remember about me") is Memory.Recall)
        assertEquals(Memory.Forget("my boiler"), Assistant.interpret("forget that my boiler"))
        assertTrue(Assistant.interpret("forget that") is Cmd.NewChat)
        assertTrue(Assistant.interpret("remember to buy milk") !is Memory.Remember)
        val f = listOf(Memory.Fact(1, "My boiler is a Worcester Greenstar", 0), Memory.Fact(2, "Anna's birthday is 4 March", 0))
        assertEquals(listOf(1L), Memory.matching(f, "my boiler").map { it.id })
    }

    @Test fun briefing() {
        listOf("good morning", "brief me", "morning briefing", "what do i need to know today", "give me my briefing").forEach {
            assertTrue(it, Assistant.interpret(it) is Brief)
        }
    }

    @Test fun repeating() {
        p("remind me on the 1st of every month to read the gas meter")!!.let {
            assertEquals("monthly", it.repeat); assertEquals(LocalDate.of(2026, 12, 1), it.date); assertEquals("Read the gas meter", it.what) }
        p("remind me every monday at 7 pm to put the bins out")!!.let { assertEquals("weekly", it.repeat); assertEquals(LocalDate.of(2026, 11, 30), it.date); assertEquals(19, it.hour) }
        p("remind me every day at 8 am to take my tablets")!!.let { assertEquals("daily", it.repeat); assertEquals(8, it.hour) }
        p("remind me every morning to water the plants")!!.let { assertEquals("daily", it.repeat); assertEquals(9, it.hour); assertEquals("Water the plants", it.what) }
        p("remind me every weekday at 8 to check the post")!!.let { assertEquals("weekdays", it.repeat) }
        p("remind me every year on 4th march about anna's birthday")!!.let { assertEquals("yearly", it.repeat); assertEquals(LocalDate.of(2027, 3, 4), it.date) }
        // month ends: the 31st becomes the 30th in November, then back to the 31st
        val t = LocalDateTime.of(2026, 10, 31, 9, 0)
        assertEquals(LocalDateTime.of(2026, 11, 30, 9, 0), Reminders.next(t, "monthly", 31))
        assertEquals(LocalDateTime.of(2026, 12, 31, 9, 0), Reminders.next(LocalDateTime.of(2026, 11, 30, 9, 0), "monthly", 31))
        assertEquals(LocalDateTime.of(2026, 11, 30, 9, 0), Reminders.next(LocalDateTime.of(2026, 11, 27, 9, 0), "weekdays"))
    }

    @Test fun aiActionLines() {
        val (shown, acts) = AiActions.split("Done – reminder set for Monday 9 am.\n[[do: {\"do\":\"reminder\",\"what\":\"read the gas meter\",\"at\":\"2026-11-30T09:00\",\"repeat\":\"monthly\"}]]")
        assertEquals("Done – reminder set for Monday 9 am.", shown)
        val r = AiActions.reminder(acts.single())!!
        assertEquals(LocalDate.of(2026, 11, 30), r.date); assertEquals(9, r.hour); assertEquals("monthly", r.repeat); assertEquals("Read the gas meter", r.what)
        assertEquals(0, AiActions.split("No actions here.").second.size)
    }
}
