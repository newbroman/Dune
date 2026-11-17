package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationTest {
    private fun c(s: String) = Assistant.interpret(s)

    @Test fun englishToPolish() {
        assertEquals(Cmd.Translate("where is the station", "pl"), c("How do you say where is the station in Polish?"))
        assertEquals(Cmd.Translate("thank you very much", "pl"), c("translate thank you very much into Polish"))
        assertEquals(Cmd.Translate("good morning", "pl"), c("What's good morning in Polish"))
        assertEquals(Cmd.Translate("beer", "pl"), c("what's the Polish for beer"))
        assertEquals(Cmd.Translate("I'm lost", "pl"), c("I'm lost in Polish"))
        assertEquals(Cmd.Translate("the bill please", "pl"), c("say the bill please in Polish"))
        assertEquals(Cmd.Translate("", "pl"), c("translate into Polish"))
    }

    @Test fun polishToEnglish() {
        assertEquals(Cmd.Translate("dziękuję", "en"), c("what does dziękuję mean"))
        assertEquals(Cmd.Translate("Dzień dobry", "en"), c("translate Dzień dobry into English"))
        assertEquals(Cmd.Translate("", "en"), c("translate from Polish"))
        assertEquals(Cmd.Translate("", "en"), c("Polish to English"))
        assertEquals("en", Translation.guessTo("Gdzie jest dworzec"))
        assertEquals("en", Translation.guessTo("jak się masz"))
        assertEquals("pl", Translation.guessTo("where is the station"))
    }

    @Test fun keepsTheWordsAsWritten() {
        // a trailing "please" is dropped, and the text keeps its capitals
        assertEquals(Cmd.Translate("Good Morning Anna", "pl"), c("Can you translate Good Morning Anna into Polish please"))
        assertEquals(Cmd.AddTask("Ring the Council"), c("add task ring the Council please"))
        assertEquals(Cmd.TimeInPolish, c("what's the time in Polish"))
    }
}
