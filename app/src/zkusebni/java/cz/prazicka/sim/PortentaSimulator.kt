package cz.prazicka.sim

import cz.prazicka.data.RecipeValidator
import cz.prazicka.protocol.arr
import cz.prazicka.protocol.asDouble
import cz.prazicka.protocol.bool
import cz.prazicka.protocol.dbl
import cz.prazicka.protocol.lng
import cz.prazicka.protocol.obj
import cz.prazicka.protocol.parseObject
import cz.prazicka.protocol.r1
import cz.prazicka.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vestavěný simulátor Portenty pro testovací variantu aplikace.
 *
 * Mluví stejným protokolem jako firmware (API.md 1.7) a napodobuje stavový automat,
 * měniče, hořák, jednoduchý tepelný model, PID, automatické ladění a receptury.
 * Neslouží k ověření firmwaru – jen k tomu, aby aplikace šla zkoušet bez desky.
 *
 * Čistý Kotlin bez Androidu (testovatelné). Volající zajistí, že se volá z jednoho vlákna.
 */
class PortentaSimulator(seed: Long = System.nanoTime()) {

    companion object {
        const val FW = "sim-1.0"
        const val PROTO = 1
        val CAPS = listOf("base", "event_log", "pid", "autotune", "recipe", "sim")
        const val HZ_10 = 31.25
        const val HZ_100 = 62.5
        const val RAMP_HZ_S = 6.0
        const val LINK_TIMEOUT_MS = 15_000L
        const val PENDING_TIMEOUT_MS = 5_000L
        const val COOL_HOLD_S = 600.0
        /** API 1.5: plyn během zapalovacího pulzu, délka pulzu, plyn po studeném zapálení. */
        const val IGNITION_GAS = 100.0
        const val IGNITION_PULSE_S = 5.0
        const val IGNITION_AFTER_GAS = 70.0
        /** Simulace detekce plamene: plamen „potvrzen“ po této době skutečného hoření. */
        const val FLAME_DETECT_S = 3.0
        const val SP_DEFAULT = 200.0
        const val ROR_WINDOW_S = 30.0
        const val BAD_TOKEN = "spatny-token"
        val VFD_NAMES = listOf("drum", "exhaust", "cooler", "coolfan")
        val VFD_DEFAULT = doubleArrayOf(80.0, 80.0, 0.0, 0.0)
        val VFD_PREFLIGHT = doubleArrayOf(10.0, 10.0, 10.0, 10.0)
        private val HEATING = setOf("IGNITION_INTERLOCK", "IGNITION_SEQUENCE", "RUNNING")
    }

    private class CmdError(val err: String, val msg: String? = null, val field: String? = null) : Exception(msg)

    private class Conn(val id: Int, val out: (String) -> Unit, val onClose: () -> Unit) {
        var authed = false
        var ageMs = 0L
    }

    private val rnd = java.util.Random(seed)

    // ---- spojení
    private val conns = LinkedHashMap<Int, Conn>()
    private var nextConnId = 1
    private var active: Conn? = null
    private var clientApp: String? = null
    private var clientVariant: String? = null
    private var linkSilenceMs = 0L
    private var linkLost = false
    private var connects = 0
    private var panics = 0
    private var authFail = 0

    // ---- čas a stav
    var simMs = 0L
        private set
    val bootId: String = String.format("%08x", rnd.nextInt())
    var state = "BOOT"
        private set
    private var stateMs = 0L
    private var restartFlow = false
    private var stopConfirmAttempt = 1
    private var faultCode = 0
    private var faultVfd: Int? = null
    private var faultMs: Long? = null
    private var faultMsg: String? = null
    private var heatBlocked = false

    // ---- vstupy
    private var lossStop = false
    private var lossPsu = false
    private val powered get() = !lossStop && !lossPsu

    // ---- měniče
    private val vfdCmd = DoubleArray(4)
    private val vfdHz = DoubleArray(4)
    private val vfdFault = Array(4) { "ok" }
    private val vfdErrCode = IntArray(4)

    // ---- hořák
    private var power = false
    private var ignition = false
    private var gas = 0.0
    private var flame = 0
    private var interlock = 0
    private var flameFails = false
    /** Plamen skutečně hoří (model): zapálí ho pulz DO_05, drží se, dokud je modul zapnutý a teče plyn. */
    private var burning = false
    private val physicalFlame get() = burning

    // ---- teploty (model) a simulované podržení
    /** Horký vzduch v bubnu – jen vnitřní veličina modelu, Portenta ho neměří. */
    private var drumAir = 24.0
    /** Vzduch / okolí TP1 – teplota vně pražičky. */
    private var air = 25.0
    private var bt = 24.0
    private var exh = 24.0
    private var beans = 0.0
    private val holdActive = BooleanArray(3)
    private val holdValue = arrayOfNulls<Double>(3)
    private val btHist = ArrayDeque<Pair<Long, Double>>()
    private var ror: Double? = null

    // ---- regulace
    private var mode = "manual"
    private var sp = SP_DEFAULT
    private var kp = 4.0
    private var ki = 0.05
    private var kd = 20.0
    private var savedKp = 4.0
    private var savedKi = 0.05
    private var savedKd = 20.0
    private var lastSaveMs = -100_000L
    private var integ = 0.0
    private var lastBt: Double? = null
    private var dFilt = 0.0

    // ---- automatické ladění
    private class Autotune(val sp: Double, val low: Double, val high: Double, val cycles: Int, val soakSp: Double, val soakS: Double) {
        var phase = "approach"
        var startMs = 0L
        var phaseMs = 0L
        var soakReachedMs = -1L
        var approachMs = -1L
        var lastCrossMs = 0L
        var cycle = 0
        var above = false
        var lastUpMs = -1L
        var maxBt = -1e9
        var minBt = 1e9
        val periods = ArrayList<Double>()
        val amps = ArrayList<Double>()
    }
    private var at: Autotune? = null

    // ---- receptura
    private var recipe: JsonObject? = null
    private var phase: String? = null
    private var run = 0
    private var chargeMs: Long? = null
    private var chargeReady = false
    private var dropReady = false
    private var inBandMs = 0L
    private var preheatReachedMs: Long? = null
    private var overrideGas = false
    private val overrideVfd = BooleanArray(4)
    private var coolNoLinkMs = 0L

    // ---- zapalování
    private var ignAttempt = 0
    private var ignStep = ""
    private var ignStepStart = 0L
    private var ignStartBt = 0.0
    private var ignStartExh = 0.0
    private var reigniting = false
    private var burnSince = -1L
    private var startSp: Double? = null

    // ---- události a odesílání
    private var seq = 0L
    private val events = ArrayDeque<JsonObject>()
    private var deferred: MutableList<JsonObject>? = null
    private var statusAccumMs = 0L
    private var statusSimAccumMs = 0L
    private var gasBeforeReignite = 0.0
    private var dirty = true

    init {
        event("info", "boot", "start Portenty (simulator)") { put("boot_id", bootId); put("reset_reason", "power_on") }
        setState("BOOT_DELAY")
    }

    // =========================================================== spojení

    /** Nové TCP spojení. Vrací id spojení. */
    fun connect(out: (String) -> Unit, onClose: () -> Unit): Int {
        if (conns.values.any { !it.authed }) {
            // už čeká jiné nepřihlášené spojení – nové hned zavřít
            onClose()
            return -1
        }
        val c = Conn(nextConnId++, out, onClose)
        conns[c.id] = c
        return c.id
    }

    fun disconnect(id: Int) {
        val c = conns.remove(id) ?: return
        if (active === c) {
            active = null
            event("warn", "link", "tablet odpojen")
        }
    }

    private fun close(c: Conn) {
        conns.remove(c.id)
        if (active === c) active = null
        c.onClose()
    }

    private fun send(c: Conn, o: JsonObject) {
        try { c.out(o.toString()) } catch (_: Exception) { }
    }

    private fun sendActive(o: JsonObject) {
        val d = deferred
        if (d != null) { d.add(o); return }
        active?.let { send(it, o) }
    }

    fun receive(id: Int, line: String) {
        val c = conns[id] ?: return
        val limit = if (line.contains("\"recipe_load\"")) 4096 else 1024
        if (line.toByteArray(Charsets.UTF_8).size > limit) {
            send(c, ack(null, false, "too_long", "zprava je prilis dlouha"))
            return
        }
        val o = parseObject(line)
        if (o == null) {
            send(c, ack(null, false, "bad_json", "zpravu nelze precist"))
            return
        }
        val idEl = o["id"] as? JsonPrimitive
        val cmdId = if (idEl != null && !idEl.isString && !idEl.content.contains('.')) idEl.content.toLongOrNull() else null
        if (cmdId == null) {
            send(c, buildJsonObject { put("t", "ack"); put("id", JsonNull); put("ok", false); put("err", "missing"); put("field", "id") })
            return
        }
        val t = o.str("t")
        if (t == "hello") {
            handleHello(c, cmdId, o)
            return
        }
        if (!c.authed) {
            send(c, ack(cmdId, false, "not_auth", "nejdriv hello"))
            return
        }
        if (c === active) linkSilenceMs = 0L
        deferred = ArrayList()
        var extraMsg: JsonObject? = null
        val reply: JsonObject = try {
            val (extra, data) = dispatch(t, o, cmdId)
            extraMsg = data
            buildJsonObject {
                put("t", "ack"); put("id", cmdId); put("ok", true)
                extra?.forEach { (k, v) -> put(k, v) }
            }
        } catch (e: CmdError) {
            buildJsonObject {
                put("t", "ack"); put("id", cmdId); put("ok", false); put("err", e.err)
                e.msg?.let { put("msg", it) }
                e.field?.let { put("field", it) }
            }
        }
        val pending = deferred ?: emptyList<JsonObject>()
        deferred = null
        send(c, reply)
        extraMsg?.let { send(c, it) }
        pending.forEach { sendActive(it) }
        if (dirty) { sendStatus(); dirty = false }
    }

    private fun ack(id: Long?, ok: Boolean, err: String?, msg: String?) = buildJsonObject {
        put("t", "ack")
        if (id == null) put("id", JsonNull) else put("id", id)
        put("ok", ok)
        err?.let { put("err", it) }
        msg?.let { put("msg", it) }
    }

    private fun handleHello(c: Conn, id: Long, o: JsonObject) {
        if (c.authed) { send(c, helloAck(id)); return }
        val token = o.str("token")
        val proto = o.lng("proto")
        if (token == null || o.str("app") == null || proto == null) {
            send(c, buildJsonObject { put("t", "hello_ack"); put("id", id); put("ok", false); put("err", "missing") })
            authFail++
            close(c)
            return
        }
        val tokenOk = token.length in 8..32 && token.all { it.code in 0x21..0x7E } && token != BAD_TOKEN
        if (!tokenOk) {
            send(c, buildJsonObject { put("t", "hello_ack"); put("id", id); put("ok", false); put("err", "bad_token") })
            authFail++
            close(c)
            return
        }
        if (proto != PROTO.toLong()) {
            send(c, buildJsonObject { put("t", "hello_ack"); put("id", id); put("ok", false); put("err", "proto"); put("proto", PROTO) })
            authFail++
            close(c)
            return
        }
        c.authed = true
        val old = active
        if (old != null && old !== c) {
            send(old, buildJsonObject { put("t", "bye"); put("reason", "replaced") })
            close(old)
        }
        active = c
        connects++
        linkSilenceMs = 0L
        linkLost = false
        clientApp = o.str("app")
        clientVariant = o.str("variant")?.takeIf { it == "test" || it == "release" } ?: "release"
        send(c, helloAck(id))
        sendStatus()
        event("info", "link", "tablet pripojen: app $clientApp, varianta $clientVariant") {
            put("app", clientApp ?: ""); put("variant", clientVariant ?: "")
        }
    }

    private fun helloAck(id: Long) = buildJsonObject {
        put("t", "hello_ack"); put("id", id); put("ok", true)
        put("fw", FW); put("build", "simulator"); put("proto", PROTO); put("boot_id", bootId)
        put("sim_vfd", true); put("sim_temp", true)
        putJsonArray("caps") { CAPS.forEach { add(it) } }
    }

    // =========================================================== události a stav

    private fun event(level: String, code: String, msg: String, extra: JsonObjectBuilder.() -> Unit = {}) {
        seq++
        val e = buildJsonObject {
            put("t", "event"); put("seq", seq); put("ms", simMs); put("level", level); put("code", code); put("msg", msg)
            extra()
        }
        events.addLast(e)
        while (events.size > 128) events.removeFirst()
        sendActive(e)
    }

    private fun setState(s: String) {
        if (s == state) return
        val from = state
        state = s
        stateMs = 0L
        dirty = true
        event(if (s == "HALTED") "warn" else "info", "state", "stav: $from -> $s") { put("from", from); put("to", s) }
    }

    private fun burnerOff() {
        power = false; ignition = false; gas = 0.0; burning = false
        if (flame == 1) flame = 0
        interlock = 0
        reigniting = false
        ignStep = ""
        dirty = true
    }

    private fun recipeRunning() = phase == "preheat" || phase == "roast" || phase == "cooling"

    private fun endRecipe(how: String) {
        if (recipe != null && recipeRunning()) {
            phase = "loaded"
            chargeMs = null
            chargeReady = false
            dropReady = false
            event(if (how == "abort") "warn" else "info", "roast", if (how == "abort") "receptura prerusena" else "receptura ukoncena") {
                put("roast_event", how); put("roast_t", JsonNull); put("run", run)
            }
        }
    }

    private fun abortAutotune(msg: String) {
        if (at == null) return
        at = null
        mode = "manual"
        sendActive(buildJsonObject { put("t", "autotune_result"); put("ok", false); put("msg", msg) })
        event("warn", "autotune", "ladeni prerusene: $msg")
    }

    private fun halt(code: Int, msg: String, vfd: Int? = null, vfdAction: () -> Unit = {}) {
        burnerOff()
        if (code == 12) flame = 2
        abortAutotune("chyba $code")
        endRecipe("abort")
        mode = "manual"
        faultCode = code; faultVfd = vfd; faultMs = simMs; faultMsg = msg.take(64)
        vfdAction()
        setState("HALTED")
        event("error", "halted", msg) { put("fault", code); if (vfd == null) put("vfd", JsonNull) else put("vfd", vfd) }
    }

    private fun heatingState() = state in HEATING

    // =========================================================== krok simulace

    /** Posune simulaci: simDtMs = simulovaný čas (může být zrychlený), realDtMs = skutečný čas. */
    fun step(simDtMs: Long, realDtMs: Long) {
        simMs += simDtMs
        stateMs += simDtMs
        val dt = simDtMs / 1000.0

        // nepřihlášená spojení bez hello do 5 s zavřít
        conns.values.toList().forEach { c ->
            c.ageMs += realDtMs
            if (!c.authed && c.ageMs > PENDING_TIMEOUT_MS) { authFail++; close(c) }
        }

        updateVfds(dt)
        updateFlameModel()
        updateThermal(dt)
        updateRor()
        checkInputs()
        updateLink(realDtMs, dt)
        updateStateMachine()
        updateControl(dt)
        updateRecipe(dt)

        // jako firmware 2× za sekundu; při zrychlené simulaci častěji, aby mezi vzorky nebyly velké skoky
        statusAccumMs += realDtMs
        statusSimAccumMs += simDtMs
        if (dirty || statusAccumMs >= 500 || statusSimAccumMs >= 1000) {
            sendStatus()
            statusAccumMs = 0
            statusSimAccumMs = 0
            dirty = false
        }
    }

    private fun pctToHz(p: Double) = if (p <= 0) 0.0 else HZ_10 + (p - 10.0) / 90.0 * (HZ_100 - HZ_10)
    private fun hzToPct(h: Double) = if (h < HZ_10) h / HZ_10 * 10.0 else 10.0 + (h - HZ_10) / (HZ_100 - HZ_10) * 90.0
    private fun vfdResponds(i: Int) = powered && vfdFault[i] != "offline"
    private fun vfdPct(i: Int) = hzToPct(vfdHz[i])

    private fun updateVfds(dt: Double) {
        for (i in 0..3) {
            var target = if (powered) pctToHz(vfdCmd[i]) else 0.0
            if (vfdFault[i] == "nostop" && vfdCmd[i] <= 0) target = vfdHz[i]
            if (vfdFault[i] == "offline") target = vfdHz[i]
            val step = RAMP_HZ_S * dt
            vfdHz[i] = if (vfdHz[i] < target) min(target, vfdHz[i] + step) else max(target, vfdHz[i] - step)
        }
    }

    private fun updateFlameModel() {
        if (!power || gas <= 0.0 || flameFails) burning = false
        else if (ignition) burning = true
    }

    private fun updateThermal(dt: Double) {
        val heat = if (physicalFlame) gas else 0.0
        val airflow = vfdPct(1).coerceIn(0.0, 100.0) / 100.0
        val airTarget = 24.0 + heat * 4.0 * (1.0 - 0.25 * airflow)
        drumAir += (airTarget - drumAir) * dt / 25.0
        val tauBt = 70.0 + 110.0 * beans
        bt += ((drumAir - 15.0) - bt) * dt / tauBt
        // komín sleduje zrno, za plamene o něco výš; nikdy dál než ±10 °C
        val exhTarget = bt + (if (heat > 0) 6.0 else -2.0)
        exh += (exhTarget - exh) * dt / 15.0
        exh = exh.coerceIn(bt - 9.5, bt + 9.5)
        // okolí: kolem 25 °C, jen pomalý drift
        air += (25.0 - air) * dt / 120.0
    }

    private fun reading(ch: Int): Double? {
        if (holdActive[ch]) return holdValue[ch]
        val v = when (ch) { 0 -> bt; 1 -> air; else -> exh }
        val noise = if (ch == 1) 0.05 else 0.02
        return v + (rnd.nextGaussian() * noise)
    }

    private fun updateRor() {
        val b = reading(0)
        if (b == null) { btHist.clear(); ror = null; return }
        btHist.addLast(simMs to b)
        while (btHist.isNotEmpty() && simMs - btHist.first().first > (ROR_WINDOW_S * 1000).toLong()) btHist.removeFirst()
        val first = btHist.first()
        val span = (simMs - first.first) / 1000.0
        ror = if (span >= 10.0) (b - first.second) / span * 60.0 else null
    }

    private fun checkInputs() {
        if (state == "HALTED") return
        if (lossStop) halt(1, "E-stop (DI_0 bez 24 V)")
        else if (lossPsu) halt(2, "PSU (DI_4 bez 24 V)")
    }

    private fun updateLink(realDtMs: Long, dt: Double) {
        linkSilenceMs += realDtMs
        if (linkSilenceMs <= LINK_TIMEOUT_MS || linkLost) return
        linkLost = true
        active?.let { c ->
            send(c, buildJsonObject { put("t", "bye"); put("reason", "timeout") })
            close(c)
        }
        when {
            heatingState() -> {
                panics++
                event("error", "link", "vypadek spojeni za ohrevu – panika")
                halt(7, "vypadek spojeni (panika)") {
                    vfdCmd[0] = 100.0; vfdCmd[1] = 100.0
                }
            }
            phase == "cooling" -> {
                coolNoLinkMs = 0
                event("warn", "link", "vypadek spojeni pri chlazeni – chlazeni dobehne")
            }
            state == "READY" -> {
                for (i in 0..3) vfdCmd[i] = 0.0
                event("warn", "link", "vypadek spojeni – menice zastaveny")
            }
        }
    }

    private fun startIgnitionAttempt(window: Boolean) {
        ignStep = "power"
        ignStepStart = simMs
        power = true
        ignition = false
        dirty = true
        event("info", "ignition", "zapalovani pokus $ignAttempt/2") { put("attempt", ignAttempt); put("of", 2) }
        if (window) Unit
    }

    private fun updateStateMachine() {
        val s = stateMs / 1000.0
        when (state) {
            "BOOT" -> setState("BOOT_DELAY")
            "BOOT_DELAY" -> if (s >= 5.0) {
                for (i in 0..3) vfdCmd[i] = VFD_PREFLIGHT[i]
                setState("PREFLIGHT")
            }
            "PREFLIGHT" -> if (s >= 5.0) {
                val bad = (0..3).firstOrNull { !vfdResponds(it) || vfdPct(it) < VFD_PREFLIGHT[it] * 0.9 }
                for (i in 0..3) vfdCmd[i] = 0.0
                if (bad != null) halt(10, "menic ${VFD_NAMES[bad]} se v kontrole nerozjel", bad)
                else { stopConfirmAttempt = 1; setState("STOP_CONFIRM") }
            }
            "STOP_CONFIRM" -> if (s >= 8.0) {
                val moving = (0..3).firstOrNull { vfdHz[it] > 1.0 }
                if (moving == null) {
                    if (restartFlow) {
                        restartFlow = false
                        heatBlocked = true
                        setState("BOOT")
                    } else {
                        setState("READY")
                    }
                } else if (stopConfirmAttempt < 2) {
                    stopConfirmAttempt++
                    stateMs = 0
                    for (i in 0..3) vfdCmd[i] = 0.0
                } else {
                    halt(14, "menic ${VFD_NAMES[moving]} nezastavil", moving)
                }
            }
            "IGNITION_INTERLOCK" -> {
                val drumOk = vfdResponds(0) && vfdPct(0) >= 9.0
                val exhOk = vfdResponds(1) && vfdPct(1) >= 9.0
                if (drumOk && exhOk) {
                    interlock = 1
                    ignAttempt = 1
                    setState("IGNITION_SEQUENCE")
                    startIgnitionAttempt(false)
                } else if (s >= 10.0) {
                    interlock = 2
                    val bad = if (!drumOk) 0 else 1
                    halt(11, "${if (bad == 0) "buben" else "odtah"} se pred zapalenim nerozjel", bad) {
                        vfdCmd[bad] = 0.0
                        vfdCmd[1 - bad] = 100.0
                    }
                }
            }
            "IGNITION_SEQUENCE", "RUNNING" -> updateIgnition()
        }
    }

    private fun updateIgnition() {
        if (ignStep.isEmpty()) return
        val elapsed = ignStepTime()
        val window = if (reigniting) 30.0 else 15.0
        when (ignStep) {
            "power" -> if (elapsed >= 1.0) {
                // API 1.5: plyn ignition_gas_pct, DO_05 jen po dobu pulzu
                gas = IGNITION_GAS
                ignition = true
                ignStep = "pulse"; ignStepStart = simMs
                burnSince = -1L
                dirty = true
            }
            "pulse", "window" -> {
                if (burning && burnSince < 0) burnSince = simMs
                if (ignStep == "pulse" && elapsed >= IGNITION_PULSE_S) {
                    ignition = false
                    // po pulzu: studené zapálení 70 %, opakované podle režimu (ruční = plyn před reignite)
                    gas = if (reigniting) (if (mode == "manual") gasBeforeReignite else gasBeforeReignite.coerceIn(10.0, 100.0)) else IGNITION_AFTER_GAS
                    ignStep = "window"; ignStepStart = simMs
                    dirty = true
                    return
                }
                val confirmed = burning && burnSince >= 0 && (simMs - burnSince) / 1000.0 >= FLAME_DETECT_S
                if (confirmed) {
                    if (ignStep == "pulse") { ignition = false; gas = if (reigniting) gasBeforeReignite else IGNITION_AFTER_GAS }
                    flame = 1
                    ignStep = ""
                    if (reigniting) {
                        reigniting = false
                        event("info", "ignition", "opakovane zapaleni uspesne") { put("ok", true) }
                    } else {
                        event("info", "flame", "plamen potvrzen") { put("flame", 1) }
                        event("info", "ignition", "zapaleno") { put("ok", true) }
                        onLit()
                    }
                    dirty = true
                } else if (ignStep == "window" && elapsed >= window) {
                    if (ignAttempt >= 2) {
                        halt(12, "plamen nenaskocil (pokus 2/2)") { vfdCmd[0] = 100.0; vfdCmd[1] = 100.0 }
                    } else {
                        ignition = false
                        gas = 0.0
                        burning = false
                        if (!reigniting) power = false
                        ignStep = "purge"; ignStepStart = simMs
                        event("warn", "ignition", "plamen nenaskocil, proplach 5 s") { put("attempt", ignAttempt); put("of", 2); put("ok", false) }
                        dirty = true
                    }
                }
            }
            "purge" -> if (elapsed >= 5.0) {
                ignAttempt = 2
                if (reigniting) {
                    // DO_01 zůstává zapnutý – znovu od pulzu
                    ignStep = "power"; ignStepStart = simMs - 1000L
                    event("info", "ignition", "opakovane zapaleni pokus 2/2") { put("attempt", 2); put("of", 2) }
                } else startIgnitionAttempt(true)
            }
        }
    }

    private fun ignStepTime() = (simMs - ignStepStart) / 1000.0

    private fun onLit() {
        setState("RUNNING")
        val tune = at
        if (tune != null && tune.phase == "ignite") {
            mode = "manual"
            gas = IGNITION_AFTER_GAS
            atNextAfterIgnite(tune)
            return
        }
        if (phase == "preheat") {
            mode = "recipe"
            bumpless(recipeTarget() ?: sp)
        } else {
            mode = "manual"
            gas = IGNITION_AFTER_GAS
            val target = startSp
            startSp = null
            if (target != null) {
                if (reading(0) == null) {
                    event("warn", "mode", "neplatna teplota zrna – rucni rezim") { put("mode", "manual"); put("reason", "sensor") }
                } else {
                    sp = target
                    mode = "pid"
                    bumpless(sp)
                    event("info", "mode", "rezim: pid na ${sp}") { put("mode", "pid"); put("reason", "user") }
                }
            }
        }
    }

    // =========================================================== regulace

    private fun bumpless(target: Double) {
        val b = reading(0) ?: return
        integ = if (ki > 0) (gas - kp * (target - b)) / ki else 0.0
        lastBt = b
    }

    private fun recipeTarget(): Double? {
        val r = recipe ?: return null
        return when (phase) {
            "preheat" -> r.dbl("charge_bt")
            "roast" -> {
                val t = roastT() ?: return null
                if (t < (r.lng("pid_delay_s") ?: 0L)) null else curveAt(r, t)
            }
            else -> null
        }
    }

    private fun curveAt(r: JsonObject, t: Double): Double {
        val pts = r.arr("curve")?.mapNotNull { e ->
            val a = e as? JsonArray ?: return@mapNotNull null
            val pt = a.getOrNull(0).asDouble() ?: return@mapNotNull null
            val pv = a.getOrNull(1).asDouble() ?: return@mapNotNull null
            pt to pv
        } ?: return sp
        if (pts.isEmpty()) return sp
        if (t <= pts.first().first) return pts.first().second
        for (i in 1 until pts.size) {
            val (t0, v0) = pts[i - 1]
            val (t1, v1) = pts[i]
            if (t <= t1) return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
        }
        return pts.last().second
    }

    private fun roastT(): Double? = chargeMs?.let { (simMs - it) / 1000.0 }

    private fun preheatLeft(): Double? {
        if (phase != "preheat") return null
        val reached = preheatReachedMs ?: return null
        val hold = recipe?.dbl("preheat_s") ?: 60.0
        return max(0.0, hold - (simMs - reached) / 1000.0)
    }

    private fun currentSp(): Double? = when {
        at != null -> at!!.let { a -> when (a.phase) { "soak" -> a.soakSp; "ignite" -> a.soakSp.takeIf { a.soakS > 0 } ?: a.sp; else -> a.sp } }
        mode == "pid" -> sp
        mode == "recipe" -> recipeTarget() ?: (if (phase == "roast") null else sp)
        else -> null
    }

    private fun updateControl(dt: Double) {
        if (state != "RUNNING" || flame != 1 || dt <= 0 || ignStep.isNotEmpty()) return
        val b = reading(0)
        val tune = at
        if (tune != null) { if (tune.phase != "ignite") updateAutotune(tune, b, dt); return }
        if (b == null && (mode == "pid" || mode == "recipe")) {
            mode = "manual"
            event("warn", "mode", "neplatna teplota zrna – rucni rezim") { put("mode", "manual"); put("reason", "sensor") }
            dirty = true
            return
        }
        when (mode) {
            "pid" -> pidStep(sp, b!!, dt)
            "recipe" -> {
                val r = recipe
                if (phase == "roast" && r != null && (roastT() ?: 0.0) < (r.lng("pid_delay_s") ?: 0L)) {
                    gas = r.dbl("charge_gas") ?: gas
                    integ = if (ki > 0) (gas - kp * ((recipeTargetNoDelay() ?: b!!) - b!!)) / ki else 0.0
                    lastBt = b
                } else {
                    val target = recipeTarget()
                    if (target != null) pidStep(target, b!!, dt)
                }
            }
        }
    }

    private fun recipeTargetNoDelay(): Double? {
        val r = recipe ?: return null
        val t = roastT() ?: return null
        return curveAt(r, t)
    }

    private fun pidStep(target: Double, b: Double, dt: Double) {
        val err = target - b
        val rawD = lastBt?.let { -(b - it) / dt } ?: 0.0
        lastBt = b
        dFilt += (rawD - dFilt) * min(1.0, dt / 3.0)
        val deriv = dFilt
        val newInteg = integ + err * dt
        var out = kp * err + ki * newInteg + kd * deriv
        if (out in 10.0..100.0) integ = newInteg
        out = out.coerceIn(10.0, 100.0)
        // status se posílá jen periodicky (jako firmware) – změna plynu sama o sobě status nevyvolá
        gas = r1(out)
    }

    private fun atPhase(a: Autotune, ph: String, msg: String) {
        a.phase = ph
        a.phaseMs = simMs
        event("info", "autotune", msg) { put("phase", ph); put("cycle", a.cycle); put("of", a.cycles) }
        dirty = true
    }

    private fun atNextAfterIgnite(a: Autotune) {
        if (a.soakS > 0) {
            bumpless(a.soakSp)
            atPhase(a, "soak", "ladeni: prohrati bubnu na ${a.soakSp}")
        } else {
            bumpless(a.sp)
            a.approachMs = simMs
            atPhase(a, "approach", "ladeni: najeti na cil ${a.sp}")
        }
    }

    private fun soakLeft(a: Autotune): Double? {
        if (a.phase != "soak") return null
        if (a.soakReachedMs < 0) return a.soakS
        return max(0.0, a.soakS - (simMs - a.soakReachedMs) / 1000.0)
    }

    private fun updateAutotune(a: Autotune, b: Double?, dt: Double) {
        when {
            b == null -> { abortAutotune("neplatna teplota zrna"); return }
            b > max(a.sp, a.soakSp) + 25.0 -> { abortAutotune("prekrocena teplota o vic nez 25 °C nad cil"); return }
            a.approachMs >= 0 && (simMs - a.approachMs) > 1_800_000L -> { abortAutotune("ladeni trvalo dele nez 30 min"); return }
        }
        val v = b!!
        when (a.phase) {
            "soak" -> {
                pidStep(a.soakSp, v, dt)
                if (a.soakReachedMs < 0 && abs(v - a.soakSp) <= 3.0) {
                    a.soakReachedMs = simMs
                    event("info", "autotune", "ladeni: teplota prohrati dosazena") { put("phase", "soak"); put("cycle", 0); put("of", a.cycles) }
                }
                if ((soakLeft(a) ?: 1.0) <= 0.0) {
                    bumpless(a.sp)
                    a.approachMs = simMs
                    atPhase(a, "approach", "ladeni: najeti na cil ${a.sp}")
                }
                return
            }
            "approach" -> {
                pidStep(a.sp, v, dt)
                if (abs(v - a.sp) <= 1.0) {
                    a.above = v >= a.sp
                    a.maxBt = v; a.minBt = v
                    a.lastCrossMs = simMs
                    atPhase(a, "oscillate", "ladeni: kmity")
                } else if (simMs - a.phaseMs > 600_000L) {
                    abortAutotune("teplota nedosahla cile")
                }
                return
            }
        }
        // oscillate
        a.maxBt = max(a.maxBt, v); a.minBt = min(a.minBt, v)
        if (a.above && v < a.sp - 0.5) {
            a.above = false
            a.lastCrossMs = simMs
        } else if (!a.above && v > a.sp + 0.5) {
            a.above = true
            a.lastCrossMs = simMs
            if (a.lastUpMs >= 0) {
                a.periods.add((simMs - a.lastUpMs) / 1000.0)
                a.amps.add((a.maxBt - a.minBt) / 2.0)
                a.cycle++
                event("info", "autotune", "ladeni: cyklus ${a.cycle}/${a.cycles}") { put("phase", "oscillate"); put("cycle", a.cycle); put("of", a.cycles) }
                dirty = true
            }
            a.lastUpMs = simMs
            a.maxBt = v; a.minBt = v
        }
        if (simMs - a.lastCrossMs > 480_000L) {
            abortAutotune(if (a.above) "min. plyn je prilis vysoko – sniz gas_low" else "max. plyn nestaci na cil – zvys gas_high")
            return
        }
        gas = if (a.above) a.low else a.high
        if (a.cycle >= a.cycles && a.periods.isNotEmpty()) {
            val tu = a.periods.average()
            val amp = max(0.1, a.amps.average())
            val d = (a.high - a.low) / 2.0
            val ku = 4.0 * d / (PI * amp)
            val pkp = 0.6 * ku
            val pki = 1.2 * ku / tu
            val pkd = 0.075 * ku * tu
            at = null
            mode = "manual"
            sendActive(buildJsonObject {
                put("t", "autotune_result"); put("ok", true)
                put("kp", round2(pkp)); put("ki", round4(pki)); put("kd", round2(pkd)); put("ku", round2(ku)); put("tu_s", r1(tu))
            })
            event("info", "autotune", "ladeni dokonceno") { put("phase", "done"); put("cycle", a.cycle); put("of", a.cycles) }
            dirty = true
        }
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round4(v: Double) = Math.round(v * 10000.0) / 10000.0

    // =========================================================== receptura

    private fun applyVfdRow(row: List<Double>) {
        for (i in 0..3) {
            if (overrideVfd[i]) continue
            val v = row.getOrNull(i + 1) ?: continue
            if (vfdCmd[i] != v) { vfdCmd[i] = v; dirty = true }
        }
    }

    private fun vfdRowAt(r: JsonObject, t: Double): List<Double>? {
        val rows = r.arr("vfd")?.mapNotNull { e -> (e as? JsonArray)?.map { it.asDouble() ?: 0.0 } } ?: return null
        return rows.lastOrNull { it[0] <= t } ?: rows.firstOrNull()
    }

    private fun updateRecipe(dt: Double) {
        val r = recipe ?: return
        when (phase) {
            "preheat" -> {
                if (state == "RUNNING" && !chargeReady) {
                    val b = reading(0)
                    val target = r.dbl("charge_bt") ?: return
                    val inBand = b != null && abs(b - target) <= 3.0
                    if (inBand && preheatReachedMs == null) {
                        preheatReachedMs = simMs
                        dirty = true
                        event("info", "roast", "teplota predehrevu dosazena – vydrz") { put("roast_event", "preheat_hold"); put("roast_t", JsonNull); put("run", run) }
                    }
                    val left = preheatLeft()
                    if (inBand && left != null && left <= 0.0) {
                        chargeReady = true
                        dirty = true
                        event("info", "roast", "predehrev hotovy – vsyp zrno") { put("roast_event", "charge_ready"); put("roast_t", JsonNull); put("run", run) }
                    }
                }
            }
            "roast" -> {
                val t = roastT() ?: return
                vfdRowAt(r, t)?.let { applyVfdRow(it) }
                if (!dropReady) {
                    val drop = r.obj("drop")
                    val b = reading(0)
                    val dbt = drop?.dbl("bt") ?: 999.0
                    val tmax = drop?.dbl("t_max") ?: 1e9
                    if ((b != null && b >= dbt) || t >= tmax) {
                        dropReady = true
                        dirty = true
                        event("info", "roast", "pripraveno k vysypani") { put("roast_event", "drop_ready"); put("roast_t", r1(t)); put("run", run) }
                    }
                }
            }
            "cooling" -> {
                val cool = r.obj("cool")
                if (!overrideVfd[2]) vfdCmd[2] = cool?.dbl("cooler") ?: vfdCmd[2]
                if (!overrideVfd[3]) vfdCmd[3] = cool?.dbl("coolfan") ?: vfdCmd[3]
                if (linkLost) {
                    coolNoLinkMs += (dt * 1000).toLong()
                    if (coolNoLinkMs >= (COOL_HOLD_S * 1000).toLong()) {
                        for (i in 0..3) vfdCmd[i] = 0.0
                        endRecipe("end")
                        event("warn", "link", "chlazeni bez spojeni skoncilo – menice zastaveny")
                    }
                }
            }
        }
    }

    // =========================================================== příkazy

    private fun requireState(cond: Boolean, msg: String) {
        if (!cond) throw CmdError("bad_state", msg)
    }

    private fun num(o: JsonObject, key: String, min: Double, max: Double, required: Boolean = true): Double? {
        val e = o[key]
        if (e == null || e is JsonNull) {
            if (required) throw CmdError("missing", "chybi $key", key) else return null
        }
        val p = e as? JsonPrimitive ?: throw CmdError("out_of_range", "$key ma spatny typ", key)
        if (p.isString) throw CmdError("out_of_range", "$key ma spatny typ", key)
        val v = p.content.toDoubleOrNull() ?: throw CmdError("out_of_range", "$key ma spatny typ", key)
        if (v < min || v > max) throw CmdError("out_of_range", "$key mimo $min..$max", key)
        return v
    }

    private fun int(o: JsonObject, key: String, min: Int, max: Int): Int {
        val p = o[key] as? JsonPrimitive ?: throw CmdError("missing", "chybi $key", key)
        if (p is JsonNull) throw CmdError("missing", "chybi $key", key)
        if (p.isString || p.content.contains('.')) throw CmdError("out_of_range", "$key musi byt cele cislo", key)
        val v = p.content.toIntOrNull() ?: throw CmdError("out_of_range", "$key musi byt cele cislo", key)
        if (v < min || v > max) throw CmdError("out_of_range", "$key mimo $min..$max", key)
        return v
    }

    private fun boolParam(o: JsonObject, key: String, required: Boolean): Boolean? {
        val e = o[key] ?: if (required) throw CmdError("missing", "chybi $key", key) else return null
        val p = e as? JsonPrimitive ?: throw CmdError("out_of_range", "$key musi byt true/false", key)
        if (p.isString) throw CmdError("out_of_range", "$key musi byt true/false", key)
        return when (p.content) { "true" -> true; "false" -> false; else -> throw CmdError("out_of_range", "$key musi byt true/false", key) }
    }

    /** Vrací doplňková pole pro ack a případnou datovou zprávu. */
    private fun dispatch(t: String?, o: JsonObject, id: Long): Pair<JsonObject?, JsonObject?> {
        return when (t) {
            "ping" -> null to null
            "get_diag" -> null to diag(id)
            "event_log" -> cmdEventLog(o)
            "vfd_set" -> cmdVfdSet(o) to null
            "heat_start" -> { cmdHeatStart(o); null to null }
            "heat_stop" -> { cmdHeatStop(); null to null }
            "gas_set" -> { cmdGasSet(o); null to null }
            "reignite" -> { cmdReignite(); null to null }
            "restart" -> { cmdRestart(); null to null }
            "mode_set" -> { cmdModeSet(o); null to null }
            "sp_set" -> { cmdSpSet(o); null to null }
            "pid_get" -> null to pidMsg(id)
            "pid_set" -> { cmdPidSet(o); null to null }
            "autotune_start" -> { cmdAutotuneStart(o); null to null }
            "autotune_abort" -> { requireState(at != null, "ladeni neprobiha"); abortAutotune("preruseno obsluhou"); null to null }
            "recipe_load" -> { cmdRecipeLoad(o); null to null }
            "recipe_get" -> null to buildJsonObject { put("t", "recipe"); put("id", id); put("recipe", recipe ?: JsonNull) }
            "recipe_clear" -> {
                requireState(phase == "loaded", "receptura neni nahrana nebo bezi")
                recipe = null; phase = null; dirty = true
                null to null
            }
            "recipe_start" -> { cmdRecipeStart(); null to null }
            "roast_event" -> { cmdRoastEvent(o); null to null }
            "recipe_end" -> {
                requireState(phase == "cooling", "recipe_end jen pri chlazeni")
                endRecipe("end"); dirty = true
                null to null
            }
            "sim_flame" -> {
                flameFails = boolParam(o, "fails", true)!!
                event("info", "sim", "sim: plamen ${if (flameFails) "nechyti" else "normalne"}") { put("what", "flame") }
                dirty = true
                null to null
            }
            "sim_temp" -> { cmdSimTemp(o); null to null }
            "sim_vfd" -> { cmdSimVfd(o); null to null }
            "sim_di" -> {
                boolParam(o, "stop", false)?.let { lossStop = !it }
                boolParam(o, "psu", false)?.let { lossPsu = !it }
                event("info", "sim", "sim: vstupy STOP ${if (lossStop) "ztrata" else "ok"}, PSU ${if (lossPsu) "ztrata" else "ok"}") { put("what", "di") }
                dirty = true
                null to null
            }
            "sim_reset" -> {
                flameFails = false; lossStop = false; lossPsu = false
                for (i in 0..2) { holdActive[i] = false; holdValue[i] = null }
                for (i in 0..3) { vfdFault[i] = "ok"; vfdErrCode[i] = 0 }
                event("info", "sim", "sim: vsechny poruchy zruseny") { put("what", "reset") }
                dirty = true
                null to null
            }
            else -> throw CmdError("unknown_cmd", "neznamy prikaz ${t ?: ""}")
        }
    }

    private fun cmdEventLog(o: JsonObject): Pair<JsonObject?, JsonObject?> {
        val since = num(o, "since", 0.0, Double.MAX_VALUE)!!.toLong()
        val list = events.filter { (it.lng("seq") ?: 0L) > since }
        val oldest = events.firstOrNull()?.lng("seq") ?: (seq + 1)
        val lost = since + 1 < oldest && seq > since
        list.forEach { e -> deferred?.add(buildJsonObject { e.forEach { (k, v) -> put(k, v) }; put("replay", true) }) }
        return buildJsonObject { put("count", list.size); put("lost", lost) } to null
    }

    private fun cmdVfdSet(o: JsonObject): JsonObject {
        val i = int(o, "vfd", 0, 3)
        var pct = num(o, "pct", 0.0, 1000.0)!!
        if (pct > 0 && pct < 10) pct = 10.0
        if (pct > 100) pct = 100.0
        pct = r1(pct)
        val ign = state == "IGNITION_INTERLOCK" || state == "IGNITION_SEQUENCE"
        requireState(state == "READY" || state == "RUNNING" || (ign && i >= 2), "vfd_set v tomto stavu nelze")
        if (heatingState() && i <= 1 && pct == 0.0) {
            event("warn", "vfd", "za ohrevu nelze zastavit ${if (i == 0) "buben" else "odtah"}") { put("vfd", i); put("err", 0) }
        } else {
            vfdCmd[i] = pct
            if (recipeRunning()) overrideVfd[i] = true
            dirty = true
        }
        return buildJsonObject { put("pct", pct) }
    }

    private fun startHeat() {
        for (i in 0..1) if (vfdCmd[i] <= 0.0) vfdCmd[i] = VFD_DEFAULT[i]
        interlock = 0
        flame = 0
        event("info", "heat", "pozadavek na ohrev")
        setState("IGNITION_INTERLOCK")
    }

    private fun cmdHeatStart(o: JsonObject) {
        requireState(state == "READY", "heat_start jen ve stavu READY")
        if (at != null) throw CmdError("busy", "probiha ladeni")
        requireState(!heatBlocked, "ohrev je po restartu zablokovany – nejdriv heat_stop")
        requireState(!recipeRunning(), "bezi receptura")
        val target = num(o, "sp", 50.0, 260.0, required = false)
        mode = "manual"
        startSp = target?.let { r1(it) }
        startHeat()
    }

    private fun cmdHeatStop() {
        abortAutotune("heat_stop")
        val wasHeating = heatingState()
        burnerOff()
        flame = 0
        mode = "manual"
        startSp = null
        heatBlocked = false
        endRecipe("abort")
        if (wasHeating) setState("READY")
        event("info", "heat", "ohrev zrusen")
        dirty = true
    }

    private fun cmdGasSet(o: JsonObject) {
        if (at != null) throw CmdError("busy", "probiha ladeni")
        requireState(state == "RUNNING", "gas_set jen ve stavu RUNNING")
        val pct = num(o, "pct", 10.0, 100.0)!!
        gas = r1(pct)
        if (mode != "manual") {
            mode = "manual"
            event("info", "mode", "rucni rezim (zasah obsluhy)") { put("mode", "manual"); put("reason", "override") }
        }
        if (recipeRunning()) overrideGas = true
        dirty = true
    }

    private fun cmdReignite() {
        requireState(state == "RUNNING", "reignite jen ve stavu RUNNING")
        requireState(ignStep.isEmpty(), "zapalovani uz probiha")
        reigniting = true
        ignAttempt = 1
        gasBeforeReignite = gas
        // API 8.2: burner.flame zůstává během opakovaného zapálení 1, DO_01 zůstává zapnutý
        event("info", "ignition", "opakovane zapaleni pokus 1/2") { put("attempt", 1); put("of", 2) }
        ignStep = "power"; ignStepStart = simMs - 1000L
        dirty = true
    }

    private fun cmdRestart() {
        requireState(state == "HALTED", "restart jen ve stavu HALTED")
        requireState(!lossStop && !lossPsu, "STOP nebo PSU neni v poradku")
        faultCode = 0; faultVfd = null; faultMs = null; faultMsg = null
        if (flame == 2) flame = 0
        for (i in 0..3) vfdCmd[i] = 0.0
        restartFlow = true
        stopConfirmAttempt = 1
        setState("STOP_CONFIRM")
    }

    private fun cmdModeSet(o: JsonObject) {
        val m = o.str("mode") ?: throw CmdError("missing", "chybi mode", "mode")
        if (m !in setOf("manual", "pid", "recipe")) throw CmdError("out_of_range", "neznamy rezim", "mode")
        if (at != null) throw CmdError("busy", "probiha ladeni")
        requireState(state == "RUNNING", "mode_set jen ve stavu RUNNING")
        if (m == "recipe") requireState(phase == "preheat" || phase == "roast", "rezim receptura jen pri bezici recepture")
        if (m != "manual") requireState(reading(0) != null, "neplatna teplota zrna")
        mode = m
        when (m) {
            "manual" -> if (recipeRunning()) overrideGas = true
            "pid" -> { bumpless(sp); if (recipeRunning()) overrideGas = true }
            "recipe" -> { overrideGas = false; bumpless(recipeTarget() ?: sp) }
        }
        event("info", "mode", "rezim: $m") { put("mode", m); put("reason", "user") }
        dirty = true
    }

    private fun cmdSpSet(o: JsonObject) {
        requireState(mode != "recipe", "v rezimu receptura nelze menit cil")
        sp = r1(num(o, "bt", 50.0, 260.0)!!)
        if (mode == "pid") bumpless(sp)
        dirty = true
    }

    private fun pidMsg(id: Long) = buildJsonObject {
        put("t", "pid"); put("id", id); put("kp", kp); put("ki", ki); put("kd", kd)
        put("saved", kp == savedKp && ki == savedKi && kd == savedKd)
    }

    private fun cmdPidSet(o: JsonObject) {
        if (at != null) throw CmdError("busy", "probiha ladeni")
        val nkp = num(o, "kp", 0.0, 100.0)!!
        val nki = num(o, "ki", 0.0, 10.0)!!
        val nkd = num(o, "kd", 0.0, 1000.0)!!
        val save = boolParam(o, "save", false) ?: false
        if (save && simMs - lastSaveMs < 10_000) throw CmdError("busy", "ulozeni nejvys 1x za 10 s")
        kp = nkp; ki = nki; kd = nkd
        if (save) { savedKp = kp; savedKi = ki; savedKd = kd; lastSaveMs = simMs }
        if (mode == "pid") bumpless(sp)
        event("info", "pid", "PID kp=$kp ki=$ki kd=$kd${if (save) " ulozeno" else ""}") {
            put("kp", kp); put("ki", ki); put("kd", kd); put("saved", kp == savedKp && ki == savedKi && kd == savedKd)
        }
    }

    private fun cmdAutotuneStart(o: JsonObject) {
        requireState(state == "RUNNING" || state == "READY", "ladeni jen ve stavu READY nebo RUNNING")
        if (at != null) throw CmdError("busy", "ladeni uz probiha")
        requireState(!recipeRunning(), "bezi receptura")
        if (state == "READY") requireState(!heatBlocked, "ohrev je po restartu zablokovany – nejdriv heat_stop")
        val tsp = num(o, "sp", 100.0, 240.0)!!
        val low = num(o, "gas_low", 10.0, 90.0)!!
        val high = num(o, "gas_high", 20.0, 100.0)!!
        val cycles = int(o, "cycles", 2, 6)
        val soakSp = num(o, "soak_sp", 50.0, 260.0, required = false) ?: tsp
        val soakS = num(o, "soak_s", 0.0, 3600.0, required = false) ?: 0.0
        if (low > high - 10) throw CmdError("out_of_range", "gas_low musi byt aspon o 10 mensi nez gas_high", "gas_low")
        val a = Autotune(tsp, low, high, cycles, r1(soakSp), soakS).also { it.startMs = simMs; it.phaseMs = simMs }
        at = a
        event("info", "autotune", "ladeni zahajeno") { put("phase", if (state == "READY") "ignite" else "soak"); put("cycle", 0); put("of", cycles) }
        if (state == "READY") {
            a.phase = "ignite"
            mode = "manual"
            startSp = null
            startHeat()
        } else {
            mode = "manual"
            atNextAfterIgnite(a)
        }
        dirty = true
    }

    private fun cmdRecipeLoad(o: JsonObject) {
        requireState(state == "READY" || state == "HALTED", "recipe_load jen ve stavu READY nebo HALTED")
        requireState(!recipeRunning(), "bezi receptura")
        val r = o.obj("recipe") ?: throw CmdError("missing", "chybi recipe", "recipe")
        val err = RecipeValidator.validate(r)
        if (err != null) throw CmdError("bad_recipe", err.msg, err.field)
        recipe = r
        phase = "loaded"
        chargeMs = null
        event("info", "roast", "receptura nahrana: ${r.str("name")}") { put("roast_event", "loaded"); put("roast_t", JsonNull); put("run", run) }
        dirty = true
    }

    private fun cmdRecipeStart() {
        requireState(state == "READY", "recipe_start jen ve stavu READY")
        if (at != null) throw CmdError("busy", "probiha ladeni")
        requireState(!heatBlocked, "ohrev je po restartu zablokovany – nejdriv heat_stop")
        requireState(phase == "loaded" && recipe != null, "receptura neni nahrana")
        run++
        phase = "preheat"
        chargeReady = false; dropReady = false; inBandMs = 0; chargeMs = null; preheatReachedMs = null
        overrideGas = false
        for (i in 0..3) overrideVfd[i] = false
        beans = 0.0
        vfdRowAt(recipe!!, 0.0)?.let { applyVfdRow(it) }
        event("info", "roast", "receptura spustena") { put("roast_event", "start"); put("roast_t", JsonNull); put("run", run) }
        startHeat()
    }

    private fun cmdRoastEvent(o: JsonObject) {
        val e = o.str("event") ?: throw CmdError("missing", "chybi event", "event")
        if (e !in setOf("charge", "dry_end", "fc_start", "fc_end", "sc_start", "drop")) throw CmdError("out_of_range", "neznama udalost", "event")
        when (e) {
            "charge" -> {
                requireState(phase == "preheat" && state == "RUNNING", "vsypani jen pri predehrevu po zapaleni")
                if (!chargeReady) event("warn", "cmd", "vsypano pred dokoncenim predehrevu") { put("cmd", "roast_event") }
                chargeMs = simMs
                phase = "roast"
                chargeReady = false
                bt = bt * 0.45 + 24.0 * 0.55
                drumAir -= 30.0
                exh = exh.coerceIn(bt - 9.5, bt + 9.5)
                beans = 1.0
                btHist.clear()
                if (!overrideGas && mode == "recipe") gas = recipe?.dbl("charge_gas") ?: gas
                event("info", "roast", "vsypano") { put("roast_event", "charge"); put("roast_t", 0.0); put("run", run) }
            }
            "drop" -> {
                requireState(phase == "roast", "vysypani jen pri prazeni")
                val t = roastT() ?: 0.0
                burnerOff()
                flame = 0
                mode = "manual"
                phase = "cooling"
                dropReady = false
                beans = 0.0
                coolNoLinkMs = 0
                setState("READY")
                event("info", "roast", "vysypano") { put("roast_event", "drop"); put("roast_t", r1(t)); put("run", run) }
            }
            else -> {
                requireState(phase == "roast", "znacky jen pri prazeni")
                val t = roastT() ?: 0.0
                event("info", "roast", e) { put("roast_event", e); put("roast_t", r1(t)); put("run", run) }
            }
        }
        dirty = true
    }

    private fun cmdSimTemp(o: JsonObject) {
        val ch = int(o, "ch", 0, 2)
        if (!o.containsKey("c")) throw CmdError("missing", "chybi c", "c")
        val cEl = o["c"]
        val c: Double? = if (cEl is JsonNull) null else num(o, "c", -20.0, 400.0)
        val hold = boolParam(o, "hold", false) ?: true
        if (hold) {
            holdActive[ch] = true
            holdValue[ch] = c
        } else {
            holdActive[ch] = false
            holdValue[ch] = null
            if (c != null) when (ch) { 0 -> bt = c; 1 -> air = c; else -> exh = c }
        }
        val valid = !(hold && c == null)
        event(if (valid) "info" else "warn", "sensor", "sim: kanal $ch ${if (!valid) "neplatny" else if (hold) "drzi ${c}" else "model"}") {
            put("ch", ch); put("valid", valid)
        }
        dirty = true
    }

    private fun cmdSimVfd(o: JsonObject) {
        val i = int(o, "vfd", 0, 3)
        val f = o.str("fault") ?: throw CmdError("missing", "chybi fault", "fault")
        if (f !in setOf("nostop", "offline", "err", "ok")) throw CmdError("out_of_range", "neznama porucha", "fault")
        vfdFault[i] = f
        vfdErrCode[i] = if (f == "err") int(o, "code", 1, 255) else 0
        event("info", "sim", "sim: menic ${VFD_NAMES[i]} $f") { put("what", "vfd") }
        if (f == "offline") event("warn", "vfd", "menic ${VFD_NAMES[i]} neodpovida") { put("vfd", i); put("err", JsonNull) }
        dirty = true
    }

    // =========================================================== zprávy

    private fun diag(id: Long) = buildJsonObject {
        put("t", "diag"); put("id", id); put("fw", FW); put("build", "simulator"); put("proto", PROTO)
        put("boot_id", bootId); put("reset_reason", "power_on"); put("uptime_ms", simMs)
        put("ip", "vestaveny simulator"); put("mac", "00:00:00:00:00:00")
        put("sim_vfd", true); put("sim_temp", true)
        putJsonObject("client") { put("app", clientApp ?: ""); put("variant", clientVariant ?: "") }
        putJsonObject("link") { put("connects", connects); put("panics", panics); put("auth_fail", authFail) }
        putJsonArray("vfd_last_err") {
            for (i in 0..3) add(
                when (vfdFault[i]) {
                    "offline" -> "SIM: menic neodpovida"
                    "nostop" -> "SIM: menic nezastavi"
                    "err" -> "SIM: chyba ${vfdErrCode[i]}"
                    else -> "-"
                }
            )
        }
        putJsonObject("pid") { put("kp", kp); put("ki", ki); put("kd", kd); put("saved", kp == savedKp && ki == savedKi && kd == savedKd) }
        putJsonObject("config") {
            putJsonArray("vfd_default_pct") { VFD_DEFAULT.forEach { add(it.toInt()) } }
            putJsonArray("vfd_preflight_pct") { VFD_PREFLIGHT.forEach { add(it.toInt()) } }
            putJsonArray("vfd_rpm_10") { add(35); add(JsonNull); add(JsonNull); add(JsonNull) }
            putJsonArray("vfd_rpm_100") { add(70); add(JsonNull); add(JsonNull); add(JsonNull) }
            put("ignition_gas_pct", IGNITION_GAS); put("ignition_after_gas_pct", IGNITION_AFTER_GAS); put("ignition_pulse_s", IGNITION_PULSE_S.toInt())
            put("ignition_window_s", 15); put("reignite_window_s", 30)
            putJsonArray("temp_period_ms") { add(250); add(15000); add(2000) }
            put("sp_default", SP_DEFAULT)
            put("link_timeout_s", 15); put("cool_hold_s", COOL_HOLD_S.toInt()); put("ror_window_s", ROR_WINDOW_S.toInt()); put("status_period_ms", 500)
        }
    }

    private fun sendStatus() {
        val c = active ?: return
        if (!c.authed) return
        send(c, statusJson())
    }

    fun statusJson(): JsonObject = buildJsonObject {
        put("t", "status"); put("ms", simMs); put("ev_seq", seq); put("state", state)
        putJsonObject("fault") {
            put("code", faultCode)
            if (faultVfd == null) put("vfd", JsonNull) else put("vfd", faultVfd)
            if (faultMs == null) put("ms", JsonNull) else put("ms", faultMs)
            if (faultMsg == null) put("msg", JsonNull) else put("msg", faultMsg)
        }
        putJsonObject("di") { put("stop", !lossStop); put("psu", !lossPsu) }
        putJsonObject("temp") {
            for ((k, ch) in listOf("bt" to 0, "air" to 1, "exh" to 2)) {
                val v = reading(ch)
                if (v == null) put(k, JsonNull) else put(k, r1(v))
            }
            val r = ror
            if (r == null) put("bt_ror", JsonNull) else put("bt_ror", r1(r))
        }
        putJsonObject("burner") {
            put("power", power); put("ignition", ignition); put("gas", r1(if (power) gas else 0.0)); put("flame", flame); put("interlock", interlock)
        }
        put("heat_blocked", heatBlocked)
        putJsonArray("vfd") {
            for (i in 0..3) addJsonObject {
                put("name", VFD_NAMES[i])
                put("cmd", r1(vfdCmd[i]))
                if (vfdResponds(i)) {
                    put("pct", r1(vfdPct(i)))
                    put("hz", r1(vfdHz[i]))
                    if (i == 0) put("rpm", r1(vfdHz[i] * 56.0 / 50.0)) else put("rpm", JsonNull)
                    put("err", vfdErrCode[i])
                } else {
                    put("pct", JsonNull); put("hz", JsonNull); put("rpm", JsonNull); put("err", JsonNull)
                }
            }
        }
        putJsonObject("ctrl") {
            put("mode", mode)
            val s = currentSp()
            if (s == null) put("sp", JsonNull) else put("sp", r1(s))
            if (power && flame == 1) put("out", r1(gas)) else put("out", JsonNull)
            val a = at
            if (a == null) put("autotune", JsonNull) else putJsonObject("autotune") {
                put("phase", a.phase); put("cycle", a.cycle); put("of", a.cycles); put("elapsed_s", r1((simMs - a.startMs) / 1000.0))
                val left = soakLeft(a)
                if (left == null) put("phase_left_s", JsonNull) else put("phase_left_s", r1(left))
            }
        }
        val r = recipe
        if (r == null) put("roast", JsonNull) else putJsonObject("roast") {
            put("run", run)
            put("phase", phase ?: "loaded")
            put("recipe", r.str("name") ?: "")
            val rid = r.str("id")
            if (rid == null) put("recipe_id", JsonNull) else put("recipe_id", rid)
            val t = roastT()
            if (t == null || (phase != "roast" && phase != "cooling")) put("t", JsonNull) else put("t", r1(t))
            val pl = preheatLeft()
            if (pl == null) put("preheat_left_s", JsonNull) else put("preheat_left_s", r1(pl))
            put("charge_ready", phase == "preheat" && chargeReady)
            put("drop_ready", phase == "roast" && dropReady)
            putJsonObject("override") {
                put("gas", overrideGas)
                putJsonArray("vfd") { overrideVfd.forEach { add(it) } }
            }
        }
        putJsonObject("sim") {
            put("vfd", true); put("temp", true); put("flame_fails", flameFails)
            putJsonArray("temp_hold") {
                for (i in 0..2) {
                    when {
                        !holdActive[i] -> add(JsonNull)
                        holdValue[i] == null -> add(false)
                        else -> add(holdValue[i]!!)
                    }
                }
            }
            putJsonArray("vfd_fault") { for (i in 0..3) add(if (vfdFault[i] == "err") "err:${vfdErrCode[i]}" else vfdFault[i]) }
            putJsonObject("di_loss") { put("stop", lossStop); put("psu", lossPsu) }
        }
    }
}
