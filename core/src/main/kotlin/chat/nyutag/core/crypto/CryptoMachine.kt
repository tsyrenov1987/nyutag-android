// Нютаг. Шифрование аккаунта на этом устройстве: ключи устройства и их публикация, приём to-device
// сообщений Olm (ключи комнат), расшифровка событий комнат Megolm, восстановление ключей из бэкапа.

package chat.nyutag.core.crypto

import chat.nyutag.core.auth.Session
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.rooms.accountData
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.core.sync.SyncObserver
import chat.nyutag.core.sync.SyncUpdate
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.BackupKey
import chat.nyutag.crypto.BackupSessionData
import chat.nyutag.crypto.CryptoException
import chat.nyutag.crypto.Ed25519KeyPair
import chat.nyutag.crypto.MegolmInboundSession
import chat.nyutag.crypto.OlmAccount
import chat.nyutag.crypto.OlmEncrypted
import chat.nyutag.crypto.RecoveryKey
import chat.nyutag.crypto.SecretStorage
import chat.nyutag.crypto.SecretStorageCiphertext
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

sealed class E2EEException(message: String) : Exception(message) {
    data class Crypto(val error: CryptoException) : E2EEException("крипто: ${error.message}")
    data class Server(val error: MatrixError) : E2EEException("сервер: ${error.message}")
    data class Store(val reason: String) : E2EEException("хранилище: $reason")
    /** Нет ключа сессии: ждать `m.room_key`, запросить у своих устройств или взять из бэкапа. */
    data class MissingSession(val sessionID: String) : E2EEException("нет сессии $sessionID")
    data class UnsupportedAlgorithm(val algorithm: String) : E2EEException("алгоритм $algorithm")
    data class BadPayload(val what: String) : E2EEException(what)
    /** Тот же индекс сессии уже был у другого события. */
    object Replay : E2EEException("повтор") { private fun readResolve(): Any = Replay }
    object NoBackup : E2EEException("нет бэкапа") { private fun readResolve(): Any = NoBackup }
    object WrongRecoveryKey : E2EEException("неверный код восстановления") { private fun readResolve(): Any = WrongRecoveryKey }
    /** Отправитель отказал в ключе (`m.room_key.withheld`): `m.no_olm`, `m.unverified`, `m.blacklisted` … */
    data class Withheld(val code: String, val reason: String?) : E2EEException("отказ в ключе: $code")
}

/** Расшифрованное событие комнаты и откуда взялся ключ. */
class DecryptedEvent(
    val event: Event,
    val senderKey: String,
    val sessionID: String,
    val messageIndex: UInt,
    /** Ключ пришёл с подписью отправителя (`m.room_key`), а не из бэкапа или пересылки. */
    val isVerifiedSession: Boolean,
)

/** Ключ SSSS и его id. */
class SecretStorageKey(val id: String, val key: ByteArray)

/** Итог восстановления из бэкапа: новых сессий и всего в бэкапе. */
data class BackupImport(val imported: Int, val total: Int)

class CryptoMachine private constructor(
    internal val client: MatrixClient,
    internal val store: Store,
    session: Session,
    internal var account: OlmAccount,
) : SyncObserver {
    val userID: String = session.userID
    val deviceID: String = session.deviceID
    val curve25519Key: String = account.curve25519Key
    val ed25519Key: String = account.ed25519Key

    /** Работа с ключами устройства (одноразовые ключи, входящие сессии Olm) идёт по одной. */
    internal val accountLock = Mutex()
    /** Комнаты, где сейчас идёт шифрование: вызовы для одной комнаты идут по очереди. */
    internal val roomLocks = ConcurrentHashMap<String, Mutex>()
    /** Идущие проверки по эмодзи; ключи SAS живут только в памяти. */
    internal val verifications = ConcurrentHashMap<String, VerificationSession>()
    /** Запрошенные у своих устройств секреты (id запроса → имя, устройство) и уже полученные ключи кросс-подписи. */
    internal val secretRequests = ConcurrentHashMap<String, Pair<String, String>>()
    internal val receivedCrossSigning = ConcurrentHashMap<CrossSigningKind, Ed25519KeyPair>()

    /** Последняя to-device посылка, которую не удалось открыть или принять: для журнала отладки. */
    @Volatile var lastToDeviceFailure: String? = null
        private set

    companion object {
        const val OLM_ALGORITHM = "m.olm.v1.curve25519-aes-sha2"
        const val MEGOLM_ALGORITHM = "m.megolm.v1.aes-sha2"
        const val BACKUP_ALGORITHM = "m.megolm_backup.v1.curve25519-aes-sha2"

        /** Ротация по умолчанию из спецификации: неделя или 100 сообщений. */
        const val DEFAULT_ROTATION_PERIOD_MS = 604_800_000L
        const val DEFAULT_ROTATION_MESSAGES = 100

        const val WITHHELD_TYPE = "m.room_key.withheld"
        const val SECRET_REQUEST_TYPE = "m.secret.request"
        const val SECRET_SEND_TYPE = "m.secret.send"
        const val BACKUP_SECRET_NAME = "m.megolm_backup.v1"
        const val SECRET_STORAGE_ALGORITHM = "m.secret_storage.v1.aes-hmac-sha2"
        /** Столько итераций PBKDF2 у парольной фразы SSSS. */
        const val PASSPHRASE_ITERATIONS = 500_000

        suspend fun create(client: MatrixClient, store: Store, session: Session): CryptoMachine {
            val account = try {
                val blob = store.cryptoBlob("account")
                if (blob != null) {
                    OlmAccount.fromJson(String(blob, Charsets.UTF_8))
                } else {
                    OlmAccount().also { store.setCryptoBlob("account", it.toJsonString().toByteArray()) }
                }
            } catch (e: Exception) {
                throw E2EEException.Store("$e")
            }
            return CryptoMachine(client, store, session, account)
        }

        /** Проверка подписи `ed25519:<keyID>` пользователя `userID` открытым ключом. */
        fun verifySignature(obj: JsonValue, userID: String, keyID: String, publicKey: String): Boolean {
            val fields = obj.obj?.toMutableMap() ?: return false
            val signature = fields["signatures"]?.get(userID)?.get("ed25519:$keyID")?.string ?: return false
            return try {
                val signatureData = Base64.decode(signature)
                val key = Base64.decode(publicKey)
                fields.remove("signatures")
                fields.remove("unsigned")
                Ed25519KeyPair.verify(signatureData, JsonValue.Obj(fields).canonical().toByteArray(), key)
            } catch (e: Exception) {
                false
            }
        }

        internal fun parseExported(exported: String, sessionID: String?): MegolmInboundSession {
            val session = crypto { MegolmInboundSession.fromExportedKey(exported) }
            if (sessionID != null && sessionID != session.sessionID) throw E2EEException.BadPayload("session_id не совпал с ключом")
            return session
        }
    }

    internal fun saveAccount() {
        try {
            store.setCryptoBlob("account", account.toJsonString().toByteArray())
        } catch (e: Exception) {
            throw E2EEException.Store("$e")
        }
    }

    // MARK: Подписи

    /** Подпись объекта ключом устройства: canonical JSON без `signatures` и `unsigned`, подпись в `signatures`. */
    fun sign(obj: JsonValue): JsonValue {
        val fields = obj.obj?.let { LinkedHashMap(it) } ?: return obj
        val unsigned = fields.remove("unsigned")
        val signatures = LinkedHashMap(fields.remove("signatures")?.obj ?: emptyMap())
        val signature = account.sign(JsonValue.Obj(fields).canonical().toByteArray())
        val mine = LinkedHashMap(signatures[userID]?.obj ?: emptyMap())
        mine["ed25519:$deviceID"] = JsonValue.Str(signature)
        signatures[userID] = JsonValue.Obj(mine)
        fields["signatures"] = JsonValue.Obj(signatures)
        if (unsigned != null) fields["unsigned"] = unsigned
        return JsonValue.Obj(fields)
    }

    /** Ключи устройства для `/keys/upload`, подписанные. */
    fun deviceKeys(): JsonValue = sign(JsonValue.of(
        "user_id" to userID,
        "device_id" to deviceID,
        "algorithms" to listOf(OLM_ALGORITHM, MEGOLM_ALGORITHM),
        "keys" to JsonValue.of("curve25519:$deviceID" to curve25519Key, "ed25519:$deviceID" to ed25519Key),
    ))

    // MARK: Публикация ключей

    /** Публикует ключи устройства (один раз), добивает одноразовые ключи до половины лимита сервера,
     *  заводит запасной ключ. `serverCount` — `signed_curve25519` из /sync или null, если неизвестно. */
    suspend fun publishKeys(serverCount: Int?): Int = accountLock.withLockE {
        val published = runCatching { store.meta("e2ee_device_keys_published") }.getOrNull() == "1"
        val target = OlmAccount.MAX_ONE_TIME_KEYS / 2
        // При запуске счётчик сервера неизвестен: спрашиваем, а не считаем нулём. Иначе каждый запуск доливает
        // ключи сверх тех, что помнит устройство, и самые старые на сервере остаются без закрытой части.
        var current = serverCount ?: 0
        if (serverCount == null && published) {
            current = server { client.uploadKeys() }["signed_curve25519"] ?: 0
        }
        if (current < target && account.unpublishedOneTimeKeys.isEmpty()) account.generateOneTimeKeys(target - current)
        if (account.unpublishedFallbackKey == null && runCatching { store.meta("e2ee_fallback_published") }.getOrNull() != "1") {
            account.generateFallbackKey()
        }
        saveAccount()
        val oneTime = account.unpublishedOneTimeKeys
        val fallback = account.unpublishedFallbackKey
        if (published && oneTime.isEmpty() && fallback == null) return@withLockE current

        val otks = LinkedHashMap<String, JsonValue>()
        for ((id, key) in oneTime) otks["signed_curve25519:$id"] = sign(JsonValue.of("key" to key))
        val fallbackKeys = LinkedHashMap<String, JsonValue>()
        if (fallback != null) {
            fallbackKeys["signed_curve25519:${fallback.first}"] = sign(JsonValue.of("key" to fallback.second, "fallback" to true))
        }
        val counts = server {
            client.uploadKeys(
                deviceKeys = if (published) null else deviceKeys(),
                oneTimeKeys = if (otks.isEmpty()) null else JsonValue.Obj(otks),
                fallbackKeys = if (fallbackKeys.isEmpty()) null else JsonValue.Obj(fallbackKeys),
            )
        }
        account.markKeysPublished()
        saveAccount()
        storing {
            store.setMeta("e2ee_device_keys_published", "1")
            if (fallback != null) store.setMeta("e2ee_fallback_published", "1")
        }
        counts["signed_curve25519"] ?: 0
    }

    // MARK: To-device

    override suspend fun syncDidReceive(update: SyncUpdate) {
        val changed = update.response.deviceListsChanged + update.response.deviceListsLeft
        if (changed.isNotEmpty()) runCatching { store.markDeviceListsChanged(changed) }
        receiveToDevice(update.response.toDevice)
        for ((roomID, room) in update.response.joined) receiveRoomVerifications(room.timeline.events, roomID)
        val count = update.response.oneTimeKeysCount["signed_curve25519"] ?: (if (update.isInitial) 0 else null)
        if (count != null && count < OlmAccount.MAX_ONE_TIME_KEYS / 2) {
            try { publishKeys(count) } catch (e: E2EEException) { }
        }
        // Новые ключи комнат (свои и полученные) уходят в бэкап, если ключ бэкапа на этом устройстве есть.
        if (runCatching { store.cryptoBlob("backup_key") }.getOrNull() != null) {
            try { uploadRoomKeysToBackup() } catch (e: E2EEException) { }
        }
    }

    /** Расшифровывает to-device сообщения Olm и принимает ключи комнат. Возвращает расшифрованные события. */
    suspend fun receiveToDevice(events: List<Event>): List<Event> {
        val decrypted = ArrayList<Event>()
        for (event in events) {
            try {
                when (event.type) {
                    EventType.ROOM_KEY_REQUEST -> answerRoomKeyRequest(event)
                    SECRET_REQUEST_TYPE -> answerSecretRequest(event)
                    WITHHELD_TYPE -> acceptWithheld(event)
                    in VerificationEvent.ALL -> receiveVerification(event)
                }
            } catch (e: E2EEException) { }
        }
        for (event in events) {
            if (event.type != EventType.ENCRYPTED) continue
            val inner = try {
                decryptToDevice(event)
            } catch (e: E2EEException) {
                lastToDeviceFailure = "${event.sender ?: "?"}: ${e.message}"
                continue
            }
            decrypted.add(inner)
            try {
                when (inner.type) {
                    EventType.ROOM_KEY -> try {
                        acceptRoomKey(inner)
                    } catch (e: E2EEException) {
                        lastToDeviceFailure = "ключ комнаты: ${e.message}"
                    }
                    EventType.FORWARDED_ROOM_KEY -> acceptForwardedRoomKey(inner)
                    SECRET_SEND_TYPE -> acceptSecret(inner)
                    in VerificationEvent.ALL -> receiveVerification(inner)
                }
            } catch (e: E2EEException) { }
        }
        return decrypted
    }

    internal suspend fun decryptToDevice(event: Event): Event {
        val content = event.content
        if (content["algorithm"]?.string != OLM_ALGORITHM) {
            throw E2EEException.UnsupportedAlgorithm(content["algorithm"]?.string ?: "")
        }
        val senderKey = content["sender_key"]?.string
        val mine = content["ciphertext"]?.get(curve25519Key)
        val type = mine?.get("type")?.int?.let { OlmEncrypted.Kind.from(it) }
        val body = mine?.get("body")?.string
        if (senderKey == null || type == null || body == null) {
            throw E2EEException.BadPayload("to-device: нет шифртекста для этого устройства")
        }
        val plaintext = decryptOlm(OlmEncrypted(type, body), senderKey)

        val json = runCatching { JsonValue.parse(String(plaintext, Charsets.UTF_8)) }.getOrNull()
        val innerType = json?.get("type")?.string ?: throw E2EEException.BadPayload("to-device: открытый текст не JSON")
        // Проверки из спецификации: сообщение адресовано нам и отправлено тем, кто указан в событии.
        if (json["sender"]?.string != event.sender || json["recipient"]?.string != userID ||
            json.at("recipient_keys", "ed25519")?.string != ed25519Key) {
            throw E2EEException.BadPayload("to-device: чужой получатель или отправитель")
        }
        val unsigned = linkedMapOf<String, JsonValue>("sender_key" to JsonValue.Str(senderKey))
        json.at("keys", "ed25519")?.string?.let { unsigned["sender_ed25519"] = JsonValue.Str(it) }
        json["sender_device"]?.string?.let { unsigned["sender_device"] = JsonValue.Str(it) }
        return Event(type = innerType, sender = event.sender, content = json["content"] ?: JsonValue.Obj(emptyMap()),
                     unsigned = JsonValue.Obj(unsigned))
    }

    private suspend fun decryptOlm(message: OlmEncrypted, senderKey: String): ByteArray = accountLock.withLockE {
        val sessions = storing { store.olmSessions(senderKey) }
        for (session in sessions) {
            if (message.type == OlmEncrypted.Kind.PRE_KEY && !session.matches(message.body)) continue
            val plaintext = try { session.decrypt(message) } catch (e: Exception) { continue }
            storing { store.saveOlmSession(session) }
            return@withLockE plaintext
        }
        if (message.type != OlmEncrypted.Kind.PRE_KEY) throw E2EEException.Crypto(CryptoException.BadMessage("olm: нет подходящей сессии"))
        val inbound = crypto { account.inboundSession(senderKey, message.body) }
        saveAccount()
        storing { store.saveOlmSession(inbound.session) }
        // Одноразовый ключ израсходован: сервер узнает об этом и пришлёт счётчик в /sync.
        inbound.plaintext
    }

    private suspend fun acceptRoomKey(event: Event) {
        val content = event.content
        val roomID = content["room_id"]?.string
        val sessionKey = content["session_key"]?.string
        val senderKey = event.unsigned?.get("sender_key")?.string
        if (content["algorithm"]?.string != MEGOLM_ALGORITHM || roomID == null || sessionKey == null || senderKey == null) {
            throw E2EEException.BadPayload("m.room_key")
        }
        val claimed = event.unsigned["sender_ed25519"]?.string
        val session = crypto { MegolmInboundSession.fromSessionKey(sessionKey) }
        if (content["session_id"]?.string != session.sessionID) throw E2EEException.BadPayload("m.room_key: session_id")
        storing { store.saveMegolmSession(session, roomID, senderKey, claimedEd25519 = claimed) }
        settleKeyRequest(session.sessionID, senderKey)
    }

    // MARK: Комнаты

    /** Расшифровка `m.room.encrypted` в комнате. Возвращает событие с настоящим типом и содержимым. */
    suspend fun decrypt(event: Event, roomID: String): DecryptedEvent {
        val content = event.content
        if (content["algorithm"]?.string != MEGOLM_ALGORITHM) {
            throw E2EEException.UnsupportedAlgorithm(content["algorithm"]?.string ?: "")
        }
        val sessionID = content["session_id"]?.string
        val ciphertext = content["ciphertext"]?.string
        if (sessionID == null || ciphertext == null) throw E2EEException.BadPayload("m.room.encrypted: нет session_id или ciphertext")
        val senderKey = content["sender_key"]?.string
        val stored = storing {
            if (senderKey != null) store.megolmSession(senderKey, sessionID) else store.megolmSession(sessionID)
        }
        if (stored == null || stored.roomID != roomID) {
            runCatching { store.withheld(sessionID, senderKey) }.getOrNull()?.let {
                throw E2EEException.Withheld(it.code, it.reason)
            }
            throw E2EEException.MissingSession(sessionID)
        }
        val result = crypto { stored.session.decrypt(ciphertext) }
        val eventID = event.eventID
        if (eventID != null) {
            val fresh = storing { store.markMegolmIndex(sessionID, result.messageIndex, eventID) }
            if (!fresh) throw E2EEException.Replay
        }
        val json = runCatching { JsonValue.parse(String(result.plaintext, Charsets.UTF_8)) }.getOrNull()
        val type = json?.get("type")?.string ?: throw E2EEException.BadPayload("megolm: открытый текст не JSON")
        if (json["room_id"]?.string != roomID) throw E2EEException.BadPayload("megolm: другая комната")
        var inner = json["content"] ?: JsonValue.Obj(emptyMap())
        // Связь с другой репликой многие клиенты кладут только снаружи шифра.
        val relation = content["m.relates_to"]
        val fields = inner.obj
        if (inner["m.relates_to"] == null && relation != null && fields != null) {
            inner = JsonValue.Obj(LinkedHashMap(fields).apply { put("m.relates_to", relation) })
        }
        val decrypted = Event(eventID = event.eventID, type = type, sender = event.sender, originServerTS = event.originServerTS,
                              stateKey = event.stateKey, content = inner, unsigned = event.unsigned, roomID = roomID)
        return DecryptedEvent(decrypted, stored.senderKey, sessionID, result.messageIndex, stored.session.isVerified)
    }

    fun megolmSessionCount(): Int = runCatching { store.megolmSessionCount() }.getOrDefault(0)

    // MARK: Бэкап ключей

    /** Ключ SSSS по recovery-коду: проверяется по описанию ключа по умолчанию в account data. */
    suspend fun secretStorageKey(recoveryKey: String): SecretStorageKey {
        val key = try { RecoveryKey.decode(recoveryKey) } catch (e: Exception) { throw E2EEException.WrongRecoveryKey }
        val (keyID, description) = defaultSecretStorageKey()
        val iv = description["iv"]?.string
        val mac = description["mac"]?.string
        if (iv != null && mac != null && !SecretStorage.check(key, iv, mac)) throw E2EEException.WrongRecoveryKey
        return SecretStorageKey(keyID, key)
    }

    /** Ключ SSSS по парольной фразе (`passphrase` в описании ключа). Ключ без фразы или чужая фраза — `WrongRecoveryKey`. */
    suspend fun secretStorageKeyFromPassphrase(passphrase: String): SecretStorageKey {
        val (keyID, description) = defaultSecretStorageKey()
        val info = description["passphrase"]
        val salt = info?.get("salt")?.string
        val iterations = info?.get("iterations")?.int
        val iv = description["iv"]?.string
        val mac = description["mac"]?.string
        if (info?.get("algorithm")?.string != "m.pbkdf2" || salt == null || iterations == null || iv == null || mac == null) {
            throw E2EEException.WrongRecoveryKey
        }
        val key = crypto { RecoveryKey.fromPassphrase(passphrase, salt, iterations, info["bits"]?.int ?: 256) }
        if (!SecretStorage.check(key, iv, mac)) throw E2EEException.WrongRecoveryKey
        return SecretStorageKey(keyID, key)
    }

    /** Описание ключа SSSS по умолчанию; `NoBackup`, если хранилища нет. */
    internal suspend fun defaultSecretStorageKey(): Pair<String, JsonValue> {
        val keyID: String
        val description: JsonValue
        try {
            val defaultKey = client.accountData(userID, "m.secret_storage.default_key")
            keyID = defaultKey["key"]?.string ?: throw E2EEException.NoBackup
            description = client.accountData(userID, "m.secret_storage.key.$keyID")
        } catch (e: MatrixError) {
            throw if (e.errcode == "M_NOT_FOUND") E2EEException.NoBackup else E2EEException.Server(e)
        }
        if (description["algorithm"]?.string != SECRET_STORAGE_ALGORITHM) {
            throw E2EEException.UnsupportedAlgorithm(description["algorithm"]?.string ?: "")
        }
        return keyID to description
    }

    /** Секрет из SSSS (`m.megolm_backup.v1`, `m.cross_signing.self_signing` …) как строка. */
    suspend fun secret(name: String, keyID: String, key: ByteArray): String {
        val json = server { client.accountData(userID, name) }
        val sealed = json["encrypted"]?.get(keyID)
        val iv = sealed?.get("iv")?.string
        val ciphertext = sealed?.get("ciphertext")?.string
        val mac = sealed?.get("mac")?.string
        if (iv == null || ciphertext == null || mac == null) throw E2EEException.BadPayload("$name: нет записи для ключа $keyID")
        val plaintext = try {
            SecretStorage.decrypt(SecretStorageCiphertext(iv, ciphertext, mac), name, key)
        } catch (e: CryptoException) {
            throw if (e == CryptoException.BadMAC) E2EEException.WrongRecoveryKey else E2EEException.Crypto(e)
        }
        return String(plaintext, Charsets.UTF_8)
    }

    /** Восстановить ключи комнат из бэкапа на сервере по recovery-коду. */
    suspend fun restoreBackup(recoveryKey: String): BackupImport = restoreBackup(secretStorageKey(recoveryKey))

    internal suspend fun restoreBackup(ssss: SecretStorageKey): BackupImport {
        val privateKey = secret(BACKUP_SECRET_NAME, ssss.id, ssss.key)
        val (backupKey, version) = adoptBackupKey(privateKey)
        return importBackup(backupKey, version)
    }

    /** Сохранить ключ бэкапа (base64 закрытого ключа), если он подходит к текущей версии бэкапа на сервере. */
    internal suspend fun adoptBackupKey(privateKey: String): Pair<BackupKey, BackupVersion> {
        val backupKey = crypto { BackupKey(Base64.decode(privateKey)) }
        val version = server { client.backupVersion() } ?: throw E2EEException.NoBackup
        if (version.algorithm != BACKUP_ALGORITHM) throw E2EEException.UnsupportedAlgorithm(version.algorithm)
        if (version.publicKey != backupKey.publicKeyBase64) throw E2EEException.WrongRecoveryKey
        storing {
            // Другая версия бэкапа: в ней нет того, что мы выгружали в прежнюю.
            if (store.meta("e2ee_backup_version") != version.version) store.resetMegolmBackedUp()
            store.setCryptoBlob("backup_key", backupKey.keyPair.rawRepresentation)
            store.setMeta("e2ee_backup_version", version.version)
        }
        return backupKey to version
    }

    /** Скачать бэкап и поднять из него ключи комнат. */
    internal suspend fun importBackup(backupKey: BackupKey, version: BackupVersion): BackupImport {
        val rooms = server { client.backupKeys(version.version) }
        var total = 0
        var imported = 0
        for ((roomID, sessions) in rooms) {
            for ((sessionID, entry) in sessions) {
                total += 1
                val data = entry["session_data"] ?: continue
                val ephemeral = data["ephemeral"]?.string ?: continue
                val ciphertext = data["ciphertext"]?.string ?: continue
                val mac = data["mac"]?.string ?: continue
                val plaintext = runCatching { backupKey.decrypt(BackupSessionData(ephemeral, ciphertext, mac)) }.getOrNull() ?: continue
                val json = runCatching { JsonValue.parse(String(plaintext, Charsets.UTF_8)) }.getOrNull() ?: continue
                if (json["algorithm"]?.string != MEGOLM_ALGORITHM) continue
                val sessionKey = json["session_key"]?.string ?: continue
                val senderKey = json["sender_key"]?.string ?: continue
                val session = try { parseExported(sessionKey, sessionID) } catch (e: E2EEException) { continue }
                val saved = storing {
                    store.saveMegolmSession(session, roomID, senderKey, backedUp = true,
                                            claimedEd25519 = json.at("sender_claimed_keys", "ed25519")?.string)
                }
                if (saved) imported += 1
            }
        }
        return BackupImport(imported, total)
    }
}

// MARK: Перевод ошибок

/** Ошибки базы и прочие неожиданные — в `E2EEException.Store`; свои пропускаем как есть. */
internal inline fun <T> storing(work: () -> T): T = try {
    work()
} catch (e: E2EEException) {
    throw e
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    throw E2EEException.Store("$e")
}

internal inline fun <T> server(work: () -> T): T = try {
    work()
} catch (e: MatrixError) {
    throw E2EEException.Server(e)
}

internal inline fun <T> crypto(work: () -> T): T = try {
    work()
} catch (e: CryptoException) {
    throw E2EEException.Crypto(e)
}

internal suspend inline fun <T> Mutex.withLockE(work: () -> T): T {
    lock()
    try {
        return work()
    } finally {
        unlock()
    }
}
