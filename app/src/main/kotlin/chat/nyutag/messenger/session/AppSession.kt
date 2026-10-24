// Нютаг. Состояние приложения: вход, список чатов из базы, синхронизация и шифрование, отправка.
// Экраны читают StateFlow; хранилище синхронное, поэтому всё, что трогает базу, идёт на Dispatchers.IO.

package chat.nyutag.messenger.session

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.os.Build
import android.util.Log
import chat.nyutag.core.auth.AccountKeeper
import chat.nyutag.core.auth.Shelf
import chat.nyutag.core.auth.SignedInAccount
import chat.nyutag.core.calls.CallEventType
import chat.nyutag.core.calls.CallCenter
import chat.nyutag.core.calls.CallEnvironment
import chat.nyutag.core.calls.CallMessage
import chat.nyutag.core.calls.CallSignal
import chat.nyutag.core.calls.RelayLink
import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.crypto.PasswordUnlockOutcome
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.sendToDevice
import chat.nyutag.core.crypto.unlockWithPassword
import chat.nyutag.core.media.Attachment
import chat.nyutag.messenger.data.OutgoingFile
import chat.nyutag.messenger.data.SoyomboSticker
import chat.nyutag.core.media.uploadEncrypted
import chat.nyutag.core.rooms.Sticker
import chat.nyutag.core.rooms.StickerPack
import chat.nyutag.core.rooms.Stickers
import chat.nyutag.core.rooms.addSticker
import chat.nyutag.core.rooms.removeSticker
import chat.nyutag.core.media.MXC
import chat.nyutag.core.media.MediaCache
import chat.nyutag.core.media.ThumbnailMethod
import chat.nyutag.core.media.uploadMedia
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.nyutag.IssuedInvite
import chat.nyutag.core.nyutag.InviteCode
import chat.nyutag.core.nyutag.NyutagService
import chat.nyutag.core.push.PushPreview
import chat.nyutag.core.push.PushReader
import chat.nyutag.core.push.ensureRingPushRule
import chat.nyutag.core.push.registerAndroidPush
import chat.nyutag.core.push.removeAndroidPush
import chat.nyutag.core.rooms.AccountDevice
import chat.nyutag.core.rooms.ChatDigest
import chat.nyutag.core.rooms.ChatFeed
import chat.nyutag.core.rooms.ChatLine
import chat.nyutag.core.rooms.FeedItem
import chat.nyutag.core.rooms.Forwarding
import chat.nyutag.core.rooms.IgnoredUsers
import chat.nyutag.core.rooms.AutoDelete
import chat.nyutag.core.rooms.ChatLayout
import chat.nyutag.core.rooms.Search
import chat.nyutag.core.rooms.invite
import chat.nyutag.core.rooms.save
import chat.nyutag.core.rooms.setRoomMuted
import chat.nyutag.core.store.SearchHit
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
import chat.nyutag.core.rooms.creators
import chat.nyutag.core.rooms.createRoom
import chat.nyutag.core.rooms.deleteDevice
import chat.nyutag.core.rooms.devices
import chat.nyutag.core.rooms.dropForgotten
import chat.nyutag.core.rooms.event
import chat.nyutag.core.rooms.join
import chat.nyutag.core.rooms.joinedMembers
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
import chat.nyutag.core.rooms.setAvatarURL
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
import chat.nyutag.messenger.calls.CallDesk
import chat.nyutag.messenger.calls.OpusCodecs
import chat.nyutag.messenger.calls.H264Codecs
import chat.nyutag.messenger.data.AndroidSql
import chat.nyutag.messenger.data.KeystoreVault
import chat.nyutag.messenger.data.Pictures
import chat.nyutag.messenger.doors.DoorHub
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AppSession(context: Context, private val doors: DoorHub) {
    enum class Phase { LAUNCHING, SIGNED_OUT, SIGNED_IN }

    enum class SignInFailure { WRONG_PASSWORD, TOO_OFTEN, NO_NETWORK, BROKEN }

    enum class InviteFailure { LIMIT, NO_NETWORK, BROKEN }

    enum class CommunityJoin { JOINED, REQUESTED, FAILED }

    class InviteException(val failure: InviteFailure) : Exception(failure.name)

    class SignInException(val failure: SignInFailure) : Exception(failure.name)



    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keeper = AccountKeeper(KeystoreVault(context), Shelf(context.filesDir, AndroidSql::open)) { homeserver, token ->
        doors.makeClient(homeserver, token)
    }

    /** Звонки аккаунта: экран звонка читает стойку. */
    val calls = CallDesk(context.applicationContext)
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
    private val historyNeedsSignInFlow = MutableStateFlow(false)
    /** Установка до 24.10 запомнила «старые сообщения закрыты ключом»: пароля в памяти нет, открыть историю может
     *  только новый вход паролем (ключ восстановления больше не спрашиваем). */
    val historyNeedsSignIn: StateFlow<Boolean> get() = historyNeedsSignInFlow

    @Volatile var me: String? = null
        private set
    /** Свой аватар (`mxc://…`) или `null` — без фото. */
    private val myAvatarFlow = MutableStateFlow<String?>(null)
    val myAvatar: StateFlow<String?> get() = myAvatarFlow
    /** Кэш медиа на диске (cacheDir: система может его чистить) и раскодированные аватары в памяти, по объёму. */
    private val mediaDirectory = File(context.cacheDir, "media")
    @Volatile private var media: MediaCache? = null
    private val avatarMemory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val blockedFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Заблокированные: их реплики спрятаны, сервер их не присылает. */
    val blocked: StateFlow<Set<String>> get() = blockedFlow
    private var ignored: Set<String>
        get() = blockedFlow.value
        set(value) { blockedFlow.value = value }
    private val mutedFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Чаты без звука: пуши о них не показываем, в списке — перечёркнутый колокольчик. */
    val mutedRooms: StateFlow<Set<String>> get() = mutedFlow
    val muted: Set<String> get() = mutedFlow.value
    private val layoutFlow = MutableStateFlow(ChatLayout())
    /** Закреплённые чаты и папки; общий формат с iOS, живёт в данных аккаунта. */
    val layout: StateFlow<ChatLayout> get() = layoutFlow
    /** Идёт запись раскладки: /sync до её конца принёс бы старую и откатил галочку. */
    private val layoutWrites = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var indexing = false
    @Volatile private var indexedCount = -1

    /** Флаги без секретов: карточка «старые сообщения закрыты» должна пережить перезапуск. */
    private val flags = context.getSharedPreferences("nyutag.history", Context.MODE_PRIVATE)
    /** Токен FCM этого телефона: нужен и при входе, и при выходе, когда Firebase уже может не ответить. */
    private val pushFlags = context.getSharedPreferences("nyutag.push", Context.MODE_PRIVATE)
    private val soyomboFlags = context.getSharedPreferences("nyutag.soyombo", Context.MODE_PRIVATE)
    /** Чат, открытый на экране, пока приложение видно: его пуши не показываем. */
    @Volatile var visibleRoom: String? = null
    /** Шифрование этого входа поднято (или не поднялось): пуш расшифровывает той же машиной, что и список. */
    @Volatile private var cryptoReady = CompletableDeferred<Unit>()

    private var account: SignedInAccount? = null
    private var engine: SyncEngine? = null
    @Volatile private var machine: CryptoMachine? = null
    private var pump: Job? = null
    private val opened = ConcurrentHashMap<String, Event>()
    private val autoJoined = HashSet<String>()
    /** Свежие шифрованные события, ключ к которым ещё не пришёл: могут оказаться сигналами звонка. */
    private var lockedSignals: List<Pair<Event, String>> = emptyList()
    private val refreshing = Mutex()
    /** Сверка книжки с сервером не чаще раза в десять минут: у сервера суточный предел. */
    private var matchedAt = 0L
    private var matches: Map<String, String> = emptyMap()

    // MARK: Вход

    /** Запуск: сохранённый вход поднимаем без сети, список строится из базы сразу. */
    fun launch() {
        if (phaseFlow.value != Phase.LAUNCHING) return
        scope.launch {
            val began = System.currentTimeMillis()
            val restored = withContext(Dispatchers.IO) { runCatching { keeper.restore() }.getOrNull() }
            log("запуск: вход поднят за ${System.currentTimeMillis() - began} мс")
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

    /** Меню закрывается раньше, чем выход закончится, и отменяет свою корутину — выход доводится до конца. */
    suspend fun signOut() = withContext(NonCancellable) {
        val account = account ?: return@withContext
        pushFlags.getString("token", null)?.let { token ->
            runCatching { account.client.removeAndroidPush(token, BuildConfig.DEBUG) }.onFailure { log("пушер не снят: $it") }
        }
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
        withContext(NonCancellable) {
            stop()
            withContext(Dispatchers.IO) {
                runCatching { account.store.close() }
                runCatching { keeper.forget(account.session.userID) }
            }
            clear()
        }
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
        blockedFlow.value = emptySet()
        mutedFlow.value = emptySet()
        layoutFlow.value = ChatLayout()
        indexedCount = -1
        catalog = emptyList()
        typingFlow.value = emptyMap()
        historyFlow.value = null
        historyNeedsSignInFlow.value = false
        matchedAt = 0L
        matches = emptyMap()
        phaseFlow.value = Phase.SIGNED_OUT
    }

    private suspend fun start(account: SignedInAccount, password: String?) {
        this.account = account
        me = account.session.userID
        media = MediaCache(account.client, mediaDirectory)
        scope.launch { myAvatarFlow.value = profile(account.session.userID)?.avatarURL?.ifEmpty { null } }
        val engine = SyncEngine(account.client, account.store, account.session, scope)
        this.engine = engine
        cryptoReady = CompletableDeferred()
        phaseFlow.value = Phase.SIGNED_IN
        val began = System.currentTimeMillis()
        refresh()
        log("запуск: список за ${System.currentTimeMillis() - began} мс")
        try {
            val machine = CryptoMachine.create(account.client, account.store, account.session)
            this.machine = machine
            engine.addObserver(machine)
            // Расшифровка своя, без сети: список с текстом вместо «Новое сообщение» — до публикации ключей.
            refresh()
            log("запуск: машина и расшифрованный список за ${System.currentTimeMillis() - began} мс")
            runCatching { machine.publishKeys(null) }
            if (password != null) {
                // Пароль уже проверен сервером: им же запираем или открываем хранилище ключей. Заперто не им —
                // заводим новое под паролем (владелец 23.10: ключ восстановления не спрашиваем никогда).
                val outcome = runCatching { machine.unlockWithPassword(password) }.getOrNull()
                historyFlow.value = outcome
                if (outcome != null) forgetLockedHistory(account.session.userID)
                log("история по паролю: ${outcome ?: "ошибка"}")
            } else {
                historyNeedsSignInFlow.value = flags.contains("locked.${account.session.userID}")
            }
        } catch (e: Exception) {
            // Без машины переписка в шифрованных чатах остаётся под замком, остальное работает.
            log("машина шифрования не поднялась: $e")
        }
        cryptoReady.complete(Unit)
        log("это устройство ${account.session.deviceID}")
        startCalls(account)
        askPushToken()
        pump = scope.launch {
            engine.updates.collect { update ->
                absorbCalls(update.response)
                noteTyping(update.response)
                refresh()
                revisionFlow.value += 1
                scope.launch { indexSearch() }
            }
        }
        engine.start()
        refresh()
        scope.launch { dropForgotten() }
    }

    /** Приложение снова на экране: за время сна чаты могли удалить на другом устройстве. */
    fun appBecameActive() {
        scope.launch { dropForgotten() }
    }

    /** Чаты, удалённые на другом устройстве, убираем из списка: /sync о них молчит. */
    private suspend fun dropForgotten() {
        val engine = engine ?: return
        val gone = withContext(Dispatchers.IO) { runCatching { engine.dropForgotten() }.getOrNull() } ?: return
        if (gone.isEmpty()) return
        log("покинуты на другом устройстве: ${gone.size}")
        refresh()
        revisionFlow.value += 1
    }

    private fun stop() {
        pump?.cancel()
        pump = null
        lockedSignals = emptyList()
        val calls = calls
        scope.launch { calls.detach() }
        engine?.stop()
        engine = null
        machine = null
        media = null
        myAvatarFlow.value = null
        avatarMemory.evictAll()
    }

    // MARK: Пуши

    /** Токен у Firebase просим при каждом входе: он мог смениться, пока приложение было закрыто. */
    private fun askPushToken() {
        runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { pushToken(it) } }
            .onFailure { log("Firebase не настроен, пушей не будет: $it") }
    }

    /** Новый токен FCM: пушер на сервере пишем сразу, повторная запись того же токена ничего не плодит. */
    fun pushToken(token: String) {
        pushFlags.edit().putString("token", token).apply()
        val account = account ?: return
        scope.launch {
            runCatching {
                account.client.registerAndroidPush(token, BuildConfig.DEBUG, deviceName, java.util.Locale.getDefault().language)
                account.client.ensureRingPushRule()
            }.onSuccess { log("пушер записан") }.onFailure { log("пушер не записан: $it") }
        }
    }

    /** Что показать по пушу. Процесс мог быть убит: вход поднимаем из хранилища, синхронизация тоже пойдёт
     *  (по ней придёт и звонок). `null` — показывать нечего: вышли, чат открыт, заглушён, сигнал звонка. */
    suspend fun pushPreview(roomID: String, eventID: String): PushPreview? {
        launch()
        if (phaseFlow.first { it != Phase.LAUNCHING } != Phase.SIGNED_IN) return null
        val account = account ?: return null
        if (roomID == visibleRoom || roomID in muted) return null
        val began = System.currentTimeMillis()
        // В фоне дверь могла запереться, а цикл /sync — уснуть в паузе после ошибок: будим обоих сразу.
        doors.pushArrived()
        withTimeoutOrNull(5_000) { cryptoReady.await() }
        engine?.wake()
        val event = withContext(Dispatchers.IO) { runCatching { account.store.event(eventID) }.getOrNull() }
            ?: pushEvent(account, roomID, eventID) ?: return neutralPreview(roomID, eventID)
        if (event.sender == account.session.userID || event.sender in ignored) return null
        var clear = if (event.type == EventType.ENCRYPTED) open(event, roomID) else event
        // Ключ сессии нового устройства собеседника часто идёт следующей синхронизацией после самого сообщения.
        while (clear == null) {
            val left = began + 14_000 - System.currentTimeMillis()
            val seen = revisionFlow.value
            if (left <= 0 || withTimeoutOrNull(left) { revisionFlow.first { it != seen } } == null) break
            clear = open(event, roomID)
        }
        log("пуш: ${if (clear == null) "не раскрыт" else "раскрыт"} через ${System.currentTimeMillis() - began} мс")
        val kind = if (clear == null) PushPreview.Kind.Locked else PushReader.kind(clear, account.session.userID)
        if (kind == PushPreview.Kind.Signal && clear?.type == CallEventType.RING) {
            // Звонок: держим процесс, пока синхронизация не донесёт его до стойки — она сама покажет входящий.
            val rung = withTimeoutOrNull(10_000) { calls.current.first { it != null } } != null
            log("пуш звонка: ${if (rung) "звонок на стойке" else "звонок не дошёл"}")
        }
        if (kind == PushPreview.Kind.Signal || kind == PushPreview.Kind.Other) return null
        val (record, senderName) = withContext(Dispatchers.IO) {
            runCatching { account.store.room(roomID) }.getOrNull() to
                event.sender?.let { runCatching { account.store.state(roomID, EventType.MEMBER, it)?.get("displayname")?.string }.getOrNull() }
        }
        return PushPreview(roomID, eventID, event.sender, senderName ?: event.sender?.let(ChatDigest::localpart),
                           title(roomID).ifEmpty { record?.name }, record?.isDirect ?: false, kind)
    }

    /** Событие не прочитали (нет связи, вышло время): как iOS, показываем «Новое сообщение» с названием чата. */
    fun neutralPreview(roomID: String, eventID: String): PushPreview? {
        if (roomID == visibleRoom || roomID in muted) return null
        val record = account?.let { runCatching { it.store.room(roomID) }.getOrNull() }
        return PushPreview(roomID, eventID, null, null, title(roomID).ifEmpty { record?.name }, record?.isDirect ?: false,
                           PushPreview.Kind.Locked)
    }

    /** Событие пуша с сервера: короткие попытки, снятая попытка рвёт соединение — висящее из пула не держит до конца. */
    private suspend fun pushEvent(account: SignedInAccount, roomID: String, eventID: String): Event? {
        repeat(3) { attempt ->
            if (attempt > 0) delay(1_000)
            doors.appBecameActive()  // гонка не удалась (сеть процесса, поднятого пушем, просыпается не сразу) — новая
            withTimeoutOrNull(5_000) {
                runCatching { account.client.event(roomID, eventID) }.onFailure { log("событие пуша: $it") }.getOrNull()
            }?.let { return it }
        }
        log("событие пуша не пришло")
        return null
    }
    // MARK: Старая история

    /** Флаг без секретов от прежних версий; новые его только снимают. */
    private fun forgetLockedHistory(userID: String) = flags.edit().remove("locked.$userID").apply()

    // MARK: Список

    private suspend fun refresh() = refreshing.withLock {
        val account = account ?: return@withLock
        val me = me ?: return@withLock
        withContext(Dispatchers.IO) {
            runCatching { IgnoredUsers.load(account.store) }.getOrNull()?.let { ignored = it }
            runCatching { MutedRooms.load(account.store) }.getOrNull()?.let { if (it != mutedFlow.value) mutedFlow.value = it }
            if (layoutWrites.get() == 0) runCatching { ChatLayout.load(account.store) }.getOrNull()?.let { if (it != layoutFlow.value) layoutFlow.value = it }
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

    /** Файл, картинка, ролик или голосовое в чат: в шифрованный — шифртекстом (ключ уедет внутри реплики),
     *  в открытый — как есть. Формат общий с iOS. */
    suspend fun sendAttachment(roomID: String, file: OutgoingFile, transactionID: String): String? {
        val account = account ?: return null
        val encrypted = withContext(Dispatchers.IO) { runCatching { account.store.room(roomID)?.isEncrypted }.getOrNull() } ?: false
        suspend fun upload(data: ByteArray, type: String, name: String): Attachment.Source =
            if (encrypted) Attachment.Source.Sealed(account.client.uploadEncrypted(data))
            else Attachment.Source.Plain(account.client.uploadMedia(data, type, name))
        val source = try {
            upload(file.data, file.mimetype, file.name)
        } catch (e: Exception) {
            log("загрузка вложения не прошла: $e")
            return null
        }
        // Миниатюра ролика — тем же способом, что и сам файл. Без неё ролик всё равно уйдёт.
        val preview = file.thumbnail?.let { thumb ->
            runCatching { upload(thumb.data, "image/jpeg", "thumb.jpg") }.getOrNull()
                ?.let { Attachment.Preview(it, size = thumb.data.size, width = thumb.width, height = thumb.height) }
        }
        val content = Attachment.content(source, file.name, file.mimetype, file.data.size, file.width, file.height,
                                         file.viewOnce, preview, file.duration, file.voice)
        return post(EventType.MESSAGE, content, roomID, transactionID)
    }

    /** Содержимое вложения, уже расшифрованное. `preview` — миниатюра для ленты, если она есть. */
    suspend fun attachmentData(attachment: Attachment, preview: Boolean): ByteArray? {
        val media = media ?: return null
        return runCatching {
            when (val source = if (preview) attachment.thumbnail ?: attachment.source else attachment.source) {
                is Attachment.Source.Sealed -> media.decrypted(source.file)
                // Открытую картинку без своей миниатюры уменьшит сервер.
                is Attachment.Source.Plain -> media.data(source.address,
                    if (preview && attachment.thumbnail == null && !attachment.isSticker) MediaCache.Size(800, 800) else null)
            }
        }.onFailure { log("вложение не скачалось: $it") }.getOrNull()
    }

    // MARK: Стикеры

    suspend fun myStickers(): StickerPack {
        val store = account?.store ?: return StickerPack(STICKER_PACK, emptyList())
        return withContext(Dispatchers.IO) { runCatching { Stickers.mine(store, STICKER_PACK) }.getOrNull() } ?: StickerPack(STICKER_PACK, emptyList())
    }

    /** Картинка из галереи в свой набор: PNG до 512 точек, загружается открыто (стикер — не секрет). */
    suspend fun addSticker(png: ByteArray, name: String, width: Int, height: Int): Sticker? {
        val account = account ?: return null
        val mxc = runCatching { account.client.uploadMedia(png, "image/png", "$name.png") }.getOrNull() ?: return null
        runCatching { media?.store(png, mxc) }
        return addSticker(Sticker(name, mxc, mimetype = "image/png", width = width, height = height))
    }

    /** Чужой стикер из ленты в свой набор: тот же адрес, без повторной загрузки. */
    suspend fun addSticker(sticker: Sticker): Sticker? {
        val account = account ?: return null
        val me = me ?: return null
        return runCatching { account.client.addSticker(sticker, me, STICKER_PACK, account.store) }
            .onFailure { log("стикер не добавился: $it") }.getOrNull()?.also { revisionFlow.value += 1 }
    }

    suspend fun removeSticker(shortcode: String): Boolean {
        val account = account ?: return false
        val me = me ?: return false
        if (runCatching { account.client.removeSticker(shortcode, me, account.store) }.isFailure) return false
        revisionFlow.value += 1
        return true
    }

    /** Стикер встроенного набора с соёмбо: при первой отправке картинка загружается, адрес запоминается для учётки. */
    suspend fun soyomboSticker(item: SoyomboSticker): Sticker? {
        val account = account ?: return null
        val me = me ?: return null
        val key = "${SoyomboSticker.VERSION}.$me.${item.shortcode}"
        val side = SoyomboSticker.SIDE
        soyomboFlags.getString(key, null)?.let(MXC::parse)?.let {
            return Sticker(item.shortcode, it, item.body, "image/png", side, side)
        }
        val png = withContext(Dispatchers.Default) { item.png() }
        val mxc = runCatching { account.client.uploadMedia(png, "image/png", item.shortcode + ".png") }.getOrNull() ?: return null
        runCatching { media?.store(png, mxc) }
        soyomboFlags.edit().putString(key, mxc.toString()).apply()
        return Sticker(item.shortcode, mxc, item.body, "image/png", side, side)
    }

    suspend fun sendSticker(roomID: String, sticker: Sticker, transactionID: String): String? =
        post(EventType.STICKER, sticker.content, roomID, transactionID)

    /** Картинка стикера целиком (они маленькие), из кэша. */
    suspend fun stickerData(mxc: MXC): ByteArray? = runCatching { media?.data(mxc) }.getOrNull()

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

    fun isBlocked(userID: String): Boolean = userID in ignored

    // MARK: Раскладка списка

    fun setPinned(roomID: String, pinned: Boolean) = change { it.pinning(roomID, pinned) }

    /** Новая папка; `with` — чат, из меню которого её завели, сразу в ней. */
    fun addFolder(name: String, with: String? = null) = change { it.addingFolder(name = name, with = with) }

    fun renameFolder(id: String, to: String) = change { it.renamingFolder(id, to) }

    fun deleteFolder(id: String) = change { it.removingFolder(id) }

    fun place(roomID: String, folderID: String, inside: Boolean) = change { it.placing(roomID, folderID, inside) }

    /** Сразу на экране, следом на сервер; сервер не принял — возвращаем как было. */
    private fun change(edit: (ChatLayout) -> ChatLayout) {
        val account = account ?: return
        val me = me ?: return
        val old = layoutFlow.value
        val new = edit(old)
        if (new == old) return
        layoutFlow.value = new
        layoutWrites.incrementAndGet()
        scope.launch {
            val ok = runCatching { account.client.save(new, me, account.store) }.onFailure { log("раскладка не сохранилась: $it") }.isSuccess
            layoutWrites.decrementAndGet()
            if (!ok) layoutFlow.compareAndSet(new, old)
        }
    }

    // MARK: Звук, выход, участники

    suspend fun setMuted(roomID: String, quiet: Boolean): Boolean {
        val account = account ?: return false
        val ok = runCatching { account.client.setRoomMuted(roomID, quiet, account.store) }.onFailure { log("звук не переключился: $it") }.isSuccess
        if (ok) mutedFlow.value = if (quiet) mutedFlow.value + roomID else mutedFlow.value - roomID
        return ok
    }

    /** Покинуть чат и забыть его: из списка уходит сразу и на всех устройствах. */
    suspend fun leaveChat(roomID: String): Boolean {
        val engine = engine ?: return false
        val ok = runCatching { engine.leave(roomID, forget = true) }.onFailure { log("выход не прошёл: $it") }.isSuccess
        if (ok) {
            if (layoutFlow.value.isPinned(roomID)) setPinned(roomID, false)
            refresh()
            revisionFlow.value += 1
        }
        return ok
    }

    /** Новая шифрованная группа с выбранными людьми. Возвращает id. */
    suspend fun createChat(name: String, members: List<String>): String? {
        val account = account ?: return null
        val roomID = runCatching { account.client.createRoom(CreateRoom(name = name.trim(), invite = members)) }
            .onFailure { log("группа не создана: $it") }.getOrNull() ?: return null
        log("группа создана, позвано ${members.size}")
        refresh()
        return roomID
    }

    /** С кем есть общие чаты: из них зовут в группу. */
    suspend fun acquaintances(): List<Person> {
        val account = account ?: return emptyList()
        val me = me ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { People.acquaintances(account.store, me) }.getOrDefault(emptyList()).filter { it.userID !in ignored }
        }
    }

    suspend fun members(roomID: String): List<Person> {
        val account = account ?: return emptyList()
        val me = me ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { People.members(roomID, account.store, me) }.getOrDefault(emptyList()) }
    }

    /** Собеседник личного чата. */
    suspend fun partner(roomID: String): String? {
        val account = account ?: return null
        val me = me ?: return null
        return withContext(Dispatchers.IO) { runCatching { People.partner(roomID, account.store, me) }.getOrNull() }
    }

    /** Позвать людей в группу; `false` — хоть одного сервер не принял. */
    suspend fun invite(users: List<String>, roomID: String): Boolean {
        val account = account ?: return false
        var ok = true
        for (user in users) {
            if (runCatching { account.client.invite(roomID, user) }.onFailure { log("приглашение в группу не прошло: $it") }.isFailure) ok = false
        }
        return ok
    }

    // MARK: Автоудаление

    suspend fun autoDelete(roomID: String): AutoDelete {
        val account = account ?: return AutoDelete()
        return withContext(Dispatchers.IO) { runCatching { AutoDelete.load(roomID, account.store) }.getOrDefault(AutoDelete()) }
    }

    /** Новый таймер с этого момента; `null` — сервер не принял (например, не хватает прав в группе). */
    suspend fun setAutoDelete(ttl: Int, roomID: String): AutoDelete? {
        val account = account ?: return null
        val rule = AutoDelete.starting(ttl)
        return runCatching { account.client.setState(roomID, EventType.AUTODELETE, content = rule.json); rule }
            .onFailure { log("таймер не сменился: $it") }.getOrNull()
    }

    // MARK: Поиск

    /** Реплика, к которой открыть чат (из поиска): чат забирает её при открытии. */
    @Volatile private var focus: Pair<String, String>? = null

    fun focus(roomID: String, eventID: String) { focus = roomID to eventID }

    fun takeFocus(roomID: String): String? = focus?.takeIf { it.first == roomID }?.second.also { focus = null }

    /** Реплики по запросу, только из чатов списка. */
    suspend fun search(query: String, roomID: String? = null): List<SearchHit> {
        val account = account ?: return emptyList()
        val known = linesFlow.value.mapTo(HashSet()) { it.id }
        return withContext(Dispatchers.IO) {
            runCatching { Search.find(query, roomID, account.store, 80) }.getOrDefault(emptyList()).filter { it.roomID in known }
        }
    }

    /** Фоновый проход индекса: только когда в базе прибавились события и предыдущий проход закончен. */
    private suspend fun indexSearch() {
        val account = account ?: return
        if (indexing) return
        indexing = true
        try {
            withContext(Dispatchers.IO) {
                val count = runCatching { account.store.eventCount() }.getOrDefault(0)
                if (count == indexedCount) return@withContext
                val added = runCatching { Search.index(account.store, ::open) }.getOrDefault(0)
                indexedCount = count
                if (added > 0) log("поиск: в индекс легло $added")
            }
        } finally {
            indexing = false
        }
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
            val server = (e as? E2EEException.Server)?.error ?: e as? MatrixError
            if (server?.errcode == "M_FORBIDDEN") scope.launch { dropForgotten() }
            null
        }
    }

    /** «Набираю текст»: сервер сам снимет отметку через 20 секунд, если не продлить. */
    suspend fun setTyping(roomID: String, typing: Boolean) {
        val account = account ?: return
        val me = me ?: return
        runCatching { account.client.setTyping(roomID, me, typing) }
    }

    /** Последнее событие комнаты любого вида, если оно чужое: отметка на нём обнуляет счёт сервера,
     *  в том числе за шифрованные сигналы звонка, которых нет в ленте. */
    suspend fun lastForeignEvent(roomID: String): String? {
        val account = account ?: return null
        val me = me ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { account.store.events(roomID, 1).lastOrNull()?.takeIf { it.sender != me }?.eventID }.getOrNull()
        }
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

    // MARK: Звонки

    enum class CallKind { NONE, DIRECT, GROUP }

    /** Позвонить в комнату: один на один, если в ней ровно один кроме нас, иначе групповой (только голос). */
    suspend fun startCall(roomID: String, video: Boolean = false) {
        when (callKind(roomID)) {
            CallKind.DIRECT -> callPeer(roomID)?.let { calls.dial(roomID, it, video) }
            CallKind.GROUP -> calls.dialGroup(roomID)
            CallKind.NONE -> {}
        }
    }

    /** Групповой — только в шифрованной комнате: ключи кадров идут шифром комнаты. */
    suspend fun callKind(roomID: String): CallKind {
        val others = callMembers(roomID)
        if (others.size == 1) return CallKind.DIRECT
        val account = account ?: return CallKind.NONE
        val encrypted = withContext(Dispatchers.IO) { runCatching { account.store.room(roomID)?.isEncrypted }.getOrNull() } ?: false
        return if (others.size > 1 && encrypted) CallKind.GROUP else CallKind.NONE
    }

    private suspend fun callPeer(roomID: String): String? = callMembers(roomID).singleOrNull()

    private suspend fun callMembers(roomID: String): List<String> {
        val account = account ?: return emptyList()
        val me = me ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { account.store.stateAll(roomID, EventType.MEMBER) }.getOrDefault(emptyList())
                .filter { it.stateKey != me && (it.content["membership"]?.string ?: "") in setOf("join", "invite") }
                .map { it.stateKey }
        }
    }

    private fun startCalls(account: SignedInAccount) {
        val client = account.client
        val env = CallEnvironment(
            me = account.session.userID, deviceID = account.session.deviceID,
            post = { signal, roomID -> if (!postSignal(signal, roomID)) throw IllegalStateException("сигнал не ушёл") },
            openTransport = { slot -> RelayLink.open(client, slot) },
            makeVoice = { calls.makeVoice() },
            codecs = OpusCodecs,
            videoCodecs = H264Codecs,
            makeCamera = { calls.makeCamera() },
            // «У меня звонит» — открытым сообщением всем устройствам звонящего: в ленту и пуши оно не попадает.
            notify = { signal, user ->
                val sent = runCatching {
                    client.sendToDevice(signal.eventType, mapOf(user to mapOf("*" to signal.content)))
                }.isSuccess
                log("сигнал ${signal.eventType} ${if (sent) "ушёл" else "не ушёл"}")
            },
        )
        calls.name = { roomID, peer -> title(roomID).ifEmpty { ChatDigest.localpart(peer) } }
        calls.memberName = { roomID, user -> displayName(roomID, user) }
        calls.attach(CallCenter(env))
    }

    /** Будильник идёт открытым всегда (по нему шлюз будит телефон пушем), остальное — как обычные события комнаты. */
    private suspend fun postSignal(signal: CallSignal, roomID: String): Boolean {
        val account = account ?: return false
        val sent = if (!signal.wantsRoomEncryption) {
            runCatching { account.client.send(roomID, signal.eventType, signal.content, TransactionID.make()) }.isSuccess
        } else {
            post(signal.eventType, signal.content, roomID, TransactionID.make()) != null
        }
        log("сигнал ${signal.eventType} ${if (sent) "ушёл" else "не ушёл"}")
        return sent
    }

    /** Сигналы звонков из свежей синхронизации. Старше двух минут не смотрим: звонок уже кончился, а расшифровка
     *  всей истории первой синхронизации стоила бы дорого. Ключ сеанса нового устройства собеседника часто
     *  приходит синхронизацией позже самого сигнала: нераскрытые свежие события пробуем снова при следующих. */
    private suspend fun absorbCalls(response: SyncResponse) {
        val fresh = System.currentTimeMillis() - 120_000
        val waiting = lockedSignals.filter { (it.first.originServerTS ?: 0) > fresh }
        val locked = ArrayList<Pair<Event, String>>()
        val arrived = ArrayList(waiting)
        for (event in response.toDevice) {
            val message = CallMessage.ofToDevice(event) ?: continue
            log("пришёл сигнал ${event.type} с устройства")
            calls.absorb(message)
        }
        for ((roomID, room) in response.joined) {
            for (event in room.timeline.events) if ((event.originServerTS ?: 0) > fresh) arrived += event to roomID
        }
        for ((event, roomID) in arrived) {
            var clear = event
            if (event.type == EventType.ENCRYPTED) {
                val opened = open(event, roomID)
                if (opened == null) {
                    if (locked.size < 64) locked += event to roomID
                    continue
                }
                clear = opened
            }
            val message = CallMessage.of(clear, roomID) ?: continue
            log("пришёл сигнал ${clear.type} из ${roomID.take(8)}…")
            calls.absorb(message)
        }
        lockedSignals = locked
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
        // Создатель комнаты 12-й версии может всё, хотя в power_levels его нет.
        val creator = runCatching { account.client.creators(spaceID) }.getOrNull()?.contains(me) == true
        return groups to (creator || (power != null && mine >= needed))
    }

    /** Участники сообщества (вместе с собой) и кто из них админ; `null` — не узнали. */
    suspend fun communityPeople(spaceID: String): Pair<List<Person>, Set<String>>? {
        val account = account ?: return null
        val people = runCatching { account.client.joinedMembers(spaceID) }.getOrNull() ?: return null
        val power = runCatching { account.client.state(spaceID, "m.room.power_levels") }.getOrNull()
        val admins = runCatching { account.client.creators(spaceID) }.getOrDefault(emptySet()) +
            power?.get("users")?.obj.orEmpty().filter { (it.value.int ?: 0) >= 100 }.keys
        return people to admins
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

    /** Новое сообщество: пространство в каталоге сервера; закрытое пускает по заявке. Сразу с общим чатом
     *  `chatName`, чтобы вступившим было где писать. Возвращает id. */
    suspend fun createCommunity(name: String, about: String, open: Boolean, chatName: String): String? {
        val account = account ?: return null
        val topic = about.trim().ifEmpty { null }
        val spaceID = runCatching {
            account.client.createRoom(CreateRoom(name = name, topic = topic, preset = "public_chat", encrypted = false, roomType = "m.space"))
        }.getOrNull() ?: return null
        runCatching { account.client.setRoomVisibility(spaceID, public = true) }
        if (!open) runCatching { account.client.setState(spaceID, "m.room.join_rules", content = JsonValue.of("join_rule" to "knock")) }
        log("сообщество создано, ${if (open) "открытое" else "закрытое"}")
        createGroup(chatName, spaceID)
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

    /** Свой аватар: квадратный JPEG грузим на сервер и пишем адрес в профиль; `null` — убрать аватар. */
    suspend fun setAvatar(jpeg: ByteArray?): Boolean {
        val account = account ?: return false
        val me = me ?: return false
        return try {
            if (jpeg != null) {
                val mxc = account.client.uploadMedia(jpeg, "image/jpeg", "avatar.jpg")
                account.client.setAvatarURL(me, mxc.toString())
                runCatching { media?.store(jpeg, mxc) }
                myAvatarFlow.value = mxc.toString()
            } else {
                account.client.setAvatarURL(me, "")
                myAvatarFlow.value = null
            }
            log("аватар ${myAvatarFlow.value ?: "убран"}")
            true
        } catch (e: Exception) {
            log("аватар не загружен: $e")
            false
        }
    }

    /** Аватар из памяти без ожидания: строка списка сразу рисуется с фото, без мигания буквы. */
    fun cachedAvatar(url: String, pixels: Int): Bitmap? = avatarMemory.get(avatarKey(url, pixels))

    /** Квадратная миниатюра аватара. Размер округляем до трёх ступеней, чтобы кэш не множился. */
    suspend fun avatar(url: String, pixels: Int): Bitmap? {
        val mxc = MXC.parse(url) ?: return null
        val key = avatarKey(url, pixels)
        avatarMemory.get(key)?.let { return it }
        val media = media ?: return null
        val side = avatarSide(pixels)
        val data = runCatching { media.data(mxc, MediaCache.Size(side, side), ThumbnailMethod.CROP) }.getOrNull() ?: return null
        val image = withContext(Dispatchers.Default) { Pictures.decode(data, side) } ?: return null
        avatarMemory.put(key, image)
        return image
    }

    /** Аватар целиком, для показа во весь экран. */
    suspend fun avatarFull(url: String): Bitmap? {
        val mxc = MXC.parse(url) ?: return null
        val data = runCatching { media?.data(mxc) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) { Pictures.decode(data, 2048) }
    }

    private fun avatarSide(pixels: Int) = if (pixels <= 96) 96 else if (pixels <= 192) 192 else 384

    private fun avatarKey(url: String, pixels: Int) = "$url#${avatarSide(pixels)}"

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
        /** Имя своего набора стикеров в данных учётки: ставится при первом стикере. */
        const val STICKER_PACK = "Нютаг"
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
