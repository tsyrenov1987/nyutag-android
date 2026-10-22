package chat.nyutag.core

import chat.nyutag.core.auth.Session
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.core.sync.SyncEngine
import chat.nyutag.core.sync.SyncFilter
import chat.nyutag.core.sync.SyncResponse
import chat.nyutag.core.sync.SyncUpdate
import chat.nyutag.json.JsonValue
import chat.nyutag.core.net.HttpRequest
import chat.nyutag.core.net.HttpResponse
import chat.nyutag.core.net.Transport
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncTest {
    private fun fixture(): JsonValue {
        val text = requireNotNull(javaClass.getResourceAsStream("/sync.json")).readBytes().toString(Charsets.UTF_8)
        return JsonValue.parse(text)
    }

    private fun engine(store: Store): SyncEngine = runBlocking {
        val client = MatrixClient(URI("http://127.0.0.1:1"), transport = StubTransport())
        val session = Session("@a:nyutag.chat", "D", "t", client.homeserver)
        SyncEngine(client, store, session, this)
    }

    @Test fun parsesSyncResponse() {
        val response = SyncResponse(fixture())
        assertEquals("s72595_4483_1934", response.nextBatch)
        assertEquals(2, response.joined.size)
        assertEquals(1, response.invited.size)
        assertEquals(1, response.left.size)
        assertEquals(listOf("m.direct", "chat.nyutag.chat_layout"), response.accountData.map { it.type })
        assertEquals("m.room.encrypted", response.toDevice.firstOrNull()?.type)
        assertEquals(listOf("@b:nyutag.chat"), response.deviceListsChanged)
        assertEquals(50, response.oneTimeKeysCount["signed_curve25519"])
        assertEquals(listOf("signed_curve25519"), response.unusedFallbackKeys)

        val group = requireNotNull(response.joined["!group:nyutag.chat"])
        assertEquals(3, group.timeline.events.size)
        assertTrue(group.timeline.limited)
        assertEquals("t34-23535_0_0", group.timeline.prevBatch)
        assertEquals("!group:nyutag.chat", group.timeline.events[0].roomID)
        assertTrue(group.timeline.events[2].isState)
        assertEquals(4, group.state.size)
        assertEquals(2, group.ephemeral.size)
        assertEquals(SyncResponse.UnreadNotifications(1, 2), group.unread)
        assertEquals(listOf("@b:nyutag.chat", "@c:nyutag.chat"), group.summary.heroes)
        assertEquals(3, group.summary.joinedCount)
    }

    @Test fun appliesSyncToStore() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        val engine = engine(store)
        val response = SyncResponse(fixture())
        val update = engine.apply(response, isInitial = true)
        assertEquals(setOf("!group:nyutag.chat", "!space:nyutag.chat", "!inv:nyutag.chat", "!old:nyutag.chat"), update.changedRooms)

        val group = requireNotNull(store.room("!group:nyutag.chat"))
        assertEquals(RoomRecord.Membership.JOIN, group.membership)
        assertEquals("Нютаг тест", group.name)
        assertEquals("Тема", group.topic)
        assertTrue(group.isEncrypted)
        assertFalse(group.isSpace)
        assertEquals(2, group.heroes.size)
        assertEquals(3, group.joinedCount)
        assertEquals(1, group.highlightCount)
        assertEquals(2, group.notificationCount)
        assertEquals(1_760_000_000_003L, group.latestTS)
        assertEquals("t34-23535_0_0", group.prevBatch)
        assertEquals(3, store.eventCount("!group:nyutag.chat"))
        assertEquals("Аюр", store.state("!group:nyutag.chat", EventType.MEMBER, "@a:nyutag.chat")?.get("displayname")?.string)
        assertEquals("\$1", store.receipt("!group:nyutag.chat", "@b:nyutag.chat"))
        assertEquals("\$1", store.receipt("!group:nyutag.chat", "@a:nyutag.chat"))
        assertEquals("\$1", store.accountData("m.fully_read", "!group:nyutag.chat")?.get("event_id")?.string)

        assertTrue(requireNotNull(store.room("!space:nyutag.chat")).isSpace)

        val invite = requireNotNull(store.room("!inv:nyutag.chat"))
        assertEquals(RoomRecord.Membership.INVITE, invite.membership)
        assertEquals("Приглашение", invite.name)
        assertTrue(invite.isDirect)

        assertEquals(RoomRecord.Membership.LEAVE, store.room("!old:nyutag.chat")?.membership)
        assertEquals("Работа", store.accountData(EventType.CHAT_LAYOUT)?.get("folders")?.get(0)?.get("name")?.string)
        assertEquals("s72595_4483_1934", store.syncToken)

        // Повторное применение того же ответа не дублирует события.
        engine.apply(response, isInitial = false)
        assertEquals(3, store.eventCount("!group:nyutag.chat"))
    }

    @Test fun directAccountDataMarksRooms() = runBlocking {
        val store = Store(JdbcSql.inMemory())
        val engine = engine(store)
        suspend fun sync(batch: String, rooms: JsonValue = JsonValue.Obj(emptyMap()), direct: JsonValue? = null): SyncUpdate {
            val accountData = if (direct != null) listOf(JsonValue.of("type" to "m.direct", "content" to direct)) else emptyList()
            val json = JsonValue.of("next_batch" to batch, "rooms" to JsonValue.of("join" to rooms),
                                    "account_data" to JsonValue.of("events" to accountData))
            return engine.apply(SyncResponse(json), isInitial = false)
        }
        val empty = JsonValue.of("timeline" to JsonValue.of("events" to emptyList<Any>()))

        // Комната пришла раньше m.direct: признак ставится, когда m.direct приходит.
        sync("1", rooms = JsonValue.of("!dm:nyutag.chat" to empty, "!g:nyutag.chat" to empty))
        assertEquals(false, store.room("!dm:nyutag.chat")?.isDirect)
        val update = sync("2", direct = JsonValue.of("@b:nyutag.chat" to listOf("!dm:nyutag.chat")))
        assertEquals(setOf("!dm:nyutag.chat"), update.changedRooms)
        assertEquals(true, store.room("!dm:nyutag.chat")?.isDirect)
        assertEquals(false, store.room("!g:nyutag.chat")?.isDirect)

        // Новая комната, уже записанная в m.direct, сразу личная.
        sync("3", rooms = JsonValue.of("!dm2:nyutag.chat" to empty), direct = JsonValue.of("@b:nyutag.chat" to listOf("!dm:nyutag.chat", "!dm2:nyutag.chat")))
        sync("4", rooms = JsonValue.of("!dm2:nyutag.chat" to empty))
        assertEquals(true, store.room("!dm2:nyutag.chat")?.isDirect)

        // Убрали из m.direct — признак снимается.
        sync("5", direct = JsonValue.of("@b:nyutag.chat" to listOf("!dm2:nyutag.chat")))
        assertEquals(false, store.room("!dm:nyutag.chat")?.isDirect)
        assertEquals(true, store.room("!dm2:nyutag.chat")?.isDirect)
    }

    @Test fun syncFilterIsCompactJSON() {
        val filter = SyncFilter.initial(20).canonical()
        assertTrue(filter.contains("\"limit\":20"))
        assertFalse(filter.contains(" "))
    }

    /** Пуш будит цикл: висящий long-poll снимается, и сразу уходит /sync без ожидания (timeout=0). */
    @Test fun wakeCancelsLongPollAndAsksAtOnce() = runBlocking {
        val asked = ArrayList<String>()
        val quick = CompletableDeferred<Unit>()
        val transport = object : Transport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                val query = request.url.rawQuery.orEmpty()
                synchronized(asked) { asked += query }
                if ("timeout=30000" in query) awaitCancellation()
                quick.complete(Unit)
                awaitCancellation()
            }
        }
        val client = MatrixClient(URI("http://127.0.0.1:1"), "t", transport = transport)
        val scope = CoroutineScope(Dispatchers.Default)
        val engine = SyncEngine(client, Store(JdbcSql.inMemory()), Session("@a:nyutag.chat", "D", "t", client.homeserver), scope)
        engine.start()
        withTimeout(5_000) { while (synchronized(asked) { asked.isEmpty() }) kotlinx.coroutines.delay(10) }
        engine.wake()
        withTimeout(5_000) { quick.await() }
        scope.cancel()
        assertEquals(2, asked.size)
        assertTrue("timeout=30000" in asked[0])
        assertTrue("timeout=0" in asked[1])
    }
}
