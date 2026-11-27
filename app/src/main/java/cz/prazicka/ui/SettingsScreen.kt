package cz.prazicka.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.AppViewModel
import cz.prazicka.BuildConfig
import cz.prazicka.data.Backup
import cz.prazicka.net.LinkState
import cz.prazicka.protocol.Proto
import cz.prazicka.variant.Variant
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: AppViewModel, go: (Screen) -> Unit) {
    val settings by vm.settings.collectAsState()
    val update by vm.update.collectAsState()
    val link by vm.link.collectAsState()
    val heating by vm.heating.collectAsState()
    val recipes by vm.recipes.collectAsState()
    val records by vm.records.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var token by remember { mutableStateOf("") }
    var useSim by remember { mutableStateOf(settings.useSimulator) }
    var searching by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val parsed = readText(context, uri)?.let { Backup.parse(it) }
            if (parsed == null) vm.toast("Soubor nejde načíst") else vm.importData(parsed.first, parsed.second)
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nastavení", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("Nástroje")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolButton("Kalibrace PID", "konstanty, automatické ladění s prohřátím", Modifier.weight(1f)) { go(Screen.Pid) }
                        ToolButton("Diagnostika", "firmware, log událostí, spojení", Modifier.weight(1f)) { go(Screen.Diag) }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Row {
                        SectionTitle("Aktualizace aplikace")
                        Spacer1()
                        Label(if (settings.lastUpdateCheck > 0) "Poslední kontrola ${fmtTime(settings.lastUpdateCheck)}" else "Zatím nekontrolováno")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ValueBox("Nainstalováno", BuildConfig.VERSION_NAME, Modifier.weight(1f), size = 17.sp)
                        ValueBox("Varianta", if (BuildConfig.IS_TEST) "Testovací" else "Provozní", Modifier.weight(1f), size = 17.sp)
                        ValueBox("Kanál", if (BuildConfig.IS_TEST) "Testovací vydání" else "Stabilní vydání", Modifier.weight(1f), size = 17.sp)
                    }
                    val latest = update.latest
                    if (latest != null) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.okPanelBg)
                                .border(1.dp, C.ok, RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("K dispozici ${latest.tag}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (latest.notes.isNotBlank()) Text(latest.notes.take(400), color = C.okText, fontSize = 13.sp)
                            val p = update.progress
                            if (p != null) {
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = C.ok, trackColor = C.surface3)
                            } else {
                                PrimaryButton(
                                    if (heating) "Během ohřevu nelze instalovat" else "Stáhnout a nainstalovat (${latest.apkSize / 1024 / 1024} MB)",
                                    { vm.downloadAndInstall() }, enabled = !heating,
                                )
                            }
                        }
                    } else if (update.upToDate) {
                        Text("Aplikace je aktuální.", color = C.okText)
                    }
                    update.error?.let { Text("Kontrola selhala: $it", color = C.dangerText, fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton(if (update.checking) "Kontroluji…" else "Zkontrolovat teď", { vm.checkUpdate() }, enabled = !update.checking)
                        CheckRow("Kontrolovat při spuštění aplikace", settings.checkUpdatesOnStart) { v -> vm.updateSettings { it.copy(checkUpdatesOnStart = v) } }
                    }
                    Label("Stahuje se z GitHub Releases (${BuildConfig.GITHUB_REPO}). Android instalaci vždy nechá potvrdit. Během ohřevu se aktualizace neinstaluje. Receptury a záznamy zůstanou zachované.")
                }

                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("Připojení k Portentě")
                    if (Variant.hasSimulator) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Vestavěný simulátor Portenty", fontWeight = FontWeight.SemiBold)
                                Label("Jen testovací varianta. Aplikace jde zkoušet bez desky – simulátor mluví stejným API jako firmware.")
                            }
                            Switch(useSim, { useSim = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.warn))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field(host, { host = it }, "Adresa Portenty", Modifier.weight(1f), mono = true, enabled = !useSim)
                        Field(port, { port = it }, "Port", Modifier.width(100.dp), numeric = true, mono = true, enabled = !useSim)
                        SecondaryButton(if (searching) "Hledám…" else "Najít Portentu", {
                            searching = true
                            scope.launch {
                                val r = vm.discover()
                                searching = false
                                if (r == null) vm.toast("Portenta v síti nenalezena – zadej adresu ručně")
                                else { host = r.host; port = r.port.toString(); vm.toast("Nalezeno: ${r.host} (firmware ${r.fw ?: "?"})") }
                            }
                        }, enabled = !useSim && !searching)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field(
                            token, { token = it.filter { c -> c.code in 0x21..0x7E }.take(32) },
                            if (vm.hasToken()) "Token (uložen – vyplň jen pro změnu)" else "Token (8–32 znaků bez mezer)",
                            Modifier.weight(1f), password = true,
                        )
                        PrimaryButton("Uložit a připojit", {
                            vm.saveConnection(host, port.toIntOrNull() ?: Proto.PORT, token.ifEmpty { null }, useSim)
                            token = ""
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (txt, col) = when (val l = link) {
                            is LinkState.Connected -> (if (l.simulator) "Spojeno se simulátorem" else "Spojeno · firmware ${l.hello.fw ?: "?"} · protokol ${l.hello.proto ?: "?"}") to C.ok
                            is LinkState.Connecting -> "Připojuji k ${l.target}…" to C.warn
                            is LinkState.Failed -> l.reason to C.danger
                            LinkState.Replaced -> "Spojení převzalo jiné zařízení" to C.warn
                            LinkState.Idle -> "Vyplň adresu a token" to C.faint
                        }
                        Dot(col)
                        Text(txt, fontSize = 13.sp)
                    }
                    Label("Token se ukládá šifrovaně v tabletu a nikdy se nezobrazuje.")
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("Vzhled")
                    Label("Motiv", size = 13.sp)
                    Segmented(
                        listOf("dark" to "Tmavý", "light" to "Světlý", "system" to "Podle systému"), settings.theme,
                        { v -> vm.updateSettings { it.copy(theme = v) } }, Modifier.fillMaxWidth(),
                    )
                    Label("Velikost rozhraní", size = 13.sp)
                    Segmented(
                        listOf(90, 100, 110, 120).map { it.toString() to "$it %" }, settings.uiScale.toString(),
                        { v -> vm.updateSettings { it.copy(uiScale = v.toInt()) } }, Modifier.fillMaxWidth(),
                    )
                    Label("Spodní okraj (pás pod aplikací)", size = 13.sp)
                    Segmented(
                        listOf(0, 16, 32, 48).map { it.toString() to if (it == 0) "žádný" else "$it dp" }, settings.bottomBand.toString(),
                        { v -> vm.updateSettings { it.copy(bottomBand = v.toInt()) } }, Modifier.fillMaxWidth(),
                    )
                    Label("Rozvržení se přizpůsobí obrazovce samo, velikostí a okrajem jde doladit.")
                }
                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("Pražení a výzvy")
                    CheckRow("Zvuk výzvy ke vsypání a vysypání", settings.soundPrompts) { v -> vm.updateSettings { it.copy(soundPrompts = v) } }
                    CheckRow("Zvuk při ztrátě spojení", settings.soundLink) { v -> vm.updateSettings { it.copy(soundLink = v) } }
                    CheckRow("Zaznamenávat ruční pražení (výchozí odpověď v dialogu)", settings.recordManual) { v -> vm.updateSettings { it.copy(recordManual = v) } }
                    Label("Chlazení po vysypání při ručním pražení", size = 13.sp)
                    Label("Míchač")
                    Segmented(listOf(30, 60, 80, 100).map { it.toString() to "$it %" }, settings.manualCooler.toString(), { v -> vm.updateSettings { it.copy(manualCooler = v.toInt()) } }, Modifier.fillMaxWidth())
                    Label("Chladicí ventilátor")
                    Segmented(listOf(50, 80, 100).map { it.toString() to "$it %" }, settings.manualCoolfan.toString(), { v -> vm.updateSettings { it.copy(manualCoolfan = v.toInt()) } }, Modifier.fillMaxWidth())
                    Label("Obrazovka během ohřevu nezhasíná.")
                }
                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("Graf – zobrazené křivky")
                    Curves.all.forEach { (key, label) ->
                        CheckRow(label, key !in settings.hiddenCurves) { v ->
                            vm.updateSettings { it.copy(hiddenCurves = if (v) it.hiddenCurves - key else it.hiddenCurves + key) }
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("Data")
                    Label("${recipes.size} receptur · ${records.size} záznamů")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Zálohovat vše", {
                            shareFile(context, "prazicka-zaloha-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json", "application/json", Backup.export(recipes, records))
                        }, Modifier.weight(1f))
                        SecondaryButton("Obnovit ze zálohy", { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.weight(1f))
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("O aplikaci")
                    KeyValue("Aplikace", "${if (BuildConfig.IS_TEST) "Pražička TEST" else "Pražička"} ${BuildConfig.VERSION_NAME}")
                    KeyValue("Protokol", "${Proto.VERSION} (API 1.8)")
                    KeyValue("Zdroj", "github.com/${BuildConfig.GITHUB_REPO}")
                }
            }
        }
    }
}

@Composable
private fun ToolButton(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(10.dp)).background(C.surface2)
            .border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(sub, color = C.muted, fontSize = 12.sp)
        }
        Text("›", color = C.muted, fontSize = 22.sp)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = C.bt, checkmarkColor = C.bg, uncheckedColor = C.muted))
        Text(label, fontSize = 14.sp)
    }
}

private fun fmtTime(ms: Long) = SimpleDateFormat("d. M. HH:mm", Locale.US).format(Date(ms))
