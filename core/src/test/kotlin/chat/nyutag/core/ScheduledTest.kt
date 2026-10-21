// Нютаг. Отложенные сообщения: порядок, что пора отправить, хранение.

package chat.nyutag.core

import chat.nyutag.core.rooms.ScheduledMessage
import chat.nyutag.core.rooms.ScheduledQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduledTest {
    private val start = 1_800_000_000_000L

    @Test fun queueOrderAndDue() {
        val later = ScheduledMessage("b", "@a:x", "!r", "потом", start + 3_600_000)
        val soon = ScheduledMessage("a", "@a:x", "!r", "скоро", start + 60_000)
        val other = ScheduledMessage("c", "@b:x", "!o", "чужое", start)
        val queue = ScheduledQueue(listOf(later, soon, other, soon))
        assertEquals(listOf("c", "a", "b"), queue.messages.map { it.id })
        assertEquals(listOf("a", "b"), queue.inRoom("!r", "@a:x").map { it.id })
        assertTrue(queue.due("@a:x", start).isEmpty())
        assertEquals(listOf("a"), queue.due("@a:x", start + 61_000).map { it.id })
        assertEquals(soon.sendAt, queue.nextDue("@a:x"))
        val now = queue.sendingNow("b", start)
        assertEquals(listOf("b"), now.due("@a:x", start).map { it.id })
        assertEquals(listOf("c", "a"), now.removing("b").messages.map { it.id })
        assertEquals(listOf("c"), queue.removingAccount("@a:x").messages.map { it.id })
        assertEquals("scheduled-a", soon.transactionID)
    }

    @Test fun storage() {
        val queue = ScheduledQueue(listOf(ScheduledMessage(account = "@a:x", roomID = "!r", body = "привет\n«мир»", sendAt = start)))
        assertEquals(queue, ScheduledQueue.parse(queue.text))
        assertEquals(listOf("y"), ScheduledQueue.parse("""[{"id":"x"},{"id":"y","account":"@a:x","room":"!r","body":"ок","at":5}]""").messages.map { it.id })
        assertTrue(ScheduledQueue.parse("мусор").messages.isEmpty())
        assertTrue(ScheduledQueue.parse(null).messages.isEmpty())
    }
}
