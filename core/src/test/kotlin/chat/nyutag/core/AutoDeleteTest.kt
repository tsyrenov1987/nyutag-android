// Нютаг. Автоудаление: формат правила, что скрывать и что удалять на сервере.

package chat.nyutag.core

import chat.nyutag.core.rooms.AutoDelete
import chat.nyutag.core.rooms.FeedItem
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AutoDeleteTest {
    private fun item(id: String, ts: Long, mine: Boolean = true, body: FeedItem.Body = FeedItem.Body.Text(id)): FeedItem =
        FeedItem(id, if (mine) "@я" else "@он", "", mine, ts, body, false, null, emptyList(), null, false)

    @Test fun format() {
        assertEquals(AutoDelete(AutoDelete.DAY, 1_700_000_000_000L), AutoDelete.from(JsonValue.parse("""{"ttl":86400,"since":1700000000000.0}""")))
        val rule = AutoDelete(AutoDelete.WEEK, 5L)
        assertEquals(rule, AutoDelete.from(rule.json))
        assertEquals(AutoDelete(), AutoDelete.from(null))
        assertFalse(AutoDelete.from(JsonValue.Str("мусор")).isOn)
        assertEquals(AutoDelete.DAY, AutoDelete(3600).choice)
        assertEquals(AutoDelete.MONTH, AutoDelete(40 * AutoDelete.DAY).choice)
        assertEquals(AutoDelete(), AutoDelete.starting(0, 123L))
    }

    @Test fun hidesExpiredAndDeletesOwn() {
        val since = 1_000_000L
        val rule = AutoDelete(60, since)
        val now = since + 120_000
        val items = listOf(
            item("старое", since - 5000),
            item("в ту же секунду", since - 500),
            item("чужое", since + 10_000, mine = false),
            item("свежее", since + 100_000),
            item("плашка", since + 1000, body = FeedItem.Body.Notice(FeedItem.Notice.Joined("он"))),
        )
        val visible = rule.visible(items, now)
        assertEquals(listOf("старое", "свежее", "плашка"), visible.map { it.id })
        assertEquals(listOf("в ту же секунду"), rule.ownToDelete(items, now))
        assertEquals(since + 160_000, rule.nextExpiry(visible))
        assertEquals(items, AutoDelete().visible(items, now))
    }
}
