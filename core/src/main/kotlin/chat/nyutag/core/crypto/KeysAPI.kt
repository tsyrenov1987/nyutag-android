// Нютаг. Эндпоинты ключей шифрования: загрузка и запрос ключей устройств, одноразовые ключи,
// to-device сообщения, бэкап ключей комнат.

package chat.nyutag.core.crypto

import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.MatrixClient.Method
import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.json.JsonValue

/** Версия бэкапа ключей на сервере. */
data class BackupVersion(
    val version: String,
    val algorithm: String,
    val publicKey: String?,
    val count: Int,
    val etag: String?,
    val authData: JsonValue,
)

/** `/keys/upload`: ключи устройства, одноразовые и запасные. Возвращает счётчики одноразовых ключей на сервере. */
suspend fun MatrixClient.uploadKeys(
    deviceKeys: JsonValue? = null, oneTimeKeys: JsonValue? = null, fallbackKeys: JsonValue? = null,
): Map<String, Int> {
    val body = LinkedHashMap<String, JsonValue>()
    if (deviceKeys != null) body["device_keys"] = deviceKeys
    if (oneTimeKeys != null) body["one_time_keys"] = oneTimeKeys
    if (fallbackKeys != null) body["fallback_keys"] = fallbackKeys
    val json = request(Method.POST, "keys/upload", body = JsonValue.Obj(body))
    return (json["one_time_key_counts"]?.obj ?: emptyMap()).mapNotNull { (k, v) -> v.int?.let { k to it } }.toMap()
}

/** `/keys/query`: ключи устройств и кросс-подписи пользователей (весь ответ как есть). */
suspend fun MatrixClient.queryKeys(userIDs: List<String>): JsonValue =
    request(Method.POST, "keys/query", body = JsonValue.of("device_keys" to userIDs.associateWith { emptyList<String>() }))

/** `/keys/claim`: по одному одноразовому ключу на устройство. Ответ: пользователь → устройство → {id: ключ}. */
suspend fun MatrixClient.claimKeys(devices: Map<String, List<String>>, algorithm: String = "signed_curve25519"): JsonValue {
    val body = devices.mapValues { (_, ids) -> ids.associateWith { algorithm } }
    val json = request(Method.POST, "keys/claim", body = JsonValue.of("one_time_keys" to body))
    return json["one_time_keys"] ?: JsonValue.Obj(emptyMap())
}

/** `/sendToDevice`: пользователь → устройство (или `*`) → содержимое. */
suspend fun MatrixClient.sendToDevice(
    type: String, messages: Map<String, Map<String, JsonValue>>, transactionID: String = TransactionID.make(),
) {
    request(Method.PUT, "sendToDevice/$type/$transactionID", body = JsonValue.of("messages" to messages))
}

/** Текущая версия бэкапа; null, если бэкапа нет. */
suspend fun MatrixClient.backupVersion(): BackupVersion? {
    val json = try {
        request(Method.GET, "room_keys/version")
    } catch (e: MatrixError) {
        if (e.errcode == "M_NOT_FOUND") return null
        throw e
    }
    val version = json["version"]?.string
    val algorithm = json["algorithm"]?.string
    if (version == null || algorithm == null) throw MatrixError(MatrixError.Kind.Decoding("room_keys/version"))
    return BackupVersion(version, algorithm, json.at("auth_data", "public_key")?.string, json["count"]?.int ?: 0,
                         json["etag"]?.string, json["auth_data"] ?: JsonValue.Obj(emptyMap()))
}

/** Завести новую версию бэкапа. Возвращает её номер. */
suspend fun MatrixClient.createBackupVersion(algorithm: String, authData: JsonValue): String {
    val json = request(Method.POST, "room_keys/version", body = JsonValue.of("algorithm" to algorithm, "auth_data" to authData))
    return json["version"]?.string ?: throw MatrixError(MatrixError.Kind.Decoding("room_keys/version"))
}

/** Все ключи бэкапа: комната → session_id → запись (`first_message_index`, `session_data` …). */
suspend fun MatrixClient.backupKeys(version: String): Map<String, Map<String, JsonValue>> {
    val json = request(Method.GET, "room_keys/keys", query = mapOf("version" to version))
    return (json["rooms"]?.obj ?: emptyMap()).mapValues { (_, room) -> room["sessions"]?.obj ?: emptyMap() }
}

/** Ключи одной комнаты из бэкапа (докачка, когда сессии нет). */
suspend fun MatrixClient.backupKey(version: String, roomID: String, sessionID: String): JsonValue? = try {
    request(Method.GET, "room_keys/keys/$roomID/$sessionID", query = mapOf("version" to version))
} catch (e: MatrixError) {
    if (e.errcode == "M_NOT_FOUND") null else throw e
}

/** Загрузка ключей в бэкап: комната → session_id → запись. */
suspend fun MatrixClient.uploadBackupKeys(version: String, rooms: Map<String, Map<String, JsonValue>>): Int {
    val body = JsonValue.of("rooms" to rooms.mapValues { (_, sessions) -> JsonValue.of("sessions" to sessions) })
    val json = request(Method.PUT, "room_keys/keys", query = mapOf("version" to version), body = body)
    return json["count"]?.int ?: 0
}

/** `/keys/signatures/upload`: пользователь → ключ (устройство или публичный ключ кросс-подписи) → объект с подписью.
 *  Возвращает `failures`. */
suspend fun MatrixClient.uploadSignatures(signed: Map<String, Map<String, JsonValue>>): JsonValue {
    val json = request(Method.POST, "keys/signatures/upload", body = JsonValue.wrap(signed))
    return json["failures"] ?: JsonValue.Obj(emptyMap())
}

/** `/keys/device_signing/upload`: ключи кросс-подписи. Сервер может потребовать подтверждение паролем:
 *  тогда первый ответ — 401 с `session`, и запрос повторяется с `auth`. */
suspend fun MatrixClient.uploadCrossSigningKeys(
    master: JsonValue, selfSigning: JsonValue, userSigning: JsonValue, auth: JsonValue? = null,
) {
    val body = linkedMapOf("master_key" to master, "self_signing_key" to selfSigning, "user_signing_key" to userSigning)
    if (auth != null) body["auth"] = auth
    request(Method.POST, "keys/device_signing/upload", body = JsonValue.Obj(body))
}
