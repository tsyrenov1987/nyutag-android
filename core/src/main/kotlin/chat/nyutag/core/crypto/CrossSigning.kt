// Нютаг. Кросс-подпись: мастер-ключ пользователя, ключ самоподписи (подписывает свои устройства) и ключ подписи
// пользователей (подписывает чужие мастер-ключи). Устройство подтверждено, если его подписал ключ самоподписи
// владельца, а мастер-ключ владельца подтверждён нами.

package chat.nyutag.core.crypto

import chat.nyutag.core.net.MatrixError
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.Ed25519KeyPair
import chat.nyutag.json.JsonValue

enum class CrossSigningKind(val rawValue: String) {
    MASTER("master"),
    SELF_SIGNING("self_signing"),
    USER_SIGNING("user_signing");

    val secretName: String get() = "m.cross_signing.$rawValue"
    val blobName: String get() = "xsign_$rawValue"
}

/** Подписать объект произвольным ключом Ed25519 (`keyID` — часть после `ed25519:`). */
internal fun signedWith(obj: JsonValue, userID: String, keyID: String, key: Ed25519KeyPair): JsonValue {
    val fields = obj.obj?.let { LinkedHashMap(it) } ?: return obj
    val unsigned = fields.remove("unsigned")
    val signatures = LinkedHashMap(fields.remove("signatures")?.obj ?: emptyMap())
    val signature = Base64.encodeUnpadded(key.sign(JsonValue.Obj(fields).canonical().toByteArray()))
    val mine = LinkedHashMap(signatures[userID]?.obj ?: emptyMap())
    mine["ed25519:$keyID"] = JsonValue.Str(signature)
    signatures[userID] = JsonValue.Obj(mine)
    fields["signatures"] = JsonValue.Obj(signatures)
    if (unsigned != null) fields["unsigned"] = unsigned
    return JsonValue.Obj(fields)
}

/** Открытый ключ из объекта ключа кросс-подписи, если он правильно оформлен. */
internal fun crossSigningPublicKey(json: JsonValue, userID: String, usage: String): String? {
    if (json["user_id"]?.string != userID) return null
    if (json["usage"]?.array?.contains(JsonValue.Str(usage)) != true) return null
    val keys = json["keys"]?.obj ?: return null
    if (keys.size != 1) return null
    val (keyID, value) = keys.entries.first()
    val key = value.string ?: return null
    return if (keyID == "ed25519:$key") key else null
}

internal fun crossSigningObject(userID: String, usage: CrossSigningKind, key: Ed25519KeyPair): JsonValue = JsonValue.of(
    "user_id" to userID, "usage" to listOf(usage.rawValue),
    "keys" to JsonValue.of("ed25519:${key.publicKeyBase64}" to key.publicKeyBase64),
)

internal fun CryptoMachine.ownCrossSigningKey(kind: CrossSigningKind): Ed25519KeyPair? {
    val raw = storing { store.cryptoBlob(kind.blobName) } ?: return null
    return crypto { Ed25519KeyPair(raw) }
}

/** Ключи кросс-подписи пользователя из ответа `/keys/query` и доверяем ли мы его мастер-ключу. */
internal fun CryptoMachine.crossSigningIdentity(user: String, response: JsonValue): CrossSigningIdentity? {
    val master = response.at("master_keys", user) ?: return null
    val masterKey = crossSigningPublicKey(master, user, "master") ?: return null
    var selfSigningKey: String? = null
    val selfSigning = response.at("self_signing_keys", user)
    if (selfSigning != null) {
        val key = crossSigningPublicKey(selfSigning, user, "self_signing")
        if (key != null && CryptoMachine.verifySignature(selfSigning, user, masterKey, masterKey)) selfSigningKey = key
    }
    val verified = if (user == userID) {
        ownCrossSigningKey(CrossSigningKind.MASTER)?.publicKeyBase64 == masterKey
    } else {
        val userSigning = ownCrossSigningKey(CrossSigningKind.USER_SIGNING)?.publicKeyBase64
        userSigning != null && CryptoMachine.verifySignature(master, userID, userSigning, userSigning)
    }
    return CrossSigningIdentity(user, masterKey, selfSigningKey, verified)
}

/** Устройство подтверждено: его подписал ключ самоподписи владельца, а мастер-ключ владельца подтверждён.
 *  Или его ключ сверен на этом устройстве по эмодзи. */
fun CryptoMachine.isVerified(device: DeviceInfo): Boolean {
    if (device.crossSigned && runCatching { store.crossSigning(device.userID) }.getOrNull()?.verified == true) return true
    return isLocallyTrusted(device)
}

/** Ключи кросс-подписи пользователя, как их видит это устройство. */
fun CryptoMachine.crossSigningIdentity(userID: String): CrossSigningIdentity? =
    runCatching { store.crossSigning(userID) }.getOrNull()

/** Есть ли на этом устройстве закрытые ключи кросс-подписи. */
val CryptoMachine.hasCrossSigningKeys: Boolean
    get() = runCatching { store.cryptoBlob(CrossSigningKind.SELF_SIGNING.blobName) }.getOrNull() != null

/** Есть ли на этом устройстве ключ бэкапа ключей комнат. */
val CryptoMachine.hasBackupKey: Boolean
    get() = runCatching { store.cryptoBlob("backup_key") }.getOrNull() != null

// MARK: Ключи из SSSS

/** Поднять ключи кросс-подписи из SSSS по recovery-коду и подписать ими это устройство.
 *  Ключи на сервере должны совпасть с сохранёнными: иначе сервер подменил личность. */
suspend fun CryptoMachine.importCrossSigning(recoveryKey: String) = importCrossSigning(secretStorageKey(recoveryKey))

internal suspend fun CryptoMachine.importCrossSigning(ssss: SecretStorageKey) {
    val pairs = HashMap<CrossSigningKind, Ed25519KeyPair>()
    for (kind in CrossSigningKind.entries) {
        val secret = try {
            secret(kind.secretName, ssss.id, ssss.key)
        } catch (e: E2EEException) {
            // Ключа подписи пользователей может не быть (старые аккаунты); без мастера и самоподписи — нельзя.
            if (kind == CrossSigningKind.USER_SIGNING && (e is E2EEException.Server || e is E2EEException.BadPayload)) continue
            throw e
        }
        pairs[kind] = crypto { Ed25519KeyPair(Base64.decode(secret)) }
    }
    val master = pairs[CrossSigningKind.MASTER] ?: throw E2EEException.NoBackup
    val selfSigning = pairs[CrossSigningKind.SELF_SIGNING] ?: throw E2EEException.NoBackup
    val response = server { client.queryKeys(listOf(userID)) }
    val serverMaster = response.at("master_keys", userID)?.let { crossSigningPublicKey(it, userID, "master") }
    val serverSelf = response.at("self_signing_keys", userID)?.let { crossSigningPublicKey(it, userID, "self_signing") }
    if (serverMaster != master.publicKeyBase64 || serverSelf != selfSigning.publicKeyBase64) {
        throw E2EEException.BadPayload("ключи кросс-подписи на сервере не совпадают с сохранёнными в SSSS")
    }
    adoptCrossSigning(master, selfSigning, pairs[CrossSigningKind.USER_SIGNING])
}

/** Сохранить закрытые ключи кросс-подписи на устройстве и подписать им само устройство. */
internal suspend fun CryptoMachine.adoptCrossSigning(master: Ed25519KeyPair, selfSigning: Ed25519KeyPair, userSigning: Ed25519KeyPair?) {
    storing {
        store.setCryptoBlob(CrossSigningKind.MASTER.blobName, master.rawRepresentation)
        store.setCryptoBlob(CrossSigningKind.SELF_SIGNING.blobName, selfSigning.rawRepresentation)
        store.setCryptoBlob(CrossSigningKind.USER_SIGNING.blobName, userSigning?.rawRepresentation)
    }
    signOwnDevice()
    storing { store.markAllDeviceListsChanged() }
}

/** Подписать ключи этого устройства ключом самоподписи и отправить подпись на сервер. */
suspend fun CryptoMachine.signOwnDevice() {
    val selfSigning = ownCrossSigningKey(CrossSigningKind.SELF_SIGNING) ?: throw E2EEException.NoBackup
    val fields = LinkedHashMap(deviceKeys().obj ?: emptyMap())
    fields.remove("signatures")
    val signed = signedWith(JsonValue.Obj(fields), userID, selfSigning.publicKeyBase64, selfSigning)
    val failures = server { client.uploadSignatures(mapOf(userID to mapOf(deviceID to signed))) }
    val failure = failures.at(userID, deviceID)
    if (failure != null) throw E2EEException.BadPayload("подпись устройства отклонена: ${failure["errcode"]?.string ?: ""}")
}

// MARK: С нуля

/** Завести ключи кросс-подписи для аккаунта, где их ещё нет. Сервер обычно просит подтвердить паролем.
 *  ⚠️ Заменяет прежнюю личность: все подтверждения устройств и собеседников сбрасываются. */
suspend fun CryptoMachine.bootstrapCrossSigning(password: String?) {
    val master = Ed25519KeyPair()
    val selfSigning = Ed25519KeyPair()
    val userSigning = Ed25519KeyPair()
    val masterObject = sign(crossSigningObject(userID, CrossSigningKind.MASTER, master))
    val selfObject = signedWith(crossSigningObject(userID, CrossSigningKind.SELF_SIGNING, selfSigning), userID, master.publicKeyBase64, master)
    val userObject = signedWith(crossSigningObject(userID, CrossSigningKind.USER_SIGNING, userSigning), userID, master.publicKeyBase64, master)
    try {
        client.uploadCrossSigningKeys(masterObject, selfObject, userObject)
    } catch (e: MatrixError) {
        val session = e.uiaSession
        if (session == null || password == null) throw E2EEException.Server(e)
        val auth = JsonValue.of(
            "type" to "m.login.password", "identifier" to JsonValue.of("type" to "m.id.user", "user" to userID),
            "password" to password, "session" to session,
        )
        server { client.uploadCrossSigningKeys(masterObject, selfObject, userObject, auth) }
    }
    adoptCrossSigning(master, selfSigning, userSigning)
}
