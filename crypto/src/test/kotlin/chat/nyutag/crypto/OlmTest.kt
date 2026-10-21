package chat.nyutag.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OlmTest {
    private class Pair(val alice: OlmAccount, val bob: OlmAccount, val aliceSession: OlmSession, val bobSession: OlmSession)

    /** Алиса пишет Бобу первое сообщение, Боб заводит входящую сессию. */
    private fun pair(): Pair {
        val alice = OlmAccount()
        val bob = OlmAccount()
        bob.generateOneTimeKeys(1)
        val oneTimeKey = bob.unpublishedOneTimeKeys.values.first()
        bob.markKeysPublished()
        val aliceSession = alice.outboundSession(bob.curve25519Key, oneTimeKey)
        val first = aliceSession.encrypt("привет".toByteArray())
        assertEquals(OlmEncrypted.Kind.PRE_KEY, first.type)
        val inbound = bob.inboundSession(alice.curve25519Key, first.body)
        assertEquals("привет", String(inbound.plaintext))
        assertEquals(0, bob.storedOneTimeKeyCount)
        assertEquals(aliceSession.sessionID, inbound.session.sessionID)
        return Pair(alice, bob, aliceSession, inbound.session)
    }

    @Test
    fun conversationAcrossRatchets() {
        val p = pair()
        // Пока Боб не ответил, Алиса шлёт сообщения установки, и они подходят к той же сессии.
        val second = p.aliceSession.encrypt("ещё".toByteArray())
        assertEquals(OlmEncrypted.Kind.PRE_KEY, second.type)
        assertTrue(p.bobSession.matches(second.body))
        assertEquals("ещё", String(p.bobSession.decrypt(second)))

        for (round in 1..4) {
            val reply = p.bobSession.encrypt("ответ $round".toByteArray())
            assertEquals(OlmEncrypted.Kind.NORMAL, reply.type)
            assertEquals("ответ $round", String(p.aliceSession.decrypt(reply)))
            val next = p.aliceSession.encrypt("вопрос $round".toByteArray())
            assertEquals(OlmEncrypted.Kind.NORMAL, next.type)
            assertEquals("вопрос $round", String(p.bobSession.decrypt(next)))
        }
    }

    @Test
    fun outOfOrderAndReplay() {
        val p = pair()
        val m1 = p.aliceSession.encrypt("1".toByteArray())
        val m2 = p.aliceSession.encrypt("2".toByteArray())
        val m3 = p.aliceSession.encrypt("3".toByteArray())
        assertEquals("3", String(p.bobSession.decrypt(m3)))
        assertEquals("1", String(p.bobSession.decrypt(m1)))
        assertEquals("2", String(p.bobSession.decrypt(m2)))
        assertThrows(CryptoException.UnknownMessageIndex::class.java) { p.bobSession.decrypt(m1) }
    }

    @Test
    fun tamperedMessageRejected() {
        val p = pair()
        val good = p.aliceSession.encrypt("целое".toByteArray())
        val raw = Base64.decode(good.body)
        raw[raw.size - 12] = (raw[raw.size - 12].toInt() xor 0x01).toByte()
        val bad = OlmEncrypted(good.type, Base64.encodeUnpadded(raw))
        assertThrows(CryptoException.BadMAC::class.java) { p.bobSession.decrypt(bad) }
        // Сессия не испорчена: настоящее сообщение читается.
        assertEquals("целое", String(p.bobSession.decrypt(good)))
    }

    @Test
    fun fallbackKeySurvivesUse() {
        val bob = OlmAccount()
        bob.generateFallbackKey()
        val fallback = requireNotNull(bob.unpublishedFallbackKey)
        bob.markKeysPublished()
        assertEquals(null, bob.unpublishedFallbackKey)

        for (i in 1..2) {
            val alice = OlmAccount()
            val session = alice.outboundSession(bob.curve25519Key, fallback.second)
            val message = session.encrypt("через запасной $i".toByteArray())
            val inbound = bob.inboundSession(alice.curve25519Key, message.body)
            assertEquals("через запасной $i", String(inbound.plaintext))
        }

        // После смены запасного ключа прежний ещё работает, пока его не забыли.
        bob.generateFallbackKey()
        val alice = OlmAccount()
        val session = alice.outboundSession(bob.curve25519Key, fallback.second)
        val message = session.encrypt("старый запасной".toByteArray())
        assertEquals("старый запасной", String(bob.inboundSession(alice.curve25519Key, message.body).plaintext))
        bob.forgetPreviousFallbackKey()
        val late = session.encrypt("поздно".toByteArray())
        assertThrows(CryptoException.BadMessage::class.java) { bob.inboundSession(alice.curve25519Key, late.body) }
    }

    @Test
    fun wrongSenderKeyRejected() {
        val alice = OlmAccount()
        val bob = OlmAccount()
        bob.generateOneTimeKeys(1)
        val oneTimeKey = bob.unpublishedOneTimeKeys.values.first()
        val session = alice.outboundSession(bob.curve25519Key, oneTimeKey)
        val message = session.encrypt("x".toByteArray())
        val error = assertThrows(CryptoException.BadMessage::class.java) { bob.inboundSession(OlmAccount().curve25519Key, message.body) }
        assertEquals("olm: ключ отправителя не совпал", error.what)
        assertEquals(1, bob.storedOneTimeKeyCount)
    }

    @Test
    fun persistenceRoundtrip() {
        val p = pair()
        val bobJson = p.bob.toJsonString()
        val bob2 = OlmAccount.fromJson(bobJson)
        assertEquals(p.bob.curve25519Key, bob2.curve25519Key)
        assertEquals(p.bob.ed25519Key, bob2.ed25519Key)
        assertEquals(bobJson, bob2.toJsonString())

        val sessionJson = p.bobSession.toJsonString()
        val bobSession2 = OlmSession.fromJson(sessionJson)
        assertEquals(p.bobSession.sessionID, bobSession2.sessionID)
        assertEquals(sessionJson, bobSession2.toJsonString())
        val message = p.aliceSession.encrypt("после восстановления".toByteArray())
        assertEquals("после восстановления", String(bobSession2.decrypt(message)))
        val reply = bobSession2.encrypt("и обратно".toByteArray())
        val aliceSession2 = OlmSession.fromJson(p.aliceSession.toJson())
        assertEquals("и обратно", String(aliceSession2.decrypt(reply)))

        // Формат совместим с iOS: байты base64 с набивкой, lastUsed числом.
        val tree = p.bobSession.toJson()
        assertTrue(tree["rootKey"]!!.string!!.endsWith("="))
        assertNotNull(tree["lastUsed"]!!.double)
        assertTrue(tree["receivedMessage"]!!.bool!!)  // Боб уже прочёл сообщение Алисы
    }

    @Test
    fun oneTimeKeyLimitEvictsOldest() {
        val account = OlmAccount()
        account.generateOneTimeKeys(100)
        assertEquals(100, account.unpublishedOneTimeKeys.size)
        assertEquals("AAAAAQ", account.unpublishedOneTimeKeys.keys.first())
        account.markKeysPublished()
        account.generateOneTimeKeys(5)
        assertEquals(100, account.storedOneTimeKeyCount)
        assertEquals(5, account.unpublishedOneTimeKeys.size)
        assertFalse(account.toJsonString().contains("\"AAAAAQ\""))
    }

    @Test
    fun signatures() {
        val account = OlmAccount()
        val signature = Base64.decode(account.sign("{}".toByteArray()))
        assertTrue(Ed25519KeyPair.verify(signature, "{}".toByteArray(), Base64.decode(account.ed25519Key)))
        assertArrayEquals(account.signing.publicKey, Base64.decode(account.ed25519Key))
    }
}
