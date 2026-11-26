package cz.prazicka.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Konstanty protokolu podle API.md (proto 1). */
object Proto {
    const val VERSION = 1
    const val PORT = 5020
    const val DISCOVERY_PORT = 5021
    const val LINK_TIMEOUT_S = 15
}

enum class RoasterState(val label: String) {
    BOOT("Start"),
    BOOT_DELAY("Start"),
    PREFLIGHT("Kontrola měničů"),
    STOP_CONFIRM("Kontrola zastavení"),
    READY("Připraveno"),
    IGNITION_INTERLOCK("Rozjezd bubnu"),
    IGNITION_SEQUENCE("Zapalování"),
    RUNNING("Ohřev"),
    HALTED("Chyba"),
    UNKNOWN("Neznámý stav");

    val heating: Boolean get() = this == IGNITION_INTERLOCK || this == IGNITION_SEQUENCE || this == RUNNING

    companion object {
        fun parse(s: String?): RoasterState = entries.firstOrNull { it.name == s } ?: UNKNOWN
    }
}

data class Temps(val bt: Double?, val air: Double?, val exh: Double?, val ror: Double?)

data class Burner(val power: Boolean, val ignition: Boolean, val gas: Double?, val flame: Int, val interlock: Int)

data class VfdState(val name: String, val cmd: Double?, val pct: Double?, val hz: Double?, val rpm: Double?, val err: Int?)

data class Fault(val code: Int, val vfd: Int?, val ms: Long?, val msg: String?)

data class AutotuneProgress(val phase: String, val cycle: Int, val of: Int, val elapsedS: Double?)

data class Ctrl(val mode: String, val sp: Double?, val out: Double?, val autotune: AutotuneProgress?)

data class Roast(
    val run: Int,
    val phase: String,
    val recipe: String,
    val recipeId: String?,
    val t: Double?,
    val chargeReady: Boolean,
    val dropReady: Boolean,
    val overrideGas: Boolean,
    val overrideVfd: List<Boolean>,
) {
    val running: Boolean get() = phase == "preheat" || phase == "roast" || phase == "cooling"
}

/** Stav simulovaného kanálu teploty: null = sleduje model, "neplatná" = drží neplatné čtení, jinak hodnota. */
data class SimInfo(
    val vfd: Boolean,
    val temp: Boolean,
    val flameFails: Boolean,
    val tempHold: List<String?>,
    val vfdFault: List<String>,
    val diLossStop: Boolean,
    val diLossPsu: Boolean,
)

data class Status(
    val ms: Long,
    val evSeq: Long,
    val state: RoasterState,
    val fault: Fault,
    val stop: Boolean,
    val psu: Boolean,
    val temps: Temps,
    val burner: Burner,
    val heatBlocked: Boolean,
    val vfd: List<VfdState>,
    val ctrl: Ctrl,
    val roast: Roast?,
    val sim: SimInfo?,
) {
    companion object {
        fun parse(o: JsonObject): Status {
            val fault = o.obj("fault")
            val di = o.obj("di")
            val temp = o.obj("temp")
            val burner = o.obj("burner")
            val ctrl = o.obj("ctrl")
            val at = ctrl?.obj("autotune")
            val roast = o.obj("roast")
            val sim = o.obj("sim")
            val vfds = o.arr("vfd")?.mapNotNull { it as? JsonObject }?.map { v ->
                VfdState(
                    name = v.str("name") ?: "?",
                    cmd = v.dbl("cmd"),
                    pct = v.dbl("pct"),
                    hz = v.dbl("hz"),
                    rpm = v.dbl("rpm"),
                    err = v.int("err"),
                )
            } ?: emptyList()
            return Status(
                ms = o.lng("ms") ?: 0L,
                evSeq = o.lng("ev_seq") ?: 0L,
                state = RoasterState.parse(o.str("state")),
                fault = Fault(
                    code = fault?.int("code") ?: 0,
                    vfd = fault?.int("vfd"),
                    ms = fault?.lng("ms"),
                    msg = fault?.str("msg"),
                ),
                stop = di?.bool("stop") ?: false,
                psu = di?.bool("psu") ?: false,
                temps = Temps(temp?.dbl("bt"), temp?.dbl("air"), temp?.dbl("exh"), temp?.dbl("bt_ror")),
                burner = Burner(
                    power = burner?.bool("power") ?: false,
                    ignition = burner?.bool("ignition") ?: false,
                    gas = burner?.dbl("gas"),
                    flame = burner?.int("flame") ?: 0,
                    interlock = burner?.int("interlock") ?: 0,
                ),
                heatBlocked = o.bool("heat_blocked") ?: false,
                vfd = vfds,
                ctrl = Ctrl(
                    mode = ctrl?.str("mode") ?: "manual",
                    sp = ctrl?.dbl("sp"),
                    out = ctrl?.dbl("out"),
                    autotune = at?.let {
                        AutotuneProgress(it.str("phase") ?: "?", it.int("cycle") ?: 0, it.int("of") ?: 0, it.dbl("elapsed_s"))
                    },
                ),
                roast = roast?.let { r ->
                    val ov = r.obj("override")
                    Roast(
                        run = r.int("run") ?: 0,
                        phase = r.str("phase") ?: "loaded",
                        recipe = r.str("recipe") ?: "",
                        recipeId = r.str("recipe_id"),
                        t = r.dbl("t"),
                        chargeReady = r.bool("charge_ready") ?: false,
                        dropReady = r.bool("drop_ready") ?: false,
                        overrideGas = ov?.bool("gas") ?: false,
                        overrideVfd = ov?.arr("vfd")?.map { it.asBool() ?: false } ?: List(4) { false },
                    )
                },
                sim = sim?.let { s ->
                    val loss = s.obj("di_loss")
                    SimInfo(
                        vfd = s.bool("vfd") ?: false,
                        temp = s.bool("temp") ?: false,
                        flameFails = s.bool("flame_fails") ?: false,
                        tempHold = s.arr("temp_hold")?.map { e ->
                            when {
                                e is JsonNull -> null
                                e is JsonPrimitive && !e.isString && e.content == "false" -> "neplatná"
                                else -> e.asDouble()?.let { fmt1(it) + " °C" }
                            }
                        } ?: List(3) { null },
                        vfdFault = s.arr("vfd_fault")?.map { it.asString() ?: "ok" } ?: List(4) { "ok" },
                        diLossStop = loss?.bool("stop") ?: false,
                        diLossPsu = loss?.bool("psu") ?: false,
                    )
                },
            )
        }
    }
}

data class Event(
    val seq: Long?,
    val ms: Long,
    val level: String,
    val code: String,
    val msg: String,
    val replay: Boolean,
    val raw: JsonObject,
) {
    val roastEvent: String? get() = raw.str("roast_event")
    val roastT: Double? get() = raw.dbl("roast_t")

    companion object {
        fun parse(o: JsonObject) = Event(
            seq = o.lng("seq"),
            ms = o.lng("ms") ?: 0L,
            level = o.str("level") ?: "info",
            code = o.str("code") ?: "?",
            msg = o.str("msg") ?: "",
            replay = o.bool("replay") ?: false,
            raw = o,
        )
    }
}

data class HelloAck(
    val ok: Boolean,
    val err: String?,
    val fw: String?,
    val build: String?,
    val proto: Int?,
    val bootId: String?,
    val simVfd: Boolean,
    val simTemp: Boolean,
    val caps: Set<String>,
) {
    val simEnabled: Boolean get() = simVfd && simTemp
    fun has(cap: String) = caps.contains(cap)

    companion object {
        fun parse(o: JsonObject) = HelloAck(
            ok = o.bool("ok") ?: false,
            err = o.str("err"),
            fw = o.str("fw"),
            build = o.str("build"),
            proto = o.int("proto"),
            bootId = o.str("boot_id"),
            simVfd = o.bool("sim_vfd") ?: false,
            simTemp = o.bool("sim_temp") ?: false,
            caps = o.arr("caps")?.mapNotNull { it.asString() }?.toSet() ?: emptySet(),
        )
    }
}

data class PidValues(val kp: Double, val ki: Double, val kd: Double, val saved: Boolean) {
    companion object {
        fun parse(o: JsonObject) = PidValues(
            kp = o.dbl("kp") ?: 0.0,
            ki = o.dbl("ki") ?: 0.0,
            kd = o.dbl("kd") ?: 0.0,
            saved = o.bool("saved") ?: false,
        )
    }
}

data class AutotuneResult(val ok: Boolean, val kp: Double?, val ki: Double?, val kd: Double?, val ku: Double?, val tuS: Double?, val msg: String?) {
    companion object {
        fun parse(o: JsonObject) = AutotuneResult(
            ok = o.bool("ok") ?: false,
            kp = o.dbl("kp"),
            ki = o.dbl("ki"),
            kd = o.dbl("kd"),
            ku = o.dbl("ku"),
            tuS = o.dbl("tu_s"),
            msg = o.str("msg"),
        )
    }
}

/** Texty pro obsluhu. */
object Texts {
    fun fault(code: Int): String = when (code) {
        0 -> "Bez chyby"
        1 -> "Stisknuté tlačítko STOP"
        2 -> "Chybí napájení 24 V (PSU)"
        7 -> "Výpadek spojení s tabletem za ohřevu"
        10 -> "Měnič se při kontrole nerozjel"
        11 -> "Buben nebo odtah se před zapálením nerozjel"
        12 -> "Plamen nenaskočil"
        14 -> "Měnič nezastavil"
        else -> "Neznámá chyba (kód $code)"
    }

    fun faultVfdAction(code: Int): String = when (code) {
        1, 2 -> "Měniče jsou bez napájení."
        7, 12 -> "Hořák je vypnutý, buben a odtah běží na maximum."
        10 -> "Měniče stojí."
        11 -> "Nerozjetý měnič stojí, druhý běží na maximum."
        else -> "Hořák je vypnutý."
    }

    fun err(err: String?, msg: String?): String {
        val base = when (err) {
            null -> "Odmítnuto"
            "offline" -> "Nepřipojeno k Portentě"
            "timeout" -> "Portenta příkaz nepotvrdila"
            "bad_json" -> "Neplatná zpráva"
            "too_long" -> "Zpráva je příliš dlouhá"
            "unknown_cmd" -> "Portenta příkaz nezná"
            "not_supported" -> "Firmware tuto funkci zatím neumí"
            "not_auth" -> "Nepřihlášeno"
            "bad_token" -> "Špatný token"
            "proto" -> "Jiná verze protokolu"
            "bad_state" -> "V tomto stavu nelze"
            "out_of_range" -> "Hodnota mimo rozsah"
            "missing" -> "Chybí parametr"
            "bad_recipe" -> "Receptura neprošla kontrolou"
            "busy" -> "Probíhá jiná činnost"
            else -> "Odmítnuto ($err)"
        }
        return if (msg.isNullOrBlank()) base else "$base: $msg"
    }

    val vfdNames = listOf("Buben", "Odtah", "Míchač", "Ventilátor")

    fun mode(mode: String) = when (mode) {
        "manual" -> "Ručně"
        "pid" -> "PID"
        "recipe" -> "Receptura"
        else -> mode
    }

    fun phase(phase: String?) = when (phase) {
        "loaded" -> "Nahraná"
        "preheat" -> "Předehřev"
        "roast" -> "Pražení"
        "cooling" -> "Chlazení"
        else -> "—"
    }

    fun roastEvent(e: String) = when (e) {
        "charge" -> "Vsypání"
        "dry_end" -> "Konec sušení"
        "fc_start" -> "1. crack"
        "fc_end" -> "1. crack konec"
        "sc_start" -> "2. crack"
        "drop" -> "Vysypání"
        "tp" -> "Bod zvratu"
        "note" -> "Poznámka"
        else -> e
    }
}

/** Formát s desetinnou čárkou, null = „—“. */
fun fmt1(v: Double?): String = if (v == null) "—" else String.format(java.util.Locale.US, "%.1f", v).replace('.', ',')

fun fmt0(v: Double?): String = if (v == null) "—" else Math.round(v).toString()

fun mmss(seconds: Double?): String {
    if (seconds == null) return "—"
    val neg = seconds < 0
    val s = Math.round(Math.abs(seconds)).toInt()
    val txt = String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    return if (neg) "-$txt" else txt
}
