package cz.prazicka.sim

import cz.prazicka.net.Connection
import cz.prazicka.net.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Běh vestavěného simulátoru v jednom vlákně. Simulátor „běží“ celou dobu jako deska –
 * spojení přicházejí a odcházejí, jeho stav zůstává.
 */
object SimulatorHost {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "simulator-portenty").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var sim: PortentaSimulator? = null
    private var loop: Job? = null

    /** Zrychlení simulovaného času (1× až 10×). Hlídání spojení běží vždy ve skutečném čase. */
    val speed = MutableStateFlow(1.0)

    private fun ensureStarted() {
        if (sim == null) sim = PortentaSimulator()
        if (loop != null) return
        loop = scope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(100)
                val now = System.nanoTime()
                val real = ((now - last) / 1_000_000).coerceIn(0, 1000)
                last = now
                sim?.step((real * speed.value).toLong(), real)
            }
        }
    }

    /** Jako vypnutí a zapnutí desky: nový boot_id, vše od začátku. */
    fun restart() {
        scope.launch { sim = PortentaSimulator() }
    }

    suspend fun connect(out: (String) -> Unit, onClose: () -> Unit): Int = withContext(dispatcher) {
        ensureStarted()
        sim!!.connect(out, onClose)
    }

    fun receive(id: Int, line: String) {
        scope.launch { sim?.receive(id, line) }
    }

    fun disconnect(id: Int) {
        scope.launch { sim?.disconnect(id) }
    }
}

class SimTransport : Transport {
    override val description: String = "vestavěný simulátor"
    override val isSimulator: Boolean = true

    override suspend fun open(): Connection {
        val ch = Channel<String>(Channel.UNLIMITED)
        val id = SimulatorHost.connect({ ch.trySend(it) }, { ch.close() })
        if (id < 0) throw IOException("simulátor spojení odmítl (čeká jiné nepřihlášené spojení)")
        return object : Connection {
            override val incoming: ReceiveChannel<String> = ch
            override suspend fun send(line: String) {
                if (ch.isClosedForSend) throw IOException("spojení je zavřené")
                SimulatorHost.receive(id, line)
            }
            override fun close() {
                ch.close()
                SimulatorHost.disconnect(id)
            }
        }
    }
}
