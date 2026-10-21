// Нютаг. Примитивы для Olm/Megolm из JDK (javax.crypto: AES-256-CBC/CTR, HMAC-SHA-256, SHA-256/512, SecureRandom)
// и свои: HKDF, PBKDF2, X25519 и Ed25519 (на Android 11 их в JCA нет). Ничего стороннего.

package chat.nyutag.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed class CryptoException(message: String) : Exception(message) {
    object BadKeyLength : CryptoException("неверная длина ключа") { private fun readResolve(): Any = BadKeyLength }
    object BadBase64 : CryptoException("не base64") { private fun readResolve(): Any = BadBase64 }
    object BadPadding : CryptoException("неверная набивка") { private fun readResolve(): Any = BadPadding }
    data class CipherFailed(val reason: String) : CryptoException("шифр: $reason")
    object BadSignature : CryptoException("неверная подпись") { private fun readResolve(): Any = BadSignature }
    data class BadMessage(val what: String) : CryptoException(what)
    object BadMAC : CryptoException("неверный MAC") { private fun readResolve(): Any = BadMAC }
    /** Сообщение раньше первого известного индекса сессии Megolm. */
    object UnknownMessageIndex : CryptoException("неизвестный индекс сообщения") { private fun readResolve(): Any = UnknownMessageIndex }
}

// MARK: - Base64 без набивки (как в libolm)

object Base64 {
    /** Кодирование без `=` в конце: так записываются ключи и сообщения Olm/Megolm. */
    fun encodeUnpadded(data: ByteArray): String = java.util.Base64.getEncoder().withoutPadding().encodeToString(data)

    /** Обычный base64 с набивкой (хранилище состояния, SSSS). */
    fun encodePadded(data: ByteArray): String = java.util.Base64.getEncoder().encodeToString(data)

    /** Декодирование строки с набивкой или без неё. */
    fun decode(string: String): ByteArray {
        val padded = when (string.length % 4) {
            2 -> "$string=="
            3 -> "$string="
            1 -> throw CryptoException.BadBase64
            else -> string
        }
        return try {
            java.util.Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw CryptoException.BadBase64
        }
    }
}

// MARK: - Хэши

object Hash {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = hmac("HmacSHA256", key, data)

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray = hmac("HmacSHA512", key, data)

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        // SecretKeySpec не принимает пустой ключ; у HMAC пустой ключ равен нулевому блоку.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, algorithm))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869): `salt` может быть пустой (тогда это 32 нулевых байта). */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputByteCount: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(outputByteCount)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < outputByteCount) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, outputByteCount - filled)
            previous.copyInto(out, filled, 0, take)
            filled += take
            counter += 1
        }
        return out
    }

    /** PBKDF2-HMAC-SHA512 (RFC 8018) своими руками: поведение провайдеров Android с паролем в UTF-8 разнится. */
    fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, outputByteCount: Int): ByteArray {
        require(iterations > 0)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA512"))
        val out = ByteArray(outputByteCount)
        var filled = 0
        var block = 1
        while (filled < outputByteCount) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = minOf(t.size, outputByteCount - filled)
            t.copyInto(out, filled, 0, take)
            filled += take
            block += 1
        }
        return out
    }
}

// MARK: - Эллиптические кривые

/** Пара X25519: 32 байта закрытого скаляра, открытый ключ — умножение на базовую точку. */
class Curve25519KeyPair(val rawRepresentation: ByteArray) {
    init {
        if (rawRepresentation.size != 32) throw CryptoException.BadKeyLength
    }

    constructor() : this(Random.bytes(32))

    val publicKey: ByteArray by lazy { Curve25519.x25519(rawRepresentation, Curve25519.basePoint) }
    val publicKeyBase64: String get() = Base64.encodeUnpadded(publicKey)

    /** X25519: общий секрет с чужим открытым ключом (32 байта). */
    fun sharedSecret(theirPublicKey: ByteArray): ByteArray {
        if (theirPublicKey.size != 32) throw CryptoException.BadKeyLength
        val secret = Curve25519.x25519(rawRepresentation, theirPublicKey)
        // Точка малого порядка даёт нули: такой ключ не принимаем, как CryptoKit.
        if (secret.all { it == 0.toByte() }) throw CryptoException.BadKeyLength
        return secret
    }
}

/** Пара Ed25519: 32 байта зерна (RFC 8032). */
class Ed25519KeyPair(val rawRepresentation: ByteArray) {
    init {
        if (rawRepresentation.size != 32) throw CryptoException.BadKeyLength
    }

    constructor() : this(Random.bytes(32))

    private val expanded: Ed25519.ExpandedKey by lazy { Ed25519.expand(rawRepresentation) }
    val publicKey: ByteArray get() = expanded.publicKey
    val publicKeyBase64: String get() = Base64.encodeUnpadded(publicKey)

    fun sign(message: ByteArray): ByteArray = Ed25519.sign(expanded, message)

    companion object {
        fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
            Ed25519.verify(signature, message, publicKey)
    }
}

// MARK: - AES

object Aes {
    /** AES-256-CBC с набивкой PKCS#7 (Olm, Megolm, бэкап ключей). */
    fun cbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray =
        cbc(Cipher.ENCRYPT_MODE, key, iv, plaintext)

    fun cbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        cbc(Cipher.DECRYPT_MODE, key, iv, ciphertext)

    private fun cbc(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        if (key.size != 32 || iv.size != 16) throw CryptoException.BadKeyLength
        return try {
            // В JCA «PKCS5Padding» для 16-байтных блоков и есть PKCS#7.
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(input)
        } catch (e: BadPaddingException) {
            throw CryptoException.BadPadding
        } catch (e: java.security.GeneralSecurityException) {
            throw CryptoException.CipherFailed(e.toString())
        }
    }

    /** AES-256-CTR без набивки (SSSS, `m.secret_storage.v1.aes-hmac-sha2`). */
    fun ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        if (key.size != 32 || iv.size != 16) throw CryptoException.BadKeyLength
        return try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(input)
        } catch (e: java.security.GeneralSecurityException) {
            throw CryptoException.CipherFailed(e.toString())
        }
    }
}

// MARK: - Случайные байты

object Random {
    private val generator = SecureRandom()

    fun bytes(count: Int): ByteArray = ByteArray(count).also { generator.nextBytes(it) }
}

/** Сравнение за постоянное время (теги HMAC). */
fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) diff = diff or (this[i].toInt() xor other[i].toInt())
    return diff == 0
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/** Четыре байта числа в сетевом порядке (big-endian). */
internal fun UInt.toBigEndianBytes(): ByteArray =
    byteArrayOf((this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte())

internal fun ByteArray.bigEndianUInt(offset: Int = 0): UInt =
    ((this[offset].toUInt() and 0xFFu) shl 24) or ((this[offset + 1].toUInt() and 0xFFu) shl 16) or
        ((this[offset + 2].toUInt() and 0xFFu) shl 8) or (this[offset + 3].toUInt() and 0xFFu)
