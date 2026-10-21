package chat.nyutag.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonValueTest {
    @Test
    fun parse() {
        val value = JsonValue.parse("""{"a":1,"b":[true,"x",2.5],"c":{"d":null},"big":1760000000000}""")
        assertEquals(1L, value["a"]!!.long)
        assertEquals(1, value["a"]!!.int)
        assertEquals(true, value["b"]!![0]!!.bool)
        assertEquals("x", value["b"]!![1]!!.string)
        assertEquals(2.5, value["b"]!![2]!!.double)
        assertNull(value["b"]!![2]!!.long)
        assertTrue(value.at("c", "d")!!.isNull)
        assertNull(value.at("c", "e"))
        assertEquals(1_760_000_000_000L, value["big"]!!.long)
        assertNull(value["big"]!!.int)
        assertThrows(JsonParseException::class.java) { JsonValue.parse("{\"a\":}") }
        assertThrows(JsonParseException::class.java) { JsonValue.parse("[1] x") }
    }

    @Test
    fun canonical() {
        val cases = listOf(
            """{"b":"2","a":"1"}""" to """{"a":"1","b":"2"}""",
            """{"本":2,"日":1}""" to """{"日":1,"本":2}""",
            """{"a":"日"}""" to """{"a":"日"}""",
            """{"a":null}""" to """{"a":null}""",
            """{"a":"\n","b":"\u0000"}""" to """{"a":"\n","b":"\u0000"}""",
            """[{"z":1,"y":[2,{"x":3}]}]""" to """[{"y":[2,{"x":3}],"z":1}]""",
            """{"a": 1.0, "b": 1e3, "c": -0.5}""" to """{"a":1,"b":1000,"c":-0.5}""",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, JsonValue.parse(input).canonical(), input)
        }
    }

    @Test
    fun roundtrip() {
        val original = JsonValue.of(
            "type" to "m.room.message",
            "content" to JsonValue.of("body" to "привет \"мир\"\\", "n" to 5, "f" to 1.25, "ok" to true, "none" to null),
            "list" to listOf(1, "two", listOf(3)),
        )
        val text = original.compact()
        assertEquals(original, JsonValue.parse(text))
        assertEquals("""{"type":"m.room.message","content":{"body":"привет \"мир\"\\","n":5,"f":1.25,"ok":true,"none":null},"list":[1,"two",[3]]}""", text)
        assertEquals(text, JsonValue.parse(text).compact())
    }

    @Test
    fun wrapRejectsUnknown() {
        assertThrows(IllegalArgumentException::class.java) { JsonValue.wrap(Any()) }
        assertEquals(JsonValue.Int(7), JsonValue.wrap(7u))
    }
}
