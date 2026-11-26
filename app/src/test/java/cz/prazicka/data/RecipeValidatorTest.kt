package cz.prazicka.data

import cz.prazicka.protocol.Status
import cz.prazicka.protocol.parseObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeValidatorTest {

    @Test
    fun ukazkovaRecepturaJeVPoradku() {
        assertNull(RecipeValidator.validate(Recipe.sample().toApiJson()))
    }

    @Test
    fun casyKrivkyMusiStoupat() {
        val r = Recipe.sample().copy(curve = listOf(CurvePoint(0, 200.0), CurvePoint(90, 100.0), CurvePoint(90, 120.0)))
        assertEquals("curve", RecipeValidator.validate(r.toApiJson())?.field)
    }

    @Test
    fun bubenNesmiStat() {
        val r = Recipe.sample().copy(vfd = listOf(VfdPoint(0, 0, 50, 0, 0)))
        assertEquals("vfd", RecipeValidator.validate(r.toApiJson())?.field)
    }

    @Test
    fun pidNemuzePrevzitPozdeji() {
        val r = Recipe.sample().copy(pidDelayS = 700)
        assertEquals("pid_delay_s", RecipeValidator.validate(r.toApiJson())?.field)
    }

    @Test
    fun desetinnyCasOdmitnut() {
        val json = parseObject("""{"name":"x","charge_bt":200,"charge_gas":60,"pid_delay_s":90.0,"curve":[[0,200],[90,100]],"vfd":[[0,80,50,0,0]],"drop":{"bt":198,"t_max":720},"cool":{"cooler":60,"coolfan":100}}""")!!
        assertEquals("pid_delay_s", RecipeValidator.validate(json)?.field)
    }

    @Test
    fun nazevDelsiNez32ZnakuOdmitnut() {
        val r = Recipe.sample().copy(name = "x".repeat(33))
        assertEquals("name", RecipeValidator.validate(r.toApiJson())?.field)
    }

    @Test
    fun ulozeniANacteni() {
        val r = Recipe.sample()
        val back = Recipe.fromJson(r.toStorageJson())!!
        assertEquals(r.copy(updatedAt = back.updatedAt), back)
    }

    @Test
    fun stavSNullHodnotami() {
        val s = Status.parse(parseObject("""{"t":"status","ms":1,"state":"RUNNING","temp":{"bt":null,"air":201.7},"vfd":[{"name":"drum","cmd":80,"pct":null}],"roast":null,"sim":null}""")!!)
        assertNull(s.temps.bt)
        assertEquals(201.7, s.temps.air!!, 0.001)
        assertNull(s.vfd[0].pct)
        assertTrue(s.state.heating)
    }
}
