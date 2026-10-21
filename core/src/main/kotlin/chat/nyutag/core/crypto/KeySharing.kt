// Нютаг. Обмен ключами комнат между своими устройствами: запрос недостающего ключа (`m.room_key_request`),
// ответ подтверждённому своему устройству (`m.forwarded_room_key`) и отказы в ключе (`m.room_key.withheld`).

package chat.nyutag.core.crypto

import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.json.JsonValue

// MARK: Запрос

/** Попросить ключ сессии у своих устройств. `false`, если такой запрос уже отправлен. */
suspend fun CryptoMachine.requestRoomKey(roomID: String, sessionID: String, senderKey: String): Boolean {
    val requestID = TransactionID.make()
    if (!storing { store.addKeyRequest(sessionID, senderKey, roomID, requestID) }) return false
    val content = JsonValue.of(
        "action" to "request", "requesting_device_id" to deviceID, "request_id" to requestID,
        "body" to JsonValue.of(
            "algorithm" to CryptoMachine.MEGOLM_ALGORITHM, "room_id" to roomID,
            "sender_key" to senderKey, "session_id" to sessionID,
        ),
    )
    try {
        client.sendToDevice(EventType.ROOM_KEY_REQUEST, mapOf(userID to mapOf("*" to content)))
    } catch (e: MatrixError) {
        runCatching { store.removeKeyRequest(sessionID, senderKey) }
        throw E2EEException.Server(e)
    }
    return true
}

/** Попросить ключ для события, которое не расшифровалось. */
suspend fun CryptoMachine.requestRoomKey(event: Event, roomID: String): Boolean {
    val sessionID = event.content["session_id"]?.string ?: throw E2EEException.BadPayload("нет session_id")
    return requestRoomKey(roomID, sessionID, event.content["sender_key"]?.string ?: "")
}

/** Ключ получен: запрос больше не нужен ни этому, ни другим устройствам. */
internal suspend fun CryptoMachine.settleKeyRequest(sessionID: String, senderKey: String) {
    val requestID = runCatching { store.keyRequestID(sessionID, senderKey) }.getOrNull()
        ?: runCatching { store.keyRequestID(sessionID, "") }.getOrNull()
        ?: return
    runCatching { store.removeKeyRequest(sessionID, senderKey) }
    runCatching { store.removeKeyRequest(sessionID, "") }
    val cancel = JsonValue.of("action" to "request_cancellation", "requesting_device_id" to deviceID, "request_id" to requestID)
    try {
        client.sendToDevice(EventType.ROOM_KEY_REQUEST, mapOf(userID to mapOf("*" to cancel)))
    } catch (e: MatrixError) { }
}

internal fun CryptoMachine.hasKeyRequest(sessionID: String, senderKey: String): Boolean {
    if (runCatching { store.keyRequestID(sessionID, senderKey) }.getOrNull() != null) return true
    return runCatching { store.keyRequestID(sessionID, "") }.getOrNull() != null
}

// MARK: Ответ

/** Своё устройство по id; если его нет в списке, список перезапрашивается (устройство могло только появиться). */
internal suspend fun CryptoMachine.ownDevice(id: String): DeviceInfo? {
    devices(listOf(userID)).firstOrNull { it.deviceID == id }?.let { return it }
    storing { store.markDeviceListsChanged(listOf(userID)) }
    return devices(listOf(userID)).firstOrNull { it.deviceID == id }
}

/** Отдать ключ на запрос своего же подтверждённого устройства. Чужим и неподтверждённым ключи не отдаём.
 *  Возвращает, ушёл ли ключ. */
internal suspend fun CryptoMachine.answerRoomKeyRequest(event: Event): Boolean {
    val content = event.content
    if (event.sender != userID || content["action"]?.string != "request") return false
    val requester = content["requesting_device_id"]?.string ?: return false
    if (requester == deviceID) return false
    val body = content["body"] ?: return false
    if (body["algorithm"]?.string != CryptoMachine.MEGOLM_ALGORITHM) return false
    val roomID = body["room_id"]?.string ?: return false
    val sessionID = body["session_id"]?.string ?: return false
    val device = ownDevice(requester) ?: return false
    if (!isVerified(device)) return false
    val senderKey = body["sender_key"]?.string
    val stored = storing {
        if (!senderKey.isNullOrEmpty()) store.megolmSession(senderKey, sessionID) else store.megolmSession(sessionID)
    }
    if (stored == null || stored.roomID != roomID) return false
    val exported = crypto { stored.session.export() }
    val forwarded = JsonValue.of(
        "algorithm" to CryptoMachine.MEGOLM_ALGORITHM, "room_id" to roomID,
        "sender_key" to stored.senderKey, "session_id" to sessionID, "session_key" to exported,
        "sender_claimed_ed25519_key" to (stored.claimedEd25519 ?: ""), "forwarding_curve25519_key_chain" to emptyList<String>(),
    )
    return sendEncryptedToDevice(EventType.FORWARDED_ROOM_KEY, forwarded, listOf(device)).isNotEmpty()
}

/** Пересланный ключ принимаем, только если сами его просили и прислало своё подтверждённое устройство. */
internal suspend fun CryptoMachine.acceptForwardedRoomKey(event: Event) {
    val content = event.content
    val from = event.unsigned?.get("sender_key")?.string
    val roomID = content["room_id"]?.string
    val sessionKey = content["session_key"]?.string
    val senderKey = content["sender_key"]?.string
    val sessionID = content["session_id"]?.string
    if (event.sender != userID || from == null || content["algorithm"]?.string != CryptoMachine.MEGOLM_ALGORITHM ||
        roomID == null || sessionKey == null || senderKey == null || sessionID == null) {
        throw E2EEException.BadPayload("m.forwarded_room_key")
    }
    if (!hasKeyRequest(sessionID, senderKey)) return
    val device = devices(listOf(userID)).firstOrNull { it.curve25519Key == from } ?: return
    if (!isVerified(device)) return
    val session = crypto { chat.nyutag.crypto.MegolmInboundSession.fromExportedKey(sessionKey) }
    if (session.sessionID != sessionID) throw E2EEException.BadPayload("session_id не совпал с ключом")
    val claimed = content["sender_claimed_ed25519_key"]?.string?.takeIf { it.isNotEmpty() }
    storing { store.saveMegolmSession(session, roomID, senderKey, claimedEd25519 = claimed) }
    settleKeyRequest(sessionID, senderKey)
}

// MARK: Отказы

/** Отказ в ключе от отправителя: показать причину вместо «ждём ключ». */
internal fun CryptoMachine.acceptWithheld(event: Event) {
    val content = event.content
    if (content["algorithm"]?.string != CryptoMachine.MEGOLM_ALGORITHM) return
    val code = content["code"]?.string ?: return
    val sessionID = content["session_id"]?.string ?: return
    val senderKey = content["sender_key"]?.string ?: ""
    if (runCatching { store.megolmSession(sessionID) }.getOrNull() != null) return
    runCatching { store.saveWithheld(sessionID, senderKey, code, content["reason"]?.string) }
}

/** Сообщить устройствам, что ключ сессии им не придёт (`m.no_olm`: нет одноразовых ключей для канала Olm). */
internal suspend fun CryptoMachine.sendWithheld(
    devices: List<DeviceInfo>, roomID: String, sessionID: String, code: String, reason: String,
) {
    if (devices.isEmpty()) return
    val content = JsonValue.of(
        "algorithm" to CryptoMachine.MEGOLM_ALGORITHM, "room_id" to roomID, "session_id" to sessionID,
        "sender_key" to curve25519Key, "code" to code, "reason" to reason, "from_device" to deviceID,
    )
    val messages = LinkedHashMap<String, MutableMap<String, JsonValue>>()
    for (device in devices) messages.getOrPut(device.userID) { LinkedHashMap() }[device.deviceID] = content
    server { client.sendToDevice(CryptoMachine.WITHHELD_TYPE, messages) }
}
