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
/** Značky, které „Smazat značku“ nemaže (mají řídicí význam nebo je zapsala Portenta). */
private val fixedMarks = setOf("charge", "drop", "preheat", "preheat_hold", "charge_ready")

class Recorder(private val appVersion: String, private val onFinished: (RoastRecord) -> Unit, private val onCheckpoint: (RoastRecord) -> Unit) {
    private val _current = MutableStateFlow<RoastRecord?>(null)
    val current: StateFlow<RoastRecord?> = _current.asStateFlow()

    /** Krátká historie posledních 30 minut pro graf, i když se nic nezaznamenává. */
    private val _live = MutableStateFlow<List<Sample>>(emptyList())
    val live: StateFlow<List<Sample>> = _live.asStateFlow()

    /** Značky v čase živého grafu (předehřev, vsypání …) – i bez záznamu. */
    private val _liveMarks = MutableStateFlow<List<Mark>>(emptyList())
    val liveMarks: StateFlow<List<Mark>> = _liveMarks.asStateFlow()

    /** Značky, které se zapíšou na začátek záznamu, až začne (např. „Předehřev“ ze stisku tlačítka). */
    private val pendingStartMarks = ArrayList<String>()

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

    /** Ruční pražení: po vysypání běží chlazení řízené aplikací – záznam pokračuje, dokud se neukončí. */
    private val _manualCooling = MutableStateFlow(false)
    val manualCooling: StateFlow<Boolean> = _manualCooling.asStateFlow()

    fun startManualCooling() { _manualCooling.value = true }

    /** Konec chlazení po ručním pražení – záznam se uloží. */
    fun endManualCooling() {
        _manualCooling.value = false
        finish()
    }

    private fun sampleOf(s: Status, t: Double) = Sample(
        t = t,
        bt = s.temps.bt, air = s.temps.air, exh = s.temps.exh, ror = s.temps.ror,
        gas = if (s.burner.power) s.burner.gas else null,
        sp = s.ctrl.sp,
        vfd = s.vfd.map { it.pct },
        state = s.state.name,
        vfdCmd = s.vfd.map { it.cmd },
    )

    private fun liveT(ms: Long) = if (liveStartMs < 0) 0.0 else (ms - liveStartMs) / 1000.0

    fun onStatus(s: Status) {
        // živý buffer pro graf: vzorky nejvýš po 0,4 s (status může chodit častěji), posledních 30 min
        if (liveStartMs < 0 || s.ms < lastMs) {
            liveStartMs = s.ms
            _live.value = emptyList()
            _liveMarks.value = emptyList()
        }
        val liveT = liveT(s.ms)
        val l = _live.value
        val prev = l.lastOrNull()
        if (prev == null || liveT - prev.t >= 0.4 || prev.state != s.state.name) {
            val cut = liveT - 1800.0
            val trimmed = if (l.isNotEmpty() && l.first().t < cut - 60.0) l.dropWhile { it.t < cut } else l
            _live.value = trimmed + sampleOf(s, liveT)
            if (trimmed !== l) _liveMarks.value = _liveMarks.value.filter { it.t >= cut }
        }

        lastStatus = s
        val rec = _current.value
        val recipeActive = s.roast?.running == true
        val heatingStarted = s.state.heating && !wasHeating
        wasHeating = s.state.heating
        if (rec == null) {
            if (heatingStarted || s.state == RoasterState.HALTED) _manualCooling.value = false
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
            val prevS = rec.samples.lastOrNull()
            if (prevS == null || t - prevS.t >= 0.4 || prevS.state != s.state.name) {
                _current.value = rec.copy(samples = rec.samples + sampleOf(s, t), gaps = rec.gaps + if (gap) 1 else 0)
            }
            // nový ohřev, chyba nebo receptura ruší chlazení po ručním pražení
            if (heatingStarted || s.state == RoasterState.HALTED || recipeActive) _manualCooling.value = false
            val end = when {
                s.state == RoasterState.HALTED -> true
                s.state.heating -> false
                recipeActive -> false
                _manualCooling.value -> false
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
            marks = pendingStartMarks.map { Mark(it, 0.0, s.temps.bt) },
            appVersion = appVersion,
            fw = fw,
        )
        pendingStartMarks.clear()
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
        if (e.code != "roast") return
        val raw = e.roastEvent ?: return
        // „start“ receptury = začátek předehřevu
        val ev = if (raw == "start") "preheat" else raw
        if (ev !in setOf("preheat", "preheat_hold", "charge_ready", "charge", "dry_end", "fc_start", "fc_end", "sc_start", "drop")) return
        if (!e.replay || _liveMarks.value.none { it.event == ev }) {
            val lt = liveT(e.ms)
            if (_liveMarks.value.none { it.event == ev && abs(it.t - lt) < 1.0 }) {
                _liveMarks.value = (_liveMarks.value + Mark(ev, lt, lastStatus?.temps?.bt)).sortedBy { it.t }
            }
        }
        val r = _current.value
        if (r == null) {
            // receptura: „Předehřev“ přijde dřív, než záznam začne (záznam startuje se stavem ohřevu)
            if (ev == "preheat" && !pendingStartMarks.contains(ev)) pendingStartMarks.add(ev)
            return
        }
        val t = ((e.ms - startMs) / 1000.0).coerceAtLeast(0.0)
        if (r.marks.any { it.event == ev && abs(it.t - t) < 1.0 }) return
        val bt = r.samples.minByOrNull { abs(it.t - t) }?.bt
        _current.value = r.copy(marks = (r.marks + Mark(ev, t, bt)).sortedBy { it.t })
    }

    /** Značka zadaná v aplikaci (pražení bez receptury, poznámka). Do živého grafu vždy, do záznamu když běží. */
    fun addLocalMark(event: String, text: String = ""): Boolean {
        lastStatus?.let { s -> _liveMarks.value = (_liveMarks.value + Mark(event, liveT(s.ms), s.temps.bt, text)).sortedBy { it.t } }
        val r = _current.value ?: return false
        val last = r.samples.lastOrNull() ?: return false
        _current.value = r.copy(marks = (r.marks + Mark(event, last.t, last.bt, text)).sortedBy { it.t })
        return true
    }

    /** Značka, která má být i na začátku záznamu, když ještě nezačal (ruční předehřev před zapálením). */
    fun markAtStart(event: String) {
        if (_current.value != null) { addLocalMark(event); return }
        lastStatus?.let { s -> _liveMarks.value = (_liveMarks.value + Mark(event, liveT(s.ms), s.temps.bt)).sortedBy { it.t } }
        if (!pendingStartMarks.contains(event)) pendingStartMarks.add(event)
    }

    /**
     * Reset grafu (ruční pražení): smaže dosavadní průběh, čas začne od 0.
     * Běží-li záznam, dosavadní část se z něj odstraní a záznam pokračuje od resetu.
     */
    fun resetGraph() {
        val s = lastStatus ?: return
        liveStartMs = s.ms
        _live.value = listOf(sampleOf(s, 0.0))
        _liveMarks.value = emptyList()
        val r = _current.value ?: return
        startMs = s.ms
        _current.value = r.copy(samples = listOf(sampleOf(s, 0.0)), marks = emptyList(), gaps = 0)
    }

    /** Smaže poslední značku jen v záznamu (Portenta značky nevrací). */
    fun removeLastMark(): Mark? {
        val r = _current.value ?: return null
        val m = r.marks.lastOrNull { it.event !in fixedMarks } ?: return null
        _current.value = r.copy(marks = r.marks - m)
        _liveMarks.value.lastOrNull { it.event == m.event }?.let { lm -> _liveMarks.value = _liveMarks.value - lm }
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

        val rows = vfdRows(roast, charge)
        // předehřev: cíl z nastavené teploty, výdrž od dosažení teploty do vsypání
        val holdMark = r.marks.firstOrNull { it.event == "preheat_hold" && it.t <= charge }
        val preheatS = holdMark?.let { (charge - it.t).toInt().coerceIn(0, 3600) } ?: 60
        val preheatTarget = r.samples.lastOrNull { it.t <= charge && it.sp != null }?.sp

        val after = r.samples.filter { it.t > drop + 5 }
        fun coolPct(i: Int): Int? = after.mapNotNull { s -> (s.vfdCmd.getOrNull(i) ?: s.vfd.getOrNull(i)) }.maxOrNull()
            ?.let { pct(it, allowZero = true) }?.takeIf { it > 0 }
        val cooler = coolPct(2) ?: 60
        val fan = coolPct(3) ?: 100

        val recipe = Recipe(
            name = name.take(32),
            chargeBt = Math.round(preheatTarget ?: chargeBt).toDouble().coerceIn(50.0, 260.0),
            preheatS = preheatS,
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

    /** Procenta pro recepturu: celé číslo, buben a odtah 10–100, míchač a ventilátor 0 nebo 10–100. */
    private fun pct(v: Double, allowZero: Boolean): Int {
        val x = Math.round(v).toInt()
        return if (allowZero) (if (x < 10) 0 else x.coerceAtMost(100)) else x.coerceIn(10, 100)
    }

    /**
     * Rozpis měničů od vsypání. Přednostně z nastavených rychlostí (přesně: řádek v čase změny,
     * přesná hodnota). Starší záznamy bez nich: ze skutečné rychlosti, ale jen ustálené – mezihodnoty
     * rozjezdu se vynechají a řádek dostane čas začátku změny.
     */
    fun vfdRows(roast: List<Sample>, charge: Double): List<VfdPoint> {
        fun row(t: Int, v: List<Double?>) = VfdPoint(
            t,
            pct(v.getOrNull(0) ?: 80.0, false), pct(v.getOrNull(1) ?: 80.0, false),
            pct(v.getOrNull(2) ?: 0.0, true), pct(v.getOrNull(3) ?: 0.0, true),
        )
        val rows = ArrayList<VfdPoint>()
        fun push(p: VfdPoint) {
            val last = rows.lastOrNull()
            when {
                last == null -> rows.add(p.copy(t = 0))
                p.values() == last.values() -> Unit
                // dvě změny těsně po sobě (do 3 s) = jedna úprava obsluhy
                p.t - last.t <= 3 && rows.size > 1 -> rows[rows.size - 1] = p.copy(t = last.t)
                p.t <= last.t -> rows[rows.size - 1] = p.copy(t = last.t)
                else -> rows.add(p)
            }
        }
        val hasCmd = roast.isNotEmpty() && roast.all { it.vfdCmd.size >= 4 }
        if (hasCmd) {
            for (s in roast) push(row((s.t - charge).toInt(), s.vfdCmd))
        } else {
            // ustálená hodnota = stejná (±1 %) aspoň 3 s; čas = poslední ustálený vzorek před změnou
            var changeStart: Double? = null
            var prev: List<Int>? = null
            var stableSince = 0.0
            for (s in roast) {
                val p = row((s.t - charge).toInt(), s.vfd)
                val v = p.values()
                if (prev == null || v.zip(prev).any { (a, b) -> kotlin.math.abs(a - b) > 1 }) {
                    if (changeStart == null && prev != null) changeStart = s.t
                    stableSince = s.t
                    prev = v
                    continue
                }
                if (s.t - stableSince >= 3.0) {
                    val t = ((changeStart ?: s.t) - charge).toInt()
                    push(p.copy(t = t.coerceAtLeast(0)))
                    changeStart = null
                }
            }
            if (rows.isEmpty() && roast.isNotEmpty()) push(row(0, roast.first().vfd))
        }
        return rows.take(16)
    }
}
