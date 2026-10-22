// Нютаг. Где приложение хранит своё: сессия входа зашифрована ключом из Android Keystore (ключ не покидает
// защищённое хранилище телефона), остальное — обычные настройки.

package chat.nyutag.messenger.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import chat.nyutag.core.auth.Session
import chat.nyutag.core.auth.SessionVault
import chat.nyutag.core.net.KeyValueStore
import chat.nyutag.json.JsonValue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Настройки приложения строками: язык, память о дверях. */
class Prefs(context: Context, name: String = "nyutag") : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** Сессия под AES-GCM ключом Keystore: в файле настроек лежат только IV и шифртекст. */
class KeystoreVault(context: Context) : SessionVault {
    private val prefs = context.getSharedPreferences("nyutag.session", Context.MODE_PRIVATE)

    override fun save(session: Session) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Испорченный ключ Keystore (бывает после смены блокировки на части прошивок) не должен навсегда закрыть вход:
        // прежняя сессия им всё равно не читается, так что ключ заводим заново.
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: java.security.GeneralSecurityException) {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        val sealed = cipher.doFinal(session.toJson().compact().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(DATA, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .commit()
    }

    override fun load(): Session? {
        val iv = prefs.getString(IV, null) ?: return null
        val data = prefs.getString(DATA, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val json = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        return Session.from(JsonValue.parse(json)) ?: throw IllegalStateException("сессия испорчена")
    }

    override fun erase() {
        prefs.edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nyutag.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = "iv"
        const val DATA = "data"
    }
}
