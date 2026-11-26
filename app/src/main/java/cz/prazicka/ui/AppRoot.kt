package cz.prazicka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.AppViewModel
import cz.prazicka.BuildConfig
import cz.prazicka.net.LinkState
import cz.prazicka.net.PortentaClient
import cz.prazicka.protocol.RoasterState
import cz.prazicka.protocol.Texts
import cz.prazicka.protocol.mmss
import cz.prazicka.variant.Variant
import kotlinx.coroutines.delay

enum class Screen(val label: String) { Roast("Pražení"), Recipes("Receptury"), Records("Záznamy"), Pid("PID"), Diag("Diagnostika"), Settings("Nastavení"), Test("Test") }

@Composable
fun AppRoot(vm: AppViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Roast) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val go: (Screen) -> Unit = { screen = it }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            if (BuildConfig.IS_TEST) TestBar(vm)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Rail(screen, go, onStop = { vm.heatStop() })
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (screen) {
                        Screen.Roast -> RoastScreen(vm, go)
                        Screen.Recipes -> RecipesScreen(vm, go)
                        Screen.Records -> RecordsScreen(vm, go)
                        Screen.Pid -> PidScreen(vm)
                        Screen.Diag -> DiagScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.Test -> Variant.Screen(vm)
                    }
                    Overlays(vm, go)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).widthIn(max = 640.dp))
    }
}

@Composable
private fun TestBar(vm: AppViewModel) {
    val link by vm.link.collectAsState()
    val sim = (link as? LinkState.Connected)?.simulator == true
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(C.warn),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TESTOVACÍ VARIANTA", color = Color(0xFF1A1406), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            "Pražička TEST ${BuildConfig.VERSION_NAME}" + if (sim) " · vestavěný simulátor Portenty" else "",
            color = Color(0xFF1A1406), fontSize = 13.sp,
        )
    }
}

@Composable
private fun Rail(screen: Screen, go: (Screen) -> Unit, onStop: () -> Unit) {
    Column(
        Modifier.width(80.dp).fillMaxHeight().background(C.surface).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (BuildConfig.IS_TEST) C.warn else C.bt),
            contentAlignment = Alignment.Center,
        ) { Text(if (BuildConfig.IS_TEST) "T" else "P", color = C.bg, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        Spacer(Modifier.height(8.dp))
        RailItem("Pražení", screen == Screen.Roast, { go(Screen.Roast) }) { FlameIcon(it) }
        RailItem("Receptury", screen == Screen.Recipes, { go(Screen.Recipes) }) { VecIcon(Icons.Filled.List, it) }
        RailItem("Záznamy", screen == Screen.Records, { go(Screen.Records) }) { VecIcon(Icons.Filled.DateRange, it) }
        RailItem("PID", screen == Screen.Pid, { go(Screen.Pid) }) { VecIcon(Icons.Filled.Build, it) }
        RailItem("Diagnostika", screen == Screen.Diag, { go(Screen.Diag) }) { VecIcon(Icons.Filled.Info, it) }
        if (Variant.hasTestScreen) {
            RailItem("Test", screen == Screen.Test, { go(Screen.Test) }, activeBg = Color(0xFF3A2E12), activeFg = C.warnText) { VecIcon(Icons.Filled.Warning, it) }
        }
        Spacer(Modifier.weight(1f))
        RailItem("Nastavení", screen == Screen.Settings, { go(Screen.Settings) }) { VecIcon(Icons.Filled.Settings, it) }
        Spacer(Modifier.height(6.dp))
        // STOP ohřevu: vždy viditelné, žádný dialog ho nezakryje (není to nouzové zastavení).
        Column(
            Modifier
                .width(66.dp)
                .height(82.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(C.stop)
                .border(2.dp, Color(0xFFFF8080), RoundedCornerShape(12.dp))
                .clickable(onClick = onStop)
                .semantics { contentDescription = "Stop ohřevu – vypne hořák" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FlameIcon(Color.White, 26.dp, crossed = true)
            Text("STOP", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 1.sp)
            Text("ohřevu", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun VecIcon(v: ImageVector, color: Color) = Icon(v, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))

@Composable
private fun RailItem(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    activeBg: Color = C.surface3,
    activeFg: Color = C.text,
    icon: @Composable (Color) -> Unit,
) {
    val fg = if (active) activeFg else C.muted
    Column(
        Modifier
            .width(68.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) activeBg else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(fg)
        Text(label, color = fg, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/** Překryvy nad obsahem (lišta se STOP zůstává volná): výpadek spojení, převzetí, chyba, výzvy. */
@Composable
private fun Overlays(vm: AppViewModel, go: (Screen) -> Unit) {
    val status by vm.status.collectAsState()
    val link by vm.link.collectAsState()
    val lastSent by vm.client.lastSentAt.collectAsState()
    val settings by vm.settings.collectAsState()
    var now by remember { mutableLongStateOf(PortentaClient.now()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = PortentaClient.now() } }

    var hiddenFaultMs by remember { mutableStateOf<Long?>(null) }
    var dismissedChargeRun by remember { mutableStateOf(-1) }
    var dismissedDropRun by remember { mutableStateOf(-1) }

    val s = status
    val connected = link is LinkState.Connected
    val heatingKnown = s?.state?.heating == true

    // výzvy se zvukem
    val roast = s?.roast
    val chargeReady = connected && roast != null && roast.chargeReady && dismissedChargeRun != roast.run
    val dropReady = connected && roast != null && roast.dropReady && dismissedDropRun != roast.run
    LaunchedEffect(chargeReady) { if (chargeReady && settings.soundPrompts) Beeper.beep() }
    LaunchedEffect(dropReady) { if (dropReady && settings.soundPrompts) Beeper.beep() }
    val linkLost = !connected && heatingKnown && link !is LinkState.Idle
    LaunchedEffect(linkLost) { if (linkLost && settings.soundLink) Beeper.beep(alarm = true) }

    when {
        link is LinkState.Replaced -> Modal(border = C.warn) {
            Text("Spojení převzalo jiné zařízení", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("K Portentě se připojil jiný tablet nebo telefon. Tato aplikace se sama znovu nepřipojí, aby se zařízení nepřetahovala.", color = C.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Připojit znovu", { vm.reconnect() }, Modifier.weight(1f))
            }
        }
        linkLost -> {
            val left = (15 - (now - lastSent) / 1000).coerceAtLeast(0)
            Modal(border = C.danger, bg = Color(0xFF1C1416), width = 640.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("$left s", fontFamily = Mono, fontSize = 60.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8080))
                    Column {
                        Text("Spojení s Portentou ztraceno", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (left > 0) "Za $left s Portenta vypne hořák a přejde do chyby." else "Portenta už pravděpodobně vypnula hořák (chyba 7).",
                            color = Color(0xFFE0B4B4),
                        )
                    }
                }
                Text("Pražení zatím běží dál. Když se spojení do 15 s neobnoví, buben a odtah poběží na maximum. Zkontroluj WiFi tabletu nebo router.", color = Color(0xFFE9D6D6))
                val l = link
                Text(
                    when (l) {
                        is LinkState.Connecting -> "Zkouším se připojit… pokus ${l.attempt}"
                        is LinkState.Failed -> l.reason
                        else -> ""
                    },
                    color = Color(0xFFE0B4B4), fontSize = 13.sp,
                )
            }
        }
        connected && s != null && s.state == RoasterState.HALTED && hiddenFaultMs != (s.fault.ms ?: -1L) -> {
            Modal(border = C.danger, width = 660.dp) {
                Text("CHYBA · kód ${s.fault.code}", color = C.dangerText, fontWeight = FontWeight.SemiBold)
                Text(Texts.fault(s.fault.code), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(Texts.faultVfdAction(s.fault.code) + (s.fault.vfd?.let { " Týká se: ${Texts.vfdNames.getOrNull(it) ?: it}." } ?: ""), color = C.sp)
                s.fault.msg?.let {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                        Text("Portenta: $it", fontFamily = Mono, fontSize = 12.sp, color = C.muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (s.stop) "STOP v pořádku" else "STOP stisknuté", if (s.stop) C.okText else C.dangerText, if (s.stop) C.okBg else Color(0xFF3A1A1C))
                    Chip(if (s.psu) "PSU v pořádku" else "PSU bez 24 V", if (s.psu) C.okText else C.dangerText, if (s.psu) C.okBg else Color(0xFF3A1A1C))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Restart", { vm.restart() }, enabled = s.stop && s.psu, height = 54.dp)
                    SecondaryButton("Skrýt", { hiddenFaultMs = s.fault.ms ?: -1L })
                    Text(
                        if (s.stop && s.psu) "Restart ověří zastavení měničů a spustí znovu kontrolu. Ohřev pak zůstane zablokovaný, dokud ho nepotvrdíš."
                        else "Nejdřív uvolni tlačítko STOP a zkontroluj napájení.",
                        color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        chargeReady && s != null -> Modal(border = C.bt) {
            Text("Předehřev hotový", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Teplota zrna drží cíl ±3 °C už 60 s. Vsyp zrno a potvrď vsypání.", color = C.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Vsypáno", { vm.roastMark("charge") }, Modifier.weight(1f), container = C.bt, height = 60.dp)
                SecondaryButton("Zavřít", { dismissedChargeRun = s.roast?.run ?: -1 }, height = 60.dp)
            }
        }
        dropReady && s != null -> Modal(border = C.bt) {
            Text("Připraveno k vysypání", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Splněna podmínka receptury (teplota nebo čas ${mmss(s.roast?.t)}). Vysyp zrno a potvrď – hořák se vypne a začne chlazení.", color = C.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Vysypáno", { vm.roastMark("drop") }, Modifier.weight(1f), container = C.bt, height = 60.dp)
                SecondaryButton("Zatím ne", { dismissedDropRun = s.roast?.run ?: -1 }, height = 60.dp)
            }
        }
    }
}

@Composable
fun Modal(
    border: Color,
    bg: Color = C.surface,
    width: androidx.compose.ui.unit.Dp = 560.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xA0050709)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .border(2.dp, border, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
