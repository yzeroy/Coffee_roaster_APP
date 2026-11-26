package cz.prazicka.ui.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import cz.prazicka.protocol.Texts
import cz.prazicka.ui.C
import cz.prazicka.ui.Chip
import cz.prazicka.ui.Field
import cz.prazicka.ui.Label
import cz.prazicka.ui.Mono
import cz.prazicka.ui.Panel
import cz.prazicka.ui.PrimaryButton
import cz.prazicka.ui.SecondaryButton
import cz.prazicka.ui.SectionTitle
import cz.prazicka.ui.Segmented
import cz.prazicka.ui.Spacer1
import cz.prazicka.ui.parseNum
import cz.prazicka.variant.Variant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put

/**
 * Obrazovka Test (jen testovací varianta): simulace ve firmwaru (sim_*), simulace v aplikaci,
 * provoz zpráv a ruční odeslání. S vestavěným simulátorem je vše povolené.
 */
@Composable
fun TestScreen(vm: AppViewModel) {
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val traffic by vm.traffic.collectAsState()
    val speed by Variant.simSpeed.collectAsState()
    val conn = link as? LinkState.Connected
    val builtIn = conn?.simulator == true
    // nástroje ovlivňující pražičku: se simulátorem vždy, se skutečnou Portentou jen se zapnutou simulací
    val affectAllowed = conn == null || builtIn || conn.hello.simEnabled
    val sim = status?.sim

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Test", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            when {
                builtIn -> Chip("Vestavěný simulátor – vše povoleno", C.okText, C.okBg)
                conn != null && conn.hello.simEnabled -> Chip("Portenta se simulací – povoleno", C.okText, C.okBg)
                conn != null -> Chip("Portenta BEZ simulace – nástroje ovlivňující pražičku vypnuty", C.dangerText, C.dangerBg)
                else -> Chip("Nepřipojeno", C.muted, C.surface2)
            }
            Spacer1()
            val active = buildList {
                if (sim?.flameFails == true) add("Plamen nechytí")
                sim?.tempHold?.forEachIndexed { i, h -> if (h != null) add("${listOf("Zrno", "Vzduch", "Odtah")[i]}: $h") }
                sim?.vfdFault?.forEachIndexed { i, f -> if (f != "ok") add("${Texts.vfdNames[i]}: $f") }
                if (sim?.diLossStop == true) add("Ztráta STOP")
                if (sim?.diLossPsu == true) add("Ztráta PSU")
            }
            active.take(4).forEach { Chip(it, C.warnText, border = C.warn) }
            SecondaryButton("Zrušit všechny poruchy", { vm.cmd("sim_reset", "Poruchy zrušeny") }, enabled = affectAllowed)
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- simulace ve firmwaru
            Panel(Modifier.width(420.dp).fillMaxHeight()) {
                SectionTitle("Simulace ve firmwaru (sim_*)")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Plamen", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                        Segmented(
                            listOf("ok" to "Normálně", "fail" to "Nechytí"), if (sim?.flameFails == true) "fail" else "ok",
                            { v -> vm.cmd("sim_flame") { put("fails", v == "fail") } }, Modifier.weight(1f), enabled = affectAllowed,
                        )
                    }
                    Text("Teploty", fontWeight = FontWeight.SemiBold)
                    listOf("Zrno", "Vzduch", "Odtah").forEachIndexed { ch, name ->
                        var value by remember { mutableStateOf("") }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = C.muted, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                            Field(value, { value = it }, "°C", Modifier.width(84.dp), numeric = true, mono = true)
                            SecondaryButton("Podržet", {
                                val c = parseNum(value)
                                if (c == null) vm.toast("Zadej teplotu") else vm.cmd("sim_temp") { put("ch", ch); put("c", c); put("hold", true) }
                            }, enabled = affectAllowed, height = 40.dp)
                            SecondaryButton("Neplatná", { vm.cmd("sim_temp") { put("ch", ch); put("c", JsonNull); put("hold", true) } }, enabled = affectAllowed, height = 40.dp)
                            SecondaryButton("Uvolnit", {
                                val c = parseNum(value)
                                vm.cmd("sim_temp") { put("ch", ch); if (c == null) put("c", JsonNull) else put("c", c); put("hold", false) }
                            }, enabled = affectAllowed, height = 40.dp)
                        }
                    }
                    Text("Měniče", fontWeight = FontWeight.SemiBold)
                    Texts.vfdNames.forEachIndexed { i, name ->
                        val cur = sim?.vfdFault?.getOrNull(i) ?: "ok"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = C.muted, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                            Segmented(
                                listOf("ok" to "OK", "nostop" to "nestaví", "offline" to "offline", "err" to "chyba"),
                                if (cur.startsWith("err")) "err" else cur,
                                { f -> vm.cmd("sim_vfd") { put("vfd", i); put("fault", f); if (f == "err") put("code", 12) } },
                                Modifier.weight(1f), enabled = affectAllowed, height = 34.dp,
                            )
                        }
                    }
                    Text("Vstupy 24 V", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val stopLoss = sim?.diLossStop == true
                        val psuLoss = sim?.diLossPsu == true
                        SecondaryButton(if (stopLoss) "Vrátit STOP" else "Ztráta STOP", { vm.cmd("sim_di") { put("stop", stopLoss) } }, Modifier.weight(1f), enabled = affectAllowed)
                        SecondaryButton(if (psuLoss) "Vrátit PSU" else "Ztráta PSU", { vm.cmd("sim_di") { put("psu", psuLoss) } }, Modifier.weight(1f), enabled = affectAllowed)
                    }
                    Label("Simulace umí 24 V jen odebrat, nikdy nahradit.")
                }
            }

            // ---- simulace v aplikaci
            Panel(Modifier.width(340.dp).fillMaxHeight()) {
                SectionTitle("Simulace v aplikaci")
                Label("Portenta je nerozliší od skutečnosti.")
                Text("Výpadek spojení", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 14, 20).forEach { sec ->
                        SecondaryButton("$sec s", { vm.silence(sec) }, Modifier.weight(1f), enabled = affectAllowed, color = if (sec > 15) C.dangerText else C.text, border = if (sec > 15) C.danger else C.line)
                    }
                }
                Label("Aplikace přestane posílat zprávy, spojení zůstane otevřené. Nad 15 s za ohřevu nastane panika (chyba 7).")
                SecondaryButton("Odpojit a znovu připojit", { vm.dropConnection() }, Modifier.fillMaxWidth())
                var delayMs by remember { mutableFloatStateOf(0f) }
                Row {
                    Text("Zpoždění příjmu", fontWeight = FontWeight.SemiBold)
                    Spacer1()
                    Text("${delayMs.toInt()} ms", fontFamily = Mono, color = C.warnText)
                }
                Slider(delayMs, { delayMs = it }, valueRange = 0f..3000f, onValueChangeFinished = { vm.setRxDelay(delayMs.toLong()) })
                if (builtIn) {
                    Text("Vestavěný simulátor", fontWeight = FontWeight.SemiBold)
                    Segmented(listOf("1" to "1×", "2" to "2×", "5" to "5×", "10" to "10×"), speed.toInt().toString(), { Variant.setSimSpeed(it.toDouble()) }, Modifier.fillMaxWidth(), height = 36.dp)
                    Label("Zrychlí simulovaný čas (teploty, receptura). Hlídání spojení běží ve skutečném čase.")
                    SecondaryButton("Restart simulátoru (nový boot)", { Variant.restartSimulator() }, Modifier.fillMaxWidth())
                }
            }

            // ---- provoz zpráv
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                var hideNoise by remember { mutableStateOf(true) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Provoz zpráv")
                    Spacer1()
                    Checkbox(hideNoise, { hideNoise = it }, colors = CheckboxDefaults.colors(checkedColor = C.bt, checkmarkColor = C.bg))
                    Text("skrýt status a ping", fontSize = 12.sp, color = C.muted)
                }
                val lines = traffic.filter { !hideNoise || (it.type != "status" && it.type != "ping" && !(it.type == "ack" && it.text.length < 40)) }.takeLast(300)
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(8.dp), state = listState) {
                    items(lines) { l ->
                        Text(
                            (if (l.out) "→ " else "← ") + l.text,
                            fontFamily = Mono, fontSize = 11.sp, maxLines = 2,
                            color = when {
                                l.text.startsWith("(potlačeno)") -> C.faint
                                l.out -> C.airText
                                l.text.contains("\"ok\":false") || l.text.contains("\"level\":\"error\"") -> C.dangerText
                                l.text.contains("\"level\":\"warn\"") -> C.warnText
                                l.type == "ack" || l.type == "hello_ack" -> C.okText
                                else -> C.sp
                            },
                        )
                    }
                }
                var raw by remember { mutableStateOf("{\"t\":\"get_diag\",\"id\":9000}") }
                Field(raw, { raw = it }, "Ruční zpráva (JSON, jeden řádek)", Modifier.fillMaxWidth(), mono = true)
                PrimaryButton("Odeslat", { vm.sendRaw(raw) }, Modifier.fillMaxWidth(), enabled = conn != null)
            }
        }
    }
}
