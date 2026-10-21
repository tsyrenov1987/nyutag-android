// Нютаг. Olm: шифрование между двумя устройствами по спецификации Olm (matrix.org/docs/spec/olm).
// Установка сессии тройным Диффи–Хеллманом (3DH), дальше двойной храповик: корневой ключ обновляется
// при каждой смене ключа храповика собеседника, цепочки — HMAC-SHA-256 на каждое сообщение.
// Состояние пишется в JSON того же вида, что у iOS-клиента (Swift Codable): байты — base64 с набивкой,
// `lastUsed` — секунды от 2001-01-01.

package chat.nyutag.crypto

import chat.nyutag.json.JsonValue
import java.io.ByteArrayOutputStream

/** Секунды от 2001-01-01 (как Date в Swift Codable) ↔ миллисекунды эпохи. */
internal object AppleTime {
    private const val REFERENCE_EPOCH_SECONDS = 978_307_200.0
    fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0 - REFERENCE_EPOCH_SECONDS
    fun toEpochMillis(seconds: Double): Long = ((seconds + REFERENCE_EPOCH_SECONDS) * 1000).toLong()
    fun fromEpochMillis(millis: Long): Double = millis / 1000.0 - REFERENCE_EPOCH_SECONDS
}

internal fun JsonValue.bytes(key: String): ByteArray =
    Base64.decode(this[key]?.string ?: throw CryptoException.BadMessage("состояние: нет поля $key"))

internal fun JsonValue.bytesOrNull(key: String): ByteArray? = this[key]?.string?.let { Base64.decode(it) }

/** Обычное сообщение Olm (тип 1): версия 3, protobuf (ключ храповика, номер в цепочке, шифртекст), 8 байт MAC. */
internal class OlmMessage(val ratchetKey: ByteArray, val chainIndex: UInt, val ciphertext: ByteArray) {
    /** Байты до MAC. */
    fun body(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        Protobuf.appendBytes(0x0A, ratchetKey, out)
        out.write(0x10)
        Protobuf.appendVarint(chainIndex.toULong(), out)
        Protobuf.appendBytes(0x22, ciphertext, out)
        return out.toByteArray()
    }

    class Parsed(val message: OlmMessage, val body: ByteArray, val mac: ByteArray)

    companion object {
        const val VERSION = 3
        const val MAC_LENGTH = 8

        fun parse(raw: ByteArray): Parsed {
            if (raw.size <= 1 + MAC_LENGTH || raw[0].toInt() != VERSION) throw CryptoException.BadMessage("olm: версия или длина")
            val bodyEnd = raw.size - MAC_LENGTH
            var ratchetKey: ByteArray? = null
            var index: UInt? = null
            var ciphertext: ByteArray? = null
            val reader = Protobuf.Reader(raw, 1, bodyEnd)
            while (true) {
                val (tag, value) = reader.next() ?: break
                when {
                    tag == 1 && value is Protobuf.Value.Bytes -> ratchetKey = value.value
                    tag == 2 && value is Protobuf.Value.Varint -> index = value.value.toUInt()
                    tag == 4 && value is Protobuf.Value.Bytes -> ciphertext = value.value
                }
            }
            if (ratchetKey == null || ratchetKey.size != 32 || index == null || ciphertext == null) {
                throw CryptoException.BadMessage("olm: нет полей")
            }
            return Parsed(OlmMessage(ratchetKey, index, ciphertext), raw.copyOfRange(0, bodyEnd), raw.copyOfRange(bodyEnd, raw.size))
        }
    }
}

/** Сообщение установки сессии (тип 0): одноразовый ключ получателя, базовый и долговременный ключи отправителя и обычное сообщение внутри. */
internal class OlmPreKeyMessage(val oneTimeKey: ByteArray, val baseKey: ByteArray, val identityKey: ByteArray, val message: ByteArray) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(OlmMessage.VERSION)
        Protobuf.appendBytes(0x0A, oneTimeKey, out)
        Protobuf.appendBytes(0x12, baseKey, out)
        Protobuf.appendBytes(0x1A, identityKey, out)
        Protobuf.appendBytes(0x22, message, out)
        return out.toByteArray()
    }

    companion object {
        fun parse(raw: ByteArray): OlmPreKeyMessage {
            if (raw.size <= 1 || raw[0].toInt() != OlmMessage.VERSION) throw CryptoException.BadMessage("olm prekey: версия")
            val fields = HashMap<Int, ByteArray>()
            val reader = Protobuf.Reader(raw, 1)
            while (true) {
                val (tag, value) = reader.next() ?: break
                if (value is Protobuf.Value.Bytes) fields[tag] = value.value
            }
            val oneTime = fields[1]
            val base = fields[2]
            val identity = fields[3]
            val message = fields[4]
            if (oneTime == null || oneTime.size != 32 || base == null || base.size != 32 ||
                identity == null || identity.size != 32 || message == null) {
                throw CryptoException.BadMessage("olm prekey: нет полей")
            }
            return OlmPreKeyMessage(oneTime, base, identity, message)
        }
    }
}

/** Зашифрованное сообщение Olm в виде поля `ciphertext[ключ].{type, body}`. */
data class OlmEncrypted(val type: Kind, val body: String) {
    enum class Kind(val rawValue: Int) {
        PRE_KEY(0),
        NORMAL(1);

        companion object {
            fun from(rawValue: Int): Kind? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }
}

/** Звено цепочки: ключ и номер следующего сообщения. */
internal class OlmChainKey(var key: ByteArray, var index: UInt) {
    fun messageKey(): ByteArray = Hash.hmacSha256(key, byteArrayOf(1))

    fun advance() {
        key = Hash.hmacSha256(key, byteArrayOf(2))
        index += 1u
    }

    fun copy(): OlmChainKey = OlmChainKey(key, index)

    fun toJson(): JsonValue = JsonValue.of("key" to Base64.encodePadded(key), "index" to index.toLong())

    companion object {
        fun fromJson(json: JsonValue): OlmChainKey = OlmChainKey(
            json.bytes("key"),
            json["index"]?.long?.toUInt() ?: throw CryptoException.BadMessage("состояние: index"),
        )
    }
}

/** Ключ пропущенного сообщения: сохраняем, чтобы расшифровать его, если оно придёт позже. */
internal class OlmSkippedKey(val ratchetKey: ByteArray, val index: UInt, val key: ByteArray) {
    fun toJson(): JsonValue = JsonValue.of(
        "ratchetKey" to Base64.encodePadded(ratchetKey), "index" to index.toLong(), "key" to Base64.encodePadded(key),
    )

    companion object {
        fun fromJson(json: JsonValue): OlmSkippedKey = OlmSkippedKey(
            json.bytes("ratchetKey"),
            json["index"]?.long?.toUInt() ?: throw CryptoException.BadMessage("состояние: index"),
            json.bytes("key"),
        )
    }
}

private class ReceiverChain(val ratchetKey: ByteArray, var chain: OlmChainKey)

/** Сессия Olm с одним устройством собеседника. */
class OlmSession private constructor(
    /** Ключи установки: долговременный ключ инициатора, его базовый ключ, одноразовый ключ получателя. */
    private val aliceIdentityKey: ByteArray,
    private val aliceBaseKey: ByteArray,
    private val bobOneTimeKey: ByteArray,
    private var rootKey: ByteArray,
    private var senderRatchet: Curve25519KeyPair?,
    private var senderChain: OlmChainKey?,
    /** Новые в начале: ключ храповика собеседника и его цепочка. */
    private val receiverChains: ArrayList<ReceiverChain>,
    private val skippedKeys: ArrayList<OlmSkippedKey>,
    receivedMessage: Boolean,
    /** Ключ собеседника (Curve25519, base64): с ним связана сессия. */
    val theirIdentityKey: String,
    lastUsedSeconds: Double,
) {
    /** Пока собеседник нам не ответил, шлём сообщения установки (тип 0). */
    var receivedMessage: Boolean = receivedMessage
        private set

    /** Секунды от 2001-01-01, как в iOS-клиенте. */
    private var lastUsedSeconds: Double = lastUsedSeconds

    val lastUsedEpochMillis: Long get() = AppleTime.toEpochMillis(lastUsedSeconds)

    /** sha256(долговременный ключ инициатора ‖ базовый ключ ‖ одноразовый ключ получателя). */
    val sessionID: String get() = Base64.encodeUnpadded(Hash.sha256(aliceIdentityKey + aliceBaseKey + bobOneTimeKey))

    companion object {
        internal const val MAX_RECEIVER_CHAINS = 5
        internal const val MAX_SKIPPED_KEYS = 40
        internal const val MAX_MESSAGE_GAP = 2000u

        /** Исходящая сессия (мы Алиса): нужен долговременный и одноразовый ключи собеседника из `/keys/claim`. */
        fun outbound(identity: Curve25519KeyPair, theirIdentityKey: ByteArray, theirOneTimeKey: ByteArray): OlmSession {
            if (theirIdentityKey.size != 32 || theirOneTimeKey.size != 32) throw CryptoException.BadKeyLength
            val base = Curve25519KeyPair()
            val ratchet = Curve25519KeyPair()
            val secret = identity.sharedSecret(theirOneTimeKey) + base.sharedSecret(theirIdentityKey) + base.sharedSecret(theirOneTimeKey)
            val (root, chain) = rootKDF(secret)
            return OlmSession(
                aliceIdentityKey = identity.publicKey, aliceBaseKey = base.publicKey, bobOneTimeKey = theirOneTimeKey,
                rootKey = root, senderRatchet = ratchet, senderChain = OlmChainKey(chain, 0u),
                receiverChains = ArrayList(), skippedKeys = ArrayList(), receivedMessage = false,
                theirIdentityKey = Base64.encodeUnpadded(theirIdentityKey), lastUsedSeconds = AppleTime.nowSeconds(),
            )
        }

        /** Входящая сессия (мы Боб) из сообщения установки. `oneTimeKey` — наш ключ, на который сослался отправитель. */
        internal fun inbound(identity: Curve25519KeyPair, oneTimeKey: Curve25519KeyPair, preKey: OlmPreKeyMessage): OlmSession {
            val inner = OlmMessage.parse(preKey.message).message
            val secret = oneTimeKey.sharedSecret(preKey.identityKey) + identity.sharedSecret(preKey.baseKey) + oneTimeKey.sharedSecret(preKey.baseKey)
            val (root, chain) = rootKDF(secret)
            return OlmSession(
                aliceIdentityKey = preKey.identityKey, aliceBaseKey = preKey.baseKey, bobOneTimeKey = preKey.oneTimeKey,
                rootKey = root, senderRatchet = null, senderChain = null,
                receiverChains = arrayListOf(ReceiverChain(inner.ratchetKey, OlmChainKey(chain, 0u))),
                skippedKeys = ArrayList(), receivedMessage = false,
                theirIdentityKey = Base64.encodeUnpadded(preKey.identityKey), lastUsedSeconds = AppleTime.nowSeconds(),
            )
        }

        private fun rootKDF(secret: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Hash.hkdfSha256(secret, ByteArray(0), "OLM_ROOT".toByteArray(), 64)
            return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
        }

        private fun ratchetKDF(root: ByteArray, secret: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Hash.hkdfSha256(secret, root, "OLM_RATCHET".toByteArray(), 64)
            return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
        }

        private class MessageKeys(val aes: ByteArray, val mac: ByteArray, val iv: ByteArray)

        private fun messageKeys(messageKey: ByteArray): MessageKeys {
            val out = Hash.hkdfSha256(messageKey, ByteArray(0), "OLM_KEYS".toByteArray(), 80)
            return MessageKeys(out.copyOfRange(0, 32), out.copyOfRange(32, 64), out.copyOfRange(64, 80))
        }

        private fun open(body: ByteArray, mac: ByteArray, ciphertext: ByteArray, messageKey: ByteArray): ByteArray {
            val keys = messageKeys(messageKey)
            val expected = Hash.hmacSha256(keys.mac, body).copyOfRange(0, OlmMessage.MAC_LENGTH)
            if (!expected.constantTimeEquals(mac)) throw CryptoException.BadMAC
            return Aes.cbcDecrypt(keys.aes, keys.iv, ciphertext)
        }

        /** Прогон цепочки до номера сообщения с запоминанием пропущенных ключей, затем проверка и расшифровка. */
        private fun advance(chain: OlmChainKey, message: OlmMessage, body: ByteArray, mac: ByteArray,
                            skipped: MutableList<OlmSkippedKey>): ByteArray {
            if (message.chainIndex < chain.index) throw CryptoException.UnknownMessageIndex
            if (message.chainIndex - chain.index > MAX_MESSAGE_GAP) throw CryptoException.BadMessage("olm: слишком большой разрыв")
            val work = chain.copy()
            val pending = ArrayList<OlmSkippedKey>()
            while (work.index < message.chainIndex) {
                pending.add(OlmSkippedKey(message.ratchetKey, work.index, work.messageKey()))
                work.advance()
            }
            val plaintext = open(body, mac, message.ciphertext, work.messageKey())
            work.advance()
            chain.key = work.key
            chain.index = work.index
            skipped.addAll(pending.takeLast(MAX_SKIPPED_KEYS))
            return plaintext
        }

        fun fromJson(json: JsonValue): OlmSession = OlmSession(
            aliceIdentityKey = json.bytes("aliceIdentityKey"),
            aliceBaseKey = json.bytes("aliceBaseKey"),
            bobOneTimeKey = json.bytes("bobOneTimeKey"),
            rootKey = json.bytes("rootKey"),
            senderRatchet = json.bytesOrNull("senderRatchet")?.let { Curve25519KeyPair(it) },
            senderChain = json["senderChain"]?.takeUnless { it.isNull }?.let { OlmChainKey.fromJson(it) },
            receiverChains = ArrayList((json["receiverChains"]?.array ?: emptyList()).map {
                ReceiverChain(it.bytes("ratchetKey"), OlmChainKey.fromJson(it["chain"] ?: throw CryptoException.BadMessage("состояние: chain")))
            }),
            skippedKeys = ArrayList((json["skippedKeys"]?.array ?: emptyList()).map { OlmSkippedKey.fromJson(it) }),
            receivedMessage = json["receivedMessage"]?.bool ?: throw CryptoException.BadMessage("состояние: receivedMessage"),
            theirIdentityKey = json["theirIdentityKey"]?.string ?: throw CryptoException.BadMessage("состояние: theirIdentityKey"),
            lastUsedSeconds = json["lastUsed"]?.double ?: throw CryptoException.BadMessage("состояние: lastUsed"),
        )

        fun fromJson(text: String): OlmSession = fromJson(JsonValue.parse(text))
    }

    fun toJson(): JsonValue {
        val fields = linkedMapOf<String, Any?>(
            "aliceIdentityKey" to Base64.encodePadded(aliceIdentityKey),
            "aliceBaseKey" to Base64.encodePadded(aliceBaseKey),
            "bobOneTimeKey" to Base64.encodePadded(bobOneTimeKey),
            "rootKey" to Base64.encodePadded(rootKey),
        )
        senderRatchet?.let { fields["senderRatchet"] = Base64.encodePadded(it.rawRepresentation) }
        senderChain?.let { fields["senderChain"] = it.toJson() }
        fields["receiverChains"] = receiverChains.map { JsonValue.of("ratchetKey" to Base64.encodePadded(it.ratchetKey), "chain" to it.chain.toJson()) }
        fields["skippedKeys"] = skippedKeys.map { it.toJson() }
        fields["receivedMessage"] = receivedMessage
        fields["theirIdentityKey"] = theirIdentityKey
        fields["lastUsed"] = lastUsedSeconds
        return JsonValue.wrap(fields)
    }

    fun toJsonString(): String = toJson().compact()

    // MARK: Установка

    /** Подходит ли сообщение установки к этой сессии (повторы тип 0 до первого ответа). */
    internal fun matches(preKey: OlmPreKeyMessage): Boolean =
        preKey.identityKey.contentEquals(aliceIdentityKey) && preKey.baseKey.contentEquals(aliceBaseKey) &&
            preKey.oneTimeKey.contentEquals(bobOneTimeKey)

    /** Подходит ли сообщение установки (тип 0, base64) к этой сессии. */
    fun matches(preKeyBody: String): Boolean {
        val preKey = try {
            OlmPreKeyMessage.parse(Base64.decode(preKeyBody))
        } catch (e: CryptoException) {
            return false
        }
        return matches(preKey)
    }

    // MARK: Шифрование

    fun encrypt(plaintext: ByteArray): OlmEncrypted {
        if (senderChain == null) {
            // Собеседник сменил ключ храповика: делаем свой новый и шагаем корнем.
            val theirs = receiverChains.firstOrNull()?.ratchetKey ?: throw CryptoException.BadMessage("olm: нет цепочки")
            val ratchet = Curve25519KeyPair()
            val (root, chain) = ratchetKDF(rootKey, ratchet.sharedSecret(theirs))
            rootKey = root
            senderRatchet = ratchet
            senderChain = OlmChainKey(chain, 0u)
        }
        val chain = senderChain ?: throw CryptoException.BadMessage("olm: нет цепочки")
        val ratchet = senderRatchet ?: throw CryptoException.BadMessage("olm: нет цепочки")
        val keys = messageKeys(chain.messageKey())
        val ciphertext = Aes.cbcEncrypt(keys.aes, keys.iv, plaintext)
        val body = OlmMessage(ratchet.publicKey, chain.index, ciphertext).body()
        val raw = body + Hash.hmacSha256(keys.mac, body).copyOfRange(0, OlmMessage.MAC_LENGTH)
        chain.advance()
        lastUsedSeconds = AppleTime.nowSeconds()

        if (receivedMessage) return OlmEncrypted(OlmEncrypted.Kind.NORMAL, Base64.encodeUnpadded(raw))
        val preKey = OlmPreKeyMessage(bobOneTimeKey, aliceBaseKey, aliceIdentityKey, raw)
        return OlmEncrypted(OlmEncrypted.Kind.PRE_KEY, Base64.encodeUnpadded(preKey.encode()))
    }

    // MARK: Расшифровка

    fun decrypt(encrypted: OlmEncrypted): ByteArray {
        val raw = Base64.decode(encrypted.body)
        return when (encrypted.type) {
            OlmEncrypted.Kind.PRE_KEY -> {
                val preKey = OlmPreKeyMessage.parse(raw)
                if (!matches(preKey)) throw CryptoException.BadMessage("olm: сообщение установки от другой сессии")
                decryptMessage(preKey.message)
            }
            OlmEncrypted.Kind.NORMAL -> decryptMessage(raw)
        }
    }

    internal fun decryptMessage(raw: ByteArray): ByteArray {
        val parsed = OlmMessage.parse(raw)
        val message = parsed.message

        // Уже известная цепочка собеседника.
        val known = receiverChains.firstOrNull { it.ratchetKey.contentEquals(message.ratchetKey) }
        if (known != null) {
            if (message.chainIndex < known.chain.index) {
                val position = skippedKeys.indexOfFirst { it.ratchetKey.contentEquals(message.ratchetKey) && it.index == message.chainIndex }
                if (position < 0) throw CryptoException.UnknownMessageIndex
                val plaintext = open(parsed.body, parsed.mac, message.ciphertext, skippedKeys[position].key)
                skippedKeys.removeAt(position)
                finish()
                return plaintext
            }
            val skipped = ArrayList<OlmSkippedKey>()
            val plaintext = advance(known.chain, message, parsed.body, parsed.mac, skipped)
            remember(skipped)
            finish()
            return plaintext
        }

        // Новый ключ храповика собеседника: проверяем на копии и только после удачной расшифровки сохраняем.
        val ours = senderRatchet ?: throw CryptoException.BadMessage("olm: неизвестная цепочка")
        val (root, chainKey) = ratchetKDF(rootKey, ours.sharedSecret(message.ratchetKey))
        val chain = OlmChainKey(chainKey, 0u)
        val skipped = ArrayList<OlmSkippedKey>()
        val plaintext = advance(chain, message, parsed.body, parsed.mac, skipped)
        rootKey = root
        receiverChains.add(0, ReceiverChain(message.ratchetKey, chain))
        if (receiverChains.size > MAX_RECEIVER_CHAINS) receiverChains.removeAt(receiverChains.size - 1)
        senderRatchet = null
        senderChain = null
        remember(skipped)
        finish()
        return plaintext
    }

    private fun finish() {
        receivedMessage = true
        lastUsedSeconds = AppleTime.nowSeconds()
    }

    private fun remember(keys: List<OlmSkippedKey>) {
        skippedKeys.addAll(keys)
        while (skippedKeys.size > MAX_SKIPPED_KEYS) skippedKeys.removeAt(0)
    }
}

/** Ключи устройства: долговременные Curve25519 и Ed25519, одноразовые ключи и запасной ключ. */
class OlmAccount private constructor(
    val identity: Curve25519KeyPair,
    val signing: Ed25519KeyPair,
    /** Номер для идентификатора одноразового ключа (`AAAAAQ` и т.п. в libolm; у нас просто счётчик). */
    private var nextKeyID: UInt,
    private val oneTimeKeys: LinkedHashMap<String, Curve25519KeyPair>,
    /** Ещё не отправленные на сервер одноразовые ключи. */
    private val unpublished: LinkedHashSet<String>,
    private var fallback: FallbackKey?,
    private var previousFallback: FallbackKey?,
) {
    private class FallbackKey(val id: String, val key: Curve25519KeyPair, var published: Boolean)

    constructor() : this(Curve25519KeyPair(), Ed25519KeyPair(), 1u, LinkedHashMap(), LinkedHashSet(), null, null)

    val curve25519Key: String get() = identity.publicKeyBase64
    val ed25519Key: String get() = signing.publicKeyBase64

    fun sign(message: ByteArray): String = Base64.encodeUnpadded(signing.sign(message))

    val storedOneTimeKeyCount: Int get() = oneTimeKeys.size

    /** Новые одноразовые ключи; старые вытесняются, если их больше лимита. */
    fun generateOneTimeKeys(count: Int) {
        repeat(count) {
            val id = keyID(nextKeyID)
            nextKeyID += 1u
            oneTimeKeys[id] = Curve25519KeyPair()
            unpublished.add(id)
        }
        val excess = oneTimeKeys.size - MAX_ONE_TIME_KEYS
        if (excess > 0) {
            for (id in oneTimeKeys.keys.sortedBy(::keyNumber).take(excess)) {
                oneTimeKeys.remove(id)
                unpublished.remove(id)
            }
        }
    }

    /** Неопубликованные одноразовые ключи: идентификатор → открытый ключ base64. */
    val unpublishedOneTimeKeys: Map<String, String>
        get() = unpublished.mapNotNull { id -> oneTimeKeys[id]?.let { id to it.publicKeyBase64 } }.toMap()

    /** Новый запасной ключ; прежний остаётся для ещё не дошедших сообщений. */
    fun generateFallbackKey() {
        fallback?.let { previousFallback = FallbackKey(it.id, it.key, false) }
        val id = keyID(nextKeyID)
        nextKeyID += 1u
        fallback = FallbackKey(id, Curve25519KeyPair(), false)
    }

    /** Идентификатор и открытый ключ (base64) ещё не отправленного запасного ключа. */
    val unpublishedFallbackKey: Pair<String, String>?
        get() = fallback?.takeUnless { it.published }?.let { it.id to it.key.publicKeyBase64 }

    /** После удачного `/keys/upload`. */
    fun markKeysPublished() {
        unpublished.clear()
        fallback?.published = true
    }

    fun forgetPreviousFallbackKey() {
        previousFallback = null
    }

    /** Исходящая сессия к устройству по ключам из `/keys/claim`. */
    fun outboundSession(theirIdentityKey: String, theirOneTimeKey: String): OlmSession =
        OlmSession.outbound(identity, Base64.decode(theirIdentityKey), Base64.decode(theirOneTimeKey))

    class Inbound(val session: OlmSession, val plaintext: ByteArray)

    /**
     * Новая входящая сессия из сообщения установки (тип 0). Использованный одноразовый ключ удаляется,
     * запасной остаётся. `theirIdentityKey` — `sender_key` события, он обязан совпасть с ключом в сообщении.
     */
    fun inboundSession(theirIdentityKey: String, preKeyBody: String): Inbound {
        val preKey = OlmPreKeyMessage.parse(Base64.decode(preKeyBody))
        if (Base64.encodeUnpadded(preKey.identityKey) != theirIdentityKey) throw CryptoException.BadMessage("olm: ключ отправителя не совпал")
        val entry = oneTimeKeys.entries.firstOrNull { it.value.publicKey.contentEquals(preKey.oneTimeKey) }
        val usedID: String?
        val usedKey: Curve25519KeyPair
        when {
            entry != null -> { usedID = entry.key; usedKey = entry.value }
            fallback?.key?.publicKey?.contentEquals(preKey.oneTimeKey) == true -> { usedID = null; usedKey = fallback!!.key }
            previousFallback?.key?.publicKey?.contentEquals(preKey.oneTimeKey) == true -> { usedID = null; usedKey = previousFallback!!.key }
            else -> throw CryptoException.BadMessage("olm: неизвестный одноразовый ключ")
        }
        val session = OlmSession.inbound(identity, usedKey, preKey)
        val plaintext = session.decryptMessage(preKey.message)
        if (usedID != null) {
            oneTimeKeys.remove(usedID)
            unpublished.remove(usedID)
        }
        return Inbound(session, plaintext)
    }

    // MARK: JSON

    fun toJson(): JsonValue {
        val fields = linkedMapOf<String, Any?>(
            "identity" to Base64.encodePadded(identity.rawRepresentation),
            "signing" to Base64.encodePadded(signing.rawRepresentation),
            "nextKeyID" to nextKeyID.toLong(),
            "oneTimeKeys" to oneTimeKeys.map { JsonValue.of("id" to it.key, "key" to Base64.encodePadded(it.value.rawRepresentation)) },
            "unpublished" to unpublished.toList(),
        )
        fallback?.let {
            fields["fallback"] = JsonValue.of("id" to it.id, "key" to Base64.encodePadded(it.key.rawRepresentation), "published" to it.published)
        }
        previousFallback?.let { fields["previousFallback"] = JsonValue.of("id" to it.id, "key" to Base64.encodePadded(it.key.rawRepresentation)) }
        return JsonValue.wrap(fields)
    }

    fun toJsonString(): String = toJson().compact()

    companion object {
        const val MAX_ONE_TIME_KEYS = 100

        private fun keyID(number: UInt): String = Base64.encodeUnpadded(number.toBigEndianBytes())

        private fun keyNumber(id: String): Long {
            val bytes = try { Base64.decode(id) } catch (e: CryptoException) { ByteArray(0) }
            return bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        }

        private fun storedKey(json: JsonValue): FallbackKey = FallbackKey(
            json["id"]?.string ?: throw CryptoException.BadMessage("состояние: id"),
            Curve25519KeyPair(json.bytes("key")),
            json["published"]?.bool ?: false,
        )

        fun fromJson(json: JsonValue): OlmAccount {
            val keys = LinkedHashMap<String, Curve25519KeyPair>()
            for (stored in json["oneTimeKeys"]?.array ?: emptyList()) {
                keys[stored["id"]?.string ?: throw CryptoException.BadMessage("состояние: id")] = Curve25519KeyPair(stored.bytes("key"))
            }
            return OlmAccount(
                identity = Curve25519KeyPair(json.bytes("identity")),
                signing = Ed25519KeyPair(json.bytes("signing")),
                nextKeyID = json["nextKeyID"]?.long?.toUInt() ?: throw CryptoException.BadMessage("состояние: nextKeyID"),
                oneTimeKeys = keys,
                unpublished = LinkedHashSet((json["unpublished"]?.array ?: emptyList()).mapNotNull { it.string }),
                fallback = json["fallback"]?.takeUnless { it.isNull }?.let(::storedKey),
                previousFallback = json["previousFallback"]?.takeUnless { it.isNull }?.let(::storedKey),
            )
        }

        fun fromJson(text: String): OlmAccount = fromJson(JsonValue.parse(text))
    }
}
