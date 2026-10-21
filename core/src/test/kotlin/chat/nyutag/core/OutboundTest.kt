// Нютаг. Отправка в зашифрованную комнату между несколькими устройствами на поддельном сервере.

package chat.nyutag.core

import chat.nyutag.core.crypto.CryptoMachine
import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.crypto.devices
import chat.nyutag.core.crypto.markDeviceListsChanged
import chat.nyutag.core.crypto.resetMegolmBackedUp
import chat.nyutag.core.crypto.sendEncrypted
import chat.nyutag.core.crypto.setCryptoBlob
import chat.nyutag.core.crypto.uploadRoomKeysToBackup
import chat.nyutag.core.store.Store
import chat.nyutag.core.sync.EventType
import chat.nyutag.crypto.BackupKey
import chat.nyutag.crypto.BackupSessionData
import chat.nyutag.crypto.Ed25519KeyPair
import chat.nyutag.crypto.MegolmInboundSession
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundTest {
    private val room = TEST_ROOM
    private val aliceID = ALICE
    private val bobID = BOB

    private suspend fun device(server: FakeHomeserver, user: String, id: String) = server.cryptoDevice(user, id)
    private suspend fun read(server: FakeHomeserver, device: CryptoDevice) = server.read(device)

    private fun sessionIDs(server: FakeHomeserver) = server.events(room).map { it.content["session_id"]?.string }

    @Test fun messageReachesEveryDeviceOnce() = runBlocking {
        val server = FakeHomeserver()
        val alice = device(server, aliceID, "A1")
        val bob1 = device(server, bobID, "B1")
        val bob2 = device(server, bobID, "B2")
        val aliceOther = device(server, aliceID, "A2")
        server.setMembers(listOf(aliceID, bobID), room)

        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("сайн байна"))
        // Ключ комнаты получили три других устройства, своему (A1) он не шлётся.
        assertTrue(server.takeToDevice(aliceID, "A1").isEmpty())
        assertEquals(listOf("сайн байна"), read(server, bob1))
        assertEquals(listOf("сайн байна"), read(server, bob2))
        assertEquals(listOf("сайн байна"), read(server, aliceOther))
        // Своё сообщение читается своей же копией ключа.
        assertEquals(listOf("сайн байна"), read(server, alice))

        // Второе сообщение идёт той же сессией: ключ повторно не рассылается.
        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("хэр байнат"))
        assertTrue(server.takeToDevice(bobID, "B1").isEmpty())
        assertEquals(listOf("сайн байна", "хэр байнат"), read(server, bob1))
        val ids = sessionIDs(server)
        assertEquals(ids[0], ids[1])

        // Боб отвечает: у Боба сессия Olm к Алисе уже есть (входящая), исходящая заводится по claim.
        bob1.machine.sendEncrypted(room, EventType.MESSAGE, text("сайн"))
        assertEquals(listOf("сайн байна", "хэр байнат", "сайн"), read(server, alice))
        assertEquals(listOf("сайн байна", "хэр байнат", "сайн"), read(server, bob2))
    }

    @Test fun keyRotatesWhenSomeoneLeaves() = runBlocking {
        val server = FakeHomeserver()
        val alice = device(server, aliceID, "A1")
        val bob = device(server, bobID, "B1")
        server.setMembers(listOf(aliceID, bobID), room)
        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("при Бобе"))
        assertEquals(listOf("при Бобе"), read(server, bob))

        server.setMembers(listOf(aliceID), room)
        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("без Боба"))
        val ids = sessionIDs(server)
        assertNotEquals(ids[0], ids[1])
        assertEquals(listOf("при Бобе", null), read(server, bob))
        assertEquals(listOf("при Бобе", "без Боба"), read(server, alice))
    }

    @Test fun newDeviceGetsKeyFromCurrentIndexOnly() = runBlocking {
        val server = FakeHomeserver()
        val alice = device(server, aliceID, "A1")
        device(server, bobID, "B1")
        server.setMembers(listOf(aliceID, bobID), room)
        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("раньше"))

        val late = device(server, bobID, "B2")
        // /sync сообщил, что у Боба сменились устройства.
        alice.machine.store.markDeviceListsChanged(listOf(bobID))
        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("позже"))
        val ids = sessionIDs(server)
        assertEquals(ids[0], ids[1])
        assertEquals(listOf(null, "позже"), read(server, late))
    }

    @Test fun forgedDeviceKeysAndOneTimeKeysAreIgnored() = runBlocking {
        val server = FakeHomeserver()
        val alice = device(server, aliceID, "A1")
        device(server, bobID, "B1")
        val mallory = Ed25519KeyPair()
        // Сервер подменяет ключ подписи устройства B1 своим: подпись больше не сходится.
        server.tamperDeviceKeys = { user, device, keys ->
            val fields = keys.obj?.let { LinkedHashMap(it) }
            val inner = fields?.get("keys")?.obj?.let { LinkedHashMap(it) }
            if (user != bobID || fields == null || inner == null) {
                keys
            } else {
                inner["ed25519:$device"] = JsonValue.Str(mallory.publicKeyBase64)
                fields["keys"] = JsonValue.Obj(inner)
                JsonValue.Obj(fields)
            }
        }
        server.setMembers(listOf(aliceID, bobID), room)
        assertTrue(alice.machine.devices(listOf(bobID)).isEmpty())
        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("x"))
        assertTrue(server.takeToDevice(bobID, "B1").isEmpty())
        assertEquals(50, server.oneTimeKeyCount(bobID, "B1"))
    }

    /** Каждый запуск приложения публикует ключи без счётчика сервера: одноразовых ключей не должно прибывать. */
    @Test fun relaunchDoesNotPileUpOneTimeKeys() = runBlocking {
        val server = FakeHomeserver()
        val session = server.session(bobID, "B1")
        val store = Store(JdbcSql.inMemory())
        repeat(4) {
            CryptoMachine.create(server.client(session), store, session).publishKeys(null)
        }
        assertEquals(50, server.oneTimeKeyCount(bobID, "B1"))
    }

    @Test fun roomKeysGoToBackup() = runBlocking {
        val server = FakeHomeserver()
        val alice = device(server, aliceID, "A1")
        val bob = device(server, bobID, "B1")
        server.setMembers(listOf(aliceID, bobID), room)
        val backupKey = BackupKey()
        bob.machine.store.setCryptoBlob("backup_key", backupKey.keyPair.rawRepresentation)
        bob.machine.store.setMeta("e2ee_backup_version", "1")

        alice.machine.sendEncrypted(room, EventType.MESSAGE, text("в бэкап"))
        read(server, bob)
        assertEquals(1, bob.machine.uploadRoomKeysToBackup())
        assertEquals(0, bob.machine.uploadRoomKeysToBackup())

        // Запись из бэкапа расшифровывается ключом бэкапа и открывает сообщение.
        val sessionID = requireNotNull(server.events(room).first().content["session_id"]?.string)
        val record = requireNotNull(server.backup[room]?.get(sessionID))
        assertEquals(0, record["first_message_index"]?.int)
        val data = requireNotNull(record["session_data"])
        val plaintext = backupKey.decrypt(BackupSessionData(
            requireNotNull(data["ephemeral"]?.string), requireNotNull(data["ciphertext"]?.string), requireNotNull(data["mac"]?.string),
        ))
        val json = JsonValue.parse(String(plaintext, Charsets.UTF_8))
        assertEquals(alice.machine.curve25519Key, json["sender_key"]?.string)
        assertEquals(alice.machine.ed25519Key, json.at("sender_claimed_keys", "ed25519")?.string)
        val session = MegolmInboundSession.fromExportedKey(requireNotNull(json["session_key"]?.string))
        val event = server.events(room).first()
        val opened = session.decrypt(requireNotNull(event.content["ciphertext"]?.string))
        assertEquals("в бэкап", JsonValue.parse(String(opened.plaintext, Charsets.UTF_8)).at("content", "body")?.string)

        // Сервер сменил версию бэкапа: старый ключ больше не годится.
        bob.machine.store.setMeta("e2ee_backup_version", "2")
        bob.machine.store.resetMegolmBackedUp()
        assertEquals(E2EEException.NoBackup, runCatching { bob.machine.uploadRoomKeysToBackup() }.exceptionOrNull())
    }
}
