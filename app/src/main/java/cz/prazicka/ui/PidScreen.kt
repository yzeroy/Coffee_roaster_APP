package cz.prazicka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import cz.prazicka.protocol.fmt0
import cz.prazicka.protocol.fmt1
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.AppViewModel
import cz.prazicka.net.LinkState
import cz.prazicka.protocol.RoasterState
import cz.prazicka.protocol.mmss
import kotlinx.coroutines.delay

private fun f(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

@Composable
fun PidScreen(vm: AppViewModel) {
    val status by vm.status.collectAsState()
    val pid by vm.pid.collectAsState()
    val link by vm.link.collectAsState()
    val result by vm.autotuneResult.collectAsState()
    val live by vm.live.collectAsState()
    val hello = (link as? LinkState.Connected)?.hello
    val s = status
    val tuning = s?.ctrl?.autotune
    val hasPid = hello?.has("pid") != false
    val hasAutotune = hello?.has("autotune") != false

    var kp by remember { mutableStateOf("") }
    var ki by remember { mutableStateOf("") }
    var kd by remember { mutableStateOf("") }
    LaunchedEffect(pid) { pid?.let { kp = f(it.kp); ki = f(it.ki); kd = f(it.kd) } }
    LaunchedEffect(Unit) { vm.refreshPid() }
    var lastSave by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    var confirmSave by remember { mutableStateOf(false) }

    var atSp by remember { mutableStateOf("180") }
    var atLow by remember { mutableStateOf("20") }
    var atHigh by remember { mutableStateOf("60") }
    var atCycles by remember { mutableStateOf("3") }
    var soakSp by remember { mutableStateOf("200") }
    var soakMin by remember { mutableStateOf("10") }

    // Pevná časová osa: od spuštění ladění, jinak od otevření obrazovky. Osa se rozšiřuje, neposouvá se.
    var openT by remember { mutableStateOf<Double?>(null) }
    if (openT == null && live.isNotEmpty()) openT = live.last().t
    val lastT = live.lastOrNull()?.t
    val tuneStart = if (tuning?.elapsedS != null && lastT != null) lastT - tuning.elapsedS else null
    var origin by remember { mutableStateOf<Double?>(null) }
    val wantOrigin = tuneStart ?: openT
    if (wantOrigin != null && (origin == null || (tuneStart != null && kotlin.math.abs(tuneStart - origin!!) > 5.0))) origin = wantOrigin

    val vals = Triple(parseNum(kp), parseNum(ki), parseNum(kd))
    val valsOk = vals.first != null && vals.second != null && vals.third != null

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val leftW = (maxWidth * 0.32f).coerceIn(290.dp, 380.dp)
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kalibrace PID", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (tuning != null) Chip("AUTOMATICKÉ LADĚNÍ", C.purpleText, C.purpleBg)
            Spacer1()
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(C.warnBg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Firmware zatím nemá limity přehřátí. Lad jen pod dohledem.", color = C.warnText, fontSize = 13.sp, maxLines = 2)
            }
        }
        if (!hasPid) Text("Připojený firmware zatím PID neumí (chybí v caps).", color = C.warnText)

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(leftW).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Row {
                        SectionTitle("Konstanty")
                        Spacer1()
                        Text(
                            when { pid == null -> "nenačteno"; pid?.saved == true -> "Uloženo v Portentě"; else -> "Jen na zkoušku" },
                            color = if (pid?.saved == true) C.okText else C.warnText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(kp, { kp = it }, "Kp", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(ki, { ki = it }, "Ki", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(kd, { kd = it }, "Kd", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                    }
                    Label("Kp % plynu na °C · Ki % na °C·s · Kd % na °C/s")
                    val wait = ((10_000 - (now - lastSave)) / 1000).coerceAtLeast(0)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Vyzkoušet", { vm.pidSet(vals.first!!, vals.second!!, vals.third!!, false) }, Modifier.weight(1f), enabled = valsOk && tuning == null)
                        SecondaryButton(if (wait > 0) "Uložit (${wait} s)" else "Uložit trvale", { confirmSave = true }, Modifier.weight(1f), enabled = valsOk && tuning == null && wait == 0L)
                    }
                    if (tuning != null) Label("Během ladění nelze konstanty měnit.")
                }
                Panel(Modifier.fillMaxWidth()) {
                    SectionTitle("Automatické ladění")
                    Label("1 · Prohřátí bubnu (drží současné konstanty)", C.text, 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field(soakSp, { soakSp = it }, "Teplota °C", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(soakMin, { soakMin = it }, "Doba min (0 = bez)", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                    }
                    Label("2 · Ladění", C.text, 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field(atSp, { atSp = it }, "Cíl °C", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(atLow, { atLow = it }, "Plyn min", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(atHigh, { atHigh = it }, "Plyn max", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(atCycles, { atCycles = it }, "Cykly", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                    }
                    val order = listOf("ignite", "soak", "approach", "oscillate", "done")
                    val cur = order.indexOf(tuning?.phase ?: "")
                    if (tuning != null) {
                        listOf(
                            "ignite" to "Zapálení",
                            "soak" to "Prohřátí bubnu",
                            "approach" to "Najetí na cíl",
                            "oscillate" to "Kmity ${tuning.cycle} ze ${tuning.of}",
                            "done" to "Hotovo – návrh konstant",
                        ).forEachIndexed { i, (key, name) ->
                            val state = when { i < cur -> 2; i == cur -> 1; else -> 0 }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Dot(when (state) { 2 -> C.ok; 1 -> C.purple; else -> C.surface3 }, 12.dp)
                                Text(name, color = when (state) { 1 -> C.text; 2 -> C.muted; else -> C.disabled }, fontWeight = if (state == 1) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                if (key == "soak" && state == 1) Text(tuning.phaseLeftS?.let { "zbývá " + mmss(it) } ?: "", fontFamily = Mono, color = C.purpleText, fontSize = 13.sp)
                            }
                        }
                        Label("Přeruší se nad max(cíl, prohřátí) + 25 °C, když cíl nejde dosáhnout, nebo po 30 min od najetí.")
                        SecondaryButton("Přerušit ladění", { vm.autotuneAbort() }, Modifier.fillMaxWidth(), color = C.dangerText, border = C.danger)
                    } else {
                        val canStart = hasAutotune && s != null && s.roast?.running != true &&
                            (s.state == RoasterState.RUNNING || (s.state == RoasterState.READY && !s.heatBlocked))
                        Label(
                            when {
                                s == null -> "Bez spojení s Portentou."
                                s.roast?.running == true -> "Během receptury ladit nejde."
                                s.state == RoasterState.READY && s.heatBlocked -> "Ohřev je po restartu zablokovaný – odblokuj ho na obrazovce Pražení."
                                s.state == RoasterState.READY -> "Spustí se z klidu: Portenta rozjede buben a odtah, zapálí, prohřeje buben a pak ladí."
                                s.state == RoasterState.RUNNING -> "Hořák hoří – ladění začne prohřátím (nebo rovnou najetím na cíl)."
                                else -> "Spustit jde ve stavu Připraveno nebo za ohřevu (teď: ${s.state.label})."
                            },
                        )
                        PrimaryButton("Spustit ladění", {
                            val sp = parseNum(atSp); val lo = parseNum(atLow); val hi = parseNum(atHigh); val cy = atCycles.trim().toIntOrNull()
                            val ssp = parseNum(soakSp); val smin = parseNum(soakMin)
                            if (sp == null || lo == null || hi == null || cy == null || ssp == null || smin == null) vm.toast("Zkontroluj hodnoty ladění")
                            else vm.autotuneStart(sp, lo, hi, cy, ssp, (smin * 60).toInt().coerceIn(0, 3600))
                        }, Modifier.fillMaxWidth(), enabled = canStart, container = C.purple)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // hodnoty čísly
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ValueBox("Teplota zrna", fmt1(s?.temps?.bt) + " °C", Modifier.weight(1.4f), color = C.bt, size = 28.sp)
                    ValueBox(if (tuning?.phase == "soak") "Cíl (prohřátí)" else "Cíl", fmt0(s?.ctrl?.sp) + " °C", Modifier.weight(1f))
                    ValueBox("Plyn", if (s?.burner?.power == true) fmt0(s?.burner?.gas) + " %" else "—", Modifier.weight(1f))
                    ValueBox("RoR", fmt1(s?.temps?.ror), Modifier.weight(1f), color = C.ror)
                    ValueBox(
                        if (tuning?.phase == "soak") "Prohřátí · zbývá" else "Ladění · celkem",
                        if (tuning == null) "—" else mmss(if (tuning.phase == "soak") tuning.phaseLeftS else tuning.elapsedS),
                        Modifier.weight(1.2f), color = C.purpleText,
                    )
                }
                Panel(Modifier.weight(1f).fillMaxWidth()) {
                    Row {
                        SectionTitle("Živý graf – zrno, cíl, plyn")
                        Spacer1()
                        Label(if (tuneStart != null) "od spuštění ladění" else "od otevření obrazovky")
                    }
                    val o = origin
                    val shown = if (o == null) live else live.filter { it.t >= o - 30.0 }
                    RoastGraph(
                        shown, emptyList(), o, Modifier.weight(1f).fillMaxWidth(),
                        hidden = setOf(Curves.AIR, Curves.EXH, Curves.ROR), minSpan = 1200.0,
                    )
                }
                val r = result
                if (r != null) {
                    Panel(Modifier.fillMaxWidth(), border = if (r.ok) C.purple else C.danger) {
                        if (!r.ok) {
                            Text("Ladění nedokončeno: ${r.msg ?: ""}", color = C.dangerText, fontWeight = FontWeight.SemiBold)
                            SecondaryButton("Zavřít", { vm.client.clearAutotuneResult() })
                        } else {
                            Row {
                                SectionTitle("Návrh z ladění", C.purpleText)
                                Spacer1()
                                Label("Ku ${r.ku?.let { f(it) } ?: "—"} · Tu ${r.tuS?.let { f(it) } ?: "—"} s")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Row { Text("", Modifier.width(90.dp)); listOf("Kp", "Ki", "Kd").forEach { Text(it, color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f)) } }
                                    Row { Text("Současné", color = C.muted, fontSize = 13.sp, modifier = Modifier.width(90.dp)); listOf(pid?.kp, pid?.ki, pid?.kd).forEach { Text(it?.let { v -> f(v) } ?: "—", fontFamily = Mono, modifier = Modifier.weight(1f)) } }
                                    Row { Text("Navržené", color = C.purpleText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp)); listOf(r.kp, r.ki, r.kd).forEach { Text(it?.let { v -> f(v) } ?: "—", fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) } }
                                    Label("Návrh se sám nepoužije. Můžeš ho upravit a vyzkoušet, než ho uložíš.")
                                }
                                Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryButton("Vyzkoušet návrh", {
                                        if (r.kp != null && r.ki != null && r.kd != null) vm.pidSet(r.kp, r.ki, r.kd, false)
                                    }, Modifier.fillMaxWidth())
                                    SecondaryButton("Upravit před použitím", {
                                        r.kp?.let { kp = f(it) }; r.ki?.let { ki = f(it) }; r.kd?.let { kd = f(it) }
                                    }, Modifier.fillMaxWidth())
                                    SecondaryButton("Zahodit", { vm.client.clearAutotuneResult() }, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            containerColor = C.surface,
            title = { Text("Uložit konstanty trvale?") },
            text = { Text("Kp ${kp}, Ki ${ki}, Kd ${kd} se zapíšou do paměti Portenty a budou platit i po restartu desky.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    if (valsOk) { vm.pidSet(vals.first!!, vals.second!!, vals.third!!, true); lastSave = System.currentTimeMillis() }
                }) { Text("Uložit", color = C.bt, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Zrušit", color = C.muted) } },
        )
    }
}
