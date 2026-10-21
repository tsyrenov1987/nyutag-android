// Поддельный домашний сервер в памяти для тестов: вход, устройства, ключи, to-device, комнаты, бэкап ключей.
// Подключается к MatrixClient как [Transport].

package chat.nyutag.core

import chat.nyutag.core.auth.Session
import chat.nyutag.core.net.HttpRequest
import chat.nyutag.core.net.HttpResponse
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.Transport
import chat.nyutag.core.sync.Event
import chat.nyutag.json.JsonValue
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

class FakeHomeserver : Transport {
    val host = "fake-" + UUID.randomUUID().toString().lowercase()
    val homeserver: URI = URI("http://$host")

    private val lock = Any()
    private val tokens = HashMap<String, Pair<String, String>>()
    private val deviceKeys = HashMap<String, HashMap<String, JsonValue>>()
    private val oneTimeKeys = HashMap<String, ArrayList<Pair<String, JsonValue>>>()
    private val fallbackKeys = HashMap<String, Pair<String, JsonValue>>()
    private val inbox = HashMap<String, ArrayList<JsonValue>>()
    private val members = HashMap<String, Set<String>>()
    private val timeline = HashMap<String, ArrayList<JsonValue>>()
    val backup = HashMap<String, HashMap<String, JsonValue>>()
    var backupVersion = "1"; private set
    var backupAuthData: JsonValue? = null; private set
    private val accountData = HashMap<String, HashMap<String, JsonValue>>()
    private var nextEvent = 0
    val deactivated = HashSet<String>()
    var erased = false; private set
    private val deviceNames = HashMap<String, String>()
    data class Report(val user: String, val roomID: String, val eventID: String, val reason: String, val score: Int)
    val reports = ArrayList<Report>()
    /** Тела запросов `/createRoom` по порядку. */
    val createdRooms = ArrayList<JsonValue>()
    private val crossSigning = HashMap<String, HashMap<String, JsonValue>>()
    var requirePassword: String? = null
    var tamperDeviceKeys: ((String, String, JsonValue) -> JsonValue)? = null
    private val passwords = HashMap<String, String>()
    private var loginCount = 0
    private val pusherTable = HashMap<String, HashMap<String, JsonValue>>()
    private val pushRules = HashMap<String, JsonValue>()
    /** Загруженные файлы по идентификатору, их Content-Type и пути скачиваний по порядку. */
    val media = HashMap<String, ByteArray>()
    val mediaTypes = HashMap<String, String>()
    val mediaRequests = ArrayList<String>()
    private var nextMedia = 0

    fun session(user: String, device: String): Session {
        val token = "tok-$user-$device"
        synchronized(lock) { tokens[token] = user to device }
        return Session(user, device, token, homeserver)
    }

    fun setPassword(password: String, user: String) = synchronized(lock) { passwords[user] = password }
    fun isTokenValid(token: String): Boolean = synchronized(lock) { tokens.containsKey(token) }
    fun pushers(user: String): List<JsonValue> = synchronized(lock) { pusherTable[user]?.values?.toList() ?: emptyList() }
    fun pushRule(user: String, kind: String, id: String): JsonValue? = synchronized(lock) { pushRules["$user|$kind|$id"] }

    /** Клиент с любым токеном или без него (для входа). */
    fun client(accessToken: String?): MatrixClient = MatrixClient(homeserver, accessToken, transport = this)
    fun client(session: Session): MatrixClient = MatrixClient(homeserver, session.accessToken, transport = this)

    fun setMembers(users: List<String>, roomID: String) = synchronized(lock) { members[roomID] = users.toSet() }

    /** Забрать накопившиеся to-device события устройства. */
    fun takeToDevice(user: String, device: String): List<Event> = synchronized(lock) {
        (inbox.remove("$user|$device") ?: emptyList()).mapNotNull { Event.from(it) }
    }

    fun events(roomID: String): List<Event> = synchronized(lock) { (timeline[roomID] ?: emptyList()).mapNotNull { Event.from(it) } }

    fun dropOneTimeKeys(user: String, device: String) = synchronized(lock) {
        oneTimeKeys.remove("$user|$device")
        fallbackKeys.remove("$user|$device")
    }

    fun crossSigningKey(user: String, usage: String): JsonValue? = synchronized(lock) { crossSigning[user]?.get(usage) }
    fun accountDataContent(user: String, type: String): JsonValue? = synchronized(lock) { accountData[user]?.get(type) }
    fun oneTimeKeyCount(user: String, device: String): Int = synchronized(lock) { oneTimeKeys["$user|$device"]?.size ?: 0 }

    // MARK: Transport

    override suspend fun send(request: HttpRequest): HttpResponse {
        val url = request.url
        mediaResponse(request)?.let { return it }
        // /_matrix/client/v3/<путь>
        val path = url.rawPath.split("/").filter { it.isNotEmpty() }.drop(3).map { URLDecoder.decode(it, "UTF-8") }
        val query = HashMap<String, String>()
        url.rawQuery?.split("&")?.forEach { item ->
            if (item.isEmpty()) return@forEach
            val eq = item.indexOf('=')
            val key = URLDecoder.decode(if (eq < 0) item else item.substring(0, eq), "UTF-8")
            val value = if (eq < 0) "" else URLDecoder.decode(item.substring(eq + 1), "UTF-8")
            query.putIfAbsent(key, value)
        }
        val body = request.body?.takeIf { it.isNotEmpty() }?.let { try { JsonValue.parse(String(it, Charsets.UTF_8)) } catch (_: Exception) { null } }
        val token = request.header("Authorization")?.removePrefix("Bearer ")
        val (status, json) = handle(request.method, path, query, body, token)
        return HttpResponse(status, mapOf("Content-Type" to listOf("application/json")), json.compact().toByteArray(Charsets.UTF_8))
    }

    /** Медиа: тела не JSON, поэтому мимо [handle]. */
    private fun mediaResponse(request: HttpRequest): HttpResponse? {
        val path = request.url.rawPath
        fun json(status: Int, value: JsonValue) =
            HttpResponse(status, mapOf("Content-Type" to listOf("application/json")), value.compact().toByteArray(Charsets.UTF_8))
        val user = request.header("Authorization")?.removePrefix("Bearer ")?.let { synchronized(lock) { tokens[it] } }
        if (path == "/_matrix/media/v3/upload" || path.startsWith("/_matrix/client/v1/media/")) {
            if (user == null) return json(401, JsonValue.of("errcode" to "M_MISSING_TOKEN", "error" to "нет токена"))
        } else {
            return null
        }
        if (request.method == "POST" && path == "/_matrix/media/v3/upload") {
            val id = "m" + synchronized(lock) { ++nextMedia }
            synchronized(lock) {
                media[id] = request.body ?: ByteArray(0)
                mediaTypes[id] = request.header("Content-Type") ?: ""
            }
            return json(200, JsonValue.of("content_uri" to "mxc://nyutag.chat/$id"))
        }
        if (path == "/_matrix/client/v1/media/config") return json(200, JsonValue.of("m.upload.size" to 50_000_000))
        val parts = path.removePrefix("/_matrix/client/v1/media/").split("/")
        if (parts.size == 3 && (parts[0] == "download" || parts[0] == "thumbnail")) {
            synchronized(lock) { mediaRequests.add(path + (request.url.rawQuery?.let { "?$it" } ?: "")) }
            val bytes = synchronized(lock) { media[parts[2]] }
                ?: return json(404, JsonValue.of("errcode" to "M_NOT_FOUND", "error" to "нет файла"))
            return HttpResponse(200, mapOf("Content-Type" to listOf("application/octet-stream")), bytes)
        }
        return json(404, JsonValue.of("errcode" to "M_UNRECOGNIZED", "error" to path))
    }

    private fun error(status: Int, errcode: String, message: String) = status to JsonValue.of("errcode" to errcode, "error" to message)
    private val ok = 200 to JsonValue.Obj(emptyMap())
    private fun uia(session: String) = 401 to JsonValue.of(
        "flows" to listOf(JsonValue.of("stages" to listOf("m.login.password"))), "params" to JsonValue.Obj(emptyMap()), "session" to session,
    )

    fun handle(method: String, path: List<String>, query: Map<String, String>, body: JsonValue?, token: String?): Pair<Int, JsonValue> = synchronized(lock) {
        if (method == "POST" && path == listOf("login")) {
            val user = body?.at("identifier", "user")?.string
            val password = body?.get("password")?.string
            if (user == null || password == null) return error(400, "M_BAD_JSON", "нет логина")
            val userID = if (user.startsWith("@")) user else "@$user:nyutag.chat"
            if (passwords[userID] != password) return error(403, "M_FORBIDDEN", "пароль")
            loginCount += 1
            val device = body["device_id"]?.string ?: "DEV$loginCount"
            val newToken = "tok-$userID-$device-$loginCount"
            tokens[newToken] = userID to device
            return 200 to JsonValue.of("user_id" to userID, "access_token" to newToken, "device_id" to device)
        }
        if (method == "GET" && path == listOf("register", "available")) {
            val nick = query["username"] ?: return error(400, "M_MISSING_PARAM", "нет username")
            if (passwords.containsKey("@$nick:nyutag.chat")) return error(400, "M_USER_IN_USE", "занято")
            return 200 to JsonValue.of("available" to true)
        }
        val who = token?.let { tokens[it] } ?: return error(401, "M_UNKNOWN_TOKEN", "нет токена")
        val (user, device) = who
        val p = path
        when {
            method == "GET" && p == listOf("account", "whoami") ->
                return 200 to JsonValue.of("user_id" to user, "device_id" to device)
            method == "POST" && p == listOf("logout") -> {
                tokens.remove(token)
                return ok
            }
            method == "GET" && p == listOf("devices") -> {
                val list = tokens.values.filter { it.first == user }.map { it.second }.toSet().sorted().map { id ->
                    val item = linkedMapOf<String, JsonValue>("device_id" to JsonValue.Str(id))
                    deviceNames[id]?.let { item["display_name"] = JsonValue.Str(it) }
                    JsonValue.Obj(item)
                }
                return 200 to JsonValue.of("devices" to list)
            }
            method == "PUT" && p.size == 2 && p[0] == "devices" -> {
                if (tokens.values.none { it.first == user && it.second == p[1] }) return error(404, "M_NOT_FOUND", "нет устройства")
                body?.get("display_name")?.string?.let { deviceNames[p[1]] = it } ?: deviceNames.remove(p[1])
                return ok
            }
            method == "DELETE" && p.size == 2 && p[0] == "devices" -> {
                val password = passwords[user]
                if (password == null || body?.at("auth", "password")?.string != password || body.at("auth", "session")?.string != "uia-device") {
                    return uia("uia-device")
                }
                tokens.entries.removeIf { it.value.first == user && it.value.second == p[1] }
                return ok
            }
            method == "POST" && p == listOf("account", "deactivate") -> {
                val password = passwords[user]
                if (password == null || body?.at("auth", "password")?.string != password || body.at("auth", "session")?.string != "uia-deactivate") {
                    return uia("uia-deactivate")
                }
                deactivated += user
                erased = body["erase"]?.bool == true
                tokens.entries.removeIf { it.value.first == user }
                return 200 to JsonValue.of("id_server_unbind_result" to "no-support")
            }
            method == "POST" && p.size == 4 && p[0] == "rooms" && p[2] == "report" -> {
                reports += Report(user, p[1], p[3], body?.get("reason")?.string ?: "", body?.get("score")?.int ?: 0)
                return ok
            }
            method == "POST" && p == listOf("pushers", "set") -> {
                val appID = body?.get("app_id")?.string
                val pushKey = body?.get("pushkey")?.string
                if (appID == null || pushKey == null) return error(400, "M_MISSING_PARAM", "нет app_id/pushkey")
                val table = pusherTable.getOrPut(user) { HashMap() }
                if (body["kind"]?.isNull == true) table.remove("$appID|$pushKey") else table["$appID|$pushKey"] = body
                return ok
            }
            method == "PUT" && p.size == 4 && p[0] == "pushrules" && p[1] == "global" -> {
                pushRules["$user|${p[2]}|${p[3]}"] = body ?: JsonValue.Obj(emptyMap())
                return ok
            }
            method == "DELETE" && p.size == 4 && p[0] == "pushrules" && p[1] == "global" -> {
                if (pushRules.remove("$user|${p[2]}|${p[3]}") == null) return error(404, "M_NOT_FOUND", "нет правила")
                return ok
            }
            method == "POST" && p == listOf("keys", "upload") -> {
                body?.get("device_keys")?.let { deviceKeys.getOrPut(user) { HashMap() }[device] = it }
                for ((id, key) in body?.get("one_time_keys")?.obj ?: emptyMap()) {
                    oneTimeKeys.getOrPut("$user|$device") { ArrayList() } += id to key
                }
                for ((id, key) in body?.get("fallback_keys")?.obj ?: emptyMap()) fallbackKeys["$user|$device"] = id to key
                val count = oneTimeKeys["$user|$device"]?.size ?: 0
                return 200 to JsonValue.of("one_time_key_counts" to JsonValue.of("signed_curve25519" to count))
            }
            method == "POST" && p == listOf("keys", "query") -> {
                val result = LinkedHashMap<String, JsonValue>()
                for (target in body?.get("device_keys")?.obj?.keys?.sorted() ?: emptyList()) {
                    val devices = LinkedHashMap<String, JsonValue>()
                    for ((id, keys) in deviceKeys[target] ?: emptyMap()) {
                        devices[id] = tamperDeviceKeys?.invoke(target, id, keys) ?: keys
                    }
                    result[target] = JsonValue.Obj(devices)
                }
                val response = linkedMapOf<String, JsonValue>("device_keys" to JsonValue.Obj(result), "failures" to JsonValue.Obj(emptyMap()))
                for ((usage, field) in listOf("master" to "master_keys", "self_signing" to "self_signing_keys", "user_signing" to "user_signing_keys")) {
                    val keys = LinkedHashMap<String, JsonValue>()
                    for (target in result.keys) {
                        // Ключ подписи пользователей виден только его владельцу.
                        if (usage == "user_signing" && target != user) continue
                        crossSigning[target]?.get(usage)?.let { keys[target] = it }
                    }
                    response[field] = JsonValue.Obj(keys)
                }
                return 200 to JsonValue.Obj(response)
            }
            method == "POST" && p == listOf("keys", "device_signing", "upload") -> {
                requirePassword?.let { password ->
                    if (body?.at("auth", "password")?.string != password || body.at("auth", "session")?.string != "uia1") return uia("uia1")
                }
                for ((usage, field) in listOf("master" to "master_key", "self_signing" to "self_signing_key", "user_signing" to "user_signing_key")) {
                    body?.get(field)?.let { crossSigning.getOrPut(user) { HashMap() }[usage] = it }
                }
                return ok
            }
            method == "POST" && p == listOf("keys", "signatures", "upload") -> {
                for ((target, keys) in body?.obj ?: emptyMap()) {
                    for ((keyID, signed) in keys.obj ?: emptyMap()) {
                        val added = signed["signatures"]?.obj ?: emptyMap()
                        fun merge(value: JsonValue): JsonValue {
                            val fields = value.obj?.let { LinkedHashMap(it) } ?: return value
                            val signatures = fields["signatures"]?.obj?.let { LinkedHashMap(it) } ?: LinkedHashMap()
                            for ((signer, entries) in added) {
                                val theirs = signatures[signer]?.obj?.let { LinkedHashMap(it) } ?: LinkedHashMap()
                                for ((id, v) in entries.obj ?: emptyMap()) theirs[id] = v
                                signatures[signer] = JsonValue.Obj(theirs)
                            }
                            fields["signatures"] = JsonValue.Obj(signatures)
                            return JsonValue.Obj(fields)
                        }
                        val deviceEntry = deviceKeys[target]?.get(keyID)
                        val master = crossSigning[target]?.get("master")
                        if (deviceEntry != null) {
                            deviceKeys[target]!![keyID] = merge(deviceEntry)
                        } else if (master != null && master.at("keys", "ed25519:$keyID") != null) {
                            crossSigning[target]!!["master"] = merge(master)
                        }
                    }
                }
                return 200 to JsonValue.of("failures" to JsonValue.Obj(emptyMap()))
            }
            method == "POST" && p == listOf("keys", "claim") -> {
                val result = LinkedHashMap<String, JsonValue>()
                for ((target, devices) in body?.get("one_time_keys")?.obj ?: emptyMap()) {
                    val claimed = LinkedHashMap<String, JsonValue>()
                    for (id in devices.obj?.keys?.sorted() ?: emptyList()) {
                        val slot = "$target|$id"
                        val keys = oneTimeKeys[slot]
                        if (!keys.isNullOrEmpty()) {
                            val key = keys.removeAt(0)
                            claimed[id] = JsonValue.Obj(mapOf(key.first to key.second))
                        } else {
                            fallbackKeys[slot]?.let { claimed[id] = JsonValue.Obj(mapOf(it.first to it.second)) }
                        }
                    }
                    result[target] = JsonValue.Obj(claimed)
                }
                return 200 to JsonValue.of("one_time_keys" to JsonValue.Obj(result), "failures" to JsonValue.Obj(emptyMap()))
            }
            method == "PUT" && p.size == 3 && p[0] == "sendToDevice" -> {
                for ((target, devices) in body?.get("messages")?.obj ?: emptyMap()) {
                    for ((id, content) in devices.obj ?: emptyMap()) {
                        val targets = if (id == "*") (deviceKeys[target]?.keys?.toList() ?: emptyList()) else listOf(id)
                        for (t in targets) {
                            inbox.getOrPut("$target|$t") { ArrayList() } += JsonValue.of("type" to p[1], "sender" to user, "content" to content)
                        }
                    }
                }
                return ok
            }
            method == "GET" && p.size == 4 && p[0] == "rooms" && p[2] == "event" -> {
                val event = (timeline[p[1]] ?: emptyList()).firstOrNull { it["event_id"]?.string == p[3] }
                    ?: return error(404, "M_NOT_FOUND", "нет события")
                return 200 to event
            }
            method == "GET" && p.size == 3 && p[0] == "rooms" && p[2] == "members" -> {
                val chunk = (members[p[1]] ?: emptySet()).sorted().map { u ->
                    JsonValue.of("type" to "m.room.member", "state_key" to u, "sender" to u, "event_id" to "\$member-$u",
                                 "content" to JsonValue.of("membership" to "join"))
                }
                return 200 to JsonValue.of("chunk" to chunk)
            }
            method == "PUT" && p.size == 5 && p[0] == "rooms" && p[2] == "send" -> {
                nextEvent += 1
                val eventID = "\$e$nextEvent"
                timeline.getOrPut(p[1]) { ArrayList() } += JsonValue.of(
                    "type" to p[3], "event_id" to eventID, "sender" to user, "origin_server_ts" to System.currentTimeMillis(),
                    "room_id" to p[1], "content" to (body ?: JsonValue.Obj(emptyMap())),
                )
                return 200 to JsonValue.of("event_id" to eventID)
            }
            method == "POST" && p == listOf("createRoom") -> {
                createdRooms.add(body ?: JsonValue.Obj(emptyMap()))
                val roomID = "!new:nyutag.chat"
                members[roomID] = setOf(user) + ((body?.get("invite")?.array ?: emptyList()).mapNotNull { it.string })
                return 200 to JsonValue.of("room_id" to roomID)
            }
            method == "PUT" && p.size == 4 && p[0] == "user" && p[2] == "account_data" -> {
                if (p[1] != user) return error(403, "M_FORBIDDEN", "чужой")
                accountData.getOrPut(user) { HashMap() }[p[3]] = body ?: JsonValue.Obj(emptyMap())
                return ok
            }
            method == "GET" && p.size == 4 && p[0] == "user" && p[2] == "account_data" -> {
                val content = if (p[1] == user) accountData[user]?.get(p[3]) else null
                return if (content == null) error(404, "M_NOT_FOUND", "нет") else 200 to content
            }
            method == "POST" && p == listOf("room_keys", "version") -> {
                backupVersion = ((backupVersion.toIntOrNull() ?: 0) + 1).toString()
                backupAuthData = body?.get("auth_data")
                backup.clear()
                return 200 to JsonValue.of("version" to backupVersion)
            }
            method == "GET" && p == listOf("room_keys", "version") -> {
                val authData = backupAuthData ?: return error(404, "M_NOT_FOUND", "нет бэкапа")
                return 200 to JsonValue.of("version" to backupVersion, "algorithm" to "m.megolm_backup.v1.curve25519-aes-sha2",
                                           "auth_data" to authData, "count" to backup.values.sumOf { it.size }, "etag" to "1")
            }
            method == "GET" && p == listOf("room_keys", "keys") -> {
                if (query["version"] != backupVersion) return error(404, "M_NOT_FOUND", "версия")
                val rooms = backup.mapValues { (_, sessions) -> JsonValue.of("sessions" to JsonValue.Obj(sessions)) }
                return 200 to JsonValue.of("rooms" to JsonValue.Obj(rooms))
            }
            method == "PUT" && p == listOf("room_keys", "keys") -> {
                if (query["version"] != backupVersion) {
                    return 403 to JsonValue.of("errcode" to "M_WRONG_ROOM_KEYS_VERSION", "error" to "версия", "current_version" to backupVersion)
                }
                for ((room, entry) in body?.get("rooms")?.obj ?: emptyMap()) {
                    for ((sessionID, record) in entry["sessions"]?.obj ?: emptyMap()) backup.getOrPut(room) { HashMap() }[sessionID] = record
                }
                return 200 to JsonValue.of("count" to backup.values.sumOf { it.size }, "etag" to "1")
            }
            else -> return error(404, "M_UNRECOGNIZED", "$method ${path.joinToString("/")}")
        }
    }
}
