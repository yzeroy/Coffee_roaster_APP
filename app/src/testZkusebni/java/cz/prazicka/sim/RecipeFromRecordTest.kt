package cz.prazicka.sim

import cz.prazicka.data.RecipeFromRecord
import cz.prazicka.data.RecipeValidator
import cz.prazicka.data.Recorder
import cz.prazicka.data.RoastRecord
import cz.prazicka.data.VfdPoint
import cz.prazicka.protocol.Event
import cz.prazicka.protocol.Status
import cz.prazicka.protocol.parseObject
import cz.prazicka.protocol.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Receptura ze záznamu ručního pražení: rozpis měničů musí odpovídat tomu, co obsluha nastavila. */
class RecipeFromRecordTest {

    private fun record(): RoastRecord {
        val sim = PortentaSimulator(seed = 3)
        val rx = ArrayList<JsonObject>()
        val conn = sim.connect({ parseObject(it)?.let { o -> rx.add(o) } }, {})
        var id = 1
        fun send(t: String, p: JsonObjectBuilder.() -> Unit = {}) { sim.receive(conn, buildJsonObject { put("t", t); put("id", id++); p() }.toString()) }
        var finished: RoastRecord? = null
        val rec = Recorder("x", { finished = it }, {})
        var seen = 0
        fun pump() { while (seen < rx.size) { val o = rx[seen++]; if (o.str("t") == "event") rec.onEvent(Event.parse(o)) } }
        fun sec(n: Int) { repeat(n) { repeat(2) { repeat(5) { sim.step(100, 100) }; rec.onStatus(Status.parse(sim.statusJson())); pump() }; send("ping") } }
        send("hello") { put("token", "simulace-test"); put("app", "x"); put("proto", 1) }
        sec(30)
        rec.nextManualChoice = true
        send("heat_start") { put("sp", 200.0) }
        rec.markAtStart("preheat")
        sec(300)
        rec.addLocalMark("preheat_hold")
        sec(120)
        rec.addLocalMark("charge")
        sec(60); send("vfd_set") { put("vfd", 0); put("pct", 63) }
        sec(120); send("vfd_set") { put("vfd", 1); put("pct", 95) }
        sec(120); send("vfd_set") { put("vfd", 0); put("pct", 70) }
        sec(200)
        rec.addLocalMark("drop")
        rec.startManualCooling()
        send("heat_stop"); send("vfd_set") { put("vfd", 2); put("pct", 40) }; send("vfd_set") { put("vfd", 3); put("pct", 90) }
        sec(60)
        rec.endManualCooling()
        return finished!!
    }

    private val expected = listOf(
        VfdPoint(0, 80, 80, 0, 0),
        VfdPoint(60, 63, 80, 0, 0),
        VfdPoint(180, 63, 95, 0, 0),
        VfdPoint(300, 70, 95, 0, 0),
    )

    @Test
    fun meniceZNastavenychRychlosti() {
        val r = record()
        val recipe = RecipeFromRecord.build(r, "test")!!
        assertEquals(expected, recipe.vfd)
        assertEquals(200.0, recipe.chargeBt, 0.01)
        assertEquals(120, recipe.preheatS)
        assertEquals(40, recipe.coolCooler)
        assertEquals(90, recipe.coolFan)
        assertNull(RecipeValidator.validate(recipe.toApiJson()))
    }

    @Test
    fun meniceZeStarehoZaznamuBezNastavenychRychlosti() {
        val r = record().let { it.copy(samples = it.samples.map { s -> s.copy(vfdCmd = emptyList()) }) }
        val recipe = RecipeFromRecord.build(r, "test")!!
        // časy můžou ujet o vzorek (status 2× za sekundu), hodnoty musí sedět přesně
        assertEquals(expected.map { it.values() }, recipe.vfd.map { it.values() })
        expected.zip(recipe.vfd).forEach { (e, a) -> assertEquals(e.t.toDouble(), a.t.toDouble(), 1.0) }
    }

    @Test
    fun zaznamPrezijeUlozeni() {
        val r = record()
        val back = RoastRecord.fromJson(r.toJson())!!
        assertEquals(expected, RecipeFromRecord.build(back, "test")!!.vfd)
    }
}
