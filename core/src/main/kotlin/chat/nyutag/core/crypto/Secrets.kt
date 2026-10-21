// Нютаг. Обмен секретами между своими устройствами (`m.secret.request` / `m.secret.send`): новое устройство после
// проверки по эмодзи получает от старого ключи кросс-подписи и ключ бэкапа без recovery-кода. Секреты отдаём только
// своему подтверждённому устройству и только по Olm; принимаем только на свой запрос и от подтверждённого устройства.

package chat.nyutag.core.crypto

import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.core.sync.Event
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.Ed25519KeyPair
import chat.nyutag.json.JsonValue

internal val sharedSecretNames: List<String>
    get() = CrossSigningKind.entries.map { it.secretName } + CryptoMachine.BACKUP_SECRET_NAME

// MARK: Локальное доверие

/** Устройство подтверждено вручную на этом устройстве (по эмодзи), без подписи кросс-подписью. */
internal fun CryptoMachine.trustLocally(device: DeviceInfo) {
    val trusted = locallyTrusted().toMutableSet()
    trusted.add("${device.userID}|${device.deviceID}|${device.ed25519Key}")
    val list = JsonValue.wrap(trusted.sorted())
    storing { store.setCryptoBlob("trusted_devices", list.compact().toByteArray()) }
}

internal fun CryptoMachine.locallyTrusted(): Set<String> {
    val blob = runCatching { store.cryptoBlob("trusted_devices") }.getOrNull() ?: return emptySet()
    val list = runCatching { JsonValue.parse(String(blob, Charsets.UTF_8)).array }.getOrNull() ?: return emptySet()
    return list.mapNotNull { it.string }.toSet()
}

internal fun CryptoMachine.isLocallyTrusted(device: DeviceInfo): Boolean =
    locallyTrusted().contains("${device.userID}|${device.deviceID}|${device.ed25519Key}")

// MARK: Запрос

/** Попросить у своего подтверждённого устройства ключи кросс-подписи и ключ бэкапа. */
internal suspend fun CryptoMachine.requestSecrets(device: DeviceInfo) {
    // Один секрет на событие: у sendToDevice одно содержимое на устройство.
    for (name in sharedSecretNames) {
        val requestID = TransactionID.make()
        secretRequests[requestID] = name to device.deviceID
        val content = JsonValue.of("name" to name, "action" to "request", "requesting_device_id" to deviceID, "request_id" to requestID)
        server { client.sendToDevice(CryptoMachine.SECRET_REQUEST_TYPE, mapOf(userID to mapOf(device.deviceID to content))) }
    }
}

/** Пришёл секрет: принять, если просили и прислало то устройство, у которого просили. */
internal suspend fun CryptoMachine.acceptSecret(event: Event) {
    if (event.sender != userID) return
    val from = event.unsigned?.get("sender_key")?.string ?: return
    val requestID = event.content["request_id"]?.string ?: return
    val secret = event.content["secret"]?.string ?: return
    val (name, requestedFrom) = secretRequests[requestID] ?: return
    val device = devices(listOf(userID)).firstOrNull { it.deviceID == requestedFrom } ?: return
    if (device.curve25519Key != from || !isVerified(device)) return
    secretRequests.remove(requestID)
    if (name == CryptoMachine.BACKUP_SECRET_NAME) {
        val (backupKey, version) = adoptBackupKey(secret)
        importBackup(backupKey, version)
        return
    }
    val kind = CrossSigningKind.entries.firstOrNull { it.secretName == name } ?: return
    receivedCrossSigning[kind] = crypto { Ed25519KeyPair(Base64.decode(secret)) }
    adoptReceivedCrossSigning()
}

/** Мастер и самоподпись пришли: сверить с сервером и сохранить. Ключа подписи пользователей у старого устройства
 *  может не быть; если он придёт позже, ключи сохраняются ещё раз. */
private suspend fun CryptoMachine.adoptReceivedCrossSigning() {
    val master = receivedCrossSigning[CrossSigningKind.MASTER] ?: return
    val selfSigning = receivedCrossSigning[CrossSigningKind.SELF_SIGNING] ?: return
    val response = server { client.queryKeys(listOf(userID)) }
    fun matches(field: String, usage: String, key: Ed25519KeyPair): Boolean =
        response.at(field, userID)?.let { crossSigningPublicKey(it, userID, usage) } == key.publicKeyBase64
    if (!matches("master_keys", "master", master) || !matches("self_signing_keys", "self_signing", selfSigning)) {
        throw E2EEException.BadPayload("присланные ключи кросс-подписи не совпадают с ключами на сервере")
    }
    var userSigning = receivedCrossSigning[CrossSigningKind.USER_SIGNING]
    if (userSigning != null && !matches("user_signing_keys", "user_signing", userSigning)) userSigning = null
    adoptCrossSigning(master, selfSigning, userSigning)
}

// MARK: Ответ

/** Отдать секрет своему подтверждённому устройству. Возвращает, ушёл ли секрет. */
internal suspend fun CryptoMachine.answerSecretRequest(event: Event): Boolean {
    val content = event.content
    if (event.sender != userID || content["action"]?.string != "request") return false
    val name = content["name"]?.string ?: return false
    if (name !in sharedSecretNames) return false
    val requester = content["requesting_device_id"]?.string ?: return false
    if (requester == deviceID) return false
    val requestID = content["request_id"]?.string ?: return false
    val device = ownDevice(requester) ?: return false
    if (!isVerified(device)) return false
    val secret = if (name == CryptoMachine.BACKUP_SECRET_NAME) {
        val raw = storing { store.cryptoBlob("backup_key") } ?: return false
        Base64.encodeUnpadded(raw)
    } else {
        val kind = CrossSigningKind.entries.firstOrNull { it.secretName == name } ?: return false
        val key = ownCrossSigningKey(kind) ?: return false
        Base64.encodeUnpadded(key.rawRepresentation)
    }
    val sent = sendEncryptedToDevice(
        CryptoMachine.SECRET_SEND_TYPE, JsonValue.of("request_id" to requestID, "secret" to secret), listOf(device),
    )
    return sent.isNotEmpty()
}
