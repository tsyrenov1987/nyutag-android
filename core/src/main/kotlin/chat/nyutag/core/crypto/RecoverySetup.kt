// Нютаг. Включение восстановления с нуля: ключи кросс-подписи, бэкап ключей комнат, хранилище секретов SSSS
// и recovery-код. Тот же вызов меняет recovery-код: бэкап остаётся прежним, секреты перешифровываются новым ключом.

package chat.nyutag.core.crypto

import chat.nyutag.core.rooms.setAccountData
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.BackupKey
import chat.nyutag.crypto.Random
import chat.nyutag.crypto.RecoveryKey
import chat.nyutag.crypto.SecretStorage
import chat.nyutag.json.JsonValue

/** Включить восстановление и вернуть новый recovery-код (показать человеку один раз).
 *  Ключи кросс-подписи заводятся, только если на сервере их ещё нет. Если они есть, а у этого устройства закрытых
 *  ключей нет, нужно сначала подтвердить устройство или ввести старый код; `resetIdentity` заводит новые ключи,
 *  и все прежние подтверждения сбрасываются.
 *  `passphrase`: ключ хранилища выводится из фразы (PBKDF2), и открыть его можно этой же фразой, без кода.
 *  Нютаг передаёт сюда пароль аккаунта: вход паролем на новом устройстве сразу поднимает историю. */
suspend fun CryptoMachine.enableRecovery(
    password: String?, resetIdentity: Boolean = false, passphrase: String? = null,
    iterations: Int = CryptoMachine.PASSPHRASE_ITERATIONS,
): String {
    ensureCrossSigning(password, resetIdentity)
    val backupKey = ensureBackup()

    val key: ByteArray
    val description = linkedMapOf<String, JsonValue>("algorithm" to JsonValue.Str(CryptoMachine.SECRET_STORAGE_ALGORITHM))
    if (passphrase != null) {
        val salt = secretStorageKeyID()
        key = crypto { RecoveryKey.fromPassphrase(passphrase, salt, iterations) }
        description["passphrase"] = JsonValue.of("algorithm" to "m.pbkdf2", "salt" to salt, "iterations" to iterations)
    } else {
        key = Random.bytes(32)
    }
    val keyID = secretStorageKeyID()
    val check = crypto { SecretStorage.encrypt(ByteArray(32), "", key) }
    description["iv"] = JsonValue.Str(check.iv)
    description["mac"] = JsonValue.Str(check.mac)
    putAccountData("m.secret_storage.key.$keyID", JsonValue.Obj(description))
    val secrets = sortedMapOf(CryptoMachine.BACKUP_SECRET_NAME to Base64.encodeUnpadded(backupKey.keyPair.rawRepresentation))
    for (kind in CrossSigningKind.entries) {
        ownCrossSigningKey(kind)?.let { secrets[kind.secretName] = Base64.encodeUnpadded(it.rawRepresentation) }
    }
    for ((name, secret) in secrets) {
        val sealed = crypto { SecretStorage.encrypt(secret.toByteArray(), name, key) }
        putAccountData(name, JsonValue.of("encrypted" to JsonValue.of(
            keyID to JsonValue.of("iv" to sealed.iv, "ciphertext" to sealed.ciphertext, "mac" to sealed.mac),
        )))
    }
    // Ключ по умолчанию меняем последним: до этого старый код продолжает работать.
    putAccountData("m.secret_storage.default_key", JsonValue.of("key" to keyID))
    uploadRoomKeysToBackup()
    return RecoveryKey.encode(key)
}

/** Закрытые ключи кросс-подписи на устройстве и совпадают с серверными; иначе завести новые, если можно. */
private suspend fun CryptoMachine.ensureCrossSigning(password: String?, resetIdentity: Boolean) {
    val response = server { client.queryKeys(listOf(userID)) }
    val serverMaster = response.at("master_keys", userID)?.let { crossSigningPublicKey(it, userID, "master") }
    if (serverMaster == null || resetIdentity) {
        bootstrapCrossSigning(password)
        return
    }
    val master = ownCrossSigningKey(CrossSigningKind.MASTER)
    if (master == null || master.publicKeyBase64 != serverMaster || ownCrossSigningKey(CrossSigningKind.SELF_SIGNING) == null) {
        throw E2EEException.BadPayload("у этого устройства нет ключей кросс-подписи: подтвердите его или введите прежний код")
    }
}

/** Ключ бэкапа, подходящий к текущей версии на сервере; если такого нет, заводится новая версия. */
private suspend fun CryptoMachine.ensureBackup(): BackupKey {
    val raw = storing { store.cryptoBlob("backup_key") }
    if (raw != null) {
        val local = runCatching { BackupKey(raw) }.getOrNull()
        val current = runCatching { client.backupVersion() }.getOrNull()
        if (local != null && current != null && current.algorithm == CryptoMachine.BACKUP_ALGORITHM &&
            current.publicKey == local.publicKeyBase64) {
            adoptBackupKey(Base64.encodeUnpadded(raw))
            return local
        }
    }
    val backupKey = BackupKey()
    var authData = sign(JsonValue.of("public_key" to backupKey.publicKeyBase64))
    ownCrossSigningKey(CrossSigningKind.MASTER)?.let { authData = signedWith(authData, userID, it.publicKeyBase64, it) }
    server { client.createBackupVersion(CryptoMachine.BACKUP_ALGORITHM, authData) }
    adoptBackupKey(Base64.encodeUnpadded(backupKey.keyPair.rawRepresentation))
    return backupKey
}

private suspend fun CryptoMachine.putAccountData(type: String, content: JsonValue) {
    server { client.setAccountData(userID, type, content = content) }
}

/** Идентификатор ключа SSSS: 32 буквы и цифры, как у Element. */
internal fun secretStorageKeyID(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return String(Random.bytes(32).map { alphabet[(it.toInt() and 0xFF) % alphabet.length] }.toCharArray())
}
