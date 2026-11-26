package cz.prazicka.net

import cz.prazicka.protocol.Proto
import cz.prazicka.protocol.int
import cz.prazicka.protocol.parseObject
import cz.prazicka.protocol.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Jedno otevřené spojení: řádky JSON dovnitř a ven. */
interface Connection {
    val incoming: ReceiveChannel<String>
    suspend fun send(line: String)
    fun close()
}

/** Způsob, jak se dostat k Portentě – skutečné TCP, nebo vestavěný simulátor (jen testovací varianta). */
interface Transport {
    val description: String
    val isSimulator: Boolean
    suspend fun open(): Connection
}

class TcpTransport(private val host: String, private val port: Int) : Transport {
    override val description: String get() = "$host:$port"
    override val isSimulator: Boolean = false

    override suspend fun open(): Connection = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 3000)
        TcpConnection(socket)
    }
}

private class TcpConnection(private val socket: Socket) : Connection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<String>(Channel.UNLIMITED)
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
    private val writeLock = Mutex()

    override val incoming: ReceiveChannel<String> get() = channel

    init {
        scope.launch {
            try {
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) channel.send(line)
                }
            } catch (_: Exception) {
            } finally {
                channel.close()
            }
        }
    }

    override suspend fun send(line: String) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        channel.close()
        scope.cancel()
    }
}

data class DiscoveryResult(val host: String, val port: Int, val fw: String?, val mac: String?)

/** Nalezení Portenty v síti podle API bod 2.3 (UDP broadcast na port 5021). */
object Discovery {
    suspend fun find(timeoutMs: Int = 1000, attempts: Int = 3): DiscoveryResult? = withContext(Dispatchers.IO) {
        var result: DiscoveryResult? = null
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs
                val msg = """{"t":"discover"}""".toByteArray(Charsets.UTF_8)
                val target = InetAddress.getByName("255.255.255.255")
                var i = 0
                while (i < attempts && result == null) {
                    i++
                    socket.send(DatagramPacket(msg, msg.size, target, Proto.DISCOVERY_PORT))
                    val buf = ByteArray(1024)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val o = parseObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        if (o != null && o.str("t") == "here") {
                            result = DiscoveryResult(
                                host = packet.address.hostAddress ?: "",
                                port = o.int("port") ?: Proto.PORT,
                                fw = o.str("fw"),
                                mac = o.str("mac"),
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        result
    }
}
