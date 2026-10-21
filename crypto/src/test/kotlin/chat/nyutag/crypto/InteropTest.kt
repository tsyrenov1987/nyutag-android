// Сверка с эталоном vodozemac: крипто-состояние и сообщения из interop.json (тот же файл, что у iOS-клиента).

package chat.nyutag.crypto

import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InteropTest {
    private val fixture: JsonValue by lazy {
        val text = javaClass.getResourceAsStream("/interop.json")!!.readBytes().toString(Charsets.UTF_8)
        JsonValue.parse(text)
    }

    @Test
    fun megolm() {
        val section = fixture["megolm"]!!
        val inbound = MegolmInboundSession.fromSessionKey(section["session_key"]!!.string!!)
        assertEquals(section["session_id"]!!.string, inbound.sessionID)
        assertTrue(inbound.isVerified)
        val messages = section["messages"]!!.array!!
        assertEquals(5, messages.size)
        for (message in messages) {
            val decrypted = inbound.decrypt(message["ciphertext"]!!.string!!)
            assertEquals(message["plaintext"]!!.string, String(decrypted.plaintext))
            assertEquals(message["index"]!!.long, decrypted.messageIndex.toLong())
        }
        assertEquals(section["export_at_2"]!!.string, inbound.export(2u))
        val exported = MegolmInboundSession.fromExportedKey(section["export_at_2"]!!.string!!)
        assertEquals(2u, exported.firstKnownIndex)
        assertEquals(messages[3]["plaintext"]!!.string, String(exported.decrypt(messages[3]["ciphertext"]!!.string!!).plaintext))
        assertThrows(CryptoException.UnknownMessageIndex::class.java) { exported.decrypt(messages[0]["ciphertext"]!!.string!!) }
    }

    @Test
    fun olm() {
        val section = fixture["olm"]!!
        val account = OlmAccount.fromJson(section["account"]!!)
        val senderKey = section["sender_key"]!!.string!!
        val messages = section["messages"]!!.array!!
        assertEquals(3, messages.size)
        assertEquals(1, account.storedOneTimeKeyCount)
        val first = account.inboundSession(senderKey, messages[0]["body"]!!.string!!)
        assertEquals(messages[0]["plaintext"]!!.string, String(first.plaintext))
        assertEquals(0, account.storedOneTimeKeyCount)
        for (message in messages.drop(1)) {
            assertEquals(0L, message["type"]!!.long)
            assertTrue(first.session.matches(message["body"]!!.string!!))
            val plaintext = first.session.decrypt(OlmEncrypted(OlmEncrypted.Kind.PRE_KEY, message["body"]!!.string!!))
            assertEquals(message["plaintext"]!!.string, String(plaintext))
        }
        assertEquals(senderKey, first.session.theirIdentityKey)
        // После прочтения можно ответить, и ответ уже обычный.
        assertEquals(OlmEncrypted.Kind.NORMAL, first.session.encrypt("ok".toByteArray()).type)
    }

    @Test
    fun backup() {
        val section = fixture["backup"]!!
        val key = BackupKey(Base64.decode(section["private_key"]!!.string!!))
        assertEquals(section["public_key"]!!.string, key.publicKeyBase64)
        val records = section["records"]!!.array!!
        assertEquals(2, records.size)
        for (record in records) {
            val data = BackupSessionData(record["ephemeral"]!!.string!!, record["ciphertext"]!!.string!!, record["mac"]!!.string!!)
            assertEquals(record["plaintext"]!!.string, String(key.decrypt(data)))
        }
    }

    @Test
    fun reverse() {
        val section = fixture["reverse"]!!
        val verified = section["verified"]!!.array!!.mapNotNull { it.string }.toSet()
        assertEquals(setOf("megolm", "olm", "backup"), verified)
        val session = OlmSession.fromJson(section["our_session"]!!)
        assertEquals(fixture["peer"]!!["identity"]!!.string, session.theirIdentityKey)
        val replies = section["replies"]!!.array!!
        assertEquals(2, replies.size)
        for (reply in replies) {
            assertEquals(1L, reply["type"]!!.long)
            val plaintext = session.decrypt(OlmEncrypted(OlmEncrypted.Kind.NORMAL, reply["body"]!!.string!!))
            assertEquals(reply["plaintext"]!!.string, String(plaintext))
        }
        assertEquals(OlmEncrypted.Kind.NORMAL, session.encrypt("дальше".toByteArray()).type)
        // Состояние после обмена переживает запись и чтение в формате iOS.
        val restored = OlmSession.fromJson(session.toJsonString())
        assertEquals(session.sessionID, restored.sessionID)
        assertEquals(OlmEncrypted.Kind.NORMAL, restored.encrypt("ещё".toByteArray()).type)
    }

    @Test
    fun sas() {
        val section = fixture["sas"]!!
        val ours = Sas(Base64.decode(section["our_private"]!!.string!!))
        assertEquals(section["our_public"]!!.string, ours.publicKeyBase64)
        val established = ours.establish(section["their_public"]!!.string!!)
        val info = section["info"]!!.string!!
        assertEquals(section["emoji_indices"]!!.array!!.map { it.int!! }, established.emojiIndices(info))
        assertEquals(section["decimals"]!!.array!!.map { it.int!! }, established.decimals(info))
        val macs = section["macs"]!!.array!!
        assertEquals(2, macs.size)
        for (mac in macs) {
            assertEquals(mac["mac"]!!.string, established.calculateMac(mac["input"]!!.string!!, mac["info"]!!.string!!))
        }
        assertEquals(64, SasEmoji.table.size)
        assertEquals("Собака", SasEmoji.table[0].nameRU)
        assertEquals("📌", SasEmoji.table[63].emoji)
    }
}
