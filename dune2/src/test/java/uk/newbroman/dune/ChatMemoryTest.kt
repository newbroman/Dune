package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryTest {
    @Test fun remembersInOrderAndForgets() {
        Brain.Chat.clear()
        Brain.Chat.add("Who wrote Pan Tadeusz?", "Adam Mickiewicz.", now = 1_000)
        Brain.Chat.add("When was he born?", "1798.", now = 2_000)
        assertEquals(listOf("Who wrote Pan Tadeusz?", "When was he born?"), Brain.Chat.history(now = 3_000).map { it.first })
        val m = Brain.chatMessages("sys", Brain.Chat.history(now = 3_000), "Where?")
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
        assertEquals("Where?", m.getJSONObject(5).getString("content"))
        // idle too long: starts afresh
        assertTrue(Brain.Chat.history(now = 2_000 + Brain.Chat.IDLE_MS + 1).isEmpty())
        assertEquals(0, Brain.Chat.size)
    }

    @Test fun keepsOnlyTheLastFew() {
        Brain.Chat.clear()
        repeat(12) { Brain.Chat.add("q$it", "a$it", now = 1_000L + it) }
        val h = Brain.Chat.history(now = 2_000)
        assertEquals(Brain.Chat.MAX, h.size); assertEquals("q4", h.first().first)
        Brain.Chat.clear()
    }

    @Test fun newConversationCommand() {
        listOf("new conversation", "New chat", "start again", "forget that", "clear the conversation", "change the subject").forEach {
            assertTrue(it, Assistant.interpret(it) is Cmd.NewChat)
        }
        assertTrue(Assistant.interpret("forget about the war") !is Cmd.NewChat)
    }
}
