package cz.prazicka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.AppViewModel
import cz.prazicka.net.LinkState
import cz.prazicka.protocol.arr
import cz.prazicka.protocol.asString
import cz.prazicka.protocol.bool
import cz.prazicka.protocol.int
import cz.prazicka.protocol.lng
import cz.prazicka.protocol.mmss
import cz.prazicka.protocol.obj
import cz.prazicka.protocol.str
import cz.prazicka.protocol.Texts

@Composable
fun DiagScreen(vm: AppViewModel) {
    val diag by vm.diag.collectAsState()
    val link by vm.link.collectAsState()
    val events by vm.eventLog.collectAsState()
    val context = LocalContext.current
    var level by remember { mutableStateOf("all") }
    LaunchedEffect(link is LinkState.Connected) { if (link is LinkState.Connected) vm.refreshDiag() }

    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(0.42f).widthIn(max = 440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Row {
                    SectionTitle("Spojení")
                    Spacer1()
                    SecondaryButton("Připojit znovu", { vm.reconnect() }, height = 38.dp)
                }
                when (val l = link) {
                    is LinkState.Connected -> {
                        KeyValue("Stav", if (l.simulator) "Vestavěný simulátor" else "Spojeno", C.okText)
                        KeyValue("Adresa", l.target)
                        KeyValue("Firmware", "${l.hello.fw ?: "?"} (${l.hello.build ?: "?"}) · protokol ${l.hello.proto ?: "?"}")
                        KeyValue("Funkce firmwaru", l.hello.caps.joinToString(", "))
                        KeyValue("Simulace", if (l.hello.simEnabled) "zapnutá" else if (l.hello.simVfd || l.hello.simTemp) "částečná" else "vypnutá")
                    }
                    is LinkState.Connecting -> KeyValue("Stav", "Připojuji k ${l.target} (pokus ${l.attempt})", C.warnText)
                    is LinkState.Failed -> KeyValue("Stav", l.reason, C.dangerText)
                    LinkState.Replaced -> KeyValue("Stav", "Spojení převzalo jiné zařízení", C.warnText)
                    LinkState.Idle -> KeyValue("Stav", "Připojení není nastavené", C.muted)
                }
            }
            Panel(Modifier.weight(1f).fillMaxWidth()) {
                Row {
                    SectionTitle("Portenta")
                    Spacer1()
                    SecondaryButton("Obnovit", { vm.refreshDiag() }, height = 38.dp)
                }
                val d = diag
                if (d == null) {
                    Label("Diagnostika zatím nenačtena.")
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val reason = d.str("reset_reason") ?: "?"
                        KeyValue("Poslední start", resetText(reason), if (reason == "watchdog" || reason == "brown_out") C.dangerText else C.text)
                        KeyValue("Doba běhu", mmss((d.lng("uptime_ms") ?: 0L) / 1000.0))
                        KeyValue("boot_id", d.str("boot_id") ?: "—")
                        KeyValue("IP / MAC", "${d.str("ip") ?: "—"} / ${d.str("mac") ?: "—"}")
                        d.obj("client")?.let { KeyValue("Aplikace (podle Portenty)", "${it.str("app") ?: "?"} · ${it.str("variant") ?: "?"}") }
                        d.obj("link")?.let { KeyValue("Přihlášení / paniky / odmítnuto", "${it.int("connects") ?: 0} / ${it.int("panics") ?: 0} / ${it.int("auth_fail") ?: 0}") }
                        d.arr("vfd_last_err")?.forEachIndexed { i, e -> KeyValue("Chyba – ${Texts.vfdNames.getOrNull(i) ?: i}", e.asString() ?: "-") }
                        d.obj("pid")?.let { KeyValue("PID", "Kp ${it["kp"]} · Ki ${it["ki"]} · Kd ${it["kd"]}" + if (it.bool("saved") == true) " · uloženo" else "") }
                        d.obj("config")?.let { cfg -> cfg.forEach { (k, v) -> KeyValue(k, v.toString()) } }
                    }
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Row {
                SectionTitle("Události z Portenty")
                Spacer1()
                Segmented(listOf("all" to "Vše", "warn" to "Varování+", "error" to "Chyby"), level, { level = it }, Modifier.widthIn(max = 320.dp).weight(1f, fill = false), height = 34.dp)
                SecondaryButton("Export", {
                    shareFile(context, "udalosti.txt", "text/plain", events.joinToString("\n") { "${it.seq ?: ""}\t${it.ms}\t${it.level}\t${it.code}\t${it.msg}" })
                }, height = 38.dp)
            }
            val shown = events.filter {
                when (level) { "warn" -> it.level != "info"; "error" -> it.level == "error"; else -> true }
            }.reversed()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(8.dp)) {
                items(shown) { e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("#${e.seq ?: "-"}", fontFamily = Mono, fontSize = 12.sp, color = C.faint, modifier = Modifier.width(54.dp))
                        Text(mmss(e.ms / 1000.0), fontFamily = Mono, fontSize = 12.sp, color = C.muted, modifier = Modifier.width(60.dp))
                        Text(e.code, fontFamily = Mono, fontSize = 12.sp, color = C.muted, modifier = Modifier.width(70.dp))
                        Text(
                            e.msg + if (e.replay) "  (doplněno)" else "",
                            fontSize = 13.sp,
                            color = when (e.level) { "error" -> C.dangerText; "warn" -> C.warnText; else -> C.text },
                            fontWeight = if (e.level == "info") FontWeight.Normal else FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private fun resetText(r: String) = when (r) {
    "power_on" -> "zapnutí napájení"
    "pin" -> "tlačítko reset"
    "software" -> "softwarový restart (např. nahrání firmwaru)"
    "watchdog" -> "WATCHDOG – firmware se zasekl"
    "brown_out" -> "POKLES NAPÁJENÍ"
    else -> r
}
