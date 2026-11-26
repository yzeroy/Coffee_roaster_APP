package cz.prazicka.data

import cz.prazicka.protocol.RoasterState
import cz.prazicka.protocol.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * Předehřev s výdrží u ručního pražení. Portenta o něm neví (API 14.1a) – drží jen cílovou teplotu,
 * odpočet výdrže a výzvu ke vsypání počítá aplikace. Pravidlo stejné jako u receptury (API 1.7):
 * odpočet běží od prvního dosažení cíle ±3 °C, krátké vyjetí z pásma ho nezastaví.
 */
class ManualPreheat(private val marks: (String) -> Unit) {
    enum class Phase { HEATING, HOLD, DONE }

    data class Ui(val phase: Phase, val target: Double, val holdS: Int, val leftS: Double?)

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui.asStateFlow()

    private var reachedMs: Long? = null
    private var sawHeating = false

    /** Volá se po stisku „Spustit“ v dialogu ručního pražení. */
    fun start(target: Double, holdS: Int) {
        reachedMs = null
        sawHeating = false
        _ui.value = if (holdS > 0) Ui(Phase.HEATING, target, holdS, holdS.toDouble()) else null
    }

    fun cancel() { _ui.value = null }

    /** Vsypáno (i před koncem výdrže) – předehřev končí. */
    fun charged() { _ui.value = null }

    fun onStatus(s: Status) {
        val u = _ui.value ?: return
        if (s.state.heating) sawHeating = true
        // ohřev skončil nebo běží receptura → předehřev už neplatí
        if ((sawHeating && !s.state.heating) || s.state == RoasterState.HALTED || s.roast?.running == true) {
            _ui.value = null
            return
        }
        if (s.state != RoasterState.RUNNING) return
        // obsluha mohla během předehřevu změnit cíl – sledujeme ten, který drží Portenta
        val target = if (s.ctrl.mode == "pid") s.ctrl.sp ?: u.target else u.target
        val bt = s.temps.bt
        when (u.phase) {
            Phase.HEATING -> if (bt != null && abs(bt - target) <= 3.0) {
                reachedMs = s.ms
                marks("preheat_hold")
                _ui.value = u.copy(phase = Phase.HOLD, target = target, leftS = u.holdS.toDouble())
            } else if (target != u.target) _ui.value = u.copy(target = target)
            Phase.HOLD -> {
                val left = max(0.0, u.holdS - (s.ms - (reachedMs ?: s.ms)) / 1000.0)
                if (left <= 0.0) {
                    marks("charge_ready")
                    _ui.value = u.copy(phase = Phase.DONE, target = target, leftS = 0.0)
                } else _ui.value = u.copy(target = target, leftS = left)
            }
            Phase.DONE -> Unit
        }
    }
}
