// Нютаг. История по паролю аккаунта: хранилище секретов SSSS заперто ключом из пароля (PBKDF2), поэтому вход паролем
// на новом устройстве сразу поднимает ключи кросс-подписи и бэкап ключей комнат. Отдельного recovery-кода человек не видит.

package chat.nyutag.core.crypto

sealed class PasswordUnlockOutcome {
    /** Хранилище открыто паролем: устройство подписано, из бэкапа поднято `imported` ключей комнат. */
    data class Opened(val imported: Int) : PasswordUnlockOutcome()
    /** Хранилища не было: завели его под этим паролем (и ключи кросс-подписи, если их не было на сервере). */
    object Created : PasswordUnlockOutcome() { override fun toString() = "Created" }
    /** Хранилище было заперто не этим паролем (старый случайный ключ, пароль сменён через почту) или личность есть
     *  без хранилища и без ключей на устройстве: завели новую личность и хранилище под паролем. Переписка под
     *  прежним ключом не читается, дальше любой вход паролем поднимает историю. */
    object Reset : PasswordUnlockOutcome() { override fun toString() = "Reset" }
}

/** Открыть или завести хранилище секретов паролем аккаунта. Вызывать после входа, пароль уже проверен сервером. */
suspend fun CryptoMachine.unlockWithPassword(
    password: String, iterations: Int = CryptoMachine.PASSPHRASE_ITERATIONS,
): PasswordUnlockOutcome {
    try {
        defaultSecretStorageKey()
    } catch (e: E2EEException.NoBackup) {
        val fresh = canStartIdentity()
        enableRecovery(password, resetIdentity = !fresh, passphrase = password, iterations = iterations)
        return if (fresh) PasswordUnlockOutcome.Created else PasswordUnlockOutcome.Reset
    }
    val ssss = try {
        secretStorageKeyFromPassphrase(password)
    } catch (e: E2EEException.WrongRecoveryKey) {
        // Владелец 23.10: ключ восстановления не спрашиваем никогда, вход паролем всегда сохраняет историю.
        enableRecovery(password, resetIdentity = true, passphrase = password, iterations = iterations)
        return PasswordUnlockOutcome.Reset
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
