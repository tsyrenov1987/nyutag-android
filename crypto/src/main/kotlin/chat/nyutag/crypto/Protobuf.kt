// Нютаг. Минимальный protobuf: только varint и length-delimited, как в форматах Olm/Megolm.

package chat.nyutag.crypto

import java.io.ByteArrayOutputStream

internal object Protobuf {
    sealed class Value {
        data class Varint(val value: ULong) : Value()
        class Bytes(val value: ByteArray) : Value()
    }

    fun appendVarint(value: ULong, out: ByteArrayOutputStream) {
        var v = value
        while (v >= 0x80u) {
            out.write(((v and 0x7Fu) or 0x80u).toInt())
            v = v shr 7
        }
        out.write(v.toInt())
    }

    fun appendBytes(tag: Int, bytes: ByteArray, out: ByteArrayOutputStream) {
        out.write(tag)
        appendVarint(bytes.size.toULong(), out)
        out.write(bytes)
    }

    class Reader(private val data: ByteArray, private var position: Int = 0, private val end: Int = data.size) {
        fun varint(): ULong {
            var result = 0uL
            var shift = 0
            while (true) {
                if (position >= end || shift >= 64) throw CryptoException.BadMessage("varint")
                val byte = data[position].toInt() and 0xFF
                position += 1
                result = result or ((byte and 0x7F).toULong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Следующее поле (номер, значение) или null в конце. Поля неизвестных типов — ошибка. */
        fun next(): Pair<Int, Value>? {
            if (position >= end) return null
            val key = varint()
            val field = (key shr 3).toInt()
            return when ((key and 7u).toInt()) {
                0 -> field to Value.Varint(varint())
                2 -> {
                    val length = varint().toLong()
                    if (length < 0 || position + length > end) throw CryptoException.BadMessage("длина поля")
                    val bytes = data.copyOfRange(position, position + length.toInt())
                    position += length.toInt()
                    field to Value.Bytes(bytes)
                }
                else -> throw CryptoException.BadMessage("тип поля")
            }
        }
    }
}
