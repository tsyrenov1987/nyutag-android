// Живой тест против ядра nyutag.chat через дверь, как в приложении. Запускается только при заданных переменных
// окружения (`tools/test-env.sh`): NYUTAG_TEST_DOOR, NYUTAG_TEST_DOOR_KEY, NYUTAG_TEST_USER, NYUTAG_TEST_PASSWORD.
// Учётка — отладочная test18: вход, sync, ключи устройства, хранилище секретов под паролем (заведено iOS-клиентом),
// расшифровка истории тестового чата, текст пуша, сервис Нютага, выход.

package chat.nyutag.core

import chat.nyutag.core.auth.login
import chat.nyutag.core.auth.logout
import chat.nyutag.core.auth.whoAmI
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.PasswordUnlockOutcome
import chat.nyutag.core.crypto.devices
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.unlockWithPassword
import chat.nyutag.core.net.Door
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.nyutag.NyutagService
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.push.PushReader
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.core.sync.SyncEngine
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class LiveServerTest {
    private val env = System.getenv()
    private val door = env["NYUTAG_TEST_DOOR"]?.let { Door.of(URI(it), "test") }
    private val doorKey = env["NYUTAG_TEST_DOOR_KEY"]
    private val user = env["NYUTAG_TEST_USER"]
    private val password = env["NYUTAG_TEST_PASSWORD"]

    private fun client(): MatrixClient =
        MatrixClient(URI("https://core.nyutag.chat"), userAgent = "Nyutag-Android-test", doorKey = doorKey, door = door, doorWaitMillis = 0)

    @Test fun throughDoorAsTestAccount() = runBlocking {
        assumeTrue(door != null && doorKey != null && user != null && password != null, "нет NYUTAG_TEST_* (tools/test-env.sh)")
        // ⛔ test17 — аккаунт владельца, им не входим.
        assumeTrue(user != "test17" && user?.startsWith("@test17:") != true, "test17 не для тестов")
        val client = client()
        val versions = client.raw(MatrixClient.Method.GET, "/_matrix/client/versions", authenticated = false)
        assertTrue(versions["versions"]?.array?.isNotEmpty() == true)

        val session = client.login(requireNotNull(user), requireNotNull(password), deviceDisplayName = "Нютаг Android тест (Мак)")
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            assertEquals("nyutag.chat", session.userID.substringAfter(':'))
            assertEquals(session.deviceID, client.whoAmI().deviceID)

            val store = Store(JdbcSql.inMemory())
            val engine = SyncEngine(client, store, session, scope)
            val first = engine.syncOnce()
            assertTrue(first.isInitial)
            val rooms = store.rooms()
            println("live sync: ${rooms.size} комнат, маркер ${store.syncToken != null}")
            assertTrue(rooms.isNotEmpty(), "у тестовой учётки должны быть комнаты")
            assertFalse(engine.syncOnce().isInitial)

            // Ключи нового устройства видны на сервере.
            val machine = CryptoMachine.create(client, store, session)
            machine.publishKeys(null)
            assertTrue(machine.devices(listOf(session.userID)).any { it.deviceID == session.deviceID })

            // Хранилище секретов под паролем: исход любой, но без ошибки и без смены личности. У test18 его на сервере
            // нет (21.10: default_key M_NOT_FOUND, кросс-подпись есть) — ответ NeedsOtherDevice, аккаунт не трогаем.
            val outcome = machine.unlockWithPassword(requireNotNull(password))
            println("live unlock: $outcome")
            if (outcome is PasswordUnlockOutcome.Opened) {
                val opened = rooms.sumOf { room ->
                    store.events(room.id, limit = 50).filter { it.type == EventType.ENCRYPTED }
                        .count { runCatching { machine.decrypt(it, room.id) }.isSuccess }
                }
                println("live history: открыто $opened")
            }

            // Своё зашифрованное сообщение в тестовый чат: ушло, пришло с сервера (в хранилище его ещё нет)
            // и расшифровалось в текст пуша.
            val testRoom = rooms.firstOrNull { it.name == "Проверка групповых звонков" }
            assertNotNull(testRoom, "нет тестового чата «Проверка групповых звонков»")
            val body = "Android-движок: проверка шифрования ${System.currentTimeMillis()}"
            val eventID = machine.sendEncrypted(requireNotNull(testRoom).id, EventType.MESSAGE, text(body))
            val preview = PushReader(client, store, session).preview(testRoom.id, eventID)
            assertEquals(PushPreview.Kind.Text(body), preview.kind)

            // Сервис Нютага за той же дверью.
            val service = NyutagService(client)
            println("live nyutag: config=${service.configuration()}, объявлений ${service.announcements().size}")
        } finally {
            scope.cancel()
            client.logout()
        }
        assertTrue(runCatching { client.whoAmI() }.exceptionOrNull() is MatrixError)
        assertNotNull(session.accessToken)
    }
}
