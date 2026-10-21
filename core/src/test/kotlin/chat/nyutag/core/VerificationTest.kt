// Нютаг. Проверка по эмодзи (SAS) между устройствами на поддельном сервере: to-device и в комнате.

package chat.nyutag.core

import chat.nyutag.core.crypto.CrossSigningKind
import chat.nyutag.core.crypto.E2EEException
import chat.nyutag.core.crypto.VerificationFlow
import chat.nyutag.core.crypto.VerificationFlow.State
import chat.nyutag.core.crypto.acceptVerification
import chat.nyutag.core.crypto.bootstrapCrossSigning
import chat.nyutag.core.crypto.cancelVerification
import chat.nyutag.core.crypto.confirmSas
import chat.nyutag.core.crypto.crossSigningIdentity
import chat.nyutag.core.crypto.devices
import chat.nyutag.core.crypto.hasCrossSigningKeys
import chat.nyutag.core.crypto.isVerified
import chat.nyutag.core.crypto.ownCrossSigningKey
import chat.nyutag.core.crypto.receiveRoomVerifications
import chat.nyutag.core.crypto.requestSecrets
import chat.nyutag.core.crypto.requestVerification
import chat.nyutag.core.crypto.requestVerificationInRoom
import chat.nyutag.core.crypto.sendEncryptedToDevice
import chat.nyutag.core.crypto.startSas
import chat.nyutag.core.crypto.trustLocally
import chat.nyutag.core.crypto.verification
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.Random
import chat.nyutag.crypto.Sas
import chat.nyutag.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerificationTest {
    private val room = "!dm:nyutag.chat"
    private val accepted = State.Cancelled("m.accepted", "Проверку приняло другое устройство")

    /** Доставлять to-device, пока очереди не опустеют. `tamper` может подменить событие по дороге. */
    private suspend fun pump(
        server: FakeHomeserver, devices: List<CryptoDevice>, tamper: (CryptoDevice, Event) -> Event = { _, e -> e },
    ) {
        repeat(10) {
            var any = false
            for (device in devices) {
                val events = server.takeToDevice(device.session.userID, device.session.deviceID)
                if (events.isEmpty()) continue
                any = true
                device.machine.receiveToDevice(events.map { tamper(device, it) })
            }
            if (!any) return
        }
    }

    private fun flow(device: CryptoDevice, id: String): VerificationFlow = requireNotNull(device.machine.verification(id))

    /** Запрос → готовность → старт → обмен ключами. Возвращает id проверки. */
    private suspend fun reachComparing(
        server: FakeHomeserver, from: CryptoDevice, to: CryptoDevice,
        tamper: (CryptoDevice, Event) -> Event = { _, e -> e },
    ): String {
        val id = from.machine.requestVerification(to.session.userID)
        pump(server, listOf(from, to))
        assertEquals(State.Requested, flow(to, id).state)
        assertTrue(flow(to, id).incoming)
        to.machine.acceptVerification(id)
        pump(server, listOf(from, to))
        assertEquals(State.Ready, flow(from, id).state)
        assertEquals(to.session.deviceID, flow(from, id).otherDeviceID)
        from.machine.startSas(id)
        pump(server, listOf(from, to), tamper)
        return id
    }

    /** Подменить поле содержимого события. */
    private fun Event.withContent(change: (MutableMap<String, JsonValue>) -> Unit): Event {
        val fields = LinkedHashMap(content.obj ?: return this)
        change(fields)
        return Event(type = type, sender = sender, content = JsonValue.Obj(fields))
    }

    private suspend fun bootstrapped(server: FakeHomeserver): Pair<CryptoDevice, CryptoDevice> {
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        alice.machine.bootstrapCrossSigning(null)
        bob.machine.bootstrapCrossSigning(null)
        return alice to bob
    }

    @Test fun ownNewDeviceGetsSignedByEmoji() = runBlocking {
        val server = FakeHomeserver()
        val old = server.cryptoDevice(ALICE, "A1")
        old.machine.bootstrapCrossSigning(null)
        val new = server.cryptoDevice(ALICE, "A2")
        val spare = server.cryptoDevice(ALICE, "A3")
        assertEquals(false, old.machine.devices(listOf(ALICE)).firstOrNull { it.deviceID == "A2" }?.crossSigned)

        // Новое устройство просит проверку у своих; ответило A1, A3 получает отмену.
        val id = reachComparing(server, new, old)
        pump(server, listOf(spare))
        assertEquals(accepted, flow(spare, id).state)

        val atNew = flow(new, id)
        val atOld = flow(old, id)
        assertEquals(State.Comparing, atNew.state)
        assertEquals(State.Comparing, atOld.state)
        assertEquals(atOld.emoji, atNew.emoji)
        assertEquals(7, atNew.emoji?.size)
        assertEquals(atOld.decimals, atNew.decimals)
        assertTrue(requireNotNull(atNew.decimals).all { it in 1000..9191 })

        old.machine.confirmSas(id)
        pump(server, listOf(new, old))
        assertEquals(State.Confirmed, flow(old, id).state)
        new.machine.confirmSas(id)
        pump(server, listOf(new, old))
        assertEquals(State.Done, flow(new, id).state)
        assertEquals(State.Done, flow(old, id).state)

        // A1 подписал A2 ключом самоподписи: теперь A2 подтверждено, A3 — нет.
        val seen = old.machine.devices(listOf(ALICE))
        val a2 = seen.first { it.deviceID == "A2" }
        assertTrue(a2.crossSigned)
        assertTrue(old.machine.isVerified(a2))
        assertEquals(false, seen.firstOrNull { it.deviceID == "A3" }?.crossSigned)

        // A2 сверило A1 и получило от него ключи кросс-подписи: теперь подтверждено и для самого себя.
        pump(server, listOf(new, old))
        assertTrue(new.machine.hasCrossSigningKeys)
        assertNotNull(new.machine.ownCrossSigningKey(CrossSigningKind.USER_SIGNING))
        assertTrue(new.machine.devices(listOf(ALICE)).first { it.deviceID == "A2" }.crossSigned)
        assertEquals(true, new.machine.crossSigningIdentity(ALICE)?.verified)
    }

    /** Секреты просит устройство, которое никто не проверял: ему ничего не уходит. */
    @Test fun secretsNotGivenToUnverifiedDevice() = runBlocking {
        val server = FakeHomeserver()
        val old = server.cryptoDevice(ALICE, "A1")
        old.machine.bootstrapCrossSigning(null)
        val stranger = server.cryptoDevice(ALICE, "A3")
        val a1 = stranger.machine.devices(listOf(ALICE)).first { it.deviceID == "A1" }
        stranger.machine.requestSecrets(a1)
        pump(server, listOf(old))
        val toStranger = server.takeToDevice(ALICE, "A3")
        assertFalse(toStranger.any { it.type == EventType.ENCRYPTED })
        assertFalse(stranger.machine.hasCrossSigningKeys)
    }

    /** Ответ пришёл на настоящий запрос, но от своего устройства, которое мы не проверяли: ключи не берём. */
    @Test fun secretsFromUnverifiedDeviceIgnored() = runBlocking {
        val server = FakeHomeserver()
        val old = server.cryptoDevice(ALICE, "A1")
        old.machine.bootstrapCrossSigning(null)
        val new = server.cryptoDevice(ALICE, "A2")
        // A1 доверяет A2, A2 не доверяет A1.
        old.machine.trustLocally(old.machine.devices(listOf(ALICE)).first { it.deviceID == "A2" })
        new.machine.requestSecrets(new.machine.devices(listOf(ALICE)).first { it.deviceID == "A1" })
        pump(server, listOf(old))
        val answers = server.takeToDevice(ALICE, "A2")
        assertEquals(3, answers.count { it.type == EventType.ENCRYPTED })
        new.machine.receiveToDevice(answers)
        assertFalse(new.machine.hasCrossSigningKeys)
    }

    /** Непрошеный секрет и ключи, которые уже не совпадают с сервером, не принимаются. */
    @Test fun unsolicitedOrStaleSecretsIgnored() = runBlocking {
        val server = FakeHomeserver()
        val old = server.cryptoDevice(ALICE, "A1")
        old.machine.bootstrapCrossSigning(null)
        val new = server.cryptoDevice(ALICE, "A2")
        val id = reachComparing(server, old, new)
        old.machine.confirmSas(id)
        new.machine.confirmSas(id)
        pump(server, listOf(new))
        // A1 получает MAC, но запросы секретов пока придерживаем.
        val toOld = server.takeToDevice(ALICE, "A1")
        val requests = toOld.filter { it.type == "m.secret.request" }
        assertEquals(4, requests.size)
        old.machine.receiveToDevice(toOld.filter { it.type != "m.secret.request" })
        assertEquals(State.Done, flow(old, id).state)

        // A1 шлёт настоящие ключи, но с чужими id запроса: A2 их не берёт.
        val a2 = old.machine.devices(listOf(ALICE)).first { it.deviceID == "A2" }
        for (kind in listOf(CrossSigningKind.MASTER, CrossSigningKind.SELF_SIGNING)) {
            val key = requireNotNull(old.machine.ownCrossSigningKey(kind))
            old.machine.sendEncryptedToDevice("m.secret.send", JsonValue.of(
                "request_id" to "nobody-asked-${kind.rawValue}", "secret" to Base64.encodeUnpadded(key.rawRepresentation),
            ), listOf(a2))
        }
        pump(server, listOf(new))
        assertTrue(new.machine.receivedCrossSigning.isEmpty())
        assertFalse(new.machine.hasCrossSigningKeys)

        // A1 отвечает на запросы, но до A2 ответ доходит, когда личность на сервере уже сменилась.
        old.machine.receiveToDevice(requests)
        val rogue = server.cryptoDevice(ALICE, "A9")
        rogue.machine.bootstrapCrossSigning(null)
        val answers = server.takeToDevice(ALICE, "A2")
        assertEquals(3, answers.count { it.type == EventType.ENCRYPTED })
        new.machine.receiveToDevice(answers)
        assertFalse(new.machine.hasCrossSigningKeys)
    }

    @Test fun usersVerifyEachOther() = runBlocking {
        val server = FakeHomeserver()
        val (alice, bob) = bootstrapped(server)
        assertNotEquals(true, alice.machine.crossSigningIdentity(BOB)?.verified)

        val id = reachComparing(server, alice, bob)
        // Боб подтверждает первым: MAC Алисы приходит к нему уже после подтверждения, его — к Алисе раньше.
        bob.machine.confirmSas(id)
        pump(server, listOf(alice, bob))
        alice.machine.confirmSas(id)
        pump(server, listOf(alice, bob))
        assertEquals(State.Done, flow(alice, id).state)
        assertEquals(State.Done, flow(bob, id).state)

        // Каждый подписал мастер-ключ другого: личность и устройства собеседника подтверждены.
        val bobDevice = bob.let { alice.machine.devices(listOf(BOB)).first() }
        assertEquals(true, alice.machine.crossSigningIdentity(BOB)?.verified)
        assertTrue(alice.machine.isVerified(bobDevice))
        assertTrue(bob.machine.isVerified(bob.machine.devices(listOf(ALICE)).first()))
    }

    @Test fun swappedStarterKeyGivesDifferentEmoji() = runBlocking {
        val server = FakeHomeserver()
        val (alice, bob) = bootstrapped(server)
        // Сервер подменяет ключ Алисы своим по дороге к Бобу.
        val mitm = Sas().publicKeyBase64
        val id = reachComparing(server, alice, bob) { device, event ->
            if (device.session.userID != BOB || event.type != "m.key.verification.key") event
            else event.withContent { it["key"] = JsonValue.Str(mitm) }
        }
        val atAlice = flow(alice, id)
        val atBob = flow(bob, id)
        assertEquals(State.Comparing, atBob.state)
        assertNotEquals(atBob.emoji, atAlice.emoji)
        // Люди видят разные эмодзи и отменяют; подписей нет.
        bob.machine.cancelVerification(id, "m.mismatched_sas", "Эмодзи не совпали")
        pump(server, listOf(alice, bob))
        assertEquals(State.Cancelled("m.mismatched_sas", "Эмодзи не совпали"), flow(alice, id).state)
        assertNotEquals(true, bob.machine.crossSigningIdentity(ALICE)?.verified)
        assertTrue(runCatching { alice.machine.confirmSas(id) }.exceptionOrNull() is E2EEException)
    }

    @Test fun swappedAccepterKeyBreaksCommitment() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        val mitm = Sas().publicKeyBase64
        val id = reachComparing(server, alice, bob) { device, event ->
            if (device.session.userID != ALICE || event.type != "m.key.verification.key") event
            else event.withContent { it["key"] = JsonValue.Str(mitm) }
        }
        val broken = State.Cancelled("m.mismatched_commitment", "Ключ не совпал с обязательством")
        assertEquals(broken, flow(alice, id).state)
        assertEquals(broken, flow(bob, id).state)
    }

    @Test fun forgedMacIsRejected() = runBlocking {
        val server = FakeHomeserver()
        val (alice, bob) = bootstrapped(server)
        val id = reachComparing(server, alice, bob)
        alice.machine.confirmSas(id)
        bob.machine.confirmSas(id)
        // MAC ключа устройства Боба подменён по дороге к Алисе (список ключей при этом верный).
        pump(server, listOf(alice, bob)) { device, event ->
            if (device.session.userID != ALICE || event.type != "m.key.verification.mac") event
            else event.withContent { fields ->
                val macs = LinkedHashMap(fields["mac"]?.obj ?: emptyMap())
                macs["ed25519:B1"] = JsonValue.Str(Base64.encodeUnpadded(Random.bytes(32)))
                fields["mac"] = JsonValue.Obj(macs)
            }
        }
        assertEquals(State.Cancelled("m.key_mismatch", "Ключи не совпали"), flow(alice, id).state)
        assertNotEquals(true, alice.machine.crossSigningIdentity(BOB)?.verified)
        alice.machine.devices(listOf(BOB))
        assertNotEquals(true, alice.machine.crossSigningIdentity(BOB)?.verified)
    }

    @Test fun bothStartAtOnceSmallerUserWins() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob = server.cryptoDevice(BOB, "B1")
        val id = alice.machine.requestVerification(BOB)
        pump(server, listOf(alice, bob))
        bob.machine.acceptVerification(id)
        pump(server, listOf(alice, bob))
        alice.machine.startSas(id)
        bob.machine.startSas(id)
        pump(server, listOf(alice, bob))
        val atAlice = flow(alice, id)
        val atBob = flow(bob, id)
        assertEquals(State.Comparing, atAlice.state)
        assertEquals(State.Comparing, atBob.state)
        assertEquals(atAlice.emoji, atBob.emoji)
        // По спецификации остаётся start Алисы: её user_id меньше. Иначе с чужими клиентами проверка зависнет.
        assertEquals(true, alice.machine.verifications[id]?.weStarted)
        assertEquals(false, bob.machine.verifications[id]?.weStarted)
    }

    @Test fun droppedKeyBreaksKeyList() = runBlocking {
        val server = FakeHomeserver()
        val (alice, bob) = bootstrapped(server)
        val id = reachComparing(server, alice, bob)
        alice.machine.confirmSas(id)
        bob.machine.confirmSas(id)
        // Из MAC Боба выкинут мастер-ключ: подпись списка ключей больше не сходится.
        pump(server, listOf(alice, bob)) { device, event ->
            if (device.session.userID != ALICE || event.type != "m.key.verification.mac") event
            else event.withContent { fields ->
                val macs = (fields["mac"]?.obj ?: emptyMap()).filterKeys { it == "ed25519:B1" }
                fields["mac"] = JsonValue.Obj(macs)
            }
        }
        assertEquals(State.Cancelled("m.key_mismatch", "Список ключей не совпал"), flow(alice, id).state)
    }

    @Test fun staleRequestIgnored() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val request = Event(type = "m.key.verification.request", sender = BOB, content = JsonValue.of(
            "from_device" to "B1", "methods" to listOf("m.sas.v1"), "transaction_id" to "old",
            "timestamp" to System.currentTimeMillis() - 11 * 60 * 1000,
        ))
        alice.machine.receiveToDevice(listOf(request))
        assertNull(alice.machine.verification("old"))
    }

    // MARK: В комнате

    /** Доставить to-device и новые события комнаты всем устройствам, пока есть что доставлять. */
    private suspend fun pumpRoom(server: FakeHomeserver, devices: List<CryptoDevice>, seen: IntArray) {
        repeat(10) {
            pump(server, devices)
            val events = server.events(room)
            if (events.size <= seen[0]) return
            val fresh = events.subList(seen[0], events.size)
            seen[0] = events.size
            for (device in devices) device.machine.receiveRoomVerifications(fresh, room)
        }
    }

    /** Element проверяет собеседника в личном чате: все шаги идут зашифрованными событиями комнаты. */
    @Test fun userVerifiedInRoom() = runBlocking {
        val server = FakeHomeserver()
        val (alice, bob) = bootstrapped(server)
        server.setMembers(listOf(ALICE, BOB), room)
        val seen = IntArray(1)

        val id = alice.machine.requestVerificationInRoom(BOB, room)
        pumpRoom(server, listOf(alice, bob), seen)
        val incoming = flow(bob, id)
        assertEquals(State.Requested, incoming.state)
        assertTrue(incoming.incoming)
        assertEquals(room, incoming.roomID)
        assertEquals("A1", incoming.otherDeviceID)
        bob.machine.acceptVerification(id)
        pumpRoom(server, listOf(alice, bob), seen)
        assertEquals("B1", flow(alice, id).otherDeviceID)
        alice.machine.startSas(id)
        pumpRoom(server, listOf(alice, bob), seen)
        val atAlice = flow(alice, id)
        val atBob = flow(bob, id)
        assertEquals(State.Comparing, atAlice.state)
        assertEquals(State.Comparing, atBob.state)
        assertEquals(atAlice.emoji, atBob.emoji)
        assertEquals(7, atAlice.emoji?.size)

        alice.machine.confirmSas(id)
        bob.machine.confirmSas(id)
        pumpRoom(server, listOf(alice, bob), seen)
        assertEquals(State.Done, flow(alice, id).state)
        assertEquals(State.Done, flow(bob, id).state)
        assertEquals(true, alice.machine.crossSigningIdentity(BOB)?.verified)
        assertEquals(true, bob.machine.crossSigningIdentity(ALICE)?.verified)

        // Ничего не ушло через to-device и ничего не лежит в комнате открытым текстом.
        val events = server.events(room)
        assertTrue(events.size >= 9)
        assertTrue(events.all { it.type == EventType.ENCRYPTED })
        assertEquals(id, events.first().eventID)
    }

    /** Чужие запросы, старые события и ссылки из другой комнаты проверку не трогают. */
    @Test fun roomEventsForOthersIgnored() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val now = System.currentTimeMillis()
        fun request(id: String, to: String, ts: Long) = Event(
            eventID = id, type = EventType.MESSAGE, sender = BOB, originServerTS = ts, content = JsonValue.of(
                "msgtype" to "m.key.verification.request", "to" to to, "from_device" to "B1",
                "methods" to listOf("m.sas.v1"), "body" to "",
            ),
        )
        alice.machine.receiveRoomVerifications(listOf(
            request("\$toCarol", "@carol:nyutag.chat", now),
            request("\$old", ALICE, now - 11 * 60 * 1000),
            request("\$ok", ALICE, now),
        ), room)
        assertNull(alice.machine.verification("\$toCarol"))
        assertNull(alice.machine.verification("\$old"))
        assertEquals(State.Requested, flow(alice, "\$ok").state)

        // Отмена с правильной ссылкой, но из другой комнаты, не действует.
        val cancel = Event(eventID = "\$c", type = "m.key.verification.cancel", sender = BOB, originServerTS = now,
                           content = JsonValue.of("code" to "m.user", "reason" to "",
                                                  "m.relates_to" to JsonValue.of("rel_type" to "m.reference", "event_id" to "\$ok")))
        alice.machine.receiveRoomVerifications(listOf(cancel), "!other:nyutag.chat")
        assertEquals(State.Requested, flow(alice, "\$ok").state)
        alice.machine.receiveRoomVerifications(listOf(cancel), room)
        assertEquals(State.Cancelled("m.user", ""), flow(alice, "\$ok").state)
    }

    /** На запрос в комнате ответило другое устройство того же человека: у этого запрос закрывается. */
    @Test fun otherOwnDeviceTookRoomRequest() = runBlocking {
        val server = FakeHomeserver()
        val alice = server.cryptoDevice(ALICE, "A1")
        val bob1 = server.cryptoDevice(BOB, "B1")
        val bob2 = server.cryptoDevice(BOB, "B2")
        server.setMembers(listOf(ALICE, BOB), room)
        val seen = IntArray(1)
        val id = alice.machine.requestVerificationInRoom(BOB, room)
        pumpRoom(server, listOf(alice, bob1, bob2), seen)
        assertEquals(State.Requested, flow(bob2, id).state)
        bob1.machine.acceptVerification(id)
        pumpRoom(server, listOf(alice, bob1, bob2), seen)
        assertEquals("B1", flow(alice, id).otherDeviceID)
        assertEquals(State.Ready, flow(bob1, id).state)
        assertEquals(accepted, flow(bob2, id).state)
    }
}
