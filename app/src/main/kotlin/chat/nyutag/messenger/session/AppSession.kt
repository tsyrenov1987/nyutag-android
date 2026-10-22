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
import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.crypto.PasswordUnlockOutcome
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.importCrossSigning
import chat.nyutag.core.crypto.relockWithPassword
import chat.nyutag.core.crypto.unlockWithPassword
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.nyutag.IssuedInvite
import chat.nyutag.core.nyutag.InviteCode
import chat.nyutag.core.nyutag.NyutagService
import chat.nyutag.core.rooms.AccountDevice
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.ChatFeed
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.rooms.FeedItem
import chat.nyutag.core.rooms.Forwarding
import chat.nyutag.core.rooms.IgnoredUsers
import chat.nyutag.core.rooms.MutedRooms
import chat.nyutag.core.rooms.CreateRoom
import chat.nyutag.core.rooms.People
import chat.nyutag.core.rooms.Person
import chat.nyutag.core.rooms.PublicRoom
import chat.nyutag.core.rooms.Relations
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.core.rooms.backfill
import chat.nyutag.core.rooms.addSpaceChild
import chat.nyutag.core.rooms.createDirectRoom
import chat.nyutag.core.rooms.createRoom
import chat.nyutag.core.rooms.deleteDevice
import chat.nyutag.core.rooms.devices
import chat.nyutag.core.rooms.join
import chat.nyutag.core.rooms.knock
import chat.nyutag.core.rooms.leave
import chat.nyutag.core.rooms.publicCommunities
import chat.nyutag.core.rooms.redact
import chat.nyutag.core.rooms.report
import chat.nyutag.core.rooms.setIgnoredUsers
import chat.nyutag.core.rooms.renameDevice
import chat.nyutag.core.rooms.roomHierarchy
import chat.nyutag.core.rooms.setRoomVisibility
import chat.nyutag.core.rooms.setSpaceParent
import chat.nyutag.core.rooms.setState
import chat.nyutag.core.rooms.state
import chat.nyutag.core.rooms.person
import chat.nyutag.core.rooms.Profile
import chat.nyutag.core.rooms.ProfileAbout
import chat.nyutag.core.rooms.SavedMessages
import chat.nyutag.core.rooms.applyProfileEdits
import chat.nyutag.core.rooms.createSavedMessages
import chat.nyutag.core.rooms.deactivate
import chat.nyutag.core.rooms.profileFieldsAPI
import chat.nyutag.core.rooms.profile
import chat.nyutag.core.rooms.searchUsers
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

    enum class InviteFailure { LIMIT, NO_NETWORK, BROKEN }

    enum class CommunityJoin { JOINED, REQUESTED, FAILED }

    class InviteException(val failure: InviteFailure) : Exception(failure.name)

    class SignInException(val failure: SignInFailure) : Exception(failure.name)

    enum class KeyFailure { WRONG_KEY, NO_NETWORK, BROKEN }

    class KeyException(val failure: KeyFailure) : Exception(failure.name)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keeper = AccountKeeper(KeystoreVault(context), Shelf(context.filesDir, AndroidSql::open)) { homeserver, token ->
        doors.makeClient(homeserver, token)
    }

    private val phaseFlow = MutableStateFlow(Phase.LAUNCHING)
    val phase: StateFlow<Phase> get() = phaseFlow
    private val linesFlow = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> get() = linesFlow
    /** Сообщества, где мы участник: пространства `m.space` из той же базы. */
    private val communitiesFlow = MutableStateFlow<List<ChatLine>>(emptyList())
    val communities: StateFlow<List<ChatLine>> get() = communitiesFlow
    /** Последний ответ каталога: карточка сообщества берёт оттуда описание и правило входа. */
    @Volatile var catalog: List<PublicRoom> = emptyList()
        private set
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

    /** Флаги без секретов: карточка «старые сообщения закрыты» должна пережить перезапуск. */
    private val flags = context.getSharedPreferences("nyutag.history", Context.MODE_PRIVATE)
    /** Пароль этого входа, пока история под старым ключом: ключ сразу перезапрёт хранилище паролем. */
    @Volatile private var heldPassword: String? = null

    private var account: SignedInAccount? = null
    private var engine: SyncEngine? = null
    @Volatile private var machine: CryptoMachine? = null
    private var pump: Job? = null
    private val opened = ConcurrentHashMap<String, Event>()
    private val autoJoined = HashSet<String>()
    private val refreshing = Mutex()
    /** Сверка книжки с сервером не чаще раза в десять минут: у сервера суточный предел. */
    private var matchedAt = 0L
    private var matches: Map<String, String> = emptyMap()

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
        clear()
    }

    /** Удалить аккаунт на сервере навсегда; после этого приложение само уходит на экран входа. */
    suspend fun deleteAccount(password: String) {
        val account = account ?: throw SignInException(SignInFailure.BROKEN)
        if (!doors.waitUntilUp()) throw SignInException(SignInFailure.NO_NETWORK)
        try {
            account.client.deactivate(account.session.userID, password)
        } catch (e: MatrixError) {
            log("отказ удаления аккаунта: $e")
            throw SignInException(failure(e))
        } catch (e: Exception) {
            log("отказ удаления аккаунта: $e")
            throw SignInException(SignInFailure.BROKEN)
        }
        stop()
        withContext(Dispatchers.IO) {
            runCatching { account.store.close() }
            runCatching { keeper.forget(account.session.userID) }
        }
        clear()
        log("аккаунт удалён")
    }

    /** Это устройство: в списке «Устройства» оно первое и без кнопки выхода. */
    val deviceID: String? get() = account?.session?.deviceID

    /** Где открыт аккаунт: своё устройство первым. Нет связи — [SignInFailure.NO_NETWORK], прочее — [SignInFailure.BROKEN]. */
    suspend fun devices(): List<AccountDevice> {
        val account = account ?: throw SignInException(SignInFailure.BROKEN)
        if (!doors.waitUntilUp()) throw SignInException(SignInFailure.NO_NETWORK)
        val list = runCatching { account.client.devices() }
            .getOrElse { log("список устройств не пришёл: $it"); throw SignInException(SignInFailure.BROKEN) }
        return AccountDevice.sorted(list, account.session.deviceID)
    }

    suspend fun renameDevice(deviceID: String, name: String): Boolean {
        val account = account ?: return false
        return runCatching { account.client.renameDevice(deviceID, name) }.onFailure { log("переименование не прошло: $it") }.isSuccess
    }

    /** Выйти на чужом устройстве, подтвердив паролем; ошибки — как при удалении аккаунта. */
    suspend fun signOutDevice(deviceID: String, password: String) {
        val account = account ?: throw SignInException(SignInFailure.BROKEN)
        if (!doors.waitUntilUp()) throw SignInException(SignInFailure.NO_NETWORK)
        try {
            account.client.deleteDevice(deviceID, account.session.userID, password)
        } catch (e: MatrixError) {
            log("отказ выхода на устройстве: $e")
            throw SignInException(failure(e))
        } catch (e: Exception) {
            log("отказ выхода на устройстве: $e")
            throw SignInException(SignInFailure.BROKEN)
        }
        log("вышли на устройстве $deviceID")
    }

    private fun clear() {
        this.account = null
        me = null
        opened.clear()
        autoJoined.clear()
        linesFlow.value = emptyList()
        communitiesFlow.value = emptyList()
        catalog = emptyList()
        typingFlow.value = emptyMap()
        historyFlow.value = null
        heldPassword = null
        matchedAt = 0L
        matches = emptyMap()
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
                val outcome = runCatching { machine.unlockWithPassword(password) }.getOrNull()
                historyFlow.value = outcome
                if (outcome == PasswordUnlockOutcome.NeedsRecoveryKey || outcome == PasswordUnlockOutcome.NeedsOtherDevice) heldPassword = password
                rememberHistory(outcome, account.session.userID)
                log("история по паролю: ${outcome ?: "ошибка"}")
            } else {
                rememberedHistory(account.session.userID)?.let { historyFlow.value = it }
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

    // MARK: Старая история

    /** Пароль этого входа ещё в памяти: ключ сразу перезапрёт хранилище, и на следующих устройствах хватит пароля. */
    val canRelockHistory: Boolean get() = heldPassword != null

    /** Открыть старую историю ключом восстановления. С паролем в памяти хранилище сразу перезапирается им,
     *  без пароля (после перезапуска) только открываем. */
    suspend fun openHistory(recoveryKey: String) {
        val machine = machine ?: throw KeyException(KeyFailure.BROKEN)
        val me = me ?: throw KeyException(KeyFailure.BROKEN)
        val key = recoveryKey.trim()
        val imported = try {
            val password = heldPassword
            if (password != null) {
                machine.relockWithPassword(key, password)
            } else {
                machine.importCrossSigning(key)
                try { machine.restoreBackup(key).imported } catch (e: E2EEException) { 0 }
            }
        } catch (e: E2EEException.WrongRecoveryKey) {
            throw KeyException(KeyFailure.WRONG_KEY)
        } catch (e: E2EEException.Server) {
            log("ключ не открыл историю: $e")
            throw KeyException(if (e.error.kind is MatrixError.Kind.Network) KeyFailure.NO_NETWORK else KeyFailure.BROKEN)
        } catch (e: Exception) {
            log("ключ не открыл историю: $e")
            throw KeyException(KeyFailure.BROKEN)
        }
        heldPassword = null
        historyFlow.value = PasswordUnlockOutcome.Opened(imported)
        rememberHistory(null, me)
        log("история открыта ключом, ключей из бэкапа $imported")
        refresh()
        revisionFlow.value += 1
    }

    private fun rememberHistory(outcome: PasswordUnlockOutcome?, userID: String) {
        val key = "locked.$userID"
        when (outcome) {
            PasswordUnlockOutcome.NeedsRecoveryKey -> flags.edit().putString(key, "key").apply()
            PasswordUnlockOutcome.NeedsOtherDevice -> flags.edit().putString(key, "device").apply()
            else -> flags.edit().remove(key).apply()
        }
    }

    private fun rememberedHistory(userID: String): PasswordUnlockOutcome? = when (flags.getString("locked.$userID", null)) {
        "key" -> PasswordUnlockOutcome.NeedsRecoveryKey
        "device" -> PasswordUnlockOutcome.NeedsOtherDevice
        else -> null
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
                val spaces = fresh.filter { it.isSpace && it.membership == RoomRecord.Membership.JOIN }
                if (chats != linesFlow.value) linesFlow.value = chats
                if (spaces != communitiesFlow.value) communitiesFlow.value = spaces
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

    /** Текст в чат, `replyTo` — ответ на реплику; `null` — не ушло. Тот же `transactionID` при повторе сервер не дублирует. */
    suspend fun sendText(roomID: String, text: String, transactionID: String = TransactionID.make(), replyTo: String? = null): String? {
        val content = if (replyTo == null) JsonValue.of("msgtype" to "m.text", "body" to text)
        else JsonValue.of("msgtype" to "m.text", "body" to text,
                          "m.relates_to" to JsonValue.of("m.in_reply_to" to JsonValue.of("event_id" to replyTo)))
        return post(EventType.MESSAGE, content, roomID, transactionID)
    }

    /** Новый текст своей реплики. */
    suspend fun edit(roomID: String, eventID: String, text: String): Boolean =
        post(EventType.MESSAGE, Relations.edit(eventID, text), roomID, TransactionID.make()) != null

    /** Удалить реплику у всех. */
    suspend fun delete(roomID: String, eventID: String): Boolean {
        val account = account ?: return false
        return runCatching { account.client.redact(roomID, eventID) }.onFailure { log("удаление не прошло: $it") }.isSuccess
    }

    /** Реакция: нашей с таким ключом ещё нет — ставим, есть — снимаем. */
    suspend fun toggleReaction(roomID: String, item: FeedItem, key: String): Boolean {
        item.reactions.firstOrNull { it.key == key }?.myEventID?.let { return delete(roomID, it) }
        return post(EventType.REACTION, Relations.reaction(item.id, key), roomID, TransactionID.make()) != null
    }

    /** Переслать реплику в другой чат. Берём её из базы (расшифровав), правленый текст — как его видно в ленте. */
    suspend fun forward(item: FeedItem, roomID: String, target: String): String? {
        val account = account ?: return null
        val stored = withContext(Dispatchers.IO) { runCatching { account.store.event(item.id) }.getOrNull() } ?: return null
        val event = if (stored.type == EventType.ENCRYPTED) open(stored, roomID) ?: return null else stored
        val text = (item.body as? FeedItem.Body.Text)?.text
        val replaced = if (item.edited && text != null) JsonValue.of("msgtype" to "m.text", "body" to text) else null
        val out = Forwarding.content(event, replaced) ?: return null
        val eventID = post(out.type, out.content, target, TransactionID.make())
        log("пересылка ${if (eventID == null) "не прошла" else "ушла"}")
        return eventID
    }

    /** Чат «Избранное», если он уже заведён: в выборе чата для пересылки он первым. */
    val savedRoomID: String? get() = account?.let { runCatching { SavedMessages.load(it.store) }.getOrNull() }

    /** Жалоба администратору сервера на сообщение. */
    suspend fun report(roomID: String, eventID: String, reason: String): Boolean {
        val account = account ?: return false
        return runCatching { account.client.report(roomID, eventID, reason) }.onFailure { log("жалоба не прошла: $it") }.isSuccess
    }

    /** Заблокировать или разблокировать человека: его реплики прячутся сразу, сервер перестаёт их присылать. */
    suspend fun setBlocked(userID: String, blocked: Boolean): Boolean {
        val account = account ?: return false
        val me = me ?: return false
        val users = if (blocked) ignored + userID else ignored - userID
        if (users == ignored) return true
        val ok = runCatching { account.client.setIgnoredUsers(users, me, account.store) }.onFailure { log("блокировка не прошла: $it") }.isSuccess
        if (!ok) return false
        ignored = users
        refresh()
        revisionFlow.value += 1
        return true
    }

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

    // MARK: Контакты

    /** С кем уже есть личный чат. */
    suspend fun knownPeople(): List<Person> {
        val account = account ?: return emptyList()
        val me = me ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { People.known(account.store, me) }.getOrDefault(emptyList()) }
    }

    /** Номера из книжки → кто из них в Нютаге. Сервер берёт до 500 номеров за раз; ответ помним десять минут. */
    suspend fun lookup(phones: List<String>, fresh: Boolean = false): Map<String, String> {
        val account = account ?: return emptyMap()
        val now = System.currentTimeMillis()
        if (!fresh && now - matchedAt < 600_000) return matches
        val service = NyutagService(account.client)
        val found = LinkedHashMap<String, String>()
        for (part in phones.chunked(500)) {
            val got = runCatching { service.lookup(part) }.getOrNull() ?: break
            for ((phone, user) in got) found.putIfAbsent(phone, user)
        }
        matchedAt = now
        matches = found
        log("сверка книжки: номеров ${phones.size}, в Нютаге ${found.size}")
        return found
    }

    suspend fun searchPeople(term: String): List<Person> {
        val account = account ?: return emptyList()
        val me = me ?: return emptyList()
        val found = runCatching { account.client.searchUsers(term) }.getOrDefault(emptyList()).toMutableList()
        // Набран номер: сервер скажет, чей он. Номер виден только тому, кто его уже знает.
        if (term.count(Char::isDigit) >= 10 && People.phoneKey(term) != null) {
            val userID = runCatching { NyutagService(account.client).lookup(listOf(term)).values.firstOrNull() }.getOrNull()
            if (userID != null && userID != me) {
                val name = runCatching { account.client.profile(userID).displayName }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: ChatDigest.localpart(userID)
                found.removeAll { it.userID == userID }
                found.add(0, Person(userID, name, phone = term))
            }
        }
        val exact = runCatching { account.client.person(term, me.substringAfter(':')) }.getOrNull()
        if (exact != null && found.none { it.userID == exact.userID }) found.add(0, exact)
        return found.filter { it.userID != me }
    }

    /** Личный чат с человеком: свой, если уже есть (приглашение принимаем), иначе новый шифрованный. */
    suspend fun openDirect(userID: String): String? {
        val account = account ?: return null
        val me = me ?: return null
        knownPeople().firstOrNull { it.userID == userID }?.directRoom?.let { roomID ->
            val membership = withContext(Dispatchers.IO) { runCatching { account.store.room(roomID)?.membership }.getOrNull() }
            if (membership == RoomRecord.Membership.INVITE && runCatching { account.client.join(roomID) }.isFailure) return null
            return roomID
        }
        val roomID = runCatching { account.client.createDirectRoom(me, userID) }.getOrNull()
        log("личный чат ${if (roomID == null) "не создан" else "создан"}")
        return roomID
    }

    // MARK: Приглашения

    suspend fun createInvite(): InviteCode {
        val account = account ?: throw InviteException(InviteFailure.BROKEN)
        return try {
            NyutagService(account.client).createInvite()
        } catch (e: MatrixError) {
            throw InviteException(inviteFailure(e))
        } catch (e: Exception) {
            throw InviteException(InviteFailure.BROKEN)
        }
    }

    /** Свои коды; `null` — не удалось узнать. */
    suspend fun myInvites(): List<IssuedInvite>? {
        val account = account ?: return null
        return runCatching { NyutagService(account.client).invites() }.getOrNull()
    }

    suspend fun revokeInvite(code: String): Boolean {
        val account = account ?: return false
        return runCatching { NyutagService(account.client).revokeInvite(code) }.isSuccess
    }

    // MARK: Сообщества

    /** Каталог сообществ сервера; `null` — нет связи. */
    suspend fun findCommunities(term: String): List<PublicRoom>? {
        val account = account ?: return null
        val search = term.trim().ifEmpty { null }
        return try {
            account.client.publicCommunities(search = search, limit = 100).rooms.sortedByDescending { it.numJoinedMembers }
                .also { if (search == null) catalog = it else catalog = (it + catalog).distinctBy(PublicRoom::roomID) }
        } catch (e: Exception) {
            log("каталог сообществ: $e")
            null
        }
    }

    /** Группы сообщества (дети первого уровня) и можем ли мы их добавлять; `null` — не узнали. */
    suspend fun communityGroups(spaceID: String): Pair<List<PublicRoom>, Boolean>? {
        val account = account ?: return null
        val me = me ?: return null
        val page = runCatching { account.client.roomHierarchy(spaceID, maxDepth = 1) }.getOrNull() ?: return null
        val groups = page.rooms.map { it.room }.filter { it.roomID != spaceID && !it.isSpace }
        val power = runCatching { account.client.state(spaceID, "m.room.power_levels") }.getOrNull()
        val mine = power?.get("users")?.get(me)?.int ?: power?.get("users_default")?.int ?: 0
        val needed = power?.get("events")?.get("m.space.child")?.int ?: power?.get("state_default")?.int ?: 50
        return groups to (power != null && mine >= needed)
    }

    /** Открытое — вступаем сразу, закрытое (`knock`) — подаём заявку. */
    suspend fun joinCommunity(spaceID: String, joinRule: String?): CommunityJoin {
        val account = account ?: return CommunityJoin.FAILED
        return try {
            if (joinRule == "knock") {
                account.client.knock(spaceID, via = listOfNotNull(server))
                CommunityJoin.REQUESTED
            } else {
                account.client.join(spaceID, listOfNotNull(server))
                refresh()
                CommunityJoin.JOINED
            }
        } catch (e: Exception) {
            log("вступление в сообщество: $e")
            CommunityJoin.FAILED
        }
    }

    suspend fun leaveCommunity(spaceID: String): Boolean {
        val engine = engine ?: return false
        if (runCatching { engine.leave(spaceID, forget = true) }.isFailure) return false
        refresh()
        return true
    }

    /** Новое сообщество: пространство в каталоге сервера; закрытое пускает по заявке. Возвращает id. */
    suspend fun createCommunity(name: String, about: String, open: Boolean): String? {
        val account = account ?: return null
        val topic = about.trim().ifEmpty { null }
        val spaceID = runCatching {
            account.client.createRoom(CreateRoom(name = name, topic = topic, preset = "public_chat", encrypted = false, roomType = "m.space"))
        }.getOrNull() ?: return null
        runCatching { account.client.setRoomVisibility(spaceID, public = true) }
        if (!open) runCatching { account.client.setState(spaceID, "m.room.join_rules", content = JsonValue.of("join_rule" to "knock")) }
        log("сообщество создано, ${if (open) "открытое" else "закрытое"}")
        refresh()
        return spaceID
    }

    /** Группа внутри сообщества: шифрованная комната, входят участники сообщества. Возвращает id. */
    suspend fun createGroup(name: String, spaceID: String): String? {
        val account = account ?: return null
        val server = server ?: return null
        val roomID = runCatching { account.client.createRoom(CreateRoom(name = name)) }.getOrNull() ?: return null
        val rule = JsonValue.of("join_rule" to "restricted",
                                "allow" to JsonValue.list(JsonValue.of("type" to "m.room_membership", "room_id" to spaceID)))
        try {
            account.client.addSpaceChild(spaceID, roomID, listOf(server))
            runCatching { account.client.setSpaceParent(roomID, spaceID, listOf(server), canonical = true) }
            runCatching { account.client.setState(roomID, "m.room.join_rules", content = rule) }
        } catch (e: Exception) {
            runCatching { account.client.leave(roomID) }
            return null
        }
        log("группа сообщества создана")
        return roomID
    }

    /** Группа из сообщества: уже в ней — просто открываем, иначе входим. */
    suspend fun joinGroup(roomID: String): Boolean {
        val account = account ?: return false
        if (linesFlow.value.any { it.id == roomID && it.membership == RoomRecord.Membership.JOIN }) return true
        return runCatching { account.client.join(roomID, listOfNotNull(server)) }.isSuccess
    }

    // MARK: Избранное

    /** Чат с собой: известный — открываем (имя по умолчанию переводим на язык телефона), иначе заводим новый. */
    suspend fun openSaved(name: String, topic: String): String? {
        val account = account ?: return null
        val me = me ?: return null
        val known = withContext(Dispatchers.IO) { runCatching { SavedMessages.load(account.store) }.getOrNull() }
        val membership = known?.let { withContext(Dispatchers.IO) { runCatching { account.store.room(it)?.membership }.getOrNull() } }
        if (known != null && (membership == RoomRecord.Membership.JOIN || membership == RoomRecord.Membership.INVITE)) {
            if (membership == RoomRecord.Membership.INVITE) runCatching { account.client.join(known) }
            val title = linesFlow.value.firstOrNull { it.id == known }?.title
            if (title != null && SavedMessages.needsRename(title, name)) {
                runCatching { account.client.setState(known, EventType.NAME, content = JsonValue.of("name" to name)) }
            }
            return known
        }
        val roomID = runCatching { account.client.createSavedMessages(name, topic, me, account.store) }.getOrNull()
        log("избранное ${if (roomID == null) "не создано" else "создано"}")
        return roomID
    }

    // MARK: Профиль

    suspend fun profile(userID: String): Profile? {
        val account = account ?: return null
        return runCatching { account.client.profile(userID) }.getOrNull()
    }

    /** Указан ли свой номер (сам номер сервер не отдаёт); `null` — не удалось узнать. */
    suspend fun hasPhone(): Boolean? {
        val account = account ?: return null
        return runCatching { NyutagService(account.client).hasPhone() }.getOrNull()
    }

    suspend fun setPhone(phone: String): Boolean {
        val account = account ?: return false
        return runCatching { NyutagService(account.client).setPhone(phone) }.isSuccess
    }

    /** Имя и «о себе»: отправляем только то, что изменилось. */
    suspend fun saveProfile(name: String, about: ProfileAbout, old: Profile): Boolean {
        val account = account ?: return false
        val me = me ?: return false
        return try {
            val clean = name.trim()
            if (clean.isNotEmpty() && clean != old.displayName) account.client.setDisplayName(me, clean)
            val edits = about.edits(ProfileAbout(old))
            if (edits.isNotEmpty()) account.client.applyProfileEdits(me, edits, account.client.profileFieldsAPI())
            true
        } catch (e: Exception) {
            log("профиль не сохранён: $e")
            false
        }
    }

    /** Домен своей учётки: через него входим в комнаты сервера. */
    private val server: String? get() = me?.substringAfter(':', "")?.ifEmpty { null }

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

        fun inviteFailure(error: MatrixError): InviteFailure = when {
            error.errcode == "NYUTAG_INVITE_LIMIT" || error.errcode == "M_LIMIT_EXCEEDED" -> InviteFailure.LIMIT
            error.kind is MatrixError.Kind.Network -> InviteFailure.NO_NETWORK
            else -> InviteFailure.BROKEN
        }

        fun failure(error: MatrixError): SignInFailure = when {
            error.status == 429 || error.errcode == "M_LIMIT_EXCEEDED" -> SignInFailure.TOO_OFTEN
            error.errcode == "M_FORBIDDEN" || error.errcode == "M_NOT_FOUND" || error.status == 403 -> SignInFailure.WRONG_PASSWORD
            error.kind is MatrixError.Kind.Network -> SignInFailure.NO_NETWORK
            else -> SignInFailure.BROKEN
        }
    }
}
