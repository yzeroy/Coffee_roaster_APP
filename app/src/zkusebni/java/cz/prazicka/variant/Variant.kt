package cz.prazicka.variant

import androidx.compose.runtime.Composable
import cz.prazicka.AppViewModel
import cz.prazicka.net.Transport
import cz.prazicka.sim.SimTransport
import cz.prazicka.sim.SimulatorHost
import cz.prazicka.ui.test.TestScreen
import kotlinx.coroutines.flow.StateFlow

/** Testovací varianta: vestavěný simulátor Portenty a obrazovka Test. */
object Variant {
    const val hasSimulator: Boolean = true
    const val hasTestScreen: Boolean = true

    fun simulatorTransport(): Transport? = SimTransport()

    val simSpeed: StateFlow<Double> get() = SimulatorHost.speed
    fun setSimSpeed(x: Double) { SimulatorHost.speed.value = x }
    fun restartSimulator() = SimulatorHost.restart()

    @Composable
    fun Screen(vm: AppViewModel) = TestScreen(vm)
}
