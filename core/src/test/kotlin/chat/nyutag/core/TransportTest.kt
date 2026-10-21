// Транспорт: клиент с дверью. Адрес запроса — дверь, имя ядра остаётся в сессии; ключ двери в заголовке;
// смена двери без пересоздания клиента; ожидание двери; жалоба на дверь, которая не отвечает.

package chat.nyutag.core

import chat.nyutag.core.net.Door
import chat.nyutag.core.net.DoorCatalogue
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.net.TransportException
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransportTest {
    private val core = URI("https://core.test.nyutag")
    private val doorA = Door.parse("https://a.test.nyutag:6167#a")!!
    private val doorB = Door.parse("https://b.test.nyutag#b")!!
    private val stub = StubTransport()

    private fun makeClient(doorKey: String? = "k-test", door: Door? = null, doorWaitMillis: Long = 20_000) =
        MatrixClient(core, "tok", transport = stub, doorKey = doorKey, door = door, doorWaitMillis = doorWaitMillis)

    @Test fun requestGoesThroughDoor() = runBlocking {
        val client = makeClient(door = doorA)
        client.raw(MatrixClient.Method.GET, "/_nyutag/v1/ping")
        val request = requireNotNull(stub.last)
        assertEquals("a.test.nyutag", request.url.host)
        assertEquals(6167, request.url.port)
        assertEquals("/_nyutag/v1/ping", request.url.rawPath)
        assertEquals("k-test", request.header(DoorCatalogue.HEADER_NAME))
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals(core, client.homeserver, "имя ядра для сессии не меняется")
    }

    @Test fun plainClient() = runBlocking {
        val client = makeClient(doorKey = null)
        client.raw(MatrixClient.Method.GET, "/ping")
        val request = requireNotNull(stub.last)
        assertEquals("core.test.nyutag", request.url.host)
        assertNull(request.header(DoorCatalogue.HEADER_NAME))
    }

    @Test fun switchKeepsClient() = runBlocking {
        val client = makeClient(door = doorA)
        client.setDoor(doorB)
        assertEquals(doorB, client.door)
        client.raw(MatrixClient.Method.GET, "/ping")
        assertEquals("b.test.nyutag", stub.last?.url?.host)
        assertEquals(-1, stub.last?.url?.port)
    }

    @Test fun waitsForDoor() = runBlocking {
        val client = makeClient()
        val started = System.nanoTime()
        val pending = async { client.raw(MatrixClient.Method.GET, "/ping") }
        delay(400)
        client.setDoor(doorA)
        pending.await()
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 400)
        assertEquals("a.test.nyutag", stub.last?.url?.host)
    }

    @Test fun noDoorNoWait() = runBlocking {
        val before = stub.hits
        val client = makeClient(doorWaitMillis = 0)
        val error = assertThrows(MatrixError::class.java) { runBlocking { client.raw(MatrixClient.Method.GET, "/ping") } }
        assertEquals(MatrixError.Kind.Network("нет пути к ядру"), error.kind)
        assertEquals(before, stub.hits)
    }

    @Test fun doorWaitExpires() {
        val client = makeClient(doorWaitMillis = 300)
        val error = assertThrows(MatrixError::class.java) { runBlocking { client.raw(MatrixClient.Method.GET, "/ping") } }
        assertEquals(MatrixError.Kind.Network("нет пути к ядру"), error.kind)
    }

    @Test fun complainsOnBadGateway() = runBlocking {
        val client = makeClient(door = doorA)
        val complaints = Collections.synchronizedList(ArrayList<Door>())
        client.setDoorTrouble { complaints += it }
        stub.status = 502
        assertThrows(MatrixError::class.java) { runBlocking { client.raw(MatrixClient.Method.GET, "/ping") } }
        assertEquals(listOf(doorA), complaints)
        stub.status = 200
        client.raw(MatrixClient.Method.GET, "/ping")
        assertEquals(listOf(doorA), complaints, "удачный ответ жалоб не добавляет")
    }

    @Test fun complainsOnTransportFailureOnly() = runBlocking {
        val client = makeClient(door = doorA)
        val complaints = Collections.synchronizedList(ArrayList<Door>())
        client.setDoorTrouble { complaints += it }
        stub.failure = TransportException.Kind.TIMED_OUT
        val error = assertThrows(MatrixError::class.java) { runBlocking { client.raw(MatrixClient.Method.GET, "/ping") } }
        assertTrue(error.kind is MatrixError.Kind.Network)
        assertEquals(listOf(doorA), complaints)
        stub.failure = TransportException.Kind.NO_INTERNET
        assertThrows(MatrixError::class.java) { runBlocking { client.raw(MatrixClient.Method.GET, "/ping") } }
        assertEquals(listOf(doorA), complaints, "без интернета на дверь не жалуемся")
    }

    @Test fun doorTroubleTable() {
        assertTrue(MatrixClient.isDoorTrouble(TransportException.Kind.TIMED_OUT))
        assertTrue(MatrixClient.isDoorTrouble(TransportException.Kind.CANNOT_CONNECT))
        assertTrue(MatrixClient.isDoorTrouble(TransportException.Kind.TLS_FAILED))
        assertTrue(MatrixClient.isDoorTrouble(TransportException.Kind.CONNECTION_LOST))
        assertFalse(MatrixClient.isDoorTrouble(TransportException.Kind.NO_INTERNET))
        assertFalse(MatrixClient.isDoorTrouble(TransportException.Kind.DATA_NOT_ALLOWED))
    }

    @Test fun followsStream() = runBlocking {
        val client = makeClient()
        val doors = MutableStateFlow<Door?>(null)
        val job = client.follow(doors, this)
        doors.value = doorB
        repeat(40) { if (client.door == null) delay(25) }
        assertEquals(doorB, client.door)
        doors.value = null
        repeat(40) { if (client.door != null) delay(25) }
        assertNull(client.door)
        job.cancel()
    }

    @Test fun encodesPathAndQuery() = runBlocking {
        val client = makeClient(doorKey = null)
        client.request(MatrixClient.Method.GET, "rooms/!a:nyutag.chat/event/\$ev", query = mapOf("b" to "2 два", "a" to "1"),
                       repeatedQuery = mapOf("via" to listOf("x", "y")))
        val url = requireNotNull(stub.last).url
        assertEquals("/_matrix/client/v3/rooms/%21a%3Anyutag.chat/event/%24ev", url.rawPath)
        assertEquals("a=1&b=2%20%D0%B4%D0%B2%D0%B0&via=x&via=y", url.rawQuery)
    }

    @Test fun parsesErrors() {
        val server = MatrixClient.parseError(429, """{"errcode":"M_LIMIT_EXCEEDED","error":"slow","retry_after_ms":1500}""".toByteArray(), null)
        assertEquals(MatrixError.Kind.Server(429, "M_LIMIT_EXCEEDED", "slow"), server.kind)
        assertEquals(1500L, server.retryAfterMillis)
        val header = MatrixClient.parseError(429, "nope".toByteArray(), "2")
        assertEquals(MatrixError.Kind.Http(429), header.kind)
        assertEquals(2000L, header.retryAfterMillis)
        val uia = MatrixClient.parseError(401, """{"flows":[],"session":"s1"}""".toByteArray(), null)
        assertEquals("s1", uia.uiaSession)
        val token = MatrixClient.parseError(401, """{"errcode":"M_UNKNOWN_TOKEN","error":"x"}""".toByteArray(), null)
        assertTrue(token.isUnknownToken)
    }
}
