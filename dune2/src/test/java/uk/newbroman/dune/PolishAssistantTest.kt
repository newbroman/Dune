package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PolishAssistantTest {
    @Test fun polishTime() {
        assertEquals("osiemnasta czterdzieści jeden", PolishTime.timeWords(18, 41))
        assertEquals("siódma zero pięć", PolishTime.timeWords(7, 5))
        assertEquals("dwudziesta pierwsza trzydzieści", PolishTime.timeWords(21, 30))
        assertEquals("dwudziesta trzecia pięćdziesiąt dziewięć", PolishTime.timeWords(23, 59))
        assertEquals("pierwsza piętnaście", PolishTime.timeWords(1, 15))
        assertEquals("zero dwadzieścia", PolishTime.timeWords(0, 20))
        assertEquals("północ", PolishTime.timeWords(0, 0))
        assertEquals("ósma", PolishTime.timeWords(8, 0))
        assertEquals("dziesiąta dziesięć", PolishTime.timeWords(10, 10))
    }

    @Test fun polishDate() {
        val d = LocalDate.of(2026, 10, 27)
        assertEquals("wtorek, 27 października", PolishTime.dateLine(d))
        assertEquals("wtorek, dwudziesty siódmy października", PolishTime.dateWords(d))
        assertEquals("trzydziesty pierwszy", PolishTime.dayOrdinal(31))
        assertEquals("pierwszy", PolishTime.dayOrdinal(1))
        assertEquals("niedziela, 1 marca", PolishTime.dateLine(LocalDate.of(2026, 3, 1)))
    }

    @Test fun commands() {
        fun c(s: String) = Assistant.interpret(s)
        assertEquals(Cmd.Navigate(null), c("navigate home"))
        assertEquals(Cmd.Navigate(null), c("Take me home"))
        assertEquals(Cmd.Navigate("Screwfix Bangor"), c("Navigate to Screwfix Bangor"))
        assertEquals(Cmd.Navigate("Newborough Warren"), c("take me to Newborough Warren"))
        assertEquals(Cmd.Alarm(7, 30), c("set an alarm for 7:30"))
        assertEquals(Cmd.Alarm(18, 0), c("set alarm for 6 pm"))
        assertEquals(Cmd.Alarm(6, 15), c("wake me up at 6 15"))
        assertEquals(Cmd.Timer(300), c("set a timer for 5 minutes"))
        assertEquals(Cmd.Timer(600), c("10 minute timer"))
        assertEquals(Cmd.Timer(1800), c("set a timer for half an hour"))
        assertEquals(Cmd.AddTask("Buy screws"), c("add task buy screws"))
        assertEquals(Cmd.AddTask("Ring the plumber"), c("remind me to ring the plumber"))
        assertEquals(Cmd.AddTask("Milk"), c("add milk to my tasks"))
        assertEquals(Cmd.Translate("where is the station"), c("how do you say where is the station in Polish"))
        assertEquals(Cmd.Translate("good morning"), c("translate good morning into Polish"))
        assertEquals(Cmd.Spotify("Pink Floyd"), c("play Pink Floyd on Spotify"))
        assertEquals(Cmd.Spotify(null), c("Spotify"))
        assertEquals(Cmd.Open("bbc sounds"), c("play BBC Sounds"))
        assertEquals(Cmd.Open("spheres"), c("open spheres"))
        assertEquals(Cmd.Media("pause"), c("pause"))
        assertEquals(Cmd.Media("next"), c("skip"))
        assertEquals(Cmd.ReadMessages, c("read my messages"))
        assertEquals(Cmd.Reply("On my way, ten minutes"), c("reply On my way, ten minutes"))
        assertEquals(Cmd.Reply("yes see you there"), c("reply saying yes see you there"))
        assertEquals(Cmd.TimeInPolish, c("what's the time"))
        assertEquals(Cmd.PhraseOfDay, c("phrase of the day"))
        assertEquals(Cmd.Search("tide times Llanddwyn"), c("search for tide times Llanddwyn"))
        assertEquals(Cmd.DriveMode(true), c("drive mode"))
        assertEquals(Cmd.DriveMode(false), c("exit drive mode"))
        assertEquals(Cmd.Dial("07700900123"), c("call 07700 900123"))
        assertEquals(Cmd.Unknown("What's the capital of Peru"), c("What's the capital of Peru?"))
    }
}
