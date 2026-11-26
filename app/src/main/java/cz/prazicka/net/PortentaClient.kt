package cz.prazicka.net

import cz.prazicka.protocol.AutotuneResult
import cz.prazicka.protocol.Event
import cz.prazicka.protocol.HelloAck
import cz.prazicka.protocol.PidValues
import cz.prazicka.protocol.Proto
import cz.prazicka.protocol.Status
import cz.prazicka.protocol.bool
import cz.prazicka.protocol.int
import cz.prazicka.protocol.obj
import cz.prazicka.protocol.parseObject
import cz.prazicka.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val attempt: Int, val target: String) : LinkState
    data class Connected(val hello: HelloAck, val simulator: Boolean, val target: String) : LinkState
    data class Failed(val reason: String, val retrying: Boolean) : LinkState
    data object Replaced : LinkState
}

data class AckResult(val ok: Boolean, val err: String?, val msg: String?, val raw: JsonObject?)

data class TrafficLine(val time: Long, val out: Boolean, val text: String) {
    val type: String get() = Regex("\"t\":\"([a-z_]+)\"").find(text)?.groupValues?.get(1) ?: ""
}

private enum class SessionEnd { Closed, Timeout, Replaced }

/**
 * Klient protokolu podle API.md: přihlášení, ping každou 1 s, hlídání stavu (3 s),
 * nové připojení každé 2 s, čekání na ack (2 s), doplnění událostí přes event_log.
 */
class PortentaClient(
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val variant: String,
) {
    companion object {
        fun now(): Long = System.nanoTime() / 1_000_000
        const val STATUS_TIMEOUT_MS = 3000L
        const val ACK_TIMEOUT_MS = 2000L
        const val RECONNECT_MS = 2000L
        const val PING_MS = 1000L
    }

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    val lastStatusAt = MutableStateFlow(0L)
    val lastSentAt = MutableStateFlow(0L)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _eventLog = MutableStateFlow<List<Event>>(emptyList())
    val eventLog: StateFlow<List<Event>> = _eventLog.asStateFlow()

    private val _traffic = MutableStateFlow<List<TrafficLine>>(emptyList())
    val traffic: StateFlow<List<TrafficLine>> = _traffic.asStateFlow()

    val pid = MutableStateFlow<PidValues?>(null)
    val diag = MutableStateFlow<JsonObject?>(null)
    val portentaRecipe = MutableStateFlow<JsonObject?>(null)

    private val _autotuneResult = MutableStateFlow<AutotuneResult?>(null)
    val autotuneResult: StateFlow<AutotuneResult?> = _autotuneResult.asStateFlow()
    fun clearAutotuneResult() { _autotuneResult.value = null }

    /** Testovací nástroje: do tohoto času se nic neodesílá (simulovaný výpadek). */
    @Volatile var silenceUntil: Long = 0L
    /** Testovací nástroje: zpoždění zpracování přijatých zpráv. */
    @Volatile var rxDelayMs: Long = 0L

    private var transportProvider: (() -> Transport)? = null
    private var token: String = ""
    private var job: Job? = null
    @Volatile private var conn: Connection? = null
    @Volatile private var currentEnd: CompletableDeferred<SessionEnd>? = null
    private val nextId = AtomicInteger(1)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val pendingData = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private var lastBootId: String? = null
    private var lastSeq: Long = 0
    private val seenEvents = LinkedHashSet<String>()

    val connected: Boolean get() = _link.value is LinkState.Connected
    val hello: HelloAck? get() = (_link.value as? LinkState.Connected)?.hello
    val isSimulator: Boolean get() = (_link.value as? LinkState.Connected)?.simulator == true

    fun start(provider: () -> Transport, token: String) {
        stop()
        transportProvider = provider
        this.token = token
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        conn?.close()
        conn = null
        failPending()
        _link.value = LinkState.Idle
    }

    fun reconnect() {
        val p = transportProvider ?: return
        start(p, token)
    }

    /** Testovací nástroj: zavře spojení a nechá klienta znovu se připojit. */
    fun dropConnection() {
        conn?.close()
    }

    private fun nextId(): Int {
        val id = nextId.getAndIncrement()
        if (id >= Int.MAX_VALUE - 1) nextId.set(1)
        return id
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            val provider = transportProvider ?: return
            val transport = provider()
            _link.value = LinkState.Connecting(attempt, transport.description)
            var stopRetry = false
            try {
                val c = transport.open()
                conn = c
                stopRetry = session(c, transport)
                if (stopRetry.not()) attempt = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _link.value = LinkState.Failed("Nelze se připojit k ${transport.description}: ${e.message ?: e.javaClass.simpleName}", true)
            } finally {
                conn?.close()
                conn = null
                failPending()
            }
            if (stopRetry) return
            delay(RECONNECT_MS)
        }
    }

    private suspend fun session(c: Connection, transport: Transport): Boolean = coroutineScope {
        val end = CompletableDeferred<SessionEnd>()
        currentEnd = end
        val reader = launch {
            try {
                for (line in c.incoming) {
                    val d = rxDelayMs
                    if (d > 0) delay(d)
                    handleLine(line)
                }
            } finally {
                end.complete(SessionEnd.Closed)
            }
        }

        val helloId = nextId()
        val hello = buildJsonObject {
            put("t", "hello")
            put("id", helloId)
            put("token", token)
            put("app", appVersion)
            put("proto", Proto.VERSION)
            put("variant", variant)
        }
        val ackDeferred = CompletableDeferred<JsonObject>()
        pendingAcks[helloId] = ackDeferred
        if (!rawSend(c, hello)) {
            reader.cancel()
            _link.value = LinkState.Failed("Odeslání přihlášení selhalo", true)
            return@coroutineScope false
        }
        val ack = withTimeoutOrNull(5000) { ackDeferred.await() }
        pendingAcks.remove(helloId)
        if (ack == null) {
            reader.cancel()
            _link.value = LinkState.Failed("Portenta neodpověděla na přihlášení", true)
            return@coroutineScope false
        }
        val h = HelloAck.parse(ack)
        if (!h.ok) {
            reader.cancel()
            val reason = when (h.err) {
                "bad_token" -> "Špatný token – oprav ho v Nastavení."
                "proto" -> "Jiná verze protokolu (Portenta ${h.proto ?: "?"}, aplikace ${Proto.VERSION}). Je potřeba aktualizovat aplikaci nebo firmware."
                else -> "Přihlášení odmítnuto (${h.err ?: "?"})."
            }
            _link.value = LinkState.Failed(reason, false)
            return@coroutineScope true
        }

        lastStatusAt.value = now()
        _link.value = LinkState.Connected(h, transport.isSimulator, transport.description)
        val post = launch { afterConnect(h) }

        val pinger = launch {
            while (isActive) {
                delay(200)
                val t = now()
                if (t - lastSentAt.value >= PING_MS && t >= silenceUntil) {
                    rawSend(c, buildJsonObject { put("t", "ping"); put("id", nextId()) })
                }
            }
        }
        val watchdog = launch {
            while (isActive) {
                delay(250)
                if (now() - lastStatusAt.value > STATUS_TIMEOUT_MS) {
                    end.complete(SessionEnd.Timeout)
                    break
                }
            }
        }

        val reason = end.await()
        post.cancel()
        reader.cancel()
        pinger.cancel()
        watchdog.cancel()
        currentEnd = null
        when (reason) {
            SessionEnd.Replaced -> {
                _link.value = LinkState.Replaced
                true
            }
            SessionEnd.Timeout -> {
                _link.value = LinkState.Failed("Spojení ztraceno (3 s bez stavu z Portenty)", true)
                false
            }
            SessionEnd.Closed -> {
                _link.value = LinkState.Failed("Spojení ukončeno", true)
                false
            }
        }
    }

    private suspend fun afterConnect(h: HelloAck) {
        if (h.bootId != lastBootId) {
            synchronized(seenEvents) { seenEvents.clear() }
            lastSeq = 0
        }
        lastBootId = h.bootId
        if (h.has("event_log")) command("event_log") { put("since", lastSeq) }
        if (h.has("pid")) query("pid_get")
        if (h.has("recipe")) query("recipe_get")
        query("get_diag")
    }

    private suspend fun rawSend(c: Connection, obj: JsonObject): Boolean {
        val text = obj.toString()
        if (now() < silenceUntil) {
            logTraffic(true, "(potlačeno) $text")
            return false
        }
        return try {
            c.send(text)
            lastSentAt.value = now()
            logTraffic(true, text)
            true
        } catch (e: Exception) {
            currentEnd?.complete(SessionEnd.Closed)
            false
        }
    }

    private fun handleLine(line: String) {
        logTraffic(false, line)
        val o = parseObject(line) ?: return
        when (o.str("t")) {
            "hello_ack", "ack" -> {
                val id = o.int("id") ?: return
                pendingAcks.remove(id)?.complete(o)
            }
            "status" -> {
                _status.value = Status.parse(o)
                lastStatusAt.value = now()
            }
            "event" -> handleEvent(Event.parse(o))
            "pid" -> {
                pid.value = PidValues.parse(o)
                o.int("id")?.let { pendingData.remove(it)?.complete(o) }
            }
            "diag" -> {
                diag.value = o
                o.int("id")?.let { pendingData.remove(it)?.complete(o) }
            }
            "recipe" -> {
                portentaRecipe.value = o.obj("recipe")
                o.int("id")?.let { pendingData.remove(it)?.complete(o) }
            }
            "autotune_result" -> _autotuneResult.value = AutotuneResult.parse(o)
            "bye" -> if (o.str("reason") == "replaced") currentEnd?.complete(SessionEnd.Replaced)
        }
    }

    private fun handleEvent(e: Event) {
        val seq = e.seq
        if (seq != null) {
            val key = "${lastBootId}:$seq"
            synchronized(seenEvents) {
                if (!seenEvents.add(key)) return
                while (seenEvents.size > 2000) seenEvents.remove(seenEvents.first())
            }
            if (seq > lastSeq) lastSeq = seq
        }
        _eventLog.value = (_eventLog.value + e).takeLast(500)
        _events.tryEmit(e)
        // PID konstanty změněné z Portenty (např. po ladění) – aktualizovat
        if (e.code == "pid") scope.launch { query("pid_get") }
        if (e.code == "roast" && (e.roastEvent == "loaded")) scope.launch { query("recipe_get") }
    }

    private fun logTraffic(out: Boolean, text: String) {
        val list = _traffic.value
        _traffic.value = (if (list.size >= 600) list.drop(100) else list) + TrafficLine(System.currentTimeMillis(), out, text)
    }

    private fun failPending() {
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()
        pendingData.values.forEach { it.cancel() }
        pendingData.clear()
    }

    /** Odešle příkaz a počká na ack (max. 2 s). */
    suspend fun command(t: String, params: JsonObjectBuilder.() -> Unit = {}): AckResult {
        val c = conn
        if (c == null || _link.value !is LinkState.Connected) return AckResult(false, "offline", null, null)
        val id = nextId()
        val obj = buildJsonObject {
            put("t", t)
            put("id", id)
            params()
        }
        val d = CompletableDeferred<JsonObject>()
        pendingAcks[id] = d
        if (!rawSend(c, obj)) {
            pendingAcks.remove(id)
            return AckResult(false, if (now() < silenceUntil) "timeout" else "offline", null, null)
        }
        val ack = try {
            withTimeoutOrNull(ACK_TIMEOUT_MS) { d.await() }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            null
        }
        pendingAcks.remove(id)
        return if (ack == null) {
            AckResult(false, "timeout", "bez odpovědi do 2 s", null)
        } else {
            AckResult(ack.bool("ok") == true, ack.str("err"), ack.str("msg"), ack)
        }
    }

    /** Dotaz (pid_get, get_diag, recipe_get): ack + datová zpráva se stejným id. */
    suspend fun query(t: String): Pair<AckResult, JsonObject?> {
        val c = conn
        if (c == null || _link.value !is LinkState.Connected) return AckResult(false, "offline", null, null) to null
        val id = nextId()
        val ackD = CompletableDeferred<JsonObject>()
        val dataD = CompletableDeferred<JsonObject>()
        pendingAcks[id] = ackD
        pendingData[id] = dataD
        if (!rawSend(c, buildJsonObject { put("t", t); put("id", id) })) {
            pendingAcks.remove(id)
            pendingData.remove(id)
            return AckResult(false, "offline", null, null) to null
        }
        val ack = try { withTimeoutOrNull(ACK_TIMEOUT_MS) { ackD.await() } } catch (e: CancellationException) { null }
        pendingAcks.remove(id)
        if (ack == null) {
            pendingData.remove(id)
            return AckResult(false, "timeout", "bez odpovědi do 2 s", null) to null
        }
        val result = AckResult(ack.bool("ok") == true, ack.str("err"), ack.str("msg"), ack)
        if (!result.ok) {
            pendingData.remove(id)
            return result to null
        }
        val data = try { withTimeoutOrNull(ACK_TIMEOUT_MS) { dataD.await() } } catch (e: CancellationException) { null }
        pendingData.remove(id)
        return result to data
    }

    /** Testovací nástroj: odešle libovolný řádek. */
    suspend fun sendRaw(line: String): Boolean {
        val c = conn ?: return false
        if (now() < silenceUntil) {
            logTraffic(true, "(potlačeno) $line")
            return false
        }
        return try {
            c.send(line)
            lastSentAt.value = now()
            logTraffic(true, line)
            true
        } catch (e: Exception) {
            false
        }
    }
}
