// Нютаг. Закрепы и папки: разбор, предел пяти закрепов, раскладка списка чатов.

package chat.nyutag.core

import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.rooms.ChatFolder
import chat.nyutag.core.rooms.ChatLayout
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatLayoutTest {
    private fun line(id: String): ChatLine = ChatLine(
        id, id, null, RoomRecord.Membership.JOIN, false, false, false,
        null as PushPreview.Kind?, null, false, ChatLine.Delivery.NONE, 0, 0, 0,
    )

    @Test fun parsingCleansInput() {
        val json = JsonValue.parse(
            """{"pins":["!a","!b","!a","!c","!d","!e","!f","!g"],
                "folders":[{"id":"w","name":" Работа ","rooms":["!a","!a","!x"]},{"id":"","name":"пусто"},{"id":"w","name":"повтор"},{"id":"f","name":"Семья"}],
                "chat.extra":1}""",
        )
        val layout = ChatLayout.from(json)
        assertEquals(listOf("!a", "!b", "!c", "!d", "!e"), layout.pins)
        assertEquals(listOf(ChatFolder("w", "Работа", listOf("!a", "!x")), ChatFolder("f", "Семья")), layout.folders)
        assertNull(layout.json["folders"]?.get(1)?.get("rooms"))
        assertEquals(layout, ChatLayout.from(layout.json))
        assertEquals(JsonValue.Obj(emptyMap()), ChatLayout().json)
        assertEquals(ChatLayout(), ChatLayout.from(JsonValue.Str("мусор")))
    }

    @Test fun pinsAndFolders() {
        var layout = ChatLayout()
        for (i in 1..6) layout = layout.pinning("!$i", true)
        assertEquals(listOf("!5", "!4", "!3", "!2", "!1"), layout.pins)
        assertFalse(layout.canPinMore)
        layout = layout.pinning("!3", false).pinning("!6", true)
        assertEquals(listOf("!6", "!5", "!4", "!2", "!1"), layout.pins)

        assertTrue(ChatLayout().addingFolder("w", "  ", "!1").folders.isEmpty())
        var folders = ChatLayout().addingFolder("w", "Работа", "!1").placing("!2", "w", true).placing("!1", "w", true)
        assertEquals(listOf("!1", "!2"), folders.folders.first().rooms)
        folders = folders.placing("!1", "w", false).renamingFolder("w", "Дело")
        assertEquals(listOf(ChatFolder("w", "Дело", listOf("!2"))), folders.folders)
        assertTrue(folders.removingFolder("w").folders.isEmpty())
    }

    @Test fun arrangeAllAndFolder() {
        val lines = listOf("!a", "!b", "!c", "!d").map { line(it) }
        val layout = ChatLayout(pins = listOf("!c", "!gone", "!a"), folders = listOf(ChatFolder("w", "Работа", listOf("!d", "!a"))))
        assertEquals(listOf("!c", "!a", "!b", "!d"), layout.arrange(lines).map { it.id })
        assertEquals(listOf("!a", "!d"), layout.arrange(lines, "w").map { it.id })
        assertTrue(layout.arrange(lines, "нет").isEmpty())
    }
}
