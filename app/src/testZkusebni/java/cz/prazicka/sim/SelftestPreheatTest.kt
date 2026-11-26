package cz.prazicka.sim

import cz.prazicka.data.ManualPreheat
import cz.prazicka.data.Selftest
import cz.prazicka.protocol.RoasterState
import cz.prazicka.protocol.Status
import cz.prazicka.protocol.bool
import cz.prazicka.protocol.int
import cz.prazicka.protocol.parseObject
import cz.prazicka.protocol.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelftestPreheatTest {
    private val sim = PortentaSimulator(seed = 2)
    private val received = ArrayList<JsonObject>()
    private val conn = sim.connect({ parseObject(it)?.let { o -> received.add(o) } }, { })
    private var id = 1

    private fun send(t: String, p: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val my = id++
        val before = received.size
        sim.receive(conn, buildJsonObject { put("t", t); put("id", my); p() }.toString())
        return received.drop(before).first { it.int("id") == my }
    }

    private fun hello(): String {
        val a = send("hello") { put("token", "simulace-test"); put("app", "0.1.0-test.1"); put("proto", 1) }
        assertTrue(a.bool("ok") == true)
        return a.str("boot_id")!!
    }

    private fun status() = Status.parse(sim.statusJson())

    /** 1 s simulace po 100 ms, každý krok status do sledovačů. */
    private fun second(feed: (Status) -> Unit) {
        for (i in 1..10) { sim.step(100, 100); feed(status()) }
        send("ping")
    }

    @Test
    fun selftestPriStartuProjde() {
        val st = Selftest()
        st.onConnected(hello())
        var t = 0
        while (t < 60 && status().state != RoasterState.READY) { second { st.onStatus(it) }; t++ }
        val u = st.ui.value
        assertNotNull(u)
        assertTrue(u!!.live)
        assertFalse(u.running)
        assertFalse(u.toString(), u.failed)
        assertTrue(u.vfdEach.all { it == Selftest.Check.OK })
        st.dismiss()
        assertNull(st.ui.value)
    }

    @Test
    fun selftestOdhaliMenic() {
        val st = Selftest()
        st.onConnected(hello())
        send("sim_vfd") { put("vfd", 2); put("fault", "offline") }
        var t = 0
        while (t < 60 && status().state != RoasterState.HALTED) { second { st.onStatus(it) }; t++ }
        val u = st.ui.value!!
        assertFalse(u.running)
        assertTrue(u.failed)
        assertEquals(Selftest.Check.FAIL, u.vfd.check)
        assertEquals(Selftest.Check.FAIL, u.vfdEach[2])
    }

    @Test
    fun rucniPredehrevSVydrzi() {
        val st = Selftest()
        st.onConnected(hello())
        var t = 0
        while (t < 60 && status().state != RoasterState.READY) { second { st.onStatus(it) }; t++ }
        val marks = ArrayList<String>()
        val p = ManualPreheat { marks.add(it) }
        assertTrue(send("heat_start") { put("sp", 150.0) }.bool("ok") == true)
        p.start(150.0, 60)
        t = 0
        while (t < 900 && p.ui.value?.phase != ManualPreheat.Phase.HOLD) { second { p.onStatus(it) }; t++ }
        assertEquals(ManualPreheat.Phase.HOLD, p.ui.value?.phase)
        assertEquals(listOf("preheat_hold"), marks)
        repeat(30) { second { p.onStatus(it) } }
        assertEquals(ManualPreheat.Phase.HOLD, p.ui.value?.phase)
        repeat(35) { second { p.onStatus(it) } }
        assertEquals(ManualPreheat.Phase.DONE, p.ui.value?.phase)
        assertEquals(listOf("preheat_hold", "charge_ready"), marks)
        send("heat_stop")
        second { p.onStatus(it) }
        assertNull(p.ui.value)
    }
}
