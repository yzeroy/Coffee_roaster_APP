package cz.prazicka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

    val vals = Triple(parseNum(kp), parseNum(ki), parseNum(kd))
    val valsOk = vals.first != null && vals.second != null && vals.third != null

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kalibrace PID", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (tuning != null) Chip("AUTOMATICKÉ LADĚNÍ", C.purpleText, Color(0xFF2A2540))
            Spacer1()
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(C.warnBg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Firmware zatím nemá limity přehřátí. Na skutečné pražičce lad jen pod dohledem.", color = C.warnText, fontSize = 13.sp)
            }
        }
        if (!hasPid) Text("Připojený firmware zatím PID neumí (chybí v caps).", color = C.warnText)

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Panel(Modifier.weight(1f).fillMaxWidth()) {
                    SectionTitle("Automatické ladění")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field(atSp, { atSp = it }, "Cíl °C", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(atLow, { atLow = it }, "Plyn min", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(atHigh, { atHigh = it }, "Plyn max", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                        Field(atCycles, { atCycles = it }, "Cykly", Modifier.weight(1f), numeric = true, mono = true, enabled = tuning == null)
                    }
                    if (tuning != null) {
                        Row {
                            Text(if (tuning.phase == "approach") "Dosahování cíle" else "Kmity · cyklus ${tuning.cycle} ze ${tuning.of}", fontWeight = FontWeight.SemiBold)
                            Spacer1()
                            Text(mmss(tuning.elapsedS), fontFamily = Mono, color = C.muted)
                        }
                        LinearProgressIndicator(
                            progress = { if (tuning.of > 0) tuning.cycle.toFloat() / tuning.of else 0f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = C.purple, trackColor = C.surface3,
                        )
                        Label("Typicky 10–20 min. Přeruší se nad cíl + 25 °C nebo po 30 min.")
                        Grow()
                        SecondaryButton("Přerušit ladění", { vm.autotuneAbort() }, Modifier.fillMaxWidth(), color = C.dangerText, border = C.danger)
                    } else {
                        Label("Spustit jde, když hořák hoří (Pražení) a neběží receptura. Ladit v podmínkách podobných pražení.")
                        Grow()
                        PrimaryButton("Spustit ladění", {
                            val sp = parseNum(atSp); val lo = parseNum(atLow); val hi = parseNum(atHigh); val cy = atCycles.trim().toIntOrNull()
                            if (sp == null || lo == null || hi == null || cy == null) vm.toast("Zkontroluj hodnoty ladění") else vm.autotuneStart(sp, lo, hi, cy)
                        }, Modifier.fillMaxWidth(), enabled = hasAutotune && s != null && s.state == RoasterState.RUNNING && s.roast?.running != true, container = C.purple)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f).fillMaxWidth()) {
                    Row {
                        SectionTitle("Živý graf – zrno, cíl, plyn")
                        Spacer1()
                        Label("posledních 15 minut")
                    }
                    val cut = (live.lastOrNull()?.t ?: 0.0) - 900.0
                    val recent = live.filter { it.t >= cut }
                    RoastGraph(
                        recent, emptyList(), recent.firstOrNull()?.t, Modifier.weight(1f).fillMaxWidth(),
                        hidden = setOf(Curves.AIR, Curves.EXH, Curves.ROR), minSpan = 900.0,
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
                                Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
