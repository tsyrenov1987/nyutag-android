// Нютаг. История по паролю аккаунта: хранилище секретов SSSS заперто ключом из пароля (PBKDF2), поэтому вход паролем
// на новом устройстве сразу поднимает ключи кросс-подписи и бэкап ключей комнат. Отдельного recovery-кода человек не видит.

package chat.nyutag.core.crypto

sealed class PasswordUnlockOutcome {
    /** Хранилище открыто паролем: устройство подписано, из бэкапа поднято `imported` ключей комнат. */
    data class Opened(val imported: Int) : PasswordUnlockOutcome()
    /** Хранилища не было: завели его под этим паролем (и ключи кросс-подписи, если их не было на сервере). */
    object Created : PasswordUnlockOutcome() { override fun toString() = "Created" }
    /** Хранилище заперто не этим паролем (старый случайный код или пароль сменили на другом устройстве). */
    object NeedsRecoveryKey : PasswordUnlockOutcome() { override fun toString() = "NeedsRecoveryKey" }
    /** Личность на сервере есть, хранилища нет, а у этого устройства закрытых ключей нет: подтвердить с другого устройства. */
    object NeedsOtherDevice : PasswordUnlockOutcome() { override fun toString() = "NeedsOtherDevice" }
}

/** Открыть или завести хранилище секретов паролем аккаунта. Вызывать после входа, пароль уже проверен сервером. */
suspend fun CryptoMachine.unlockWithPassword(
    password: String, iterations: Int = CryptoMachine.PASSPHRASE_ITERATIONS,
): PasswordUnlockOutcome {
    try {
        defaultSecretStorageKey()
    } catch (e: E2EEException.NoBackup) {
        if (!canStartIdentity()) return PasswordUnlockOutcome.NeedsOtherDevice
        enableRecovery(password, passphrase = password, iterations = iterations)
        return PasswordUnlockOutcome.Created
    }
    val ssss = try {
        secretStorageKeyFromPassphrase(password)
    } catch (e: E2EEException.WrongRecoveryKey) {
        return PasswordUnlockOutcome.NeedsRecoveryKey
    }
    importCrossSigning(ssss)
    return PasswordUnlockOutcome.Opened(restoreBackupIfAny(ssss))
}

/** Хранилище заперто старым кодом: открыть его кодом один раз и перезапереть паролем. Возвращает число ключей из бэкапа. */
suspend fun CryptoMachine.relockWithPassword(
    recoveryKey: String, password: String, iterations: Int = CryptoMachine.PASSPHRASE_ITERATIONS,
): Int {
    val ssss = secretStorageKey(recoveryKey)
    importCrossSigning(ssss)
    val imported = restoreBackupIfAny(ssss)
    enableRecovery(password, passphrase = password, iterations = iterations)
    return imported
}

/** Личность можно завести или переиспользовать: на сервере её нет, либо её закрытые ключи уже на этом устройстве. */
private suspend fun CryptoMachine.canStartIdentity(): Boolean {
    val response = server { client.queryKeys(listOf(userID)) }
    val serverMaster = response.at("master_keys", userID)?.let { crossSigningPublicKey(it, userID, "master") } ?: return true
    val master = ownCrossSigningKey(CrossSigningKind.MASTER)
    val selfSigning = ownCrossSigningKey(CrossSigningKind.SELF_SIGNING)
    return master?.publicKeyBase64 == serverMaster && selfSigning != null
}

/** Бэкапа ещё нет (или секрет не сохранён) — это не ошибка входа: ключей просто ноль. */
private suspend fun CryptoMachine.restoreBackupIfAny(ssss: SecretStorageKey): Int = try {
    restoreBackup(ssss).imported
} catch (e: E2EEException.NoBackup) {
    0
}
