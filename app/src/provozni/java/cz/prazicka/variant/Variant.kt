package cz.prazicka.variant

import androidx.compose.runtime.Composable
import cz.prazicka.AppViewModel
import cz.prazicka.net.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Provozní varianta: žádný simulátor ani testovací nástroje. */
object Variant {
    const val hasSimulator: Boolean = false
    const val hasTestScreen: Boolean = false

    fun simulatorTransport(): Transport? = null

    private val speed = MutableStateFlow(1.0)
    val simSpeed: StateFlow<Double> get() = speed
    fun setSimSpeed(x: Double) { }
    fun restartSimulator() { }

    @Composable
    fun Screen(vm: AppViewModel) { }
}
