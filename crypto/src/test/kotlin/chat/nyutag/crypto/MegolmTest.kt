package chat.nyutag.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream

class MegolmTest {
    private fun seeded(): MegolmRatchet = MegolmRatchet(ByteArray(128) { it.toByte() }, 0u)

    private fun part(ratchet: MegolmRatchet, index: Int): ByteArray = ratchet.data.copyOfRange(index * 32, (index + 1) * 32)

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 255, 256, 257, 511, 1000, 65535, 65536, 65537, 70000])
    fun jumpEqualsSteps(target: Long) {
        val stepped = seeded()
        repeat(target.toInt()) { stepped.advance() }
        val jumped = seeded()
        jumped.advanceTo(target.toUInt())
        assertEquals(target.toUInt(), jumped.counter)
        assertEquals(stepped, jumped)
    }

    @Test
    fun jumpFromMiddle() {
        val stepped = seeded()
        repeat(70001) { stepped.advance() }
        val jumped = seeded()
        jumped.advanceTo(300u)
        jumped.advanceTo(70001u)
        assertEquals(stepped, jumped)
    }

    @Test
    fun rehashDefinitions() {
        val start = seeded()
        val one = start.copy()
        one.advance()
        assertArrayEquals(Hash.hmacSha256(part(start, 3), byteArrayOf(3)), part(one, 3))
        assertArrayEquals(part(start, 2), part(one, 2))

        val at255 = seeded().also { it.advanceTo(255u) }
        val at256 = at255.copy().also { it.advance() }
        assertArrayEquals(Hash.hmacSha256(part(at255, 2), byteArrayOf(3)), part(at256, 3))
        assertArrayEquals(Hash.hmacSha256(part(at255, 2), byteArrayOf(2)), part(at256, 2))
        assertArrayEquals(part(at255, 1), part(at256, 1))
    }

    @Test
    fun roundTripThroughSessionKey() {
        val outbound = MegolmOutboundSession()
        outbound.encrypt("нулевое".toByteArray())
        val inbound = MegolmInboundSession.fromSessionKey(outbound.sessionKey())
        assertEquals(outbound.sessionID, inbound.sessionID)
        assertEquals(1u, inbound.firstKnownIndex)
        assertTrue(inbound.isVerified)

        val messages = (0 until 300).map { outbound.encrypt("сообщение $it".toByteArray()) }
        val last = inbound.decrypt(messages[299])
        assertEquals("сообщение 299", String(last.plaintext))
        assertEquals(300u, last.messageIndex)
        val first = inbound.decrypt(messages[0])
        assertEquals("сообщение 0", String(first.plaintext))
        assertEquals(1u, first.messageIndex)
        assertEquals(301u, outbound.messageIndex)
    }

    @Test
    fun exportFromLaterIndex() {
        val outbound = MegolmOutboundSession()
        val inbound = MegolmInboundSession.fromSessionKey(outbound.sessionKey())
        val messages = (0 until 10).map { outbound.encrypt("m$it".toByteArray()) }
        val exported = MegolmInboundSession.fromExportedKey(inbound.export(5u))
        assertFalse(exported.isVerified)
        assertEquals(5u, exported.firstKnownIndex)
        assertEquals(inbound.sessionID, exported.sessionID)
        assertThrows(CryptoException.UnknownMessageIndex::class.java) { exported.decrypt(messages[4]) }
        assertEquals("m7", String(exported.decrypt(messages[7]).plaintext))
        assertEquals(inbound.export(), MegolmInboundSession.fromExportedKey(inbound.export()).export())
        assertThrows(CryptoException.UnknownMessageIndex::class.java) { exported.export(2u) }
    }

    @Test
    fun tampering() {
        val outbound = MegolmOutboundSession()
        val inbound = MegolmInboundSession.fromSessionKey(outbound.sessionKey())
        val message = outbound.encrypt("целое".toByteArray())
        val raw = Base64.decode(message)
        raw[raw.size - 70] = (raw[raw.size - 70].toInt() xor 1).toByte()
        assertThrows(CryptoException.BadSignature::class.java) { inbound.decrypt(Base64.encodeUnpadded(raw)) }

        val key = Base64.decode(outbound.sessionKey())
        key[10] = (key[10].toInt() xor 1).toByte()
        assertThrows(CryptoException.BadSignature::class.java) { MegolmInboundSession.fromSessionKey(Base64.encodeUnpadded(key)) }
        assertThrows(CryptoException.BadMessage::class.java) { MegolmInboundSession.fromSessionKey(Base64.encodeUnpadded(key.copyOf(10))) }
    }

    @Test
    fun protobufVarints() {
        for (value in listOf(0u, 1u, 127u, 128u, 300u, 16384u, UInt.MAX_VALUE)) {
            val out = ByteArrayOutputStream()
            Protobuf.appendVarint(value.toULong(), out)
            val bytes = out.toByteArray()
            assertEquals(value.toULong(), Protobuf.Reader(bytes).varint())
        }
        val out = ByteArrayOutputStream()
        Protobuf.appendVarint(300u, out)
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), out.toByteArray())
    }
}
