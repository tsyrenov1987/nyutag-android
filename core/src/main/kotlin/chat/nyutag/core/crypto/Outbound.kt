// Нютаг. Отправка в зашифрованные комнаты: учёт устройств участников, сессии Olm к ним, раздача ключа
// комнаты (`m.room_key`) и шифрование событий Megolm. Плюс выгрузка ключей комнат в бэкап на сервере.

package chat.nyutag.core.crypto

import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.core.rooms.members
import chat.nyutag.core.rooms.send
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.crypto.BackupKey
import chat.nyutag.crypto.MegolmInboundSession
import chat.nyutag.crypto.MegolmOutboundSession
import chat.nyutag.crypto.OlmSession
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.sync.Mutex

// MARK: Устройства

/** Устройства пользователей с проверенными подписями. Списки, о смене которых сообщил /sync, и новые
 *  пользователи запрашиваются заново через `/keys/query`. */
suspend fun CryptoMachine.devices(users: List<String>): List<DeviceInfo> {
    val stale = storing { store.usersNeedingDeviceQuery(users) }
    for (chunk in stale.chunked(100)) {
        val response = server { client.queryKeys(chunk) }
        for (user in chunk) {
            // Сервер пользователя не ответил: список остаётся «грязным», спросим в следующий раз.
            val entries = response.at("device_keys", user)?.obj ?: continue
            val identity = crossSigningIdentity(user, response)
            storing { store.saveCrossSigning(identity, user) }
            val selfSigning = identity?.selfSigningKey
            val known = storing { store.devices(user) }.associate { it.deviceID to it.ed25519Key }
            val fresh = ArrayList<DeviceInfo>()
            for ((deviceID, keys) in entries) {
                if (keys["user_id"]?.string != user || keys["device_id"]?.string != deviceID) continue
                val ed = keys.at("keys", "ed25519:$deviceID")?.string ?: continue
                val curve = keys.at("keys", "curve25519:$deviceID")?.string ?: continue
                if (!CryptoMachine.verifySignature(keys, user, deviceID, ed)) continue
                // Ключ подписи устройства не меняется; если сервер прислал другой, устройству не доверяем.
                val old = known[deviceID]
                if (old != null && old != ed) continue
                val crossSigned = selfSigning?.let { CryptoMachine.verifySignature(keys, user, it, it) } ?: false
                fresh.add(DeviceInfo(user, deviceID, curve, ed, keys.at("unsigned", "device_display_name")?.string, crossSigned))
            }
            storing { store.replaceDevices(fresh, user) }
        }
    }
    return users.flatMap { storing { store.devices(it) } }
}

// MARK: Olm к устройствам

/** Сессии Olm к устройствам: имеющиеся из базы, недостающие — по одноразовым ключам из `/keys/claim`.
 *  Устройства без ключей (сервер не выдал) в ответ не попадают. */
internal suspend fun CryptoMachine.olmSessions(devices: List<DeviceInfo>): MutableMap<String, OlmSession> {
    val sessions = LinkedHashMap<String, OlmSession>()
    val missing = LinkedHashMap<String, MutableList<String>>()
    for (device in devices) {
        val session = storing { store.olmSessions(device.curve25519Key) }.firstOrNull()
        if (session != null) sessions[device.curve25519Key] = session
        else missing.getOrPut(device.userID) { ArrayList() }.add(device.deviceID)
    }
    if (missing.isEmpty()) return sessions
    val claimed = server { client.claimKeys(missing) }
    for (device in devices) {
        if (sessions[device.curve25519Key] != null) continue
        val entry = claimed.at(device.userID, device.deviceID)?.obj?.entries
            ?.firstOrNull { it.key.startsWith("signed_curve25519:") } ?: continue
        val oneTimeKey = entry.value["key"]?.string ?: continue
        if (!CryptoMachine.verifySignature(entry.value, device.userID, device.deviceID, device.ed25519Key)) continue
        val session = crypto { account.outboundSession(device.curve25519Key, oneTimeKey) }
        storing { store.saveOlmSession(session) }
        sessions[device.curve25519Key] = session
    }
    return sessions
}

/** Содержимое `m.room.encrypted` для одного устройства (to-device, Olm). */
internal fun CryptoMachine.olmEncrypt(type: String, content: JsonValue, device: DeviceInfo, session: OlmSession): JsonValue {
    val payload = JsonValue.of(
        "type" to type, "content" to content,
        "sender" to userID, "sender_device" to deviceID,
        "recipient" to device.userID, "recipient_keys" to JsonValue.of("ed25519" to device.ed25519Key),
        "keys" to JsonValue.of("ed25519" to ed25519Key),
    )
    val sealed = crypto { session.encrypt(payload.compact().toByteArray()) }
    return JsonValue.of(
        "algorithm" to CryptoMachine.OLM_ALGORITHM, "sender_key" to curve25519Key,
        "ciphertext" to JsonValue.of(device.curve25519Key to JsonValue.of("type" to sealed.type.rawValue, "body" to sealed.body)),
    )
}

/** Отправить одно to-device событие каждому устройству, шифруя Olm. Возвращает устройства, которым ушло. */
suspend fun CryptoMachine.sendEncryptedToDevice(type: String, content: JsonValue, devices: List<DeviceInfo>): List<DeviceInfo> {
    val sessions = olmSessions(devices)
    val sent = ArrayList<DeviceInfo>()
    val batches = arrayListOf(LinkedHashMap<String, MutableMap<String, JsonValue>>())
    for (device in devices) {
        val session = sessions[device.curve25519Key] ?: continue
        if (sent.size > 0 && sent.size % 250 == 0) batches.add(LinkedHashMap())
        batches.last().getOrPut(device.userID) { LinkedHashMap() }[device.deviceID] = olmEncrypt(type, content, device, session)
        storing { store.saveOlmSession(session) }
        sent.add(device)
    }
    for (batch in batches) {
        if (batch.isEmpty()) continue
        server { client.sendToDevice(EventType.ENCRYPTED, batch) }
    }
    return sent
}

// MARK: Комнаты

/** Устройства, которым положен ключ комнаты: все участники (и приглашённые, если история им видна), кроме этого устройства. */
internal suspend fun CryptoMachine.recipients(roomID: String): List<DeviceInfo> {
    val members: List<Event> = server { client.members(roomID) }
    val visibility = storing { store.state(roomID, "m.room.history_visibility", "")?.get("history_visibility")?.string }
    val users = members.mapNotNull { event ->
        val user = event.stateKey
        if (event.type != EventType.MEMBER || user == null) return@mapNotNull null
        when (event.content["membership"]?.string) {
            "join" -> user
            "invite" -> if (visibility == "joined") null else user
            else -> null
        }
    }.toSortedSet()
    return devices(users.toList()).filter { !(it.userID == userID && it.deviceID == deviceID) }
}

/** Готовит исходящую сессию комнаты: меняет её, если пора (срок, число сообщений, ушёл участник или устройство),
 *  и раздаёт ключ устройствам, у которых его ещё нет. */
suspend fun CryptoMachine.shareRoomKey(roomID: String) {
    val devices = recipients(roomID)
    val settings = storing { store.state(roomID, EventType.ENCRYPTION, "") }
    val periodMs = settings?.get("rotation_period_ms")?.long ?: CryptoMachine.DEFAULT_ROTATION_PERIOD_MS
    val maxMessages = settings?.get("rotation_period_msgs")?.int ?: CryptoMachine.DEFAULT_ROTATION_MESSAGES
    val current = LinkedHashMap<String, String>()
    for (device in devices) current.putIfAbsent("${device.userID}|${device.deviceID}", device.curve25519Key)

    var record = storing { store.outbound(roomID) }
    if (record != null) {
        val expired = System.currentTimeMillis() - record.createdAtEpochMillis >= periodMs || record.messageCount >= maxMessages
        // Ключ видело устройство, которого в комнате больше нет (или с другим ключом): дальше ему читать нельзя.
        val someoneLeft = record.sharedWith.any { (key, value) -> current[key] != value }
        if (expired || someoneLeft) record = null
    }
    if (record == null) {
        val session = MegolmOutboundSession()
        record = OutboundRecord(session)
        // Своя входящая копия: читать свои же сообщения и выгрузить ключ в бэкап.
        val inbound = crypto { MegolmInboundSession.fromSessionKey(session.sessionKey()) }
        storing {
            store.saveMegolmSession(inbound, roomID, curve25519Key, claimedEd25519 = ed25519Key)
            store.saveOutbound(record, roomID)
        }
    }
    val pending = devices.filter { record.sharedWith["${it.userID}|${it.deviceID}"] != it.curve25519Key }
    if (pending.isEmpty()) return
    val session = storing { record.session() }
    val roomKey = JsonValue.of(
        "algorithm" to CryptoMachine.MEGOLM_ALGORITHM, "room_id" to roomID,
        "session_id" to session.sessionID, "session_key" to session.sessionKey(),
    )
    val sent = sendEncryptedToDevice(EventType.ROOM_KEY, roomKey, pending)
    val sharedWith = LinkedHashMap(record.sharedWith)
    for (device in sent) sharedWith["${device.userID}|${device.deviceID}"] = device.curve25519Key
    var withheldFrom = record.withheldFrom
    // Без одноразовых ключей канал Olm не открыть: говорим устройству, почему ключа нет (один раз).
    val told = (record.withheldFrom ?: emptyList()).toSet()
    val unreachable = pending.filter {
        sharedWith["${it.userID}|${it.deviceID}"] == null && !told.contains("${it.userID}|${it.deviceID}")
    }
    if (unreachable.isNotEmpty()) {
        sendWithheld(unreachable, roomID, session.sessionID, "m.no_olm", "Нет одноразовых ключей для устройства")
        withheldFrom = told.toList() + unreachable.map { "${it.userID}|${it.deviceID}" }
    }
    val shared = record.copy(sharedWith = sharedWith, withheldFrom = withheldFrom)
    storing { store.saveOutbound(shared, roomID) }
}

/** Шифрует событие для комнаты: содержимое для `PUT /rooms/{id}/send/m.room.encrypted/{txn}`.
 *  Перед этим раздаёт ключ комнаты тем, у кого его нет. Вызовы для одной комнаты идут по очереди. */
suspend fun CryptoMachine.encrypt(roomID: String, type: String, content: JsonValue): JsonValue =
    roomLocks.getOrPut(roomID) { Mutex() }.withLockE {
        shareRoomKey(roomID)
        val record = storing { store.outbound(roomID) } ?: throw E2EEException.MissingSession("")
        val session = storing { record.session() }
        val plaintext = JsonValue.of("type" to type, "content" to content, "room_id" to roomID)
        val ciphertext = crypto { session.encrypt(plaintext.compact().toByteArray()) }
        val next = OutboundRecord(session, record.messageCount + 1, record.sharedWith, record.withheldFrom)
            .copy(createdAtEpochMillis = record.createdAtEpochMillis)
        storing { store.saveOutbound(next, roomID) }
        val sealed = linkedMapOf<String, JsonValue>(
            "algorithm" to JsonValue.Str(CryptoMachine.MEGOLM_ALGORITHM), "sender_key" to JsonValue.Str(curve25519Key),
            "ciphertext" to JsonValue.Str(ciphertext), "session_id" to JsonValue.Str(session.sessionID),
            "device_id" to JsonValue.Str(deviceID),
        )
        // Связь с другой репликой (правка, реакция, ответ) остаётся снаружи: по ней сервер группирует события.
        content["m.relates_to"]?.let { sealed["m.relates_to"] = it }
        JsonValue.Obj(sealed)
    }

/** Шифрует и отправляет событие в комнату. Возвращает `event_id`. */
suspend fun CryptoMachine.sendEncrypted(
    roomID: String, type: String, content: JsonValue, transactionID: String = TransactionID.make(),
): String {
    val sealed = encrypt(roomID, type, content)
    return server { client.send(roomID, EventType.ENCRYPTED, sealed, transactionID) }
}

/** Забыть исходящую сессию комнаты: следующее сообщение пойдёт с новым ключом. */
fun CryptoMachine.discardRoomKey(roomID: String) {
    storing { store.saveOutbound(null, roomID) }
}

// MARK: Бэкап

/** Выгружает в бэкап на сервере ключи комнат, которых там ещё нет. Нужен ключ бэкапа, поднятый `restoreBackup`.
 *  Возвращает число выгруженных сессий. */
suspend fun CryptoMachine.uploadRoomKeysToBackup(): Int {
    val version = storing { store.meta("e2ee_backup_version") } ?: throw E2EEException.NoBackup
    val raw = storing { store.cryptoBlob("backup_key") } ?: throw E2EEException.NoBackup
    val publicKey = crypto { BackupKey(raw).publicKeyBase64 }
    var uploaded = 0
    while (true) {
        val batch = storing { store.megolmSessionsNotBackedUp(200) }
        if (batch.isEmpty()) break
        val rooms = LinkedHashMap<String, MutableMap<String, JsonValue>>()
        for (stored in batch) {
            val claimed = LinkedHashMap<String, JsonValue>()
            stored.claimedEd25519?.let { claimed["ed25519"] = JsonValue.Str(it) }
            val plaintext = JsonValue.of(
                "algorithm" to CryptoMachine.MEGOLM_ALGORITHM, "sender_key" to stored.senderKey,
                "session_key" to crypto { stored.session.export() }, "sender_claimed_keys" to JsonValue.Obj(claimed),
                "forwarding_curve25519_key_chain" to emptyList<String>(),
            )
            val sealed = crypto { BackupKey.encrypt(plaintext.compact().toByteArray(), publicKey) }
            rooms.getOrPut(stored.roomID) { LinkedHashMap() }[stored.session.sessionID] = JsonValue.of(
                "first_message_index" to stored.session.firstKnownIndex.toLong(), "forwarded_count" to 0,
                "is_verified" to stored.session.isVerified,
                "session_data" to JsonValue.of("ephemeral" to sealed.ephemeral, "ciphertext" to sealed.ciphertext, "mac" to sealed.mac),
            )
        }
        try {
            client.uploadBackupKeys(version, rooms)
        } catch (e: MatrixError) {
            // Бэкап на сервере заменили: старый ключ больше не годится, нужен новый recovery-код.
            if (e.errcode == "M_WRONG_ROOM_KEYS_VERSION") {
                storing { store.setMeta("e2ee_backup_version", null) }
                throw E2EEException.NoBackup
            }
            throw E2EEException.Server(e)
        }
        storing { store.markMegolmBackedUp(batch) }
        uploaded += batch.size
    }
    return uploaded
}
