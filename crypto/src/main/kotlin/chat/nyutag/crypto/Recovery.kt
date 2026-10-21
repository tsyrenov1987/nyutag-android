// Нютаг. Восстановление ключей: recovery-код (base58), хранилище секретов SSSS
// (`m.secret_storage.v1.aes-hmac-sha2`) и бэкап ключей комнат (`m.megolm_backup.v1.curve25519-aes-sha2`).

package chat.nyutag.crypto

// MARK: - Recovery-код

object RecoveryKey {
    private val PREFIX = byteArrayOf(0x8B.toByte(), 0x01)
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Ключ SSSS (32 байта) из кода вида `EsTx xxxx …`; пробелы не важны. */
    fun decode(code: String): ByteArray {
        val raw = base58Decode(code.filterNot { it.isWhitespace() })
        if (raw.size != PREFIX.size + 32 + 1 || !raw.copyOfRange(0, 2).contentEquals(PREFIX)) throw CryptoException.BadMessage("recovery: формат")
        if (raw.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } != 0) throw CryptoException.BadMessage("recovery: чётность")
        return raw.copyOfRange(2, 34)
    }

    /** Код для показа человеку: группы по четыре символа. */
    fun encode(key: ByteArray): String {
        val body = PREFIX + key
        val raw = body + byteArrayOf(body.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte())
        return base58Encode(raw).chunked(4).joinToString(" ")
    }

    internal fun base58Decode(text: String): ByteArray {
        val bytes = ArrayList<Int>()  // младшие байты в конце
        for (char in text) {
            val digit = ALPHABET.indexOf(char)
            if (digit < 0) throw CryptoException.BadMessage("recovery: символ")
            var carry = digit
            for (i in bytes.indices.reversed()) {
                carry += bytes[i] * 58
                bytes[i] = carry and 0xFF
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes.add(0, carry and 0xFF)
                carry = carry shr 8
            }
        }
        val zeros = text.takeWhile { it == '1' }.length
        return ByteArray(zeros) + ByteArray(bytes.size) { bytes[it].toByte() }
    }

    internal fun base58Encode(bytes: ByteArray): String {
        val digits = ArrayList<Int>()  // младшие цифры в конце
        for (byte in bytes) {
            var carry = byte.toInt() and 0xFF
            for (i in digits.indices.reversed()) {
                carry += digits[i] shl 8
                digits[i] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits.add(0, carry % 58)
                carry /= 58
            }
        }
        val zeros = bytes.takeWhile { it == 0.toByte() }.size
        return "1".repeat(zeros) + digits.map { ALPHABET[it] }.joinToString("")
    }

    /** Ключ из парольной фразы: PBKDF2-SHA-512 (`m.pbkdf2` в описании ключа SSSS). */
    fun fromPassphrase(passphrase: String, salt: String, iterations: Int, bits: Int = 256): ByteArray =
        Hash.pbkdf2Sha512(passphrase.toByteArray(), salt.toByteArray(), iterations, bits / 8)
}

// MARK: - SSSS

/** Зашифрованный секрет или проверочная запись ключа: `{iv, ciphertext, mac}` в base64. */
data class SecretStorageCiphertext(val iv: String, val ciphertext: String, val mac: String)

object SecretStorage {
    /** Шифрование секрета `name` ключом SSSS. Открытый текст — сам секрет (у ключей это base64 строкой). */
    fun encrypt(plaintext: ByteArray, name: String, key: ByteArray, iv: ByteArray? = null): SecretStorageCiphertext {
        val vector = (iv ?: Random.bytes(16)).copyOf()
        if (vector.size != 16) throw CryptoException.BadKeyLength
        vector[8] = (vector[8].toInt() and 0x7F).toByte()  // по спецификации: бит 63 сброшен, чтобы счётчик CTR не переполнился
        val (aes, mac) = derive(key, name)
        val ciphertext = Aes.ctr(aes, vector, plaintext)
        val tag = Hash.hmacSha256(mac, ciphertext)
        // Element и спецификация пишут SSSS обычным base64 с набивкой; читаем любой.
        return SecretStorageCiphertext(Base64.encodePadded(vector), Base64.encodePadded(ciphertext), Base64.encodePadded(tag))
    }

    fun decrypt(encrypted: SecretStorageCiphertext, name: String, key: ByteArray): ByteArray {
        val (aes, mac) = derive(key, name)
        val ciphertext = Base64.decode(encrypted.ciphertext)
        if (!Hash.hmacSha256(mac, ciphertext).constantTimeEquals(Base64.decode(encrypted.mac))) throw CryptoException.BadMAC
        return Aes.ctr(aes, Base64.decode(encrypted.iv), ciphertext)
    }

    /** Подходит ли ключ к описанию `m.secret_storage.key.<id>`: шифруем 32 нуля с пустым именем и тем же iv. */
    fun check(key: ByteArray, iv: String, mac: String): Boolean = try {
        val expected = encrypt(ByteArray(32), "", key, Base64.decode(iv))
        Base64.decode(expected.mac).constantTimeEquals(Base64.decode(mac))
    } catch (e: CryptoException) {
        false
    }

    private fun derive(key: ByteArray, name: String): Pair<ByteArray, ByteArray> {
        val out = Hash.hkdfSha256(key, ByteArray(32), name.toByteArray(), 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }
}

// MARK: - Бэкап ключей комнат

/** Зашифрованная запись бэкапа (`session_data`). */
data class BackupSessionData(val ephemeral: String, val ciphertext: String, val mac: String)

/** Ключ бэкапа `m.megolm_backup.v1.curve25519-aes-sha2`: Curve25519, открытая часть лежит в версии бэкапа на сервере. */
class BackupKey(val keyPair: Curve25519KeyPair) {
    constructor() : this(Curve25519KeyPair())
    constructor(privateKey: ByteArray) : this(Curve25519KeyPair(privateKey))

    val publicKeyBase64: String get() = keyPair.publicKeyBase64

    /** Расшифровка записи. libolm (а за ним и все клиенты) считает MAC от пустой строки, а не от шифртекста; принимаем оба варианта. */
    fun decrypt(data: BackupSessionData): ByteArray {
        val ciphertext = Base64.decode(data.ciphertext)
        val keys = derive(keyPair.sharedSecret(Base64.decode(data.ephemeral)))
        val mac = Base64.decode(data.mac)
        val legacy = Hash.hmacSha256(keys.mac, ByteArray(0)).copyOfRange(0, 8)
        val proper = Hash.hmacSha256(keys.mac, ciphertext).copyOfRange(0, 8)
        if (!legacy.constantTimeEquals(mac) && !proper.constantTimeEquals(mac)) throw CryptoException.BadMAC
        return Aes.cbcDecrypt(keys.aes, keys.iv, ciphertext)
    }

    private class Keys(val aes: ByteArray, val mac: ByteArray, val iv: ByteArray)

    companion object {
        /** Шифрование записи под открытый ключ бэкапа (MAC как у libolm, чтобы читали все клиенты). */
        fun encrypt(plaintext: ByteArray, publicKey: String): BackupSessionData {
            val ephemeral = Curve25519KeyPair()
            val keys = derive(ephemeral.sharedSecret(Base64.decode(publicKey)))
            val ciphertext = Aes.cbcEncrypt(keys.aes, keys.iv, plaintext)
            val mac = Hash.hmacSha256(keys.mac, ByteArray(0)).copyOfRange(0, 8)
            return BackupSessionData(ephemeral.publicKeyBase64, Base64.encodeUnpadded(ciphertext), Base64.encodeUnpadded(mac))
        }

        private fun derive(secret: ByteArray): Keys {
            val out = Hash.hkdfSha256(secret, ByteArray(32), ByteArray(0), 80)
            return Keys(out.copyOfRange(0, 32), out.copyOfRange(32, 64), out.copyOfRange(64, 80))
        }
    }
}
