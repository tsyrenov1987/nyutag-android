// Нютаг. Список входов в аккаунт: порядок, переименование, выход на другом устройстве с паролем.

package chat.nyutag.core

import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.rooms.AccountDevice
import chat.nyutag.core.rooms.deleteDevice
import chat.nyutag.core.rooms.devices
import chat.nyutag.core.rooms.renameDevice
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevicesTest {
    private val me = "@test:nyutag.chat"

    @Test fun order() {
        val list = listOf(
            AccountDevice("A", lastSeenTS = 100), AccountDevice("B", lastSeenTS = 300),
            AccountDevice("ME", lastSeenTS = 50), AccountDevice("C"),
        )
        assertEquals(listOf("ME", "B", "A", "C"), AccountDevice.sorted(list, "ME").map { it.deviceID })
    }

    @Test fun decode() {
        val json = JsonValue.parse("""{"device_id":"QWE","display_name":"Нютаг iPhone","last_seen_ts":1761000000000,"last_seen_ip":"10.0.0.1"}""")
        assertEquals(AccountDevice("QWE", "Нютаг iPhone", 1_761_000_000_000L, "10.0.0.1"), AccountDevice.from(json))
    }

    @Test fun renameAndDelete() = runBlocking {
        val server = FakeHomeserver()
        server.setPassword("секрет", me)
        val mine = server.session(me, "D1")
        val other = server.session(me, "D2")
        val client = server.client(mine)

        assertEquals(listOf("D1", "D2"), client.devices().map { it.deviceID })
        client.renameDevice("D2", "Старый iPad")
        assertEquals("Старый iPad", client.devices().first { it.deviceID == "D2" }.displayName)

        assertThrows(MatrixError::class.java) { runBlocking { client.deleteDevice("D2", me, "не тот") } }
        assertTrue(server.isTokenValid(other.accessToken))
        client.deleteDevice("D2", me, "секрет")
        assertFalse(server.isTokenValid(other.accessToken))
        assertTrue(server.isTokenValid(mine.accessToken))
        assertEquals(listOf("D1"), client.devices().map { it.deviceID })
    }
}
