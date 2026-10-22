// Нютаг. Состояние приложения: вход, список чатов из базы, синхронизация и шифрование, отправка.
// Экраны читают StateFlow; хранилище синхронное, поэтому всё, что трогает базу, идёт на Dispatchers.IO.

package chat.nyutag.messenger.session

import android.content.Context
import android.os.Build
import android.util.Log
import chat.nyutag.core.auth.AccountKeeper
import chat.nyutag.core.auth.Shelf
import chat.nyutag.core.auth.SignedInAccount
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.PasswordUnlockOutcome
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.unlockWithPassword
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.nyutag.NyutagService
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.ChatFeed
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.rooms.FeedItem
import chat.nyutag.core.rooms.IgnoredUsers
import chat.nyutag.core.rooms.MutedRooms
import chat.nyutag.core.rooms.Relations
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.core.rooms.backfill
import chat.nyutag.core.rooms.join
import chat.nyutag.core.rooms.send
import chat.nyutag.core.rooms.setDisplayName
import chat.nyutag.core.rooms.setReadMarkers
import chat.nyutag.core.rooms.setTyping
import chat.nyutag.core.store.RoomRecord
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.core.sync.SyncEngine
import chat.nyutag.core.sync.SyncResponse
import chat.nyutag.json.JsonValue
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.data.AndroidSql
import chat.nyutag.messenger.data.KeystoreVault
import chat.nyutag.messenger.doors.DoorHub
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppSession(context: Context, private val doors: DoorHub) {
    enum class Phase { LAUNCHING, SIGNED_OUT, SIGNED_IN }

    enum class SignInFailure { WRONG_PASSWORD, TOO_OFTEN, NO_NETWORK, BROKEN }

    class SignInException(val failure: SignInFailure) : Exception(failure.name)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keeper = AccountKeeper(KeystoreVault(context), Shelf(context.filesDir, AndroidSql::open)) { homeserver, token ->
        doors.makeClient(homeserver, token)
    }

    private val phaseFlow = MutableStateFlow(Phase.LAUNCHING)
    val phase: StateFlow<Phase> get() = phaseFlow
    private val linesFlow = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> get() = linesFlow
    /** Растёт на каждый /sync: открытый чат перечитывает ленту. */
    private val revisionFlow = MutableStateFlow(0)
    val revision: StateFlow<Int> get() = revisionFlow
    /** Кто сейчас печатает, по комнатам (Matrix ID). */
    private val typingFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val typing: StateFlow<Map<String, List<String>>> get() = typingFlow
    private val historyFlow = MutableStateFlow<PasswordUnlockOutcome?>(null)
    val history: StateFlow<PasswordUnlockOutcome?> get() = historyFlow

    @Volatile var me: String? = null
        private set
    private var ignored: Set<String> = emptySet()
    var muted: Set<String> = emptySet()
        private set

    private var account: SignedInAccount? = null
    private var engine: SyncEngine? = null
    @Volatile private var machine: CryptoMachine? = null
    private var pump: Job? = null
    private val opened = ConcurrentHashMap<String, Event>()
    private val autoJoined = HashSet<String>()
    private val refreshing = Mutex()

    // MARK: Вход

    /** Запуск: сохранённый вход поднимаем без сети, список строится из базы сразу. */
    fun launch() {
        if (phaseFlow.value != Phase.LAUNCHING) return
        scope.launch {
            val restored = withContext(Dispatchers.IO) { runCatching { keeper.restore() }.getOrNull() }
            if (restored == null) phaseFlow.value = Phase.SIGNED_OUT else start(restored, password = null)
        }
    }

    /** Вход по имени, телефону или почте. Телефон и почту сервер Нютага переводит в Matrix ID.
     *  `displayName` — имя нового участника сразу после регистрации: ставим его в профиль до первого /sync. */
    suspend fun signIn(identifier: String, password: String, displayName: String? = null) {
        val typed = identifier.trim()
        if (!doors.waitUntilUp()) throw SignInException(SignInFailure.NO_NETWORK)
        val signed = try {
            val user = if (needsResolve(typed)) NyutagService(doors.makeClient()).resolve(typed, password) else typed
            withContext(Dispatchers.IO) { keeper.signIn(DoorHub.CORE, user, password, deviceName) }.also { account ->
                if (displayName != null) runCatching { account.client.setDisplayName(account.session.userID, displayName) }
            }
        } catch (e: MatrixError) {
            log("отказ входа: $e")
            throw SignInException(failure(e))
        } catch (e: Exception) {
            log("отказ входа: $e")
            throw SignInException(SignInFailure.BROKEN)
        }
        // Экран входа уходит с переходом в SIGNED_IN, и его корутина отменяется — запуск живёт в своей области.
        scope.launch { start(signed, password) }
    }

    suspend fun signOut() {
        val account = account ?: return
        stop()
        withContext(Dispatchers.IO) { keeper.signOut(account) }
        this.account = null
        me = null
        opened.clear()
        autoJoined.clear()
        linesFlow.value = emptyList()
        typingFlow.value = emptyMap()
        historyFlow.value = null
        phaseFlow.value = Phase.SIGNED_OUT
    }

    private suspend fun start(account: SignedInAccount, password: String?) {
        this.account = account
        me = account.session.userID
        val engine = SyncEngine(account.client, account.store, account.session, scope)
        this.engine = engine
        phaseFlow.value = Phase.SIGNED_IN
        refresh()
        try {
            val machine = CryptoMachine.create(account.client, account.store, account.session)
            this.machine = machine
            engine.addObserver(machine)
            runCatching { machine.publishKeys(null) }
            if (password != null) {
                // Пароль уже проверен сервером: им же запираем или открываем хранилище ключей.
                historyFlow.value = runCatching { machine.unlockWithPassword(password) }.getOrNull()
                log("история по паролю: ${historyFlow.value ?: "ошибка"}")
            }
        } catch (e: Exception) {
            // Без машины переписка в шифрованных чатах остаётся под замком, остальное работает.
            log("машина шифрования не поднялась: $e")
        }
        log("это устройство ${account.session.deviceID}")
        pump = scope.launch {
            engine.updates.collect { update ->
                noteTyping(update.response)
                refresh()
                revisionFlow.value += 1
            }
        }
        engine.start()
        refresh()
    }

    private fun stop() {
        pump?.cancel()
        pump = null
        engine?.stop()
        engine = null
        machine = null
    }

    // MARK: Список

    private suspend fun refresh() = refreshing.withLock {
        val account = account ?: return@withLock
        val me = me ?: return@withLock
        withContext(Dispatchers.IO) {
            runCatching { IgnoredUsers.load(account.store) }.getOrNull()?.let { ignored = it }
            runCatching { MutedRooms.load(account.store) }.getOrNull()?.let { muted = it }
            runCatching { ChatDigest.lines(account.store, me, ignored, ::open) }.getOrNull()?.let { fresh ->
                val chats = fresh.filter { !it.isSpace }
                if (chats != linesFlow.value) linesFlow.value = chats
            }
        }
        acceptInvites(account)
    }

    /** Сервер закрытый, чужих на нём нет: приглашение от участника принимаем сразу, как в Telegram. */
    private suspend fun acceptInvites(account: SignedInAccount) {
        val invited = withContext(Dispatchers.IO) {
            runCatching { account.store.rooms(RoomRecord.Membership.INVITE) }.getOrNull()
        } ?: return
        for (room in invited) {
            if (!autoJoined.add(room.id)) continue
            val joined = runCatching { account.client.join(room.id) }.isSuccess
            log("приглашение ${room.id} ${if (joined) "принято" else "не принято"}")
        }
    }

    private fun noteTyping(response: SyncResponse) {
        val me = me ?: return
        var map = typingFlow.value
        for ((roomID, room) in response.joined) {
            val who = Relations.typing(room.ephemeral, me) ?: continue
            map = if (who.isEmpty()) map - roomID else map + (roomID to who)
        }
        if (map != typingFlow.value) typingFlow.value = map
    }

    /** Расшифровка с памятью: список перестраивается на каждый /sync. Неудачи не запоминаем — ключ может прийти. */
    private suspend fun open(event: Event, roomID: String): Event? {
        val id = event.eventID ?: return null
        opened[id]?.let { return it }
        val machine = machine ?: return null
        val clear = runCatching { machine.decrypt(event, roomID).event }.getOrNull() ?: return null
        if (opened.size > 2000) opened.clear()
        opened[id] = clear
        return clear
    }

    // MARK: Чат

    fun title(roomID: String): String = linesFlow.value.firstOrNull { it.id == roomID }?.title.orEmpty()

    suspend fun feed(roomID: String, limit: Int): List<FeedItem> {
        val account = account ?: return emptyList()
        val me = me ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { ChatFeed.items(roomID, account.store, me, limit, ignored, ::open) }.getOrDefault(emptyList())
        }
    }

    /** Ещё страница истории с сервера; `false` — дошли до начала комнаты или нет связи. */
    suspend fun loadOlder(roomID: String): Boolean {
        val engine = engine ?: return false
        return runCatching { engine.backfill(roomID, 50) }.getOrNull()?.let { it.added > 0 || it.hasMore } ?: false
    }

    /** Текст в чат; `null` — не ушло. */
    suspend fun sendText(roomID: String, text: String, transactionID: String = TransactionID.make()): String? =
        post(EventType.MESSAGE, JsonValue.of("msgtype" to "m.text", "body" to text), roomID, transactionID)

    /** Событие в комнату; в шифрованную — через машину. */
    private suspend fun post(type: String, content: JsonValue, roomID: String, transactionID: String): String? {
        val account = account ?: return null
        val encrypted = withContext(Dispatchers.IO) { runCatching { account.store.room(roomID)?.isEncrypted }.getOrNull() } ?: false
        return try {
            if (encrypted) {
                val machine = machine ?: return null
                machine.sendEncrypted(roomID, type, content, transactionID)
            } else {
                account.client.send(roomID, type, content, transactionID)
            }
        } catch (e: Exception) {
            log("отправка $type не прошла: $e")
            null
        }
    }

    /** «Набираю текст»: сервер сам снимет отметку через 20 секунд, если не продлить. */
    suspend fun setTyping(roomID: String, typing: Boolean) {
        val account = account ?: return
        val me = me ?: return
        runCatching { account.client.setTyping(roomID, me, typing) }
    }

    /** Отметка прочтения: публичная, чтобы у собеседника загорелись две галочки. */
    suspend fun markRead(roomID: String, eventID: String) {
        runCatching { account?.client?.setReadMarkers(roomID, fullyRead = eventID, read = eventID) }
    }

    /** Имя участника для «печатает». */
    suspend fun displayName(roomID: String, userID: String): String {
        val account = account ?: return ChatDigest.localpart(userID)
        return withContext(Dispatchers.IO) {
            runCatching { account.store.state(roomID, EventType.MEMBER, userID)?.get("displayname")?.string }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        } ?: ChatDigest.localpart(userID)
    }

    private fun log(line: String) {
        if (BuildConfig.DEBUG) Log.d("ui-check", line)
    }

    companion object {
        private val deviceName = "Нютаг Android" + (Build.MODEL?.let { " ($it)" } ?: "")

        fun needsResolve(identifier: String): Boolean {
            if (identifier.startsWith("@")) return false
            if ('@' in identifier) return true
            val digits = identifier.count(Char::isDigit)
            return digits >= 7 && identifier.all { it.isDigit() || it in "+-() " }
        }

        fun failure(error: MatrixError): SignInFailure = when {
            error.status == 429 || error.errcode == "M_LIMIT_EXCEEDED" -> SignInFailure.TOO_OFTEN
            error.errcode == "M_FORBIDDEN" || error.errcode == "M_NOT_FOUND" || error.status == 403 -> SignInFailure.WRONG_PASSWORD
            error.kind is MatrixError.Kind.Network -> SignInFailure.NO_NETWORK
            else -> SignInFailure.BROKEN
        }
    }
}
