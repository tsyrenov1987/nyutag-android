// Нютаг. Цикл /sync: long-poll, разбор, запись в хранилище, уведомление подписчиков.
// Шифрование подключается через [SyncObserver]: крипто-модуль получает to-device и счётчики ключей отсюда.

package chat.nyutag.core.sync

import chat.nyutag.core.auth.Session
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.store.Store
import chat.nyutag.json.JsonValue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

class SyncUpdate(
    val response: SyncResponse,
    /** Комнаты, у которых изменился таймлайн или состояние. */
    val changedRooms: Set<String>,
    val isInitial: Boolean,
)

fun interface SyncObserver {
    suspend fun syncDidReceive(update: SyncUpdate)
}

class SyncEngine(
    val client: MatrixClient,
    val store: Store,
    val session: Session,
    private val scope: CoroutineScope,
) {
    sealed class State {
        object Idle : State()
        object Running : State()
        data class WaitingForNetwork(val reason: String) : State()
        data class Stopped(val error: MatrixError?) : State()
    }

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = stateFlow

    private var job: Job? = null
    private val observers = CopyOnWriteArrayList<SyncObserver>()
    private val updatesFlow = MutableSharedFlow<SyncUpdate>(extraBufferCapacity = 64)
    /** Поток обновлений для UI. */
    val updates: SharedFlow<SyncUpdate> get() = updatesFlow

    var longPollTimeoutMillis = 30_000L
    /** Задержка после сетевой ошибки: 1, 2, 4 … до 60 с. */
    private var backoffMillis = 1_000L
    /** Побудка цикла (пуш): прервать паузу или висящий long-poll и сразу спросить сервер без ожидания. */
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    // MARK: Подписка

    fun addObserver(observer: SyncObserver) {
        observers.addIfAbsent(observer)
    }

    fun removeObserver(observer: SyncObserver) {
        observers.remove(observer)
    }

    // MARK: Цикл

    @Synchronized
    fun start() {
        if (job != null) return
        stateFlow.value = State.Running
        job = scope.launch { loop() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        stateFlow.value = State.Stopped(null)
    }

    /** Один проход /sync, без цикла (для пушей и тестов). */
    suspend fun syncOnce(timeoutMillis: Long = 0): SyncUpdate {
        val since = store.syncToken
        val response = client.sync(since, if (since == null) SyncFilter.initial() else SyncFilter.incremental(), timeoutMillis)
        return apply(response, isInitial = since == null)
    }

    /** Пуш пришёл: ключи и сообщение уже на сервере, ждать паузы после ошибки или конца long-poll некогда. */
    fun wake() {
        wakeUp.trySend(Unit)
    }

    private suspend fun loop() {
        var quick = false
        while (true) {
            try {
                if (!pollOnce(if (quick) 0 else longPollTimeoutMillis)) {
                    quick = true
                    continue
                }
                quick = false
                backoffMillis = 1_000
                stateFlow.value = State.Running
            } catch (e: CancellationException) {
                return
            } catch (e: MatrixError) {
                if (e.isUnknownToken) {
                    stateFlow.value = State.Stopped(e)
                    synchronized(this) { job = null }
                    return
                }
                stateFlow.value = State.WaitingForNetwork(e.kind.toString())
                quick = withTimeoutOrNull(backoffMillis) { wakeUp.receive() } != null
                backoffMillis = if (quick) 1_000 else minOf(backoffMillis * 2, 60_000)
            }
        }
    }

    /** Один запрос цикла; `false` — разбудили посреди ожидания ответа, запрос снят, ответа нет. Применение
     *  ответа не прерывается: побудка во время записи в хранилище просто ждёт следующего круга. */
    private suspend fun pollOnce(timeoutMillis: Long): Boolean {
        val since = store.syncToken
        val response = coroutineScope {
            val call = async { client.sync(since, if (since == null) SyncFilter.initial() else SyncFilter.incremental(), timeoutMillis) }
            select<SyncResponse?> {
                call.onAwait { it }
                wakeUp.onReceive { call.cancel(); null }
            }
        } ?: return false
        apply(response, isInitial = since == null)
        return true
    }

    // MARK: Применение

    suspend fun apply(response: SyncResponse, isInitial: Boolean): SyncUpdate {
        val changed = LinkedHashSet<String>()
        try {
            for ((roomID, room) in response.joined) {
                applyJoined(roomID, room)
                changed += roomID
            }
            for ((roomID, room) in response.invited) {
                val record = store.room(roomID) ?: RoomRecord(roomID, RoomRecord.Membership.INVITE)
                record.membership = RoomRecord.Membership.INVITE
                for (event in room.inviteState) store.setState(event)
                refreshSummary(record, room.inviteState)
                store.upsert(record)
                changed += roomID
            }
            for ((roomID, room) in response.left) {
                val record = store.room(roomID) ?: RoomRecord(roomID, RoomRecord.Membership.LEAVE)
                record.membership = RoomRecord.Membership.LEAVE
                for (event in room.state) store.setState(event)
                store.append(room.timeline.events)
                for (event in room.timeline.events) if (event.isState) store.setState(event)
                store.upsert(record)
                changed += roomID
            }
            for ((roomID, room) in response.knocked) {
                val record = store.room(roomID) ?: RoomRecord(roomID, RoomRecord.Membership.KNOCK)
                record.membership = RoomRecord.Membership.KNOCK
                for (event in room.knockState) store.setState(event)
                store.upsert(record)
                changed += roomID
            }
            for (event in response.accountData) {
                store.setAccountData(event.type, content = event.content)
            }
            // Новый m.direct: пересчитать признак личного чата у всех комнат (на первом синке комнаты уже записаны выше).
            if (response.accountData.any { it.type == EventType.DIRECT }) {
                for (record in store.rooms()) {
                    val direct = isDirect(record.id)
                    if (direct == record.isDirect) continue
                    record.isDirect = direct
                    store.upsert(record)
                    changed += record.id
                }
            }
            store.setMeta("sync_token", response.nextBatch)
        } catch (e: MatrixError) {
            throw e
        } catch (e: Exception) {
            throw MatrixError(MatrixError.Kind.Decoding("хранилище: $e"))
        }

        val update = SyncUpdate(response, changed, isInitial)
        for (observer in observers) observer.syncDidReceive(update)
        updatesFlow.tryEmit(update)
        return update
    }

    private fun applyJoined(roomID: String, room: SyncResponse.JoinedRoom) {
        val record = store.room(roomID) ?: RoomRecord(roomID, RoomRecord.Membership.JOIN)
        record.membership = RoomRecord.Membership.JOIN
        for (event in room.state) store.setState(event)
        store.append(room.timeline.events)
        for (event in room.timeline.events) if (event.isState) store.setState(event)
        val last = room.timeline.events.lastOrNull()?.originServerTS
        if (last != null && last > record.latestTS) record.latestTS = last
        val prevBatch = room.timeline.prevBatch
        if (room.timeline.limited && prevBatch != null) {
            // Разрыв в таймлайне: запоминаем, откуда докачивать историю.
            record.prevBatch = prevBatch
        } else if (record.prevBatch == null && prevBatch != null) {
            record.prevBatch = prevBatch
        }
        record.highlightCount = room.unread.highlight
        record.notificationCount = room.unread.notification
        room.summary.heroes?.let { record.heroes = it }
        room.summary.joinedCount?.let { record.joinedCount = it }
        room.summary.invitedCount?.let { record.invitedCount = it }
        refreshSummary(record, room.state + room.timeline.events.filter { it.isState })
        for (event in room.accountData) {
            store.setAccountData(event.type, roomID, event.content)
        }
        for (event in room.ephemeral) if (event.type == EventType.RECEIPT) applyReceipts(roomID, event.content)
        store.upsert(record)
    }

    /** Имя, тема, аватар, алиас, шифрование, тип комнаты по пришедшему состоянию (или по сохранённому, если пришло не всё). */
    private fun refreshSummary(record: RoomRecord, stateEvents: List<Event>) {
        val roomID = record.id
        fun content(type: String): JsonValue? {
            stateEvents.lastOrNull { it.type == type && it.stateKey == "" }?.let { return it.content }
            return store.state(roomID, type)
        }
        content(EventType.NAME)?.let { record.name = it["name"]?.string }
        content(EventType.TOPIC)?.let { record.topic = it["topic"]?.string }
        content(EventType.AVATAR)?.let { record.avatarURL = it["url"]?.string }
        content(EventType.CANONICAL_ALIAS)?.let { record.canonicalAlias = it["alias"]?.string }
        if (content(EventType.ENCRYPTION) != null) record.isEncrypted = true
        content(EventType.CREATE)?.let { record.isSpace = it["type"]?.string == "m.space" }
        record.isDirect = isDirect(roomID, stateEvents)
    }

    /** Личный чат: комната есть в нашем `m.direct` или наш m.room.member помечен `is_direct` (так приходит приглашение). */
    private fun isDirect(roomID: String, stateEvents: List<Event> = emptyList()): Boolean {
        var member = stateEvents.lastOrNull { it.type == EventType.MEMBER && it.stateKey == session.userID }?.content
        if (member == null) member = store.state(roomID, EventType.MEMBER, session.userID)
        if (member?.get("is_direct")?.bool == true) return true
        val direct = store.accountData(EventType.DIRECT)?.obj ?: emptyMap()
        return direct.values.any { list -> list.array?.any { it.string == roomID } == true }
    }

    private fun applyReceipts(roomID: String, content: JsonValue) {
        for ((eventID, receipts) in content.obj ?: emptyMap()) {
            for ((userID, data) in receipts["m.read"]?.obj ?: emptyMap()) {
                store.setReceipt(roomID, userID, eventID, data["ts"]?.long ?: 0)
            }
            for ((userID, data) in receipts["m.read.private"]?.obj ?: emptyMap()) {
                if (userID == session.userID) store.setReceipt(roomID, userID, eventID, data["ts"]?.long ?: 0)
            }
        }
    }
}
