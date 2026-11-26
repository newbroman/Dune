package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RemindersTest {
    private val today = LocalDate.of(2026, 11, 26)            // a Thursday
    private fun p(s: String) = Reminders.parse(s.lowercase(), today) as? Reminders.Set

    @Test fun gasMeter() {
        listOf(
            "set a reminder on Mon 30th November to read the gas meter",
            "remind me on monday 30th november to read the gas meter",
            "remind me to read the gas meter on Monday 30 November",
            "set a reminder for 30th November to read the gas meter",
            "reminder Monday the 30th of November read the gas meter",
            "remind me on November 30th to read the gas meter",
            "remind me on 30/11 to read the gas meter",
            "remind me on the thirtieth to read the gas meter",
            "remind me to read the gas meter on monday",
        ).forEach { s ->
            val r = p(s) ?: error("not a reminder: $s")
            assertEquals(s, LocalDate.of(2026, 11, 30), r.date)
            assertEquals(s, "Read the gas meter", r.what)
            assertEquals(s, false, r.dayMismatch)
            assertEquals(LocalDateTime.of(2026, 11, 30, 9, 0), Reminders.moment(r, LocalDateTime.of(2026, 11, 26, 19, 5)))
        }
    }

    @Test fun times() {
        p("remind me tomorrow at 6 to put the bins out")!!.let { assertEquals(today.plusDays(1), it.date); assertEquals(18, it.hour); assertEquals("Put the bins out", it.what) }
        p("remind me at 7:30 am tomorrow to call the plumber")!!.let { assertEquals(7, it.hour); assertEquals(30, it.minute) }
        p("remind me in 2 hours to ring mum")!!.let { assertEquals(120, it.inMinutes); assertEquals("Ring mum", it.what) }
        p("remind me tomorrow morning to take the car in")!!.let { assertEquals(9, it.hour) }
        p("remind me on friday evening about the quiz")!!.let { assertEquals(LocalDate.of(2026, 11, 27), it.date); assertEquals(18, it.hour); assertEquals("The quiz", it.what) }
        p("remind me on 3rd january to renew the insurance")!!.let { assertEquals(LocalDate.of(2027, 1, 3), it.date) }
        p("remind me on tuesday 30th november to read the meter")!!.let { assertTrue(it.dayMismatch) }
    }

    @Test fun notReminders() {
        assertEquals(null, Reminders.parse("remind me to buy milk", today))           // a task
        assertTrue(Assistant.interpret("remind me to buy milk") is Cmd.AddTask)
        assertTrue(Reminders.parse("what reminders do i have", today) is Reminders.ListAll)
        assertEquals(Reminders.Cancel("gas meter"), Reminders.parse("cancel the gas meter reminder", today))
        assertTrue(Assistant.interpret("Set a reminder on Mon 30th November to read the gas meter") is Reminders.Set)
        assertEquals("Ring Anna about BT", (Assistant.interpret("remind me tomorrow to ring Anna about BT") as Reminders.Set).what)
    }
}
