package cz.prazicka.data

import cz.prazicka.protocol.arr
import cz.prazicka.protocol.asDouble
import cz.prazicka.protocol.dbl
import cz.prazicka.protocol.lng
import cz.prazicka.protocol.obj
import cz.prazicka.protocol.r1
import cz.prazicka.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

data class CurvePoint(val t: Int, val bt: Double)

data class VfdPoint(val t: Int, val drum: Int, val exhaust: Int, val cooler: Int, val coolfan: Int) {
    fun values() = listOf(drum, exhaust, cooler, coolfan)
}

/** Receptura podle API bod 8.6.1. Časy jsou celé sekundy, procenta měničů celá čísla. */
data class Recipe(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val chargeBt: Double,
    /** Výdrž na teplotě předehřevu (s), API 1.7 `preheat_s`. */
    val preheatS: Int = 60,
    val chargeGas: Double,
    val pidDelayS: Int,
    val curve: List<CurvePoint>,
    val vfd: List<VfdPoint>,
    val dropBt: Double,
    val dropTMax: Int,
    val coolCooler: Int,
    val coolFan: Int,
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun curveAt(t: Double): Double {
        if (curve.isEmpty()) return chargeBt
        if (t <= curve.first().t) return curve.first().bt
        for (i in 1 until curve.size) {
            val a = curve[i - 1]
            val b = curve[i]
            if (t <= b.t) return a.bt + (b.bt - a.bt) * (t - a.t) / (b.t - a.t).toDouble()
        }
        return curve.last().bt
    }

    /** Přesně formát pro recipe_load. */
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("charge_bt", r1(chargeBt))
        put("preheat_s", preheatS)
        put("charge_gas", r1(chargeGas))
        put("pid_delay_s", pidDelayS)
        putJsonArray("curve") {
            curve.forEach { p -> addJsonArray { add(p.t); add(r1(p.bt)) } }
        }
        putJsonArray("vfd") {
            vfd.forEach { p -> addJsonArray { add(p.t); add(p.drum); add(p.exhaust); add(p.cooler); add(p.coolfan) } }
        }
        putJsonObject("drop") {
            put("bt", r1(dropBt))
            put("t_max", dropTMax)
        }
        putJsonObject("cool") {
            put("cooler", coolCooler)
            put("coolfan", coolFan)
        }
    }

    fun toStorageJson(): JsonObject = buildJsonObject {
        toApiJson().forEach { (k, v) -> put(k, v) }
        put("notes", notes)
        put("updated_at", updatedAt)
    }

    companion object {
        fun fromJson(o: JsonObject): Recipe? = try {
            val drop = o.obj("drop")
            val cool = o.obj("cool")
            Recipe(
                id = o.str("id") ?: UUID.randomUUID().toString().take(8),
                name = o.str("name") ?: "Bez názvu",
                chargeBt = o.dbl("charge_bt") ?: 200.0,
                preheatS = o.lng("preheat_s")?.toInt() ?: 60,
                chargeGas = o.dbl("charge_gas") ?: 60.0,
                pidDelayS = o.lng("pid_delay_s")?.toInt() ?: 90,
                curve = o.arr("curve")?.mapNotNull { e ->
                    val a = e as? JsonArray ?: return@mapNotNull null
                    val t = a.getOrNull(0).asDouble() ?: return@mapNotNull null
                    val bt = a.getOrNull(1).asDouble() ?: return@mapNotNull null
                    CurvePoint(t.toInt(), bt)
                } ?: emptyList(),
                vfd = o.arr("vfd")?.mapNotNull { e ->
                    val a = e as? JsonArray ?: return@mapNotNull null
                    if (a.size < 5) return@mapNotNull null
                    val v = a.map { it.asDouble()?.toInt() ?: 0 }
                    VfdPoint(v[0], v[1], v[2], v[3], v[4])
                } ?: emptyList(),
                dropBt = drop?.dbl("bt") ?: 200.0,
                dropTMax = drop?.lng("t_max")?.toInt() ?: 720,
                coolCooler = cool?.lng("cooler")?.toInt() ?: 60,
                coolFan = cool?.lng("coolfan")?.toInt() ?: 100,
                notes = o.str("notes") ?: "",
                updatedAt = o.lng("updated_at") ?: System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            null
        }

        fun sample() = Recipe(
            id = "etiopie1",
            name = "Etiopie light",
            chargeBt = 200.0,
            preheatS = 300,
            chargeGas = 60.0,
            pidDelayS = 90,
            curve = listOf(CurvePoint(0, 200.0), CurvePoint(90, 100.0), CurvePoint(240, 150.0), CurvePoint(480, 185.0), CurvePoint(600, 198.0)),
            vfd = listOf(VfdPoint(0, 80, 50, 0, 0), VfdPoint(300, 80, 70, 0, 0)),
            dropBt = 198.0,
            dropTMax = 720,
            coolCooler = 60,
            coolFan = 100,
        )
    }
}

data class RecipeError(val field: String, val msg: String)

/**
 * Kontrola receptury přesně podle API bod 8.6.2 a 9. Pracuje s JSON pro recipe_load,
 * takže ji používá aplikace před odesláním i vestavěný simulátor Portenty.
 */
object RecipeValidator {
    private fun isInt(e: Any?): Boolean {
        val p = e as? JsonPrimitive ?: return false
        if (p.isString) return false
        val d = p.content.toDoubleOrNull() ?: return false
        return d == Math.floor(d) && !p.content.contains('.') && !p.content.contains('e') && !p.content.contains('E')
    }

    private fun num(o: JsonObject, key: String, min: Double, max: Double, field: String = key): Pair<Double?, RecipeError?> {
        val p = o[key] as? JsonPrimitive ?: return null to RecipeError(field, "chybí $field")
        if (p.isString) return null to RecipeError(field, "$field musí být číslo")
        val v = p.content.toDoubleOrNull() ?: return null to RecipeError(field, "$field musí být číslo")
        if (v < min || v > max) return null to RecipeError(field, "$field mimo rozsah ${fmtR(min)}–${fmtR(max)}")
        return v to null
    }

    private fun fmtR(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun pctOk(v: Double, allowZero: Boolean): Boolean =
        v == Math.floor(v) && ((allowZero && v == 0.0) || (v >= 10.0 && v <= 100.0))

    fun validate(r: JsonObject): RecipeError? {
        val name = r.str("name") ?: return RecipeError("name", "chybí název")
        if (name.isBlank()) return RecipeError("name", "název je prázdný")
        if (name.codePointCount(0, name.length) > 32) return RecipeError("name", "název má víc než 32 znaků")
        if (name.toByteArray(Charsets.UTF_8).size > 64) return RecipeError("name", "název má víc než 64 bajtů")
        r.str("id")?.let { id ->
            if (id.length > 36 || id.any { it.code !in 0x20..0x7E }) return RecipeError("id", "id musí být ASCII do 36 znaků")
        }
        num(r, "charge_bt", 50.0, 260.0).second?.let { return it }
        if (r.containsKey("preheat_s")) {
            num(r, "preheat_s", 0.0, 3600.0).second?.let { return it }
            if (!isInt(r["preheat_s"])) return RecipeError("preheat_s", "preheat_s musí být celé číslo")
        }
        num(r, "charge_gas", 10.0, 100.0).second?.let { return it }
        val (pidDelay, e1) = num(r, "pid_delay_s", 0.0, 600.0)
        if (e1 != null) return e1
        if (!isInt(r["pid_delay_s"])) return RecipeError("pid_delay_s", "pid_delay_s musí být celé číslo")

        val curve = r.arr("curve") ?: return RecipeError("curve", "chybí křivka")
        if (curve.size < 2 || curve.size > 32) return RecipeError("curve", "křivka musí mít 2–32 bodů")
        var lastT = -1.0
        curve.forEachIndexed { i, e ->
            val a = e as? JsonArray ?: return RecipeError("curve", "bod ${i + 1} křivky má špatný tvar")
            if (a.size != 2) return RecipeError("curve", "bod ${i + 1} křivky má špatný tvar")
            if (!isInt(a[0])) return RecipeError("curve", "čas bodu ${i + 1} musí být celé číslo sekund")
            val t = a[0].asDouble() ?: return RecipeError("curve", "čas bodu ${i + 1} chybí")
            val bt = a[1].asDouble() ?: return RecipeError("curve", "teplota bodu ${i + 1} chybí")
            if (t < 0 || t > 1800) return RecipeError("curve", "čas bodu ${i + 1} mimo 0–1800 s")
            if (bt < 50 || bt > 260) return RecipeError("curve", "teplota bodu ${i + 1} mimo 50–260 °C")
            if (i == 0 && t != 0.0) return RecipeError("curve", "první bod křivky musí mít čas 0")
            if (t <= lastT) return RecipeError("curve", "časy křivky musí stoupat")
            lastT = t
        }
        if ((pidDelay ?: 0.0) > lastT) return RecipeError("pid_delay_s", "PID nemůže převzít později než poslední bod křivky")

        val vfd = r.arr("vfd") ?: return RecipeError("vfd", "chybí rozpis měničů")
        if (vfd.isEmpty() || vfd.size > 16) return RecipeError("vfd", "rozpis měničů musí mít 1–16 řádků")
        lastT = -1.0
        vfd.forEachIndexed { i, e ->
            val a = e as? JsonArray ?: return RecipeError("vfd", "řádek ${i + 1} rozpisu má špatný tvar")
            if (a.size != 5) return RecipeError("vfd", "řádek ${i + 1} rozpisu má špatný tvar")
            if (!isInt(a[0])) return RecipeError("vfd", "čas řádku ${i + 1} musí být celé číslo sekund")
            val t = a[0].asDouble() ?: return RecipeError("vfd", "čas řádku ${i + 1} chybí")
            if (t < 0 || t > 1800) return RecipeError("vfd", "čas řádku ${i + 1} mimo 0–1800 s")
            if (i == 0 && t != 0.0) return RecipeError("vfd", "první řádek rozpisu musí mít čas 0")
            if (t <= lastT) return RecipeError("vfd", "časy rozpisu musí stoupat")
            lastT = t
            for (k in 1..4) {
                val v = a[k].asDouble() ?: return RecipeError("vfd", "řádek ${i + 1}: chybí hodnota")
                val allowZero = k >= 3
                if (!pctOk(v, allowZero)) {
                    return if (k <= 2) RecipeError("vfd", "řádek ${i + 1}: buben a odtah 10–100 % (za ohřevu nesmí stát)")
                    else RecipeError("vfd", "řádek ${i + 1}: míchač a ventilátor 0 nebo 10–100 %")
                }
            }
        }

        val drop = r.obj("drop") ?: return RecipeError("drop", "chybí podmínka vysypání")
        num(drop, "bt", 50.0, 260.0, "drop.bt").second?.let { return it }
        num(drop, "t_max", 60.0, 1800.0, "drop.t_max").second?.let { return it }
        if (!isInt(drop["t_max"])) return RecipeError("drop.t_max", "drop.t_max musí být celé číslo")
        val cool = r.obj("cool") ?: return RecipeError("cool", "chybí chlazení")
        for (k in listOf("cooler", "coolfan")) {
            val (v, e) = num(cool, k, 0.0, 100.0, "cool.$k")
            if (e != null) return e
            if (!pctOk(v!!, true)) return RecipeError("cool.$k", "cool.$k musí být 0 nebo 10–100 %")
        }
        return null
    }
}
