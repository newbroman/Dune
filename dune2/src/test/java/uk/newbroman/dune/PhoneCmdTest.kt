package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneCmdTest {
    private fun c(s: String) = Assistant.interpret(s)

    @Test fun torchVolumeRinger() {
        assertEquals(PhoneCmd.Torch(true), c("Turn on the torch"))
        assertEquals(PhoneCmd.Torch(false), c("turn the flashlight off"))
        assertEquals(PhoneCmd.Torch(null), c("torch"))
        assertEquals(PhoneCmd.Torch(true), c("flashlight on please"))
        assertEquals(PhoneCmd.Volume(step = 1), c("Volume up"))
        assertEquals(PhoneCmd.Volume(step = -1), c("turn it down"))
        assertEquals(PhoneCmd.Volume(mute = true), c("mute"))
        assertEquals(PhoneCmd.Volume(to = 50), c("set volume to 50%"))
        assertEquals(PhoneCmd.Volume(to = 70), c("volume 7"))
        assertEquals(PhoneCmd.Volume(to = 100), c("max volume"))
        assertEquals(PhoneCmd.Ringer("silent"), c("put the phone on silent"))
        assertEquals(PhoneCmd.Ringer("vibrate"), c("vibrate mode"))
        assertEquals(PhoneCmd.Ringer("normal"), c("turn off silent mode"))
        assertEquals(PhoneCmd.Dnd(true), c("turn on do not disturb"))
        assertEquals(PhoneCmd.Dnd(false), c("do not disturb off"))
        assertEquals(PhoneCmd.Brightness(step = 1), c("make the screen brighter"))
        assertEquals(PhoneCmd.Brightness(to = 30), c("brightness to 30 percent"))
    }

    @Test fun radios() {
        assertEquals(PhoneCmd.Radio("Wi-Fi", false), c("turn off wifi"))
        assertEquals(PhoneCmd.Radio("Bluetooth", true), c("Turn Bluetooth on"))
        assertEquals(PhoneCmd.Radio("Flight mode", true), c("turn on airplane mode"))
        assertEquals(PhoneCmd.Radio("Mobile data", null), c("open mobile data settings"))
    }

    @Test fun questions() {
        assertEquals(PhoneCmd.Battery, c("How much battery have I got?"))
        assertEquals(PhoneCmd.Battery, c("is my phone charging"))
        assertEquals(PhoneCmd.DateTime("date"), c("What's the date today?"))
        assertEquals(PhoneCmd.DateTime("date"), c("what day is it"))
        assertEquals(PhoneCmd.DateTime("time"), c("what time is it"))
        assertEquals(Cmd.TimeInPolish, c("what's the time"))                       // kept: the time in Polish
        assertEquals(PhoneCmd.Weather(0, null, false), c("What's the weather?"))
        assertEquals(PhoneCmd.Weather(1, null, false), c("what's the weather like tomorrow"))
        assertEquals(PhoneCmd.Weather(0, "bangor", false), c("weather in Bangor"))
        assertEquals(PhoneCmd.Weather(1, null, true), c("will it rain tomorrow"))
        assertEquals(PhoneCmd.Weather(0, null, true), c("do I need an umbrella"))
        assertEquals(PhoneCmd.Agenda(0), c("What's on today?"))
        assertEquals(PhoneCmd.Agenda(1), c("what have I got tomorrow"))
        assertEquals(PhoneCmd.Agenda(null, next = true), c("when's my next appointment"))
        assertEquals(PhoneCmd.WhereAmI, c("where am I"))
    }

    @Test fun cameraAndApps() {
        assertEquals(PhoneCmd.Camera(false, false), c("take a photo"))
        assertEquals(PhoneCmd.Camera(false, true), c("take a selfie"))
        assertEquals(PhoneCmd.Camera(true, false), c("take a video"))
        assertEquals(PhoneCmd.Camera(true, false), c("record a video"))
        assertEquals(PhoneCmd.OpenKind("calculator"), c("open calculator"))
        assertEquals(PhoneCmd.OpenKind("calculator"), c("calculator"))
        assertEquals(PhoneCmd.OpenKind("gallery"), c("open the gallery"))
        assertEquals(PhoneCmd.ScreenAct("lock"), c("lock the phone"))
        assertEquals(PhoneCmd.ScreenAct("shot"), c("take a screenshot"))
        assertEquals(PhoneCmd.Chance("coin"), c("flip a coin"))
        assertEquals(Cmd.Open("whatsapp"), c("open whatsapp"))                      // other apps unchanged
    }

    @Test fun sums() {
        assertEquals(84.0, (c("what's 12 times 7") as PhoneCmd.Sum).value, 1e-9)
        assertEquals(12.0, (c("what is 15 percent of 80") as PhoneCmd.Sum).value, 1e-9)
        assertEquals(9.0, (c("square root of 81") as PhoneCmd.Sum).value, 1e-9)
        assertEquals(2.5, (c("what's 10 divided by 4") as PhoneCmd.Sum).value, 1e-9)
        assertEquals(17.0, (c("what's 5 plus 3 times 4") as PhoneCmd.Sum).value, 1e-9)
        assertEquals(Cmd.Translate("hello", "pl"), c("what's hello in polish"))
        assertNull(Calc.eval("the weather"))
    }

    @Test fun reminders() {
        assertEquals(Cmd.Alarm(17, 0, label = "call mum"), c("remind me to call mum at 5 pm"))
        assertEquals(Cmd.AddTask("Buy milk"), c("remind me to buy milk"))
    }
}
