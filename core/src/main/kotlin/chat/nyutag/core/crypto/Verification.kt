// Нютаг. Проверка своих устройств и собеседников по эмодзи (`m.sas.v1`) через to-device или в комнате:
// request → ready → start → accept → key ↔ key → люди сверяют эмодзи → mac ↔ mac → done.
// В комнате (так собеседника проверяет Element) запрос — сообщение `m.key.verification.request`, его event_id
// служит id проверки, остальные события ссылаются на него через `m.relates_to` (`m.reference`).
// Итог: своё устройство подписывается ключом самоподписи, мастер-ключ собеседника — ключом подписи пользователей.

package chat.nyutag.core.crypto

import chat.nyutag.core.net.MatrixError
import chat.nyutag.core.rooms.TransactionID
import chat.nyutag.core.rooms.send
import chat.nyutag.core.sync.Event
import chat.nyutag.core.sync.EventType
import chat.nyutag.crypto.Base64
import chat.nyutag.crypto.EstablishedSas
import chat.nyutag.crypto.Hash
import chat.nyutag.crypto.Sas
import chat.nyutag.crypto.SasEmoji
import chat.nyutag.json.JsonValue

/** Проверка, как её видит интерфейс. */
data class VerificationFlow(
    val transactionID: String,
    val otherUserID: String,
    val otherDeviceID: String? = null,
    /** Запрос пришёл к нам, а не от нас. */
    val incoming: Boolean,
    val state: State,
    /** Номера эмодзи в `SasEmoji.table` и три числа: появляются в состоянии `Comparing`. */
    val emoji: List<Int>? = null,
    val decimals: List<Int>? = null,
    /** Комната, в которой идёт проверка; null — проверка через to-device. */
    val roomID: String? = null,
) {
    sealed class State {
        /** Запрос ушёл (или пришёл), ответа ещё нет. */
        object Requested : State() { override fun toString() = "Requested" }
        /** Обе стороны готовы, можно начинать сверку. */
        object Ready : State() { override fun toString() = "Ready" }
        /** Идёт обмен ключами. */
        object Started : State() { override fun toString() = "Started" }
        /** Эмодзи готовы: человек сравнивает их с экраном другого устройства. */
        object Comparing : State() { override fun toString() = "Comparing" }
        /** Человек подтвердил совпадение, ждём подтверждения с другой стороны. */
        object Confirmed : State() { override fun toString() = "Confirmed" }
        object Done : State() { override fun toString() = "Done" }
        data class Cancelled(val code: String, val reason: String) : State()
    }

    /** Эмодзи сверки с названиями, в том порядке, в каком их показывает другая сторона. */
    val emojiSymbols: List<SasEmoji.Entry> get() = (emoji ?: emptyList()).map { SasEmoji.table[it] }
}

/** Внутреннее состояние проверки: ключ SAS живёт только в памяти. */
internal data class VerificationSession(
    val flow: VerificationFlow,
    /** Устройства, которым ушёл запрос (кроме выбранного, им потом уйдёт отмена). */
    val requestedDevices: List<String> = emptyList(),
    val sas: Sas = Sas(),
    val established: EstablishedSas? = null,
    val weStarted: Boolean = false,
    val startContent: JsonValue? = null,
    val commitment: String? = null,
    val keySent: Boolean = false,
    val theirKey: String? = null,
    val theirMac: JsonValue? = null,
) {
    fun withState(state: VerificationFlow.State) = copy(flow = flow.copy(state = state))
}

object VerificationEvent {
    const val REQUEST = "m.key.verification.request"
    const val READY = "m.key.verification.ready"
    const val START = "m.key.verification.start"
    const val ACCEPT = "m.key.verification.accept"
    const val KEY = "m.key.verification.key"
    const val MAC = "m.key.verification.mac"
    const val DONE = "m.key.verification.done"
    const val CANCEL = "m.key.verification.cancel"
    val ALL: Set<String> = setOf(REQUEST, READY, START, ACCEPT, KEY, MAC, DONE, CANCEL)
    /** Запросы старше этого (или «из будущего» больше чем на 5 минут) не показываем. */
    const val REQUEST_LIFETIME_MS = 10 * 60 * 1000L

    const val SAS = "m.sas.v1"
    const val KEY_AGREEMENT = "curve25519-hkdf-sha256"
    const val HASH = "sha256"
    const val MAC_METHOD = "hkdf-hmac-sha256.v2"
}

fun CryptoMachine.verification(transactionID: String): VerificationFlow? = verifications[transactionID]?.flow

val CryptoMachine.verificationFlows: List<VerificationFlow> get() = verifications.values.map { it.flow }

// MARK: Действия человека

/** Попросить проверку у пользователя: у всех его устройств (у себя — кроме этого) или у перечисленных.
 *  Возвращает id проверки. */
suspend fun CryptoMachine.requestVerification(other: String, deviceIDs: List<String>? = null): String {
    val targets = devices(listOf(other)).map { it.deviceID }.filter {
        (other != userID || it != deviceID) && deviceIDs?.contains(it) != false
    }
    if (targets.isEmpty()) throw E2EEException.BadPayload("у $other нет устройств для проверки")
    val transactionID = TransactionID.make()
    val content = JsonValue.of(
        "from_device" to deviceID, "methods" to listOf(VerificationEvent.SAS),
        "timestamp" to System.currentTimeMillis(), "transaction_id" to transactionID,
    )
    server { client.sendToDevice(VerificationEvent.REQUEST, mapOf(other to targets.associateWith { content })) }
    verifications[transactionID] = VerificationSession(
        VerificationFlow(transactionID, other, incoming = false, state = VerificationFlow.State.Requested),
        requestedDevices = targets,
    )
    return transactionID
}

/** Попросить собеседника о проверке в комнате (обычно в личном чате). Возвращает id проверки: event_id запроса. */
suspend fun CryptoMachine.requestVerificationInRoom(other: String, roomID: String): String {
    if (other == userID) throw E2EEException.BadPayload("свои устройства проверяются через to-device")
    val content = JsonValue.of(
        "msgtype" to VerificationEvent.REQUEST, "to" to other, "from_device" to deviceID,
        "methods" to listOf(VerificationEvent.SAS),
        "body" to "$userID просит проверить ключи шифрования. Ваш клиент не поддерживает проверку в чате.",
    )
    val transactionID = sendRoomEvent(roomID, EventType.MESSAGE, content)
    verifications[transactionID] = VerificationSession(VerificationFlow(
        transactionID, other, incoming = false, state = VerificationFlow.State.Requested, roomID = roomID,
    ))
    return transactionID
}

/** Согласиться на пришедший запрос. */
suspend fun CryptoMachine.acceptVerification(transactionID: String) {
    val session = verifications[transactionID]
    if (session == null || !session.flow.incoming || session.flow.state != VerificationFlow.State.Requested) {
        throw E2EEException.BadPayload("нечего принимать")
    }
    sendVerification(VerificationEvent.READY, JsonValue.of("from_device" to deviceID, "methods" to listOf(VerificationEvent.SAS)), session)
    verifications[transactionID] = session.withState(VerificationFlow.State.Ready)
}

/** Начать сверку эмодзи (после `ready`; начать может любая сторона). */
suspend fun CryptoMachine.startSas(transactionID: String) {
    val session = verifications[transactionID]
    if (session == null || session.flow.state != VerificationFlow.State.Ready) throw E2EEException.BadPayload("проверка не готова")
    val content = JsonValue.of(
        "from_device" to deviceID, "method" to VerificationEvent.SAS,
        "key_agreement_protocols" to listOf(VerificationEvent.KEY_AGREEMENT),
        "hashes" to listOf(VerificationEvent.HASH),
        "message_authentication_codes" to listOf(VerificationEvent.MAC_METHOD),
        "short_authentication_string" to listOf("decimal", "emoji"), "transaction_id" to transactionID,
    )
    // Обязательство считается от start в том виде, в каком его получила другая сторона.
    val sent = sendVerification(VerificationEvent.START, content, session)
    verifications[transactionID] = session.copy(weStarted = true, startContent = sent).withState(VerificationFlow.State.Started)
}

/** Эмодзи совпали: отправить MAC своих ключей. Если MAC другой стороны уже пришёл, проверить его. */
suspend fun CryptoMachine.confirmSas(transactionID: String) {
    val session = verifications[transactionID]
    val established = session?.established
    val otherDevice = session?.flow?.otherDeviceID
    if (session == null || session.flow.state != VerificationFlow.State.Comparing || established == null || otherDevice == null) {
        throw E2EEException.BadPayload("сверять нечего")
    }
    val keys = linkedMapOf("ed25519:$deviceID" to ed25519Key)
    ownCrossSigningKey(CrossSigningKind.MASTER)?.publicKeyBase64?.let { keys["ed25519:$it"] = it }
    val base = "MATRIX_KEY_VERIFICATION_MAC" + userID + deviceID + session.flow.otherUserID + otherDevice + transactionID
    val mac = LinkedHashMap<String, JsonValue>()
    for ((id, key) in keys) mac[id] = JsonValue.Str(established.calculateMac(key, base + id))
    val keyIDs = keys.keys.sorted().joinToString(",")
    sendVerification(VerificationEvent.MAC, JsonValue.of(
        "mac" to JsonValue.Obj(mac), "keys" to established.calculateMac(keyIDs, base + "KEY_IDS"),
    ), session)
    val confirmed = session.withState(VerificationFlow.State.Confirmed)
    verifications[transactionID] = confirmed
    if (confirmed.theirMac != null) checkMac(transactionID)
}

/** Отказаться: эмодзи не совпали (`m.mismatched_sas`) или человек передумал (`m.user`). */
suspend fun CryptoMachine.cancelVerification(transactionID: String, code: String = "m.user", reason: String = "Отменено пользователем") {
    val session = verifications[transactionID] ?: return
    cancel(session, code, reason)
}

// MARK: Входящие события

internal suspend fun CryptoMachine.receiveVerification(event: Event) {
    val transactionID = event.content["transaction_id"]?.string ?: return
    receiveVerification(event, transactionID, null)
}

/** События проверки из таймлайнов комнат; зашифрованные сначала расшифровываются. */
internal suspend fun CryptoMachine.receiveRoomVerifications(events: List<Event>, roomID: String) {
    val now = System.currentTimeMillis()
    for (original in events) {
        // Старые события (первая синхронизация, догрузка истории) живых проверок не касаются.
        val ts = original.originServerTS ?: continue
        if (now - ts >= VerificationEvent.REQUEST_LIFETIME_MS) continue
        var event = original
        if (event.type == EventType.ENCRYPTED) {
            event = try { decrypt(event, roomID).event } catch (e: E2EEException) { continue }
        }
        receiveRoomVerification(event, roomID)
    }
}

internal suspend fun CryptoMachine.receiveRoomVerification(event: Event, roomID: String) {
    val content = event.content
    val sender = event.sender ?: return
    if (event.type == EventType.MESSAGE) {
        val eventID = event.eventID ?: return
        if (content["msgtype"]?.string != VerificationEvent.REQUEST || sender == userID || content["to"]?.string != userID) return
        receiveRequest(content, sender, eventID, event.originServerTS, roomID)
        return
    }
    if (event.type !in VerificationEvent.ALL || event.type == VerificationEvent.REQUEST) return
    if (content.at("m.relates_to", "rel_type")?.string != "m.reference") return
    val transactionID = content.at("m.relates_to", "event_id")?.string ?: return
    val session = verifications[transactionID] ?: return
    if (session.flow.roomID != roomID) return
    if (sender == userID) {
        // Наше эхо пропускаем; если на запрос ответило другое наше устройство, здесь проверка закончена.
        if (session.flow.incoming && session.flow.state == VerificationFlow.State.Requested && event.type == VerificationEvent.READY &&
            content["from_device"]?.string != deviceID) {
            verifications[transactionID] = session.withState(
                VerificationFlow.State.Cancelled("m.accepted", "Проверку приняло другое устройство"))
        }
        return
    }
    receiveVerification(event, transactionID, roomID)
}

private suspend fun CryptoMachine.receiveVerification(event: Event, transactionID: String, roomID: String?) {
    val content = event.content
    val sender = event.sender ?: return
    if (event.type == VerificationEvent.REQUEST) {
        receiveRequest(content, sender, transactionID, content["timestamp"]?.long, null)
        return
    }
    val session = verifications[transactionID] ?: return
    if (session.flow.otherUserID != sender || session.flow.roomID != roomID) return
    if (session.flow.state is VerificationFlow.State.Cancelled || session.flow.state == VerificationFlow.State.Done) return
    when (event.type) {
        VerificationEvent.CANCEL -> verifications[transactionID] = session.withState(VerificationFlow.State.Cancelled(
            content["code"]?.string ?: "m.unknown", content["reason"]?.string ?: ""))
        VerificationEvent.READY -> {
            val from = content["from_device"]?.string
            if (session.flow.incoming || session.flow.state != VerificationFlow.State.Requested || from == null ||
                !(session.flow.roomID != null || session.requestedDevices.contains(from)) ||
                content["methods"]?.array?.contains(JsonValue.Str(VerificationEvent.SAS)) != true) {
                return unexpected(session)
            }
            verifications[transactionID] = session.copy(flow = session.flow.copy(otherDeviceID = from, state = VerificationFlow.State.Ready))
            // Остальным устройствам: проверку взяло другое.
            val others = session.requestedDevices.filter { it != from }
            if (others.isNotEmpty()) {
                val cancel = JsonValue.of("transaction_id" to transactionID, "code" to "m.accepted",
                                          "reason" to "Проверку приняло другое устройство")
                try {
                    client.sendToDevice(VerificationEvent.CANCEL, mapOf(sender to others.associateWith { cancel }))
                } catch (e: MatrixError) { }
            }
        }
        VerificationEvent.START -> receiveStart(content, session)
        VerificationEvent.ACCEPT -> {
            val commitment = content["commitment"]?.string
            if (!session.weStarted || session.flow.state != VerificationFlow.State.Started || session.commitment != null ||
                content["key_agreement_protocol"]?.string != VerificationEvent.KEY_AGREEMENT ||
                content["hash"]?.string != VerificationEvent.HASH ||
                content["message_authentication_code"]?.string != VerificationEvent.MAC_METHOD || commitment == null) {
                return unexpected(session)
            }
            verifications[transactionID] = session.copy(commitment = commitment)
            sendKey(transactionID)
        }
        VerificationEvent.KEY -> receiveKey(content, session)
        VerificationEvent.MAC -> {
            val state = session.flow.state
            if ((state != VerificationFlow.State.Comparing && state != VerificationFlow.State.Confirmed) || session.theirMac != null) {
                return unexpected(session)
            }
            verifications[transactionID] = session.copy(theirMac = content)
            if (state == VerificationFlow.State.Confirmed) {
                try { checkMac(transactionID) } catch (e: E2EEException) { }
            }
        }
    }
}

private fun CryptoMachine.receiveRequest(content: JsonValue, sender: String, transactionID: String, timestamp: Long?, roomID: String?) {
    if (verifications[transactionID] != null) return
    val from = content["from_device"]?.string ?: return
    if (sender == userID && from == deviceID) return
    if (content["methods"]?.array?.contains(JsonValue.Str(VerificationEvent.SAS)) != true || timestamp == null) return
    // Старые и «из будущего» запросы не показываем.
    val age = System.currentTimeMillis() - timestamp
    if (age >= VerificationEvent.REQUEST_LIFETIME_MS || age <= -5 * 60 * 1000L) return
    verifications[transactionID] = VerificationSession(VerificationFlow(
        transactionID, sender, from, incoming = true, state = VerificationFlow.State.Requested, roomID = roomID,
    ))
}

private suspend fun CryptoMachine.receiveStart(content: JsonValue, current: VerificationSession) {
    var session = current
    val transactionID = session.flow.transactionID
    if (content["from_device"]?.string != session.flow.otherDeviceID ||
        !(session.flow.state == VerificationFlow.State.Ready || (session.flow.state == VerificationFlow.State.Started && session.weStarted))) {
        return unexpected(session)
    }
    if (session.weStarted) {
        // Начали обе стороны сразу: остаётся start того, чей user_id (затем device_id) меньше.
        val theirUser = session.flow.otherUserID
        val theirDevice = session.flow.otherDeviceID ?: ""
        val mineFirst = userID < theirUser || (userID == theirUser && deviceID < theirDevice)
        if (mineFirst) return
    }
    fun offers(field: String, value: String) = content[field]?.array?.contains(JsonValue.Str(value)) == true
    if (content["method"]?.string != VerificationEvent.SAS || !offers("key_agreement_protocols", VerificationEvent.KEY_AGREEMENT) ||
        !offers("hashes", VerificationEvent.HASH) || !offers("message_authentication_codes", VerificationEvent.MAC_METHOD)) {
        return cancel(session, "m.unknown_method", "Нет общего способа проверки")
    }
    val modes = (content["short_authentication_string"]?.array ?: emptyList())
        .filter { it == JsonValue.Str("emoji") || it == JsonValue.Str("decimal") }
    if (!modes.contains(JsonValue.Str("decimal"))) return cancel(session, "m.unknown_method", "Нет общего способа проверки")
    val commitment = Base64.encodeUnpadded(Hash.sha256((session.sas.publicKeyBase64 + content.canonical()).toByteArray()))
    val accept = JsonValue.of(
        "method" to VerificationEvent.SAS, "key_agreement_protocol" to VerificationEvent.KEY_AGREEMENT,
        "hash" to VerificationEvent.HASH, "message_authentication_code" to VerificationEvent.MAC_METHOD,
        "short_authentication_string" to modes, "commitment" to commitment,
    )
    try { sendVerification(VerificationEvent.ACCEPT, accept, session) } catch (e: E2EEException) { return }
    session = session.copy(weStarted = false, startContent = content).withState(VerificationFlow.State.Started)
    verifications[transactionID] = session
}

private suspend fun CryptoMachine.receiveKey(content: JsonValue, current: VerificationSession) {
    var session = current
    val transactionID = session.flow.transactionID
    val key = content["key"]?.string
    val startContent = session.startContent
    if (session.flow.state != VerificationFlow.State.Started || session.theirKey != null || key == null || startContent == null) {
        return unexpected(session)
    }
    if (session.weStarted) {
        // Мы начали: другая сторона заранее обязалась ключом в accept.
        val commitment = session.commitment
        if (!session.keySent || commitment == null) return unexpected(session)
        val expected = Base64.encodeUnpadded(Hash.sha256((key + startContent.canonical()).toByteArray()))
        if (expected != commitment) return cancel(session, "m.mismatched_commitment", "Ключ не совпал с обязательством")
    } else if (session.keySent) {
        return unexpected(session)
    }
    val established = runCatching { session.sas.establish(key) }.getOrNull()
    val other = session.flow.otherDeviceID
    if (established == null || other == null) return cancel(session, "m.invalid_message", "Неверный ключ")
    session = session.copy(theirKey = key, established = established)
    verifications[transactionID] = session
    if (!session.weStarted) {
        sendKey(transactionID)
        val sent = verifications[transactionID]
        if (sent == null || !sent.keySent) return
        session = sent
    }
    val ours = listOf(userID, deviceID, session.sas.publicKeyBase64)
    val theirs = listOf(session.flow.otherUserID, other, key)
    val (starter, accepter) = if (session.weStarted) ours to theirs else theirs to ours
    val info = (listOf("MATRIX_KEY_VERIFICATION_SAS") + starter + accepter + transactionID).joinToString("|")
    session = session.copy(flow = session.flow.copy(
        emoji = established.emojiIndices(info), decimals = established.decimals(info), state = VerificationFlow.State.Comparing,
    ))
    verifications[transactionID] = session
}

private suspend fun CryptoMachine.sendKey(transactionID: String) {
    val session = verifications[transactionID] ?: return
    try {
        sendVerification(VerificationEvent.KEY, JsonValue.of("key" to session.sas.publicKeyBase64), session)
    } catch (e: E2EEException) { return }
    verifications[transactionID] = (verifications[transactionID] ?: session).copy(keySent = true)
}

/** Проверить MAC другой стороны и довериться её ключам: подписать устройство или мастер-ключ. */
private suspend fun CryptoMachine.checkMac(transactionID: String) {
    val session = verifications[transactionID] ?: return
    val content = session.theirMac ?: return
    val established = session.established ?: return
    val otherDevice = session.flow.otherDeviceID ?: return
    val other = session.flow.otherUserID
    val base = "MATRIX_KEY_VERIFICATION_MAC" + other + otherDevice + userID + deviceID + transactionID
    val macs = content["mac"]?.obj
    val keysMac = content["keys"]?.string
    if (macs == null || keysMac == null ||
        established.calculateMac(macs.keys.sorted().joinToString(","), base + "KEY_IDS") != keysMac) {
        return cancel(session, "m.key_mismatch", "Список ключей не совпал")
    }
    val device = devices(listOf(other)).firstOrNull { it.deviceID == otherDevice }
        ?: return cancel(session, "m.key_mismatch", "Устройство не найдено")
    val identity = storing { store.crossSigning(other) }
    var deviceOK = false
    var masterKey: String? = null
    for ((id, value) in macs) {
        val key = when {
            id == "ed25519:$otherDevice" -> device.ed25519Key
            identity != null && id == "ed25519:${identity.masterKey}" -> identity.masterKey
            else -> continue
        }
        if (value.string != established.calculateMac(key, base + id)) return cancel(session, "m.key_mismatch", "Ключи не совпали")
        if (key == device.ed25519Key) deviceOK = true else masterKey = key
    }
    if (!deviceOK) return cancel(session, "m.key_mismatch", "Нет ключа устройства")

    // Подписать нечем — доверяем устройству на этом устройстве. Своё новое устройство без ключей кросс-подписи
    // тут же просит их (и ключ бэкапа) у только что проверенного.
    if (other == userID && ownCrossSigningKey(CrossSigningKind.SELF_SIGNING) != null) {
        signDevice(device)
    } else if (other != userID && masterKey != null && ownCrossSigningKey(CrossSigningKind.USER_SIGNING) != null) {
        signUser(other, masterKey)
    } else {
        trustLocally(device)
    }
    sendVerification(VerificationEvent.DONE, JsonValue.Obj(emptyMap()), session)
    if (other == userID && ownCrossSigningKey(CrossSigningKind.SELF_SIGNING) == null) requestSecrets(device)
    verifications[transactionID] = session.withState(VerificationFlow.State.Done)
}

// MARK: Подписи

/** Подписать своё другое устройство ключом самоподписи. */
internal suspend fun CryptoMachine.signDevice(device: DeviceInfo) {
    val selfSigning = ownCrossSigningKey(CrossSigningKind.SELF_SIGNING) ?: throw E2EEException.NoBackup
    val response = server { client.queryKeys(listOf(userID)) }
    val keys = response.at("device_keys", userID, device.deviceID)
    if (keys == null || keys.at("keys", "ed25519:${device.deviceID}")?.string != device.ed25519Key) {
        throw E2EEException.BadPayload("ключи устройства на сервере изменились")
    }
    val signed = signedWith(withoutSignatures(keys), userID, selfSigning.publicKeyBase64, selfSigning)
    uploadSignature(userID, device.deviceID, signed)
}

/** Подписать мастер-ключ собеседника своим ключом подписи пользователей. */
internal suspend fun CryptoMachine.signUser(other: String, masterKey: String) {
    val userSigning = ownCrossSigningKey(CrossSigningKind.USER_SIGNING) ?: throw E2EEException.NoBackup
    val response = server { client.queryKeys(listOf(other)) }
    val master = response.at("master_keys", other)
    if (master == null || crossSigningPublicKey(master, other, "master") != masterKey) {
        throw E2EEException.BadPayload("мастер-ключ собеседника на сервере изменился")
    }
    val signed = signedWith(withoutSignatures(master), userID, userSigning.publicKeyBase64, userSigning)
    uploadSignature(other, masterKey, signed)
}

private suspend fun CryptoMachine.uploadSignature(user: String, keyID: String, signed: JsonValue) {
    val failures = server { client.uploadSignatures(mapOf(user to mapOf(keyID to signed))) }
    val failure = failures.at(user, keyID)
    if (failure != null) throw E2EEException.BadPayload("подпись отклонена: ${failure["errcode"]?.string ?: ""}")
    storing { store.markDeviceListsChanged(listOf(user)) }
}

internal fun withoutSignatures(obj: JsonValue): JsonValue {
    val fields = obj.obj?.let { LinkedHashMap(it) } ?: return obj
    fields.remove("signatures")
    fields.remove("unsigned")
    return JsonValue.Obj(fields)
}

// MARK: Отправка

/** Отправить событие проверки. Возвращает содержимое в том виде, в каком оно ушло. */
private suspend fun CryptoMachine.sendVerification(type: String, content: JsonValue, session: VerificationSession): JsonValue {
    val fields = content.obj?.let { LinkedHashMap(it) } ?: throw E2EEException.BadPayload("содержимое проверки не объект")
    val roomID = session.flow.roomID
    if (roomID != null) {
        fields["m.relates_to"] = JsonValue.of("rel_type" to "m.reference", "event_id" to session.flow.transactionID)
        sendRoomEvent(roomID, type, JsonValue.Obj(fields))
        return JsonValue.Obj(fields)
    }
    val device = session.flow.otherDeviceID ?: throw E2EEException.BadPayload("нет устройства собеседника")
    fields["transaction_id"] = JsonValue.Str(session.flow.transactionID)
    server { client.sendToDevice(type, mapOf(session.flow.otherUserID to mapOf(device to JsonValue.Obj(fields)))) }
    return JsonValue.Obj(fields)
}

/** Событие в комнату: в зашифрованной (или неизвестной локально) — через Megolm. */
private suspend fun CryptoMachine.sendRoomEvent(roomID: String, type: String, content: JsonValue): String {
    val record = storing { store.room(roomID) }
    if (record?.isEncrypted ?: true) return sendEncrypted(roomID, type, content)
    return server { client.send(roomID, type, content) }
}

private suspend fun CryptoMachine.unexpected(session: VerificationSession) =
    cancel(session, "m.unexpected_message", "Неожиданное сообщение проверки")

private suspend fun CryptoMachine.cancel(session: VerificationSession, code: String, reason: String) {
    val content = JsonValue.of("transaction_id" to session.flow.transactionID, "code" to code, "reason" to reason)
    val targets = session.flow.otherDeviceID?.let { listOf(it) } ?: session.requestedDevices
    if (session.flow.roomID != null) {
        try { sendVerification(VerificationEvent.CANCEL, JsonValue.of("code" to code, "reason" to reason), session) } catch (e: E2EEException) { }
    } else if (targets.isNotEmpty()) {
        try {
            client.sendToDevice(VerificationEvent.CANCEL, mapOf(session.flow.otherUserID to targets.associateWith { content }))
        } catch (e: MatrixError) { }
    }
    verifications[session.flow.transactionID] = session.withState(VerificationFlow.State.Cancelled(code, reason))
}
