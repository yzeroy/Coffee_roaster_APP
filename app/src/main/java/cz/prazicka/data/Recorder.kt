package cz.prazicka.data

import cz.prazicka.protocol.Event
import cz.prazicka.protocol.RoasterState
import cz.prazicka.protocol.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Záznam pražení ze zpráv status a event (API: záznam dělá aplikace).
 * Začne s ohřevem, při receptuře pokračuje přes chlazení, skončí návratem do klidu nebo chybou.
 * Výpadek spojení = mezera v záznamu.
 */
class Recorder(private val appVersion: String, private val onFinished: (RoastRecord) -> Unit, private val onCheckpoint: (RoastRecord) -> Unit) {
    private val _current = MutableStateFlow<RoastRecord?>(null)
    val current: StateFlow<RoastRecord?> = _current.asStateFlow()

    /** Krátká historie posledních 20 minut pro graf, i když se nic nezaznamenává. */
    private val _live = MutableStateFlow<List<Sample>>(emptyList())
    val live: StateFlow<List<Sample>> = _live.asStateFlow()

    private var startMs: Long = 0
    private var lastMs: Long = -1
    private var liveStartMs: Long = -1
    private var lastCheckpoint = 0L
    private var lastStatus: Status? = null
    /** Výchozí volba z Nastavení pro ruční pražení spuštěné jinak než z dialogu. */
    var defaultManual = true
    /** Volba z dialogu před ručním pražením: zaznamenat / nezaznamenat (platí pro nejbližší ohřev). */
    var nextManualChoice: Boolean? = null
    var fw: String = ""
    private var wasHeating = false

    private fun sampleOf(s: Status, t: Double) = Sample(
        t = t,
        bt = s.temps.bt, air = s.temps.air, exh = s.temps.exh, ror = s.temps.ror,
        gas = if (s.burner.power) s.burner.gas else null,
        sp = s.ctrl.sp,
        vfd = s.vfd.map { it.pct },
        state = s.state.name,
    )

    fun onStatus(s: Status) {
        // živý buffer pro graf
        if (liveStartMs < 0 || s.ms < lastMs) liveStartMs = s.ms
        val liveT = (s.ms - liveStartMs) / 1000.0
        val l = _live.value
        _live.value = (if (l.size > 2600) l.drop(200) else l) + sampleOf(s, liveT)

        lastStatus = s
        val rec = _current.value
        val recipeActive = s.roast?.running == true
        val heatingStarted = s.state.heating && !wasHeating
        wasHeating = s.state.heating
        if (rec == null) {
            if (heatingStarted) {
                val record = recipeActive || (nextManualChoice ?: defaultManual)
                nextManualChoice = null
                if (record) start(s)
            }
        } else {
            if (s.ms < lastMs) {
                // Portenta se restartovala – záznam končí
                finish()
                lastMs = s.ms
                return
            }
            val t = (s.ms - startMs) / 1000.0
            val gap = lastMs >= 0 && s.ms - lastMs > 2500
            _current.value = rec.copy(samples = rec.samples + sampleOf(s, t), gaps = rec.gaps + if (gap) 1 else 0)
            val end = when {
                s.state == RoasterState.HALTED -> true
                s.state.heating -> false
                recipeActive -> false
                else -> true
            }
            if (end) finish()
            else if (System.currentTimeMillis() - lastCheckpoint > 30_000) {
                lastCheckpoint = System.currentTimeMillis()
                _current.value?.let(onCheckpoint)
            }
        }
        lastMs = s.ms
    }

    private fun start(s: Status) {
        startMs = s.ms
        lastCheckpoint = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        _current.value = RoastRecord(
            id = id,
            startedAt = System.currentTimeMillis(),
            recipeName = s.roast?.takeIf { it.running }?.recipe,
            recipeId = s.roast?.takeIf { it.running }?.recipeId,
            manual = s.roast?.running != true,
            samples = listOf(sampleOf(s, 0.0)),
            marks = emptyList(),
            appVersion = appVersion,
            fw = fw,
        )
    }

    /** „Začít zaznamenávat“ během ručního pražení bez záznamu – od tohoto okamžiku. */
    fun startNow(): Boolean {
        if (_current.value != null) return false
        val s = lastStatus ?: return false
        if (!s.state.heating) return false
        start(s)
        return true
    }

    fun finish() {
        val r = _current.value ?: return
        _current.value = null
        if (r.samples.size >= 10) onFinished(r)
    }

    fun onEvent(e: Event) {
        val r = _current.value ?: return
        if (e.code != "roast") return
        val ev = e.roastEvent ?: return
        if (ev !in setOf("charge", "dry_end", "fc_start", "fc_end", "sc_start", "drop")) return
        val t = (e.ms - startMs) / 1000.0
        if (r.marks.any { it.event == ev && abs(it.t - t) < 1.0 }) return
        val bt = r.samples.minByOrNull { abs(it.t - t) }?.bt
        _current.value = r.copy(marks = (r.marks + Mark(ev, t, bt)).sortedBy { it.t })
    }

    /** Značka zadaná v aplikaci (pražení bez receptury, poznámka). */
    fun addLocalMark(event: String, text: String = ""): Boolean {
        val r = _current.value ?: return false
        val last = r.samples.lastOrNull() ?: return false
        _current.value = r.copy(marks = (r.marks + Mark(event, last.t, last.bt, text)).sortedBy { it.t })
        return true
    }

    /** Smaže poslední značku jen v záznamu (Portenta značky nevrací). */
    fun removeLastMark(): Mark? {
        val r = _current.value ?: return null
        val m = r.marks.lastOrNull { it.event != "charge" && it.event != "drop" } ?: return null
        _current.value = r.copy(marks = r.marks - m)
        return m
    }
}

/** Receptura z uloženého záznamu (spec 4.2 / 4.4). */
object RecipeFromRecord {
    fun build(r: RoastRecord, name: String): Recipe? {
        val samples = r.samples.filter { it.bt != null }
        if (samples.size < 20) return null
        val charge = r.chargeT ?: samples.first().t
        val drop = r.dropT ?: samples.last().t
        val roast = samples.filter { it.t in charge..drop }
        if (roast.size < 10) return null
        fun at(t: Double) = roast.minByOrNull { abs(it.t - t) }!!

        val chargeBt = (at(charge).bt ?: 200.0).coerceIn(50.0, 260.0)
        // bod zvratu: minimum teploty v prvních 4 minutách
        val tp = roast.filter { it.t - charge <= 240 }.minByOrNull { it.bt!! } ?: roast.first()
        val tpT = (tp.t - charge).toInt().coerceIn(1, 600)
        val gasToTp = roast.filter { it.t <= tp.t }.mapNotNull { it.gas }
        val chargeGas = (if (gasToTp.isEmpty()) 60.0 else gasToTp.average()).coerceIn(10.0, 100.0)

        val end = (drop - charge).toInt()
        val times = sortedSetOf(0, tpT)
        var t = tpT + 90
        while (t < end - 30) { times.add(t); t += 90 }
        times.add(end.coerceAtLeast(tpT + 1))
        val curve = times.take(32).map { s -> CurvePoint(s, (at(charge + s).bt ?: chargeBt).coerceIn(50.0, 260.0)) }

        // rozpis měničů z ručních změn (zaokrouhleno na 5 %)
        fun q(v: Double?, min: Int): Int {
            val x = ((v ?: 0.0) / 5.0).let { Math.round(it) * 5 }.toInt()
            return when {
                min > 0 -> x.coerceIn(min, 100)
                x < 10 -> 0
                else -> x.coerceAtMost(100)
            }
        }
        val rows = ArrayList<VfdPoint>()
        for (s in roast) {
            val p = VfdPoint((s.t - charge).toInt(), q(s.vfd.getOrNull(0), 10), q(s.vfd.getOrNull(1), 10), q(s.vfd.getOrNull(2), 0), q(s.vfd.getOrNull(3), 0))
            val last = rows.lastOrNull()
            if (last == null) rows.add(p.copy(t = 0))
            else if (p.values().zip(last.values()).any { (a, b) -> abs(a - b) >= 5 } && p.t > last.t + 10) rows.add(p)
            if (rows.size >= 16) break
        }

        val after = r.samples.filter { it.t > drop + 5 }
        val cooler = after.mapNotNull { it.vfd.getOrNull(2) }.maxOrNull()?.let { q(it, 0) }?.takeIf { it > 0 } ?: 60
        val fan = after.mapNotNull { it.vfd.getOrNull(3) }.maxOrNull()?.let { q(it, 0) }?.takeIf { it > 0 } ?: 100

        val recipe = Recipe(
            name = name.take(32),
            chargeBt = Math.round(chargeBt).toDouble(),
            chargeGas = Math.round(chargeGas).toDouble(),
            pidDelayS = tpT.coerceAtMost(curve.last().t),
            curve = curve,
            vfd = rows,
            dropBt = Math.round(at(drop).bt ?: 200.0).toDouble().coerceIn(50.0, 260.0),
            dropTMax = (end + 60).coerceIn(60, 1800),
            coolCooler = cooler,
            coolFan = fan,
            notes = "Vytvořeno ze záznamu ${r.id}",
        )
        return recipe
    }
}
