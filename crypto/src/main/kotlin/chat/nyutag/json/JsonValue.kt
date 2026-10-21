// Нютаг. Динамический JSON: содержимое событий Matrix разнородно, поэтому держим его как дерево.
// Разбор и запись свои; canonical JSON — по приложению спецификации Matrix «Signing JSON».

package chat.nyutag.json

sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Int(val value: Long) : JsonValue()
    data class Double(val value: kotlin.Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val value: List<JsonValue>) : JsonValue()
    data class Obj(val value: Map<String, JsonValue>) : JsonValue()

    // MARK: - Доступ

    operator fun get(key: String): JsonValue? = (this as? Obj)?.value?.get(key)
    operator fun get(index: kotlin.Int): JsonValue? = (this as? Arr)?.value?.getOrNull(index)

    /** Значение по пути: `event.at("content", "m.relates_to", "event_id")`. */
    fun at(vararg path: String): JsonValue? {
        var current: JsonValue? = this
        for (key in path) current = current?.get(key) ?: return null
        return current
    }

    val string: String? get() = (this as? Str)?.value
    val long: Long? get() = when (this) {
        is Int -> value
        is Double -> if (value == Math.rint(value) && Math.abs(value) < 9_007_199_254_740_992.0) value.toLong() else null
        else -> null
    }
    val int: kotlin.Int? get() = long?.let { if (it in kotlin.Int.MIN_VALUE..kotlin.Int.MAX_VALUE) it.toInt() else null }
    val double: kotlin.Double? get() = when (this) {
        is Int -> value.toDouble()
        is Double -> value
        else -> null
    }
    val bool: Boolean? get() = (this as? Bool)?.value
    val array: List<JsonValue>? get() = (this as? Arr)?.value
    val obj: Map<String, JsonValue>? get() = (this as? Obj)?.value
    val isNull: Boolean get() = this === Null

    // MARK: - Сериализация

    /** Компактный JSON без экранирования `/`. Для подписей нужен `canonical()`. */
    fun compact(): String = StringBuilder().also { append(it, canonical = false) }.toString()

    /** Canonical JSON: ключи отсортированы по кодовым точкам, без пробелов, целые числа как есть. */
    fun canonical(): String = StringBuilder().also { append(it, canonical = true) }.toString()

    override fun toString(): String = compact()

    private fun append(out: StringBuilder, canonical: Boolean) {
        when (this) {
            is Null -> out.append("null")
            is Bool -> out.append(if (value) "true" else "false")
            is Int -> out.append(value)
            is Double -> if (value == Math.rint(value) && Math.abs(value) < 1e15) out.append(value.toLong()) else out.append(value)
            is Str -> appendEscaped(value, out)
            is Arr -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    item.append(out, canonical)
                }
                out.append(']')
            }
            is Obj -> {
                out.append('{')
                // Сортировка по кодовым точкам Unicode, не по локали и не по UTF-16.
                val keys = if (canonical) value.keys.sortedWith(::compareCodePoints) else value.keys
                keys.forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    appendEscaped(key, out)
                    out.append(':')
                    value.getValue(key).append(out, canonical)
                }
                out.append('}')
            }
        }
    }

    companion object {
        fun of(vararg pairs: Pair<String, Any?>): Obj = Obj(linkedMapOf(*pairs.map { it.first to wrap(it.second) }.toTypedArray()))
        fun list(vararg items: Any?): Arr = Arr(items.map(::wrap))

        /** Kotlin-значение → JSON: null, Boolean, числа, String, List, Map, JsonValue. */
        fun wrap(value: Any?): JsonValue = when (value) {
            null -> Null
            is JsonValue -> value
            is Boolean -> Bool(value)
            is kotlin.Int -> Int(value.toLong())
            is Long -> Int(value)
            is UInt -> Int(value.toLong())
            is kotlin.Double -> Double(value)
            is Float -> Double(value.toDouble())
            is String -> Str(value)
            is List<*> -> Arr(value.map(::wrap))
            is Map<*, *> -> Obj(value.entries.associate { it.key.toString() to wrap(it.value) })
            else -> throw IllegalArgumentException("не JSON-значение: ${value::class}")
        }

        fun parse(text: String): JsonValue = JsonParser(text).parseDocument()

        private fun compareCodePoints(a: String, b: String): kotlin.Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) return ca.compareTo(cb)
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            return (a.length - i).compareTo(b.length - j)
        }

        private fun appendEscaped(string: String, out: StringBuilder) {
            out.append('"')
            for (ch in string) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else -> if (ch < ' ') out.append(String.format("\\u%04x", ch.code)) else out.append(ch)
                }
            }
            out.append('"')
        }
    }
}

class JsonParseException(message: String) : Exception(message)

private class JsonParser(private val text: String) {
    private var pos = 0

    fun parseDocument(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        if (pos != text.length) throw JsonParseException("лишние символы на позиции $pos")
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (pos >= text.length) throw JsonParseException("неожиданный конец")
        return when (val ch = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (ch == '-' || ch in '0'..'9') parseNumber() else throw JsonParseException("неожиданный символ «$ch» на позиции $pos")
        }
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, pos)) throw JsonParseException("ожидалось $word на позиции $pos")
        pos += word.length
        return value
    }

    private fun parseObject(): JsonValue {
        pos++ // {
        val map = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { pos++; return JsonValue.Obj(map) }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw JsonParseException("ожидался ключ на позиции $pos")
            val key = parseString()
            skipWhitespace()
            if (peek() != ':') throw JsonParseException("ожидалось «:» на позиции $pos")
            pos++
            map[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return JsonValue.Obj(map) }
                else -> throw JsonParseException("ожидалось «,» или «}» на позиции $pos")
            }
        }
    }

    private fun parseArray(): JsonValue {
        pos++ // [
        val list = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { pos++; return JsonValue.Arr(list) }
        while (true) {
            list.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return JsonValue.Arr(list) }
                else -> throw JsonParseException("ожидалось «,» или «]» на позиции $pos")
            }
        }
    }

    private fun peek(): Char? = if (pos < text.length) text[pos] else null

    private fun parseString(): String {
        pos++ // "
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) throw JsonParseException("незакрытая строка")
            val ch = text[pos++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) throw JsonParseException("незакрытая строка")
                    when (val esc = text[pos++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw JsonParseException("обрезанный \\u")
                            out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw JsonParseException("неизвестное экранирование \\$esc")
                    }
                }
                else -> {
                    if (ch < ' ') throw JsonParseException("управляющий символ в строке")
                    out.append(ch)
                }
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (peek() == '-') pos++
        var isInteger = true
        while (pos < text.length) {
            val ch = text[pos]
            if (ch in '0'..'9') pos++
            else if (ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') { isInteger = false; pos++ }
            else break
        }
        val token = text.substring(start, pos)
        if (isInteger) token.toLongOrNull()?.let { return JsonValue.Int(it) }
        return JsonValue.Double(token.toDoubleOrNull() ?: throw JsonParseException("не число: $token"))
    }
}
