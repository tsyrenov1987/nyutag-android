// Нютаг. Megolm: групповой храповик комнаты по спецификации Olm/Megolm (matrix.org/docs/spec/megolm).
// Храповик из четырёх 32-байтных частей R0…R3 и 32-битного счётчика; части обновляются HMAC-SHA-256
// с номером части как сообщением. Ключи сообщения: HKDF-SHA-256(R, info "MEGOLM_KEYS") → AES-256 32 + HMAC 32 + IV 16.

package chat.nyutag.crypto

import java.io.ByteArrayOutputStream

/** Состояние храповика: 128 байт и счётчик. */
class MegolmRatchet(data: ByteArray, counter: UInt) {
    init {
        if (data.size != LENGTH) throw CryptoException.BadKeyLength
    }

    var data: ByteArray = data.copyOf()
        private set
    var counter: UInt = counter
        private set

    fun copy(): MegolmRatchet = MegolmRatchet(data, counter)

    private fun part(index: Int): ByteArray = data.copyOfRange(index * PART_LENGTH, (index + 1) * PART_LENGTH)

    /** R(to) = HMAC(ключ R(from), байт to). */
    private fun rehash(from: Int, to: Int) {
        Hash.hmacSha256(part(from), byteArrayOf(to.toByte())).copyInto(data, to * PART_LENGTH)
    }

    /** Шаг на одно сообщение вперёд. */
    fun advance() {
        var mask = 0x00FF_FFFFu
        var h = 0
        counter += 1u
        while (h < PARTS) {
            if (counter and mask == 0u) break
            h += 1
            mask = mask shr 8
        }
        for (i in PARTS - 1 downTo h) rehash(h, i)
    }

    /** Переход сразу к индексу без пошагового прохода (как `megolm_advance_to` в эталонной реализации). */
    fun advanceTo(target: UInt) {
        for (j in 0 until PARTS) {
            val shift = (PARTS - j - 1) * 8
            val mask = UInt.MAX_VALUE shl shift
            var steps = ((target shr shift) - (counter shr shift)) and 0xFFu
            if (steps == 0u) {
                // Счётчик чуть больше цели только при переполнении R0: прокрутить его 256 раз.
                if (target < counter) steps = 0x100u else continue
            }
            while (steps > 1u) {
                rehash(j, j)
                steps -= 1u
            }
            for (k in PARTS - 1 downTo j) rehash(j, k)
            counter = target and mask
        }
    }

    internal class MessageKeys(val aes: ByteArray, val mac: ByteArray, val iv: ByteArray)

    /** AES-ключ, HMAC-ключ и IV текущего сообщения. */
    internal fun messageKeys(): MessageKeys {
        val keys = Hash.hkdfSha256(data, ByteArray(0), "MEGOLM_KEYS".toByteArray(), 80)
        return MessageKeys(keys.copyOfRange(0, 32), keys.copyOfRange(32, 64), keys.copyOfRange(64, 80))
    }

    override fun equals(other: Any?): Boolean = other is MegolmRatchet && other.counter == counter && other.data.contentEquals(data)
    override fun hashCode(): Int = data.contentHashCode() * 31 + counter.hashCode()

    companion object {
        const val PART_LENGTH = 32
        const val PARTS = 4
        const val LENGTH = PART_LENGTH * PARTS

        /** Свежий случайный храповик для новой исходящей сессии. */
        fun random(): MegolmRatchet = MegolmRatchet(Random.bytes(LENGTH), 0u)
    }
}

/** Сообщение Megolm в двоичном виде: версия 3, поля protobuf (индекс, шифртекст), 8 байт MAC, 64 байта подписи. */
class MegolmMessage(val messageIndex: UInt, val ciphertext: ByteArray) {
    internal class Parsed(val message: MegolmMessage, val body: ByteArray, val mac: ByteArray, val signature: ByteArray)

    companion object {
        const val VERSION = 3
        const val MAC_LENGTH = 8
        const val SIGNATURE_LENGTH = 64

        /** Байты до MAC (их и подписывает, вместе с MAC, ключ отправителя). */
        internal fun body(index: UInt, ciphertext: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(VERSION)
            out.write(0x08)
            Protobuf.appendVarint(index.toULong(), out)
            Protobuf.appendBytes(0x12, ciphertext, out)
            return out.toByteArray()
        }

        /** Разбор без проверки MAC и подписи; возвращает ещё и границы частей для проверок. */
        internal fun parse(raw: ByteArray): Parsed {
            if (raw.size <= 1 + MAC_LENGTH + SIGNATURE_LENGTH || raw[0].toInt() != VERSION) throw CryptoException.BadMessage("версия или длина")
            val bodyEnd = raw.size - MAC_LENGTH - SIGNATURE_LENGTH
            var index: UInt? = null
            var ciphertext: ByteArray? = null
            val reader = Protobuf.Reader(raw, 1, bodyEnd)
            while (true) {
                val (tag, value) = reader.next() ?: break
                when {
                    tag == 1 && value is Protobuf.Value.Varint -> index = value.value.toUInt()
                    tag == 2 && value is Protobuf.Value.Bytes -> ciphertext = value.value
                }
            }
            if (index == null || ciphertext == null) throw CryptoException.BadMessage("нет индекса или шифртекста")
            return Parsed(
                MegolmMessage(index, ciphertext), raw.copyOfRange(0, bodyEnd),
                raw.copyOfRange(bodyEnd, bodyEnd + MAC_LENGTH), raw.copyOfRange(bodyEnd + MAC_LENGTH, raw.size),
            )
        }
    }
}

/** Исходящая сессия: шифрует наши сообщения в комнату. Ключ подписи Ed25519 — имя сессии (session_id). */
class MegolmOutboundSession(val ratchet: MegolmRatchet, val signingKey: Ed25519KeyPair, val createdAtEpochMillis: Long) {
    constructor() : this(MegolmRatchet.random(), Ed25519KeyPair(), System.currentTimeMillis())

    val sessionID: String get() = signingKey.publicKeyBase64
    val messageIndex: UInt get() = ratchet.counter

    /** Шифрует и сдвигает храповик. Результат — base64 без паддинга для поля `ciphertext`. */
    fun encrypt(plaintext: ByteArray): String {
        val keys = ratchet.messageKeys()
        val ciphertext = Aes.cbcEncrypt(keys.aes, keys.iv, plaintext)
        val body = MegolmMessage.body(ratchet.counter, ciphertext)
        val withMac = body + Hash.hmacSha256(keys.mac, body).copyOfRange(0, MegolmMessage.MAC_LENGTH)
        val message = withMac + signingKey.sign(withMac)
        ratchet.advance()
        return Base64.encodeUnpadded(message)
    }

    /** `session_key` для события `m.room_key`: версия 2, счётчик, храповик, ключ подписи и подпись всего этого. */
    fun sessionKey(): String {
        val signed = byteArrayOf(2) + ratchet.counter.toBigEndianBytes() + ratchet.data + signingKey.publicKey
        return Base64.encodeUnpadded(signed + signingKey.sign(signed))
    }
}

/** Входящая сессия: расшифровывает сообщения одного отправителя в комнате. */
class MegolmInboundSession(
    /** Самый ранний известный храповик (с него можно расшифровать всё, начиная с его индекса). */
    val initialRatchet: MegolmRatchet,
    /** Ключ подписи сессии; он же session_id в base64. */
    val signingKey: ByteArray,
    /** Пришла ли сессия с подписью отправителя (`m.room_key`), а не из бэкапа или пересылки. */
    val isVerified: Boolean,
) {
    val sessionID: String get() = Base64.encodeUnpadded(signingKey)
    val firstKnownIndex: UInt get() = initialRatchet.counter

    class Decrypted(val plaintext: ByteArray, val messageIndex: UInt)

    companion object {
        private const val HEADER = 1 + 4

        /** Из `session_key` события `m.room_key` (версия 2, с подписью). */
        fun fromSessionKey(sessionKey: String): MegolmInboundSession {
            val raw = Base64.decode(sessionKey)
            if (raw.size != HEADER + MegolmRatchet.LENGTH + 32 + 64 || raw[0].toInt() != 2) throw CryptoException.BadMessage("session_key")
            val signedEnd = raw.size - 64
            val key = raw.copyOfRange(HEADER + MegolmRatchet.LENGTH, signedEnd)
            if (!Ed25519KeyPair.verify(raw.copyOfRange(signedEnd, raw.size), raw.copyOfRange(0, signedEnd), key)) throw CryptoException.BadSignature
            return fromRaw(raw, verified = true)
        }

        /** Из выгрузки (`m.forwarded_room_key`, бэкап ключей): версия 1, без подписи. */
        fun fromExportedKey(exportedKey: String): MegolmInboundSession {
            val raw = Base64.decode(exportedKey)
            if (raw.size != HEADER + MegolmRatchet.LENGTH + 32 || raw[0].toInt() != 1) throw CryptoException.BadMessage("выгрузка ключа")
            return fromRaw(raw, verified = false)
        }

        private fun fromRaw(raw: ByteArray, verified: Boolean): MegolmInboundSession = MegolmInboundSession(
            MegolmRatchet(raw.copyOfRange(HEADER, HEADER + MegolmRatchet.LENGTH), raw.bigEndianUInt(1)),
            raw.copyOfRange(HEADER + MegolmRatchet.LENGTH, HEADER + MegolmRatchet.LENGTH + 32),
            verified,
        )
    }

    /** Выгрузка начиная с индекса `index` (для пересылки ключа и бэкапа). */
    fun export(at: UInt? = null): String {
        val ratchet = initialRatchet.copy()
        if (at != null) {
            if (at < ratchet.counter) throw CryptoException.UnknownMessageIndex
            ratchet.advanceTo(at)
        }
        return Base64.encodeUnpadded(byteArrayOf(1) + ratchet.counter.toBigEndianBytes() + ratchet.data + signingKey)
    }

    /**
     * Расшифровка `ciphertext` события `m.room.encrypted`. Возвращает открытый текст и индекс сообщения
     * (индекс нужен для защиты от повтора: одна пара сессия+индекс не должна встречаться у двух разных событий).
     */
    fun decrypt(ciphertext: String): Decrypted {
        val parsed = MegolmMessage.parse(Base64.decode(ciphertext))
        if (!Ed25519KeyPair.verify(parsed.signature, parsed.body + parsed.mac, signingKey)) throw CryptoException.BadSignature
        val index = parsed.message.messageIndex
        if (index < initialRatchet.counter) throw CryptoException.UnknownMessageIndex
        val ratchet = initialRatchet.copy()
        ratchet.advanceTo(index)
        val keys = ratchet.messageKeys()
        val mac = Hash.hmacSha256(keys.mac, parsed.body).copyOfRange(0, MegolmMessage.MAC_LENGTH)
        if (!mac.constantTimeEquals(parsed.mac)) throw CryptoException.BadMAC
        return Decrypted(Aes.cbcDecrypt(keys.aes, keys.iv, parsed.message.ciphertext), index)
    }
}
