package cz.prazicka.data

import cz.prazicka.protocol.RoasterState
import cz.prazicka.protocol.Status
import cz.prazicka.protocol.Texts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dialog „Probíhá selftest“: kontrola zařízení při startu Portenty (BOOT → PREFLIGHT → STOP_CONFIRM → READY),
 * po restartu z chyby a při připojení aplikace.
 *
 * Aplikace nic nespouští – jen sleduje `status` (stav automatu, vstupy STOP a PSU, platnost teplot,
 * rozjezd a zastavení měničů) a výsledek ukáže. Rozhoduje Portenta (při chybě přejde do HALTED).
 *
 * Když se aplikace připojí k Portentě, která už je připravená, otáčení měničů se znovu nezkouší –
 * kontroluje se jen, že vstupy, teploty a měniče odpovídají.
 */
class Selftest {
    enum class Check { PENDING, OK, FAIL }

    data class Item(val check: Check, val detail: String)

    data class Ui(
        /** true = sleduje se běžící kontrola Portenty, false = jen kontrola stavu při připojení. */
        val live: Boolean,
        val running: Boolean,
        val stop: Item,
        val psu: Item,
        val temps: Item,
        val vfd: Item,
        val vfdEach: List<Check>,
        val note: String?,
    ) {
        val failed: Boolean get() = listOf(stop, psu, temps, vfd).any { it.check == Check.FAIL }
    }

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui.asStateFlow()

    private var lastBoot: String? = null
    private var awaitingFirst = false
    private var prevState: RoasterState? = null
    private val vfdSeen = BooleanArray(4)
    private val vfdPhase = arrayOfNulls<String>(1)

    companion object {
        val PRE = setOf(RoasterState.BOOT, RoasterState.BOOT_DELAY, RoasterState.PREFLIGHT, RoasterState.STOP_CONFIRM)
    }

    /** Úspěšné přihlášení. Selftest se ukáže při prvním připojení a vždy, když se Portenta mezitím restartovala. */
    fun onConnected(bootId: String?) {
        if (bootId == null || bootId != lastBoot) {
            lastBoot = bootId
            awaitingFirst = true
            prevState = null
        }
    }

    fun dismiss() {
        if (_ui.value?.running == false) _ui.value = null
    }

    fun onStatus(s: Status) {
        val st = s.state
        if (awaitingFirst) {
            awaitingFirst = false
            when {
                st in PRE -> begin()
                // za ohřevu (návrat po výpadku WiFi) dialog nepřekáží
                !st.heating -> _ui.value = snapshot(s)
            }
        } else if (st in PRE && prevState != null && prevState !in PRE && _ui.value?.running != true) {
            // restart z chyby (HALTED → STOP_CONFIRM → BOOT …)
            begin()
        }
        prevState = st
        val u = _ui.value ?: return
        if (u.live && u.running) _ui.value = follow(u, s)
    }

    private fun begin() {
        vfdSeen.fill(false)
        vfdPhase[0] = null
        _ui.value = Ui(
            live = true, running = true,
            stop = Item(Check.PENDING, "čeká"), psu = Item(Check.PENDING, "čeká"),
            temps = Item(Check.PENDING, "čeká na čtení čidel"),
            vfd = Item(Check.PENDING, "čeká na rozjezd"),
            vfdEach = List(4) { Check.PENDING },
            note = null,
        )
    }

    private fun stopItem(ok: Boolean) = if (ok) Item(Check.OK, "24 V přítomno – tlačítko uvolněné") else Item(Check.FAIL, "tlačítko STOP je stisknuté (DI_0 bez 24 V)")
    private fun psuItem(ok: Boolean) = if (ok) Item(Check.OK, "napájení 24 V v pořádku") else Item(Check.FAIL, "ztráta napájení PSU (DI_4 bez 24 V)")

    private fun missingTemps(s: Status): List<String> = buildList {
        if (s.temps.bt == null) add("zrno TP0")
        if (s.temps.air == null) add("vzduch TP1")
        if (s.temps.exh == null) add("komín TP2")
    }

    private fun follow(u: Ui, s: Status): Ui {
        val st = s.state
        val stop = stopItem(s.stop)
        val psu = psuItem(s.psu)
        val missing = missingTemps(s)
        var temps = if (missing.isEmpty()) Item(Check.OK, "zrno, vzduch i komín čtou platné hodnoty")
        else if (u.temps.check == Check.OK) u.temps
        else Item(Check.PENDING, "čeká na: ${missing.joinToString(", ")}")

        if (st == RoasterState.PREFLIGHT) {
            vfdPhase[0] = "run"
            for (i in 0..3) if ((s.vfd.getOrNull(i)?.pct ?: 0.0) >= 9.0) vfdSeen[i] = true
        } else if (st == RoasterState.STOP_CONFIRM && vfdPhase[0] == "run") {
            vfdPhase[0] = "stop"
        }
        var each = List(4) { i -> if (vfdSeen[i]) Check.OK else Check.PENDING }
        var vfd = when (vfdPhase[0]) {
            null -> Item(Check.PENDING, if (st == RoasterState.STOP_CONFIRM) "kontrola zastavení…" else "čeká na rozjezd")
            "run" -> Item(Check.PENDING, "rozjezd na kontrolní rychlost…")
            else -> Item(Check.PENDING, "kontrola zastavení…")
        }
        var running = true
        var note: String? = null
        when (st) {
            RoasterState.READY -> {
                running = false
                // po restartu z chyby proběhne jen kontrola zastavení – rozjezd se pak neověřuje znovu
                val notRun = (0..3).filter { !vfdSeen[it] }
                each = List(4) { i -> if (vfdSeen[i] || vfdPhase[0] == null) Check.OK else Check.FAIL }
                vfd = if (notRun.isEmpty() || vfdPhase[0] == null) Item(Check.OK, "rozjely se a zastavily") else Item(Check.FAIL, "nerozjel se: ${notRun.joinToString { Texts.vfdNames[it] }}")
                if (missing.isNotEmpty()) temps = Item(Check.FAIL, "neplatné čtení: ${missing.joinToString(", ")}")
            }
            RoasterState.HALTED -> {
                running = false
                val code = s.fault.code
                val bad = s.fault.vfd
                when (code) {
                    10, 14 -> {
                        each = List(4) { i -> if (i == bad) Check.FAIL else if (vfdSeen[i]) Check.OK else Check.PENDING }
                        vfd = Item(Check.FAIL, (if (code == 10) "nerozjel se" else "nezastavil") + (bad?.let { ": ${Texts.vfdNames.getOrNull(it) ?: it}" } ?: ""))
                    }
                    else -> if (vfd.check == Check.PENDING) vfd = Item(Check.PENDING, "nedokončeno")
                }
                if (temps.check == Check.PENDING) temps = Item(Check.PENDING, "nedokončeno")
                note = "Portenta zastavila: ${Texts.fault(code)}" + (s.fault.msg?.let { " ($it)" } ?: "")
            }
            else -> Unit
        }
        return u.copy(running = running, stop = stop, psu = psu, temps = temps, vfd = vfd, vfdEach = each, note = note)
    }

    private fun snapshot(s: Status): Ui {
        val missing = missingTemps(s)
        val each = s.vfd.take(4).map { v -> if (v.pct != null && (v.err ?: 0) == 0) Check.OK else Check.FAIL }
            .let { if (it.size < 4) it + List(4 - it.size) { Check.FAIL } else it }
        val badVfd = (0..3).filter { each[it] == Check.FAIL }
        return Ui(
            live = false, running = false,
            stop = stopItem(s.stop),
            psu = psuItem(s.psu),
            temps = if (missing.isEmpty()) Item(Check.OK, "zrno, vzduch i komín čtou platné hodnoty") else Item(Check.FAIL, "neplatné čtení: ${missing.joinToString(", ")}"),
            vfd = if (badVfd.isEmpty()) Item(Check.OK, "všechny odpovídají") else Item(Check.FAIL, "neodpovídá nebo hlásí chybu: ${badVfd.joinToString { Texts.vfdNames[it] }}"),
            vfdEach = each,
            note = if (s.state == RoasterState.HALTED) "Portenta je v chybě: ${Texts.fault(s.fault.code)}"
            else "Portenta už běžela – otáčení měničů se kontrolovalo při jejím startu, teď jen ověřeno, že odpovídají.",
        )
    }
}
