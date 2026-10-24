// Нютаг. Звонок в комнате: свои события, формат на проводе общий с iOS-клиентом.
// `chat.nyutag.ring` — открытый звонок-будильник: по его типу шлюз шлёт пуш о звонке, поэтому он не шифруется и
// ничего секретного не несёт. Остальные — `chat.nyutag.call.offer|answer|end|join|leave`, идут шифром комнаты (и в открытой):
// в offer и answer лежат открытые ключи X25519, из которых стороны выводят ключ кадров.
// Групповой звонок (в любой группе): будильник с `group`, дальше каждый вошедший шлёт
// `chat.nyutag.call.join` со слотом релея, своим ключом кадров и меткой, ушедший — `chat.nyutag.call.leave`.
// `chat.nyutag.call.ringing` — не событие комнаты, а сообщение устройству (to-device): «у меня звонит». Звонящий по нему
// переходит от «Звоним…» к гудкам. В ленту и пуши оно не попадает, старые версии его просто не знают.

package chat.nyutag.core.calls

import chat.nyutag.core.sync.Event
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.Random
import chat.nyutag.json.JsonValue

enum class CallEndReason(val wire: String) {
    HANG_UP("hangup"),
    DECLINED("declined"),
    NO_ANSWER("no_answer"),
    BUSY("busy"),
    LOST("lost"),
    /** Принят на другом устройстве этого же человека. */
    ELSEWHERE("elsewhere");

    companion object {
        fun of(wire: String?): CallEndReason = entries.firstOrNull { it.wire == wire } ?: HANG_UP
    }
}

sealed class CallSignal {
    abstract val callID: String

    /** Звонок пошёл. `lifetimeMillis` — сколько звонить, после него будильник считается пропущенным. */
    data class Ring(override val callID: String, val video: Boolean, val lifetimeMillis: Long, val group: Boolean = false) : CallSignal()

    class Offer(override val callID: String, val key: ByteArray, val video: Boolean) : CallSignal() {
        override fun equals(other: Any?) = other is Offer && other.callID == callID && other.key.contentEquals(key) && other.video == video
        override fun hashCode() = callID.hashCode()
    }

    class Answer(override val callID: String, val key: ByteArray, val deviceID: String) : CallSignal() {
        override fun equals(other: Any?) = other is Answer && other.callID == callID && other.key.contentEquals(key) && other.deviceID == deviceID
        override fun hashCode() = callID.hashCode()
    }

    data class End(override val callID: String, val reason: CallEndReason) : CallSignal()

    /** Вошёл в групповой звонок: кадры с меткой `tag` открываются ключом `key`. */
    class Join(override val callID: String, val slot: String, val key: ByteArray, val tag: Long, val deviceID: String) : CallSignal() {
        override fun equals(other: Any?) = other is Join && other.callID == callID && other.slot == slot &&
            other.key.contentEquals(key) && other.tag == tag && other.deviceID == deviceID
        override fun hashCode() = callID.hashCode()
    }

    data class Leave(override val callID: String, val deviceID: String) : CallSignal()

    /** Будильник звенит на устройстве `deviceID` того, кому звонят. Идёт сообщением устройству, комнату несёт в себе. */
    data class Ringing(override val callID: String, val roomID: String, val deviceID: String) : CallSignal()

    val eventType: String get() = when (this) {
        is Ring -> CallEventType.RING
        is Offer -> CallEventType.OFFER
        is Answer -> CallEventType.ANSWER
        is End -> CallEventType.END
        is Join -> CallEventType.JOIN
        is Leave -> CallEventType.LEAVE
        is Ringing -> CallEventType.RINGING
    }

    /** Нужен ли шифр комнаты. Будильник открыт всегда: иначе сервер не узнает, что это звонок. */
    val wantsRoomEncryption: Boolean get() = this !is Ring

    val content: JsonValue get() = when (this) {
        is Ring -> JsonValue.of(
            "call_id" to callID, "video" to video, "lifetime" to lifetimeMillis, "group" to group,
            "sender_ts" to System.currentTimeMillis(),
            // Шлюз пушей передаёт телефону содержимое как есть; по этому полю приложение понимает, что звонок свой.
            "m.call.intent" to (if (video) "video" else "audio"), "version" to VERSION)
        is Offer -> JsonValue.of("call_id" to callID, "key" to Base64.encodePadded(key), "video" to video, "version" to VERSION)
        is Answer -> JsonValue.of("call_id" to callID, "key" to Base64.encodePadded(key), "device_id" to deviceID, "version" to VERSION)
        is End -> JsonValue.of("call_id" to callID, "reason" to reason.wire, "version" to VERSION)
        is Join -> JsonValue.of("call_id" to callID, "slot" to slot, "key" to Base64.encodePadded(key), "tag" to tag,
                                "device_id" to deviceID, "version" to VERSION)
        is Leave -> JsonValue.of("call_id" to callID, "device_id" to deviceID, "version" to VERSION)
        is Ringing -> JsonValue.of("call_id" to callID, "room_id" to roomID, "device_id" to deviceID, "version" to VERSION)
    }

    companion object {
        const val VERSION = 1

        /** Разбор уже расшифрованного события. Чужие и битые — `null`. */
        fun parse(type: String, content: JsonValue): CallSignal? {
            val id = content["call_id"]?.string ?: return null
            if (!CallID.isValid(id)) return null
            fun key(): ByteArray? {
                val text = content["key"]?.string ?: return null
                val data = runCatching { Base64.decode(text) }.getOrNull() ?: return null
                return if (data.size == 32) data else null
            }
            return when (type) {
                CallEventType.RING -> {
                    val ms = content["lifetime"]?.long ?: 45_000
                    Ring(id, content["video"]?.bool ?: false, ms.coerceIn(5_000, 120_000), content["group"]?.bool ?: false)
                }
                CallEventType.OFFER -> Offer(id, key() ?: return null, content["video"]?.bool ?: false)
                CallEventType.ANSWER -> Answer(id, key() ?: return null, content["device_id"]?.string ?: return null)
                CallEventType.END -> End(id, CallEndReason.of(content["reason"]?.string))
                CallEventType.JOIN -> {
                    val key = key() ?: return null
                    val slot = content["slot"]?.string ?: return null
                    if (!CallID.isValid(slot)) return null
                    val tag = content["tag"]?.long ?: return null
                    if (tag <= 0 || tag > 0xFFFF_FFFFL) return null
                    Join(id, slot, key, tag, content["device_id"]?.string ?: return null)
                }
                CallEventType.LEAVE -> Leave(id, content["device_id"]?.string ?: return null)
                CallEventType.RINGING -> {
                    val room = content["room_id"]?.string ?: return null
                    if (!room.startsWith("!")) return null
                    Ringing(id, room, content["device_id"]?.string ?: return null)
                }
                else -> null
            }
        }
    }
}

/** Сигнал вместе с тем, кто и когда его прислал. */
data class CallMessage(
    val signal: CallSignal,
    val roomID: String,
    val sender: String,
    val eventID: String? = null,
    /** Время сервера, мс с 1970. */
    val timestamp: Long? = null,
) {
    /** Будильник ещё звонит: старые из первой синхронизации или пришедшие с опозданием не поднимают экран. */
    fun isRinging(now: Long = System.currentTimeMillis()): Boolean {
        val ring = signal as? CallSignal.Ring ?: return false
        val timestamp = timestamp ?: return false
        return now - timestamp < ring.lifetimeMillis
    }

    companion object {
        /** Из события ленты (шифрованное уже открыто). Событие без отправителя не годится. */
        fun of(event: Event, roomID: String): CallMessage? {
            if (event.type !in CallEventType.all) return null
            val sender = event.sender ?: return null
            val signal = CallSignal.parse(event.type, event.content) ?: return null
            return CallMessage(signal, roomID, sender, event.eventID, event.originServerTS)
        }

        /** Из сообщения устройству: только «у меня звонит», комната — из содержимого. */
        fun ofToDevice(event: Event): CallMessage? {
            if (event.type != CallEventType.RINGING) return null
            val sender = event.sender ?: return null
            val signal = CallSignal.parse(event.type, event.content) as? CallSignal.Ringing ?: return null
            return CallMessage(signal, signal.roomID, sender)
        }
    }
}

object CallID {
    /** 16 случайных байт в hex. */
    fun make(): String = Random.bytes(16).joinToString("") { "%02x".format(it) }

    fun isValid(id: String): Boolean =
        id.length in 8..64 && id.all { it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_') }
}
