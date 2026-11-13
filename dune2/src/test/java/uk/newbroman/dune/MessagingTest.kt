package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagingTest {
    private val people = listOf(
        Messaging.Contact("Anna Kowalska", "07700 900001", 1, false),
        Messaging.Contact("Anna Smith", "07700 900002", 2, true),
        Messaging.Contact("Tom", "07700 900003", 3, false),
        Messaging.Contact("Mum", "07700 900004", 4, false),
    )

    @Test fun splitsNameFromMessage() {
        Messaging.match("Anna Kowalska I'm running late", people)!!.let { assertEquals(1L, it.contact.id); assertEquals("I'm running late", it.text) }
        Messaging.match("anna see you at six", people)!!.let { assertEquals(2L, it.contact.id); assertEquals("see you at six", it.text) }   // starred wins
        Messaging.match("Tom saying on my way", people)!!.let { assertEquals(3L, it.contact.id); assertEquals("on my way", it.text) }
        Messaging.match("mum", people)!!.let { assertEquals(4L, it.contact.id); assertEquals("", it.text) }
        assertNull(Messaging.match("Bob hello", people))
    }

    @Test fun parsesSendCommands() {
        assertEquals(Cmd.Send("Anna saying I'm late", null), Assistant.interpret("Send a message to Anna saying I'm late"))
        assertEquals(Cmd.Send("Tom running late", "sms"), Assistant.interpret("text Tom running late"))
        assertEquals(Cmd.Send("Mum see you soon", "whatsapp"), Assistant.interpret("WhatsApp Mum see you soon"))
        assertEquals(Cmd.Send("Jo I'm here", "whatsapp"), Assistant.interpret("send a WhatsApp to Jo I'm here"))
        assertEquals(Cmd.Send("Jo dinner's ready", null), Assistant.interpret("tell Jo dinner's ready"))
        assertEquals(Cmd.ReadMessages, Assistant.interpret("read my messages"))
        assertEquals(Cmd.Reply("ok"), Assistant.interpret("reply ok"))
    }
}
