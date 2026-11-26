package cz.prazicka

import android.app.Application
import cz.prazicka.data.RecipeRepository
import cz.prazicka.data.RecordRepository
import cz.prazicka.data.ManualPreheat
import cz.prazicka.data.Recorder
import cz.prazicka.data.Selftest
import cz.prazicka.data.SettingsStore
import cz.prazicka.net.LinkState
import cz.prazicka.net.PortentaClient
import cz.prazicka.net.TcpTransport
import cz.prazicka.net.Transport
import cz.prazicka.variant.Variant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Vše, co má žít déle než obrazovka: spojení, záznam, knihovny. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appVersion: String = BuildConfig.VERSION_NAME
    val settings = SettingsStore(app)
    val recipes = RecipeRepository(app.filesDir)
    val records = RecordRepository(app.filesDir)
    val client = PortentaClient(scope, appVersion, BuildConfig.VARIANT)
    val recorder = Recorder(
        appVersion = appVersion,
        onFinished = { r -> scope.launch { records.save(r) } },
        onCheckpoint = { r -> scope.launch { records.save(r) } },
    )
    val preheat = ManualPreheat { mark -> recorder.addLocalMark(mark) }
    val selftest = Selftest()

    init {
        scope.launch { recipes.load() }
        scope.launch { records.load() }
        scope.launch {
            client.status.collect { s ->
                if (s != null) {
                    recorder.onStatus(s)
                    preheat.onStatus(s)
                    selftest.onStatus(s)
                }
            }
        }
        scope.launch { client.events.collect { e -> recorder.onEvent(e) } }
        scope.launch {
            client.link.collect { l ->
                if (l is LinkState.Connected) {
                    recorder.fw = l.hello.fw ?: ""
                    selftest.onConnected(l.hello.bootId)
                }
            }
        }
        scope.launch { settings.settings.collect { recorder.defaultManual = it.recordManual } }
        connect()
    }

    /** (Znovu) připojí podle nastavení: vestavěný simulátor, nebo Portenta na zadané adrese. */
    fun connect() {
        val s = settings.settings.value
        val useSim = Variant.hasSimulator && s.useSimulator
        val token = if (useSim) settings.token().ifEmpty { "simulace-test" } else settings.token()
        if (!useSim && (s.host.isBlank() || token.isBlank())) {
            client.stop()
            return
        }
        val provider: () -> Transport = if (useSim) {
            { Variant.simulatorTransport() ?: TcpTransport(s.host, s.port) }
        } else {
            { TcpTransport(s.host, s.port) }
        }
        client.start(provider, token)
    }
}

class PrazickaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
