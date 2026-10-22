// Нютаг. Двери в процессе приложения: гонка проб по каталогу, выдача двери клиентам, повтор гонки при смене
// сети, возврате из фона и жалобе клиента. Пока двери нет, запросы клиентов ждут её (`doorWaitMillis`)
// и падают ошибкой сети; на `core.nyutag.chat` напрямую они не ходят — снаружи он закрыт.

package chat.nyutag.messenger.doors

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import chat.nyutag.core.net.Door
import chat.nyutag.core.net.DoorCatalogue
import chat.nyutag.core.net.DoorMemory
import chat.nyutag.core.net.DoorRace
import chat.nyutag.core.net.KeyValueStore
import chat.nyutag.core.net.MatrixClient
import chat.nyutag.core.net.UrlConnectionTransport
import chat.nyutag.json.JsonValue
import chat.nyutag.messenger.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

class DoorHub(context: Context, private val memory: KeyValueStore) {
    sealed class State {
        object Idle : State()
        object Probing : State()
        data class Up(val door: Door) : State()
        object Failed : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    /** Ключ и двери из ресурсов (`assets/nyutag-catalogue.txt`, не в git). Содержимое нигде не печатается. */
    private val bundled: DoorCatalogue? = runCatching {
        context.assets.open(CATALOGUE).use { String(it.readBytes(), Charsets.UTF_8) }
    }.getOrNull()?.let(DoorCatalogue::parse)

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = stateFlow

    /** Дверь для клиентов. До первой гонки — последняя удачная из памяти: сохранённый вход начинает /sync сразу. */
    private val current = MutableStateFlow(if (bundled == null) null else DoorMemory.recall(memory)?.door)

    @Volatile private var doors: List<Door> = DoorCatalogue.merge(bundled?.doors.orEmpty(), DoorMemory.recallDynamic(memory))
    private var race: Job? = null
    @Volatile private var lastRace = 0L
    private var fetchedCatalogue = false
    private var watching = false

    /** Есть ли сеть вообще: без неё беды запросов — беда сети, а не двери. */
    fun isOnline(): Boolean = connectivity?.activeNetwork != null

    // MARK: Запуск

    /** Один раз за процесс: первая гонка сразу, дальше по смене сети. */
    @Synchronized fun begin() {
        if (watching) return
        watching = true
        startRace()
        connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == last) return
                last = network
                networkChanged()
            }
        })
    }

    /** Сеть сменилась: лучшая дверь могла стать другой, а запертая — открыться. */
    private fun networkChanged() {
        if (since(lastRace) > 5_000) startRace()
    }

    @Synchronized private fun startRace() {
        if (race?.isActive == true) return
        lastRace = System.currentTimeMillis()
        log("гонка: ${doors.size} дверей, знакомая ${current.value?.name ?: "нет"}")
        if (stateFlow.value !is State.Up) stateFlow.value = State.Probing
        val doors = doors
        val preferred = current.value
        race = scope.launch {
            val winner = DoorRace.run(doors, preferred, headStartMillis = 400) { knock(it) }
            raceEnded(winner)
        }
    }

    private fun raceEnded(winner: Door?) {
        log(winner?.let { "победила ${it.name}" } ?: "ни одна дверь не открылась")
        if (winner == null) {
            publish(State.Failed)
            return
        }
        publish(State.Up(winner))
        bundled?.key?.let { DoorMemory.remember(winner, it, memory) }
        val fetch = synchronized(this) { !fetchedCatalogue.also { fetchedCatalogue = true } }
        if (fetch) fetchCatalogue()
    }

    /** Проба: ядро за дверью отвечает списком версий. Любой другой ответ — дверь не наша или заперта. */
    private suspend fun knock(door: Door): Boolean {
        val started = System.currentTimeMillis()
        return try {
            runInterruptible(Dispatchers.IO) {
                val url = URI(door.url.toString().trimEnd('/') + "/_matrix/client/versions").toURL()
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = PROBE_MILLIS
                    connection.readTimeout = PROBE_MILLIS
                    connection.useCaches = false
                    connection.instanceFollowRedirects = false
                    bundled?.key?.let { connection.setRequestProperty(DoorCatalogue.HEADER_NAME, it) }
                    connection.setRequestProperty("User-Agent", "Nyutag")
                    val status = connection.responseCode
                    val open = status == 200 && runCatching {
                        JsonValue.parse(connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })["versions"]
                            ?.array?.isNotEmpty() == true
                    }.getOrDefault(false)
                    log("дверь ${door.name}: $status${if (open) "" else " (не ядро)"} за ${since(started)} мс")
                    open
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: java.io.IOException) {
            log("дверь ${door.name}: ${e.javaClass.simpleName} за ${since(started)} мс")
            false
        }
    }

    /** Сервер может прислать новые двери (`doors` в каталоге): запоминаем их на следующие запуски. */
    private fun fetchCatalogue() {
        val client = makeClient()
        scope.launch {
            val json = runCatching { client.raw(MatrixClient.Method.GET, "/_nyutag/v1/catalogue", authenticated = false) }
                .getOrNull() ?: return@launch
            val fresh = DoorCatalogue.doors(json["doors"]?.array.orEmpty().mapNotNull { it.string })
            if (fresh.isEmpty()) return@launch
            DoorMemory.rememberDynamic(fresh, memory)
            doors = DoorCatalogue.merge(bundled?.doors.orEmpty(), fresh)
        }
    }

    // MARK: Жалобы и возврат из фона

    /** Клиент не достучался до двери: если это наша текущая и гонка не шла только что — ищем заново. */
    fun trouble(door: Door) {
        if (current.value != door || race?.isActive == true || since(lastRace) <= 10_000) return
        startRace()
    }

    /** Приложение снова на экране: за время сна дверь могли запереть. */
    fun appBecameActive() {
        when (val now = stateFlow.value) {
            is State.Up -> if (since(lastRace) > 30_000) scope.launch { if (!knock(now.door)) startRace() }
            State.Failed, State.Idle -> startRace()
            State.Probing -> {}
        }
    }

    /** Ждёт открытой двери не дольше `limitMillis`: вход сразу после запуска не должен упасть, пока идёт гонка. */
    suspend fun waitUntilUp(limitMillis: Long = 20_000): Boolean =
        withTimeoutOrNull(limitMillis) { stateFlow.first { it is State.Up } } != null

    private fun publish(new: State) {
        stateFlow.value = new
        current.value = when (new) {
            is State.Up -> new.door
            State.Failed -> null
            else -> return
        }
    }

    // MARK: Клиенты

    /** Клиент к ядру: стартует с текущей дверью, следует за её сменами и жалуется сюда, если дверь не отвечает. */
    fun makeClient(homeserver: URI = CORE, accessToken: String? = null): MatrixClient {
        val client = MatrixClient(homeserver, accessToken, transport = UrlConnectionTransport(isOnline = ::isOnline),
                                  userAgent = "Nyutag", doorKey = bundled?.key, door = current.value)
        client.setDoorTrouble { trouble(it) }
        client.follow(current, scope)
        return client
    }

    private fun since(millis: Long) = System.currentTimeMillis() - millis

    private fun log(line: String) {
        if (BuildConfig.DEBUG) Log.d("doors", line)
    }

    companion object {
        /** Имя ядра для сессий и токенов; адрес запросов — дверь. */
        val CORE: URI = URI("https://core.nyutag.chat")
        private const val CATALOGUE = "nyutag-catalogue.txt"
        private const val PROBE_MILLIS = 8_000

    }
}
