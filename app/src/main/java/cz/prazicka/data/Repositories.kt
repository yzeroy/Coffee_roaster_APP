package cz.prazicka.data

import cz.prazicka.protocol.arr
import cz.prazicka.protocol.asDouble
import cz.prazicka.protocol.asString
import cz.prazicka.protocol.bool
import cz.prazicka.protocol.dbl
import cz.prazicka.protocol.int
import cz.prazicka.protocol.lng
import cz.prazicka.protocol.parseObject
import cz.prazicka.protocol.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** Knihovna receptur: jeden soubor JSON na recepturu v interním úložišti aplikace. */
class RecipeRepository(baseDir: File) {
    private val dir = File(baseDir, "recipes").apply { mkdirs() }
    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val list = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            parseObject(f.readText())?.let { Recipe.fromJson(it) }
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (list.isEmpty()) {
            val s = Recipe.sample()
            File(dir, "${s.id}.json").writeText(s.toStorageJson().toString())
            _recipes.value = listOf(s)
        } else {
            _recipes.value = list
        }
    }

    suspend fun save(r: Recipe) = withContext(Dispatchers.IO) {
        val updated = r.copy(updatedAt = System.currentTimeMillis())
        File(dir, "${safe(updated.id)}.json").writeText(updated.toStorageJson().toString())
        _recipes.value = (_recipes.value.filter { it.id != updated.id } + updated).sortedBy { it.name.lowercase() }
        updated
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "${safe(id)}.json").delete()
        _recipes.value = _recipes.value.filter { it.id != id }
    }

    fun byId(id: String?): Recipe? = _recipes.value.firstOrNull { it.id == id }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "recept" }
}

data class Sample(
    val t: Double,
    val bt: Double?,
    val air: Double?,
    val exh: Double?,
    val ror: Double?,
    val gas: Double?,
    val sp: Double?,
    val vfd: List<Double?>,
    val state: String,
)

data class Mark(val event: String, val t: Double, val bt: Double?, val text: String = "")

/** Záznam jednoho pražení. Čas t = sekundy od začátku záznamu. */
data class RoastRecord(
    val id: String,
    val startedAt: Long,
    val recipeName: String?,
    val recipeId: String?,
    val manual: Boolean,
    val samples: List<Sample>,
    val marks: List<Mark>,
    val notes: String = "",
    val weightIn: Double? = null,
    val weightOut: Double? = null,
    val gaps: Int = 0,
    val appVersion: String = "",
    val fw: String = "",
) {
    val chargeT: Double? get() = marks.firstOrNull { it.event == "charge" }?.t
    val dropT: Double? get() = marks.lastOrNull { it.event == "drop" }?.t
    val title: String get() = recipeName ?: "Ruční pražení"
    val durationS: Double? get() {
        val c = chargeT ?: return samples.lastOrNull()?.t
        val d = dropT ?: samples.lastOrNull()?.t ?: return null
        return d - c
    }
    val lossPct: Double? get() {
        val i = weightIn ?: return null
        val o = weightOut ?: return null
        if (i <= 0) return null
        return (i - o) / i * 100.0
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("id", id)
        put("started_at", startedAt)
        put("recipe_name", recipeName)
        put("recipe_id", recipeId)
        put("manual", manual)
        put("notes", notes)
        put("weight_in", weightIn)
        put("weight_out", weightOut)
        put("gaps", gaps)
        put("app", appVersion)
        put("fw", fw)
        putJsonArray("marks") {
            marks.forEach { m -> addJsonObject { put("event", m.event); put("t", m.t); put("bt", m.bt); put("text", m.text) } }
        }
        // vzorky jako pole polí kvůli velikosti: t, bt, air, exh, ror, gas, sp, v0..v3, state
        putJsonArray("samples") {
            samples.forEach { s ->
                addJsonArray {
                    add(s.t); add(s.bt); add(s.air); add(s.exh); add(s.ror); add(s.gas); add(s.sp)
                    for (i in 0..3) add(s.vfd.getOrNull(i))
                    add(s.state)
                }
            }
        }
    }

    companion object {
        fun fromJson(o: JsonObject): RoastRecord? = try {
            RoastRecord(
                id = o.str("id") ?: return null,
                startedAt = o.lng("started_at") ?: 0L,
                recipeName = o.str("recipe_name"),
                recipeId = o.str("recipe_id"),
                manual = o.bool("manual") ?: true,
                notes = o.str("notes") ?: "",
                weightIn = o.dbl("weight_in"),
                weightOut = o.dbl("weight_out"),
                gaps = o.int("gaps") ?: 0,
                appVersion = o.str("app") ?: "",
                fw = o.str("fw") ?: "",
                marks = o.arr("marks")?.mapNotNull { e ->
                    val m = e as? JsonObject ?: return@mapNotNull null
                    Mark(m.str("event") ?: return@mapNotNull null, m.dbl("t") ?: 0.0, m.dbl("bt"), m.str("text") ?: "")
                } ?: emptyList(),
                samples = o.arr("samples")?.mapNotNull { e ->
                    val a = e as? JsonArray ?: return@mapNotNull null
                    if (a.size < 12) return@mapNotNull null
                    Sample(
                        t = a[0].asDouble() ?: 0.0,
                        bt = a[1].asDouble(), air = a[2].asDouble(), exh = a[3].asDouble(), ror = a[4].asDouble(),
                        gas = a[5].asDouble(), sp = a[6].asDouble(),
                        vfd = listOf(a[7].asDouble(), a[8].asDouble(), a[9].asDouble(), a[10].asDouble()),
                        state = a[11].asString() ?: "",
                    )
                } ?: emptyList(),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Uložené záznamy pražení. Seznam drží jen hlavičky, vzorky se načítají při otevření. */
class RecordRepository(baseDir: File) {
    private val dir = File(baseDir, "records").apply { mkdirs() }
    private val _records = MutableStateFlow<List<RoastRecord>>(emptyList())
    val records: StateFlow<List<RoastRecord>> = _records.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _records.value = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            parseObject(f.readText())?.let { RoastRecord.fromJson(it) }
        }?.sortedByDescending { it.startedAt } ?: emptyList()
    }

    suspend fun save(r: RoastRecord) = withContext(Dispatchers.IO) {
        File(dir, "${r.id}.json").writeText(r.toJson().toString())
        _records.value = (_records.value.filter { it.id != r.id } + r).sortedByDescending { it.startedAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        _records.value = _records.value.filter { it.id != id }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

/** Záloha celé knihovny (receptury + záznamy) do jednoho JSON. */
object Backup {
    fun export(recipes: List<Recipe>, records: List<RoastRecord>): String = buildJsonObject {
        put("prazicka_backup", 1)
        put("created_at", System.currentTimeMillis())
        putJsonArray("recipes") { recipes.forEach { add(it.toStorageJson()) } }
        putJsonArray("records") { records.forEach { add(it.toJson()) } }
    }.toString()

    fun parse(text: String): Pair<List<Recipe>, List<RoastRecord>>? {
        val o = parseObject(text) ?: return null
        if (o["prazicka_backup"] == null) {
            // jednotlivá receptura
            return Recipe.fromJson(o)?.let { listOf(it) to emptyList() }
        }
        val rs = (o["recipes"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(Recipe::fromJson) } ?: emptyList()
        val rec = (o["records"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(RoastRecord::fromJson) } ?: emptyList()
        return rs to rec
    }
}

/** CSV pro tabulkový procesor: oddělovač středník, desetinná čárka. */
object CsvExport {
    fun record(r: RoastRecord): String {
        val c = r.chargeT ?: 0.0
        val sb = StringBuilder()
        sb.append("cas_s;od_vsypani_s;zrno;vzduch;odtah;ror;plyn;cil;buben;odtah_pct;michac;ventilator;stav\n")
        fun f(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.1f", it).replace('.', ',') } ?: ""
        r.samples.forEach { s ->
            sb.append(f(s.t)).append(';').append(f(s.t - c)).append(';')
                .append(f(s.bt)).append(';').append(f(s.air)).append(';').append(f(s.exh)).append(';')
                .append(f(s.ror)).append(';').append(f(s.gas)).append(';').append(f(s.sp)).append(';')
                .append(f(s.vfd.getOrNull(0))).append(';').append(f(s.vfd.getOrNull(1))).append(';')
                .append(f(s.vfd.getOrNull(2))).append(';').append(f(s.vfd.getOrNull(3))).append(';')
                .append(s.state).append('\n')
        }
        sb.append("\nudalost;cas_s;od_vsypani_s;zrno;text\n")
        r.marks.forEach { m -> sb.append(m.event).append(';').append(f(m.t)).append(';').append(f(m.t - c)).append(';').append(f(m.bt)).append(';').append(m.text.replace(';', ',')).append('\n') }
        return sb.toString()
    }
}
