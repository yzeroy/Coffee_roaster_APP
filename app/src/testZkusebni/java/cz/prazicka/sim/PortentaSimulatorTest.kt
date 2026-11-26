package cz.prazicka.sim

import cz.prazicka.data.Recipe
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
import org.junit.Assert.assertTrue
import org.junit.Test

class PortentaSimulatorTest {

    private class Harness {
        val sim = PortentaSimulator(seed = 1)
        val received = ArrayList<JsonObject>()
        var closed = false
        val connId = sim.connect({ line -> parseObject(line)?.let { received.add(it) } }, { closed = true })
        private var nextId = 1

        fun send(t: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject {
            val id = nextId++
            val before = received.size
            sim.receive(connId, buildJsonObject { put("t", t); put("id", id); params() }.toString())
            return received.drop(before).first { (it.str("t") == "ack" || it.str("t") == "hello_ack") && it.int("id") == id }
        }

        fun hello(token: String = "simulace-test") = send("hello") {
            put("token", token); put("app", "0.1.0-test.1"); put("proto", 1); put("variant", "test")
        }

        /** Krok po 100 ms, s pingem každou sekundu (jinak by nastal výpadek spojení). */
        fun run(seconds: Double, ping: Boolean = true) {
            val steps = (seconds * 10).toInt()
            for (i in 1..steps) {
                sim.step(100, 100)
                if (ping && i % 10 == 0) send("ping")
            }
        }

        fun status(): Status = Status.parse(sim.statusJson())

        fun runUntil(maxSeconds: Double, cond: (Status) -> Boolean): Boolean {
            var t = 0.0
            while (t < maxSeconds) {
                run(1.0)
                t += 1.0
                if (cond(status())) return true
            }
            return false
        }

        fun events(code: String) = received.filter { it.str("t") == "event" && it.str("code") == code }
    }

    private fun booted(): Harness {
        val h = Harness()
        assertTrue(h.hello().bool("ok") == true)
        assertTrue("simulátor se má dostat do READY", h.runUntil(40.0) { it.state == RoasterState.READY })
        return h
    }

    @Test
    fun spatnyTokenOdmitnut() {
        val h = Harness()
        val ack = h.hello("spatny-token")
        assertFalse(ack.bool("ok") == true)
        assertEquals("bad_token", ack.str("err"))
        assertTrue(h.closed)
    }

    @Test
    fun prikazPredPrihlasenimOdmitnut() {
        val h = Harness()
        assertEquals("not_auth", h.send("ping").str("err"))
    }

    @Test
    fun startDoReady() {
        val h = booted()
        val s = h.status()
        assertEquals(0, s.fault.code)
        assertTrue(s.vfd.all { (it.hz ?: 0.0) <= 1.0 })
    }

    @Test
    fun ohrevZapaliAPrejdeDoRunning() {
        val h = booted()
        assertTrue(h.send("heat_start").bool("ok") == true)
        assertTrue(h.runUntil(40.0) { it.state == RoasterState.RUNNING })
        val s = h.status()
        assertEquals(1, s.burner.flame)
        assertEquals("manual", s.ctrl.mode)
        assertTrue((s.vfd[0].cmd ?: 0.0) >= 10.0)
    }

    @Test
    fun ohrevSCilovouTeplotouPrejdeDoPid() {
        val h = booted()
        assertTrue(h.send("heat_start") { put("sp", 205.0) }.bool("ok") == true)
        assertTrue(h.runUntil(40.0) { it.state == RoasterState.RUNNING })
        h.run(1.0)
        val s = h.status()
        assertEquals("pid", s.ctrl.mode)
        assertEquals(205.0, s.ctrl.sp ?: 0.0, 0.01)
        assertEquals("out_of_range", h.send("heat_stop").let { h.send("heat_start") { put("sp", 300) } }.str("err"))
    }

    @Test
    fun ackPredUdalosti() {
        val h = booted()
        val before = h.received.size
        h.send("heat_start")
        val after = h.received.drop(before)
        val ackIdx = after.indexOfFirst { it.str("t") == "ack" }
        val evIdx = after.indexOfFirst { it.str("t") == "event" }
        assertTrue(ackIdx >= 0 && evIdx > ackIdx)
    }

    @Test
    fun zaOhrevuNejdeZastavitBuben() {
        val h = booted()
        h.send("heat_start")
        h.runUntil(40.0) { it.state == RoasterState.RUNNING }
        val ack = h.send("vfd_set") { put("vfd", 0); put("pct", 0) }
        assertTrue(ack.bool("ok") == true)
        assertTrue((h.status().vfd[0].cmd ?: 0.0) > 0.0)
    }

    @Test
    fun vypadekSpojeniZaOhrevuZpusobiPaniku() {
        val h = booted()
        h.send("heat_start")
        assertTrue(h.runUntil(40.0) { it.state == RoasterState.RUNNING })
        h.run(16.0, ping = false)
        val s = h.status()
        assertEquals(RoasterState.HALTED, s.state)
        assertEquals(7, s.fault.code)
        assertEquals(100.0, s.vfd[0].cmd ?: 0.0, 0.01)
    }

    @Test
    fun plamenNenaskociAChyba12() {
        val h = booted()
        h.send("sim_flame") { put("fails", true) }
        h.send("heat_start")
        assertTrue(h.runUntil(60.0) { it.state == RoasterState.HALTED })
        assertEquals(12, h.status().fault.code)
    }

    @Test
    fun restartZChybyAZablokovanyOhrev() {
        val h = booted()
        h.send("sim_di") { put("stop", false) }
        h.run(1.0)
        assertEquals(1, h.status().fault.code)
        assertEquals("bad_state", h.send("restart").str("err"))
        h.send("sim_di") { put("stop", true) }
        assertTrue(h.send("restart").bool("ok") == true)
        assertTrue(h.runUntil(60.0) { it.state == RoasterState.READY })
        assertTrue(h.status().heatBlocked)
        assertEquals("bad_state", h.send("heat_start").str("err"))
        h.send("heat_stop")
        assertFalse(h.status().heatBlocked)
    }

    @Test
    fun spatnaRecepturaOdmitnuta() {
        val h = booted()
        val bad = Recipe.sample().copy(curve = emptyList())
        val ack = h.send("recipe_load") { put("recipe", bad.toApiJson()) }
        assertEquals("bad_recipe", ack.str("err"))
    }

    @Test
    fun prubehReceptury() {
        val h = booted()
        assertTrue(h.send("recipe_load") { put("recipe", Recipe.sample().toApiJson()) }.bool("ok") == true)
        assertTrue(h.send("recipe_start").bool("ok") == true)
        assertTrue("předehřev má skončit výzvou ke vsypání", h.runUntil(1200.0) { it.roast?.chargeReady == true })
        assertEquals("recipe", h.status().ctrl.mode)
        assertTrue(h.send("roast_event") { put("event", "charge") }.bool("ok") == true)
        assertEquals("roast", h.status().roast?.phase)
        h.run(100.0)
        assertTrue(h.send("roast_event") { put("event", "fc_start") }.bool("ok") == true)
        assertTrue(h.send("roast_event") { put("event", "drop") }.bool("ok") == true)
        val s = h.status()
        assertEquals("cooling", s.roast?.phase)
        assertEquals(RoasterState.READY, s.state)
        h.run(1.0)
        assertEquals(100.0, h.status().vfd[3].cmd ?: 0.0, 0.01)
        assertTrue(h.send("recipe_end").bool("ok") == true)
        assertEquals("loaded", h.status().roast?.phase)
        assertTrue(h.events("roast").any { it.str("roast_event") == "fc_start" })
    }

    @Test
    fun eventLogVraciZmeskane() {
        val h = booted()
        val before = h.received.size
        val ack = h.send("event_log") { put("since", 0) }
        assertTrue((ack.int("count") ?: 0) > 3)
        val replays = h.received.drop(before).filter { it.bool("replay") == true }
        assertEquals(ack.int("count"), replays.size)
    }

    @Test
    fun noveSpojeniNahradiStare() {
        val h = booted()
        var oldClosed = false
        val second = ArrayList<JsonObject>()
        val id2 = h.sim.connect({ parseObject(it)?.let { o -> second.add(o) } }, { })
        h.sim.receive(id2, """{"t":"hello","id":1,"token":"simulace-test","app":"x","proto":1}""")
        assertNotNull(second.firstOrNull { it.str("t") == "hello_ack" && it.bool("ok") == true })
        assertTrue(h.received.any { it.str("t") == "bye" && it.str("reason") == "replaced" })
        oldClosed = h.closed
        assertTrue(oldClosed)
    }

    @Test
    fun teplotaOkoliAKominu() {
        val h = booted()
        assertTrue(h.send("heat_start") { put("sp", 200.0) }.bool("ok") == true)
        var maxDiff = 0.0
        var airMin = 99.0
        var airMax = 0.0
        for (i in 1..900) {
            h.run(1.0)
            val t = h.status().temps
            val bt = t.bt ?: continue
            val exh = t.exh ?: continue
            val air = t.air ?: continue
            maxDiff = maxOf(maxDiff, kotlin.math.abs(exh - bt))
            airMin = minOf(airMin, air); airMax = maxOf(airMax, air)
        }
        assertTrue("odtah se liší od zrna o $maxDiff", maxDiff <= 10.0)
        assertTrue("okolí $airMin–$airMax", airMin >= 22.0 && airMax <= 28.0)
        assertTrue("zrno má dosáhnout cíle", kotlin.math.abs((h.status().temps.bt ?: 0.0) - 200.0) < 5.0)
    }

    @Test
    fun predehrevSVydrzi() {
        val h = booted()
        val r = Recipe.sample().copy(preheatS = 120)
        assertTrue(h.send("recipe_load") { put("recipe", r.toApiJson()) }.bool("ok") == true)
        assertTrue(h.send("recipe_start").bool("ok") == true)
        assertTrue("teplota předehřevu má být dosažena", h.runUntil(1200.0) { it.roast?.preheatLeftS != null })
        assertTrue(h.events("roast").any { it.str("roast_event") == "preheat_hold" })
        assertFalse(h.status().roast!!.chargeReady)
        h.run(60.0)
        val left = h.status().roast!!.preheatLeftS!!
        assertTrue("zbývá $left", left in 50.0..65.0)
        assertFalse(h.status().roast!!.chargeReady)
        assertTrue(h.runUntil(120.0) { it.roast?.chargeReady == true })
    }

    @Test
    fun ladeniZKliduSProhratim() {
        val h = booted()
        val ack = h.send("autotune_start") {
            put("sp", 180.0); put("gas_low", 20.0); put("gas_high", 60.0); put("cycles", 2)
            put("soak_sp", 190.0); put("soak_s", 120)
        }
        assertTrue(ack.toString(), ack.bool("ok") == true)
        assertEquals("ignite", h.status().ctrl.autotune?.phase)
        assertFalse(h.send("heat_start").bool("ok") == true)
        assertTrue("má přejít do prohřátí", h.runUntil(120.0) { it.ctrl.autotune?.phase == "soak" })
        assertEquals(190.0, h.status().ctrl.sp ?: 0.0, 0.01)
        assertEquals("busy", h.send("gas_set") { put("pct", 50.0) }.str("err"))
        assertTrue("prohřátí má odpočítávat", h.runUntil(900.0) { (it.ctrl.autotune?.phaseLeftS ?: 999.0) < 100.0 })
        assertTrue("má najet na cíl", h.runUntil(400.0) { it.ctrl.autotune?.phase == "approach" })
        assertTrue("mají začít kmity", h.runUntil(700.0) { it.ctrl.autotune?.phase == "oscillate" })
        var done = false
        for (i in 1..2400) {
            h.run(1.0)
            if (h.received.any { it.str("t") == "autotune_result" }) { done = true; break }
        }
        assertTrue("ladění má skončit", done)
        val res = h.received.last { it.str("t") == "autotune_result" }
        assertTrue(res.toString(), res.bool("ok") == true)
        assertEquals(RoasterState.RUNNING, h.status().state)
        assertEquals("manual", h.status().ctrl.mode)
    }
}
