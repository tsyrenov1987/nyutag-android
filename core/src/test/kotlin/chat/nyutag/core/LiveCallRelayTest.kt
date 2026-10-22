// Живой релей звонков: тот же путь, что у приложения (дверь → nginx ядра с проверкой токена → релей на rtc).
// Нужен стенд `tools/test-env.sh` с дверью и учёткой (NYUTAG_TEST_*). Свой WebSocket проверяется на настоящем TLS.

package chat.nyutag.core

import chat.nyutag.core.auth.login
import chat.nyutag.core.auth.logout
import chat.nyutag.core.calls.CallID
import chat.nyutag.core.calls.RelayInbound
import chat.nyutag.core.calls.RelayLink
import chat.nyutag.core.net.Door
import chat.nyutag.core.net.MatrixClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI

class LiveCallRelayTest {
    private val env = System.getenv()
    private val door = env["NYUTAG_TEST_DOOR"]?.let { Door.of(URI(it), "test") }
    private val doorKey = env["NYUTAG_TEST_DOOR_KEY"]
    private val user = env["NYUTAG_TEST_USER"]
    private val password = env["NYUTAG_TEST_PASSWORD"]

    private fun client(token: String? = null): MatrixClient =
        MatrixClient(URI("https://core.nyutag.chat"), userAgent = "Nyutag-Android-test", doorKey = doorKey, door = door,
                     doorWaitMillis = 0).also { if (token != null) it.setAccessToken(token) }

    @Test fun twoLinksMeetInSlot() = runBlocking {
        assumeTrue(door != null && doorKey != null && user != null && password != null, "нет NYUTAG_TEST_* (tools/test-env.sh)")
        // ⛔ test17 — аккаунт владельца, им не входим.
        assumeTrue(user != "test17" && user?.startsWith("@test17:") != true, "test17 не для тестов")
        val login = client()
        val session = login.login(requireNotNull(user), requireNotNull(password), deviceDisplayName = "Нютаг Android тест релея (Мак)")
        try {
            // Без токена nginx не пускает к релею: сокет не открывается или сразу закрывается.
            val slot = CallID.make()
            val refused = RelayLink.open(client("nope"), slot)
            if (refused != null) assertNull(withTimeoutOrNull(5_000) { refused.inbound.receiveCatching().getOrNull() })

            val core = client(session.accessToken)
            val a = requireNotNull(RelayLink.open(core, slot)) { "сокет A не открылся" }
            val b = requireNotNull(RelayLink.open(core, slot)) { "сокет B не открылся" }
            withTimeoutOrNull(5_000) { a.inbound.receive() }
            // Оба в слоте: релей сообщает каждому.
            var sawTwo = false
            withTimeoutOrNull(5_000) { repeat(3) { if (!sawTwo && b.inbound.receive() == RelayInbound.Peers(2)) sawTwo = true } }
            assertTrue(sawTwo)
            val packet = ByteArray(101) { it.toByte() }
            val started = System.nanoTime()
            b.send(packet)
            var got: ByteArray? = null
            withTimeoutOrNull(5_000) {
                while (got == null) (a.inbound.receive() as? RelayInbound.Packet)?.let { got = it.data }
            }
            println("relay: кадр через ядро и rtc за ${(System.nanoTime() - started) / 1_000_000} мс")
            assertArrayEquals(packet, got)
            a.close()
            b.close()
        } finally {
            login.logout()
        }
    }
}
