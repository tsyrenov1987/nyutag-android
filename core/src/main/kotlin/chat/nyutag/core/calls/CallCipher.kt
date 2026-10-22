// Нютаг. Ключи звонка и шифр кадров, как у iOS-клиента.
// Каждая сторона на звонок заводит одноразовую пару X25519 и отдаёт открытый ключ в offer/answer. Из общего
// секрета HKDF-SHA256 выводит два ключа ChaChaPoly (по одному на направление) и «слот» релея: релей сводит
// участников по слоту и не знает ни комнаты, ни ключа.
//
// Кадр: [вид 1 байт][номер 4 байта BE][шифртекст][метка 16 байт]. Одноразовое число — номер кадра, дополненный
// нулями, а для видео и служебных — с видом в первом байте: ключи у направлений разные, у каждого вида свой счёт,
// поэтому оно не повторяется. Заголовок входит в проверку подлинности.

package chat.nyutag.core.calls

import chat.nyutag.crypto.Curve25519KeyPair
import chat.nyutag.crypto.Hash
import chat.nyutag.crypto.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CallKeyPair {
    private val pair = Curve25519KeyPair()

    val publicKey: ByteArray get() = pair.publicKey

    /** Ключи звонка. `isCaller` — мы звоним: наш ключ отправки первый в выводе. */
    fun secret(theirs: ByteArray, callID: String, isCaller: Boolean): CallSecret {
        val shared = pair.sharedSecret(theirs)
        val bytes = Hash.hkdfSha256(shared, callID.toByteArray(Charsets.UTF_8), "nyutag call v1".toByteArray(Charsets.UTF_8), 80)
        val callerKey = bytes.copyOfRange(0, 32)
        val calleeKey = bytes.copyOfRange(32, 64)
        val slot = bytes.copyOfRange(64, 80).joinToString("") { "%02x".format(it) }
        return CallSecret(slot, if (isCaller) callerKey else calleeKey, if (isCaller) calleeKey else callerKey)
    }
}

class CallSecret(
    /** 32 hex-символа: адрес встречи на релее. */
    val slot: String,
    internal val sendKey: ByteArray,
    internal val receiveKey: ByteArray,
) {
    companion object {
        /** Групповой звонок: у каждого участника свой случайный ключ, им он шифрует свои кадры, а остальные — открывают. */
        fun group(slot: String, key: ByteArray): CallSecret? = if (key.size == 32) CallSecret(slot, key, key) else null

        fun makeGroupKey(): ByteArray = Random.bytes(32)
    }
}

enum class FrameKind(val code: Byte) {
    AUDIO(1),
    VIDEO(2),
    /** Служебное внутри звонка: просьба о ключевом кадре, камера выключена. */
    CONTROL(3);

    companion object {
        fun of(code: Byte): FrameKind? = entries.firstOrNull { it.code == code }
    }
}

class OpenedFrame(val kind: FrameKind, val sequence: Long, val payload: ByteArray)

/** Запечатывает исходящие кадры и открывает входящие. Номер исходящих растёт сам: один шифр — один вид кадров.
 *  `seal` не потокобезопасен (один поток отправки), `open` можно звать откуда угодно. */
class CallCipher(private val secret: CallSecret) {
    private var nextSequence = 0L

    fun seal(kind: FrameKind, payload: ByteArray): ByteArray? {
        val sequence = nextSequence
        nextSequence = (nextSequence + 1) and 0xFFFF_FFFFL
        val header = byteArrayOf(kind.code, (sequence shr 24).toByte(), (sequence shr 16).toByte(), (sequence shr 8).toByte(), sequence.toByte())
        val box = runCatching { chacha(Cipher.ENCRYPT_MODE, secret.sendKey, nonce(kind, sequence), header, payload) }.getOrNull() ?: return null
        return header + box
    }

    /** `null` — кадр подделан, повреждён или не от собеседника. */
    fun open(packet: ByteArray): OpenedFrame? {
        if (packet.size < HEADER_SIZE + 16) return null
        val kind = FrameKind.of(packet[0]) ?: return null
        val sequence = ((packet[1].toLong() and 0xFF) shl 24) or ((packet[2].toLong() and 0xFF) shl 16) or
            ((packet[3].toLong() and 0xFF) shl 8) or (packet[4].toLong() and 0xFF)
        val header = packet.copyOfRange(0, HEADER_SIZE)
        val box = packet.copyOfRange(HEADER_SIZE, packet.size)
        val payload = runCatching { chacha(Cipher.DECRYPT_MODE, secret.receiveKey, nonce(kind, sequence), header, box) }.getOrNull() ?: return null
        return OpenedFrame(kind, sequence, payload)
    }

    companion object {
        const val HEADER_SIZE = 5

        private fun nonce(kind: FrameKind, sequence: Long): ByteArray {
            val bytes = ByteArray(12)
            // Голос — с нулём, как в первой версии кадра.
            if (kind != FrameKind.AUDIO) bytes[0] = kind.code
            bytes[8] = (sequence shr 24).toByte()
            bytes[9] = (sequence shr 16).toByte()
            bytes[10] = (sequence shr 8).toByte()
            bytes[11] = sequence.toByte()
            return bytes
        }

        /** Шифртекст с меткой в конце, как `ChaChaPoly` у iOS. Имя шифра у JDK и у Conscrypt (Android) разное. */
        private fun chacha(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
            val cipher = cipher()
            cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        }

        @Volatile private var algorithm: String? = null

        private fun cipher(): Cipher {
            algorithm?.let { return Cipher.getInstance(it) }
            for (name in listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding")) {
                val cipher = runCatching { Cipher.getInstance(name) }.getOrNull() ?: continue
                algorithm = name
                return cipher
            }
            throw IllegalStateException("нет ChaCha20-Poly1305")
        }
    }
}
