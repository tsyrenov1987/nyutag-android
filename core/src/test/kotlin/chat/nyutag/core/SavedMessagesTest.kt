// Нютаг. «Избранное»: номер комнаты в данных учётки и переименование только своих имён.

package chat.nyutag.core

import chat.nyutag.core.rooms.SavedMessages
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SavedMessagesTest {
    @Test fun readsSharedFormat() {
        assertEquals("!abc:nyutag.chat", SavedMessages.roomID(JsonValue.parse("""{"room_id":"!abc:nyutag.chat","extra":1}""")))
        assertEquals("!x:y", SavedMessages.roomID(SavedMessages.json("!x:y")))
        assertNull(SavedMessages.roomID(null))
        assertNull(SavedMessages.roomID(JsonValue.parse("""{"room_id":""}""")))
        assertNull(SavedMessages.roomID(JsonValue.parse("""{"room_id":5}""")))
    }

    @Test fun renamesOnlyDefaultNames() {
        assertTrue(SavedMessages.needsRename("Saved messages", "Избранное"))
        assertTrue(SavedMessages.needsRename("Хадагалһан", "Избранное"))
        assertFalse(SavedMessages.needsRename("Избранное", "Избранное"))
        assertFalse(SavedMessages.needsRename("Мои заметки", "Избранное"))
    }
}
