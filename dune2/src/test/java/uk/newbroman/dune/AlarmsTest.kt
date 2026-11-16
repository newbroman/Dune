package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.Calendar.*

class AlarmsTest {
    private fun c(s: String) = Assistant.interpret(s)

    @Test fun plain() {
        assertEquals(Cmd.Alarm(7, 0), c("Set an alarm for 7"))
        assertEquals(Cmd.Alarm(6, 30), c("set an alarm for 6:30 am"))
        assertEquals(Cmd.Alarm(18, 45), c("set alarm at 18.45"))
        assertEquals(Cmd.Alarm(7, 0), c("wake me up at 7"))
        assertEquals(Cmd.Alarm(7, 0), c("Can you set an alarm for 7 please?"))
        assertEquals(Cmd.Alarm(7, 0), c("could you wake me up at 7"))
        assertEquals(Cmd.Alarm(7, 0), c("I need an alarm at 7am"))
        assertEquals(Cmd.Alarm(6, 30), c("set an alarm for half past 6"))
        assertEquals(Cmd.Alarm(12, 0), c("set an alarm for noon"))
    }

    @Test fun relative() {
        assertEquals(Cmd.Alarm(0, 0, inMinutes = 40), c("set an alarm in 40 minutes"))
        assertEquals(Cmd.Alarm(0, 0, inMinutes = 120), c("wake me in 2 hours"))
        assertEquals(Cmd.Alarm(0, 0, inMinutes = 30), c("alarm in half an hour"))
        assertEquals(Cmd.Alarm(0, 0, inMinutes = 90), c("set an alarm in an hour and a half"))
    }

    @Test fun days() {
        assertEquals(Cmd.Alarm(7, 0, on = 10 + 1), c("set an alarm for Monday at 7"))
        assertEquals(Cmd.Alarm(6, 0, on = 1), c("wake me up tomorrow at 6"))
        assertEquals(Cmd.Alarm(21, 0, on = 0), c("set an alarm for 9 tonight"))
        assertEquals(Cmd.Alarm(7, 0, days = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)), c("set an alarm for 7 every weekday"))
        assertEquals(Cmd.Alarm(8, 0, days = listOf(SATURDAY, SUNDAY)), c("set an alarm at 8 at weekends"))
        assertEquals(Cmd.Alarm(6, 15, days = listOf(MONDAY, FRIDAY)), c("set an alarm every Monday and Friday at 6:15"))
        assertEquals(Cmd.Alarm(7, 0, days = (1..7).toList()), c("set an alarm for 7 every day"))
    }

    @Test fun labels() {
        assertEquals(Cmd.Alarm(6, 0, label = "bins"), c("set an alarm at 6 called bins"))
        assertEquals(Cmd.Alarm(21, 0, label = "take pills"), c("set an alarm to take pills at 9 pm"))
        assertEquals(Cmd.Alarm(7, 0, label = "put the bins out", on = 10 + 4), c("set an alarm for Thursday at 7 to put the bins out"))
        assertEquals(Cmd.Alarm(7, 0, days = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), label = "gym"), c("alarm every weekday at 7 called gym"))
    }

    @Test fun cancelAndList() {
        assertEquals(Cmd.AlarmCancel(all = true), c("cancel all alarms"))
        assertEquals(Cmd.AlarmCancel(all = true), c("delete all my alarms"))
        assertEquals(Cmd.AlarmCancel(hour = 7, minute = 0), c("cancel my 7 am alarm"))
        assertEquals(Cmd.AlarmCancel(hour = 7, minute = 0), c("cancel the alarm at 7"))
        assertEquals(Cmd.AlarmCancel(label = "bins"), c("cancel the bins alarm"))
        assertEquals(Cmd.AlarmCancel(), c("turn off my alarm"))
        assertEquals(Cmd.AlarmList, c("what alarms do I have"))
        assertEquals(Cmd.AlarmList, c("when's my next alarm"))
        assertEquals(Cmd.AlarmList, c("list my alarms"))
        assertEquals(Cmd.Media("pause"), c("stop"))
        assertEquals(Cmd.Timer(300), c("set a timer for 5 minutes"))
    }

    @Test fun whenItRings() {
        val now = LocalDateTime.of(2026, 11, 16, 1, 30)             // a Monday, 01:30
        assertEquals(LocalDateTime.of(2026, 11, 16, 2, 10), Alarms.moment(Cmd.Alarm(0, 0, inMinutes = 40), now))
        assertEquals(LocalDateTime.of(2026, 11, 17, 6, 0), Alarms.moment(Cmd.Alarm(6, 0, on = 1), now))
        assertEquals(LocalDateTime.of(2026, 11, 20, 7, 0), Alarms.moment(Cmd.Alarm(7, 0, on = 10 + 5), now))   // Friday
        assertEquals(LocalDateTime.of(2026, 11, 16, 7, 0), Alarms.moment(Cmd.Alarm(7, 0, on = 10 + 1), now))   // Monday, later today
        assertEquals(LocalDateTime.of(2026, 11, 23, 1, 0), Alarms.moment(Cmd.Alarm(1, 0, on = 10 + 1), now))   // Monday, already past
        assertEquals(null, Alarms.moment(Cmd.Alarm(7, 0), now))
    }
}
