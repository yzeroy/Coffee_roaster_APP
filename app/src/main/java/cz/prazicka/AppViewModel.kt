package cz.prazicka

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.prazicka.data.AppSettings
import cz.prazicka.data.Recipe
import cz.prazicka.data.RecipeFromRecord
import cz.prazicka.data.RecipeValidator
import cz.prazicka.data.ReleaseInfo
import cz.prazicka.data.RoastRecord
import cz.prazicka.data.SettingsStore
import cz.prazicka.data.Updater
import cz.prazicka.net.Discovery
import cz.prazicka.net.DiscoveryResult
import cz.prazicka.net.PortentaClient
import cz.prazicka.protocol.Texts
import cz.prazicka.protocol.r1
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.io.File

data class UpdateUi(
    val checking: Boolean = false,
    val latest: ReleaseInfo? = null,
    val upToDate: Boolean = false,
    val error: String? = null,
    val progress: Float? = null,
    val file: File? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val c: AppContainer = (app as PrazickaApp).container
    val client: PortentaClient = c.client

    val status = client.status
    val link = client.link
    val eventLog = client.eventLog
    val traffic = client.traffic
    val pid = client.pid
    val diag = client.diag
    val portentaRecipe = client.portentaRecipe
    val autotuneResult = client.autotuneResult
    val settings: StateFlow<AppSettings> = c.settings.settings
    val recipes = c.recipes.recipes
    val records = c.records.records
    val currentRecord = c.recorder.current
    val live = c.recorder.live

    val heating: StateFlow<Boolean> = status.map { it?.state?.heating == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun toast(m: String) { _messages.tryEmit(m) }

    private val _update = MutableStateFlow(UpdateUi())
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    /** Recepturu, kterou má editor otevřít (např. po vytvoření ze záznamu). */
    val openRecipeId = MutableStateFlow<String?>(null)

    init {
        val s = settings.value
        if (s.checkUpdatesOnStart && System.currentTimeMillis() - s.lastUpdateCheck > 6 * 3600_000L) {
            checkUpdate(silent = true)
        }
    }

    // ------------------------------------------------------------ příkazy

    fun cmd(t: String, okMsg: String? = null, params: JsonObjectBuilder.() -> Unit = {}): Job = viewModelScope.launch {
        val r = client.command(t, params)
        if (!r.ok) toast(Texts.err(r.err, r.msg)) else if (okMsg != null) toast(okMsg)
    }

    private var gasJob: Job? = null
    private var pendingGas = 0.0
    private var lastGasSent = 0L

    /** Plyn z posuvníku: nejvýš 2× za sekundu a vždy poslední hodnota (API 3.2). */
    fun setGas(pct: Double) {
        pendingGas = pct.coerceIn(10.0, 100.0)
        if (gasJob?.isActive == true) return
        gasJob = viewModelScope.launch {
            val wait = 500 - (PortentaClient.now() - lastGasSent)
            if (wait > 0) delay(wait)
            lastGasSent = PortentaClient.now()
            val v = r1(pendingGas)
            val r = client.command("gas_set") { put("pct", v) }
            if (!r.ok) toast(Texts.err(r.err, r.msg))
        }
    }

    fun setVfd(i: Int, pct: Int) = cmd("vfd_set") { put("vfd", i); put("pct", pct) }
    fun heatStart() = cmd("heat_start")
    fun heatStop() = cmd("heat_stop", "Hořák vypnut")
    fun reignite() = cmd("reignite")
    fun restart() = cmd("restart")
    fun modeSet(m: String) = cmd("mode_set") { put("mode", m) }
    fun spSet(v: Double) = cmd("sp_set") { put("bt", r1(v)) }

    /** Značka pražení: při receptuře přes Portentu, bez receptury jen do záznamu. */
    fun roastMark(event: String) {
        val roast = status.value?.roast
        if (roast != null && (roast.phase == "preheat" || roast.phase == "roast")) {
            cmd("roast_event") { put("event", event) }
        } else if (!c.recorder.addLocalMark(event)) {
            toast("Záznam neběží – značky se zapisují jen během pražení")
        }
    }

    fun note(text: String) {
        if (!c.recorder.addLocalMark("note", text)) toast("Záznam neběží")
    }

    fun removeLastMark() {
        val m = c.recorder.removeLastMark()
        toast(if (m == null) "Není co smazat" else "Značka „${Texts.roastEvent(m.event)}“ smazána ze záznamu")
    }

    fun recipeLoad(r: Recipe, then: (() -> Unit)? = null) = viewModelScope.launch {
        val json = r.toApiJson()
        RecipeValidator.validate(json)?.let { toast("Receptura: ${it.msg}"); return@launch }
        val res = client.command("recipe_load") { put("recipe", json) }
        if (!res.ok) { toast(Texts.err(res.err, res.msg)); return@launch }
        client.query("recipe_get")
        if (then == null) toast("Receptura „${r.name}“ nahrána do Portenty") else then()
    }

    fun recipeStart(r: Recipe) {
        val roast = status.value?.roast
        if (roast?.phase == "loaded" && roast.recipeId == r.id) {
            cmd("recipe_start", "Předehřev spuštěn")
        } else {
            recipeLoad(r) { cmd("recipe_start", "Předehřev spuštěn") }
        }
    }

    fun recipeEnd() = cmd("recipe_end")

    fun pidSet(kp: Double, ki: Double, kd: Double, save: Boolean) = cmd("pid_set", if (save) "PID uloženo trvale" else "PID nastaveno na zkoušku") {
        put("kp", kp); put("ki", ki); put("kd", kd); put("save", save)
    }

    fun autotuneStart(sp: Double, low: Double, high: Double, cycles: Int) = cmd("autotune_start", "Ladění zahájeno") {
        put("sp", sp); put("gas_low", low); put("gas_high", high); put("cycles", cycles)
    }

    fun autotuneAbort() = cmd("autotune_abort")
    fun refreshPid() = viewModelScope.launch { client.query("pid_get") }
    fun refreshDiag() = viewModelScope.launch {
        val (r, _) = client.query("get_diag")
        if (!r.ok) toast(Texts.err(r.err, r.msg))
    }

    // ------------------------------------------------------------ knihovny

    fun saveRecipe(r: Recipe) = viewModelScope.launch {
        val saved = c.recipes.save(r)
        openRecipeId.value = saved.id
        toast("Receptura uložena")
    }

    fun deleteRecipe(id: String) = viewModelScope.launch { c.recipes.delete(id) }

    fun saveRecord(r: RoastRecord) = viewModelScope.launch { c.records.save(r) }
    fun deleteRecord(id: String) = viewModelScope.launch { c.records.delete(id) }

    fun recipeFromRecord(r: RoastRecord, name: String, onDone: (String) -> Unit) = viewModelScope.launch {
        val recipe = RecipeFromRecord.build(r, name)
        if (recipe == null) { toast("Ze záznamu nejde recepturu sestavit (málo dat)"); return@launch }
        val err = RecipeValidator.validate(recipe.toApiJson())
        val saved = c.recipes.save(recipe)
        openRecipeId.value = saved.id
        toast(if (err == null) "Receptura vytvořena" else "Receptura vytvořena – zkontroluj: ${err.msg}")
        onDone(saved.id)
    }

    fun importData(recipes: List<Recipe>, records: List<RoastRecord>) = viewModelScope.launch {
        recipes.forEach { c.recipes.save(it) }
        records.forEach { c.records.save(it) }
        toast("Načteno: ${recipes.size} receptur, ${records.size} záznamů")
    }

    // ------------------------------------------------------------ nastavení a spojení

    fun updateSettings(f: (AppSettings) -> AppSettings) = c.settings.update(f)

    fun saveConnection(host: String, port: Int, token: String?, useSimulator: Boolean) {
        if (token != null) {
            if (token.isNotEmpty() && !SettingsStore.tokenValid(token)) {
                toast("Token musí mít 8–32 znaků bez mezer")
                return
            }
            c.settings.saveToken(token)
        }
        c.settings.update { it.copy(host = host.trim(), port = port, useSimulator = useSimulator) }
        c.connect()
    }

    fun hasToken() = c.settings.hasToken()
    fun reconnect() = c.connect()

    suspend fun discover(): DiscoveryResult? {
        val r = Discovery.find()
        if (r != null) c.settings.update { it.copy(host = r.host, port = r.port, lastFoundAt = System.currentTimeMillis()) }
        return r
    }

    // ------------------------------------------------------------ aktualizace

    fun checkUpdate(silent: Boolean = false) = viewModelScope.launch {
        _update.value = _update.value.copy(checking = true, error = null)
        try {
            val r = Updater.latest(BuildConfig.GITHUB_REPO, BuildConfig.IS_TEST)
            c.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            val newer = r != null && Updater.isNewer(r.tag, BuildConfig.VERSION_NAME)
            _update.value = _update.value.copy(checking = false, latest = if (newer) r else null, upToDate = !newer)
            if (!silent && !newer) toast("Aplikace je aktuální")
        } catch (e: Exception) {
            _update.value = _update.value.copy(checking = false, error = e.message ?: "kontrola selhala")
        }
    }

    fun downloadAndInstall() = viewModelScope.launch {
        if (heating.value) { toast("Během ohřevu se aktualizace neinstaluje"); return@launch }
        val info = _update.value.latest ?: return@launch
        try {
            _update.value = _update.value.copy(progress = 0f, error = null)
            val f = Updater.download(getApplication(), info) { p -> _update.value = _update.value.copy(progress = p) }
            _update.value = _update.value.copy(progress = null, file = f)
            Updater.install(getApplication(), f)
        } catch (e: Exception) {
            _update.value = _update.value.copy(progress = null, error = e.message ?: "stažení selhalo")
        }
    }

    // ------------------------------------------------------------ testovací nástroje

    fun silence(seconds: Int) {
        client.silenceUntil = PortentaClient.now() + seconds * 1000L
        toast("Aplikace ${seconds} s nic neodešle")
    }

    fun dropConnection() = client.dropConnection()
    fun setRxDelay(ms: Long) { client.rxDelayMs = ms }
    fun sendRaw(line: String) = viewModelScope.launch {
        if (!client.sendRaw(line.trim())) toast("Odeslání selhalo (nepřipojeno nebo potlačeno)")
    }
}
