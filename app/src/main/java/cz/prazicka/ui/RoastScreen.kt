package cz.prazicka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.AppViewModel
import cz.prazicka.data.Recipe
import cz.prazicka.net.LinkState
import cz.prazicka.protocol.RoasterState
import cz.prazicka.protocol.Status
import cz.prazicka.protocol.Texts
import cz.prazicka.protocol.fmt0
import cz.prazicka.protocol.fmt1
import cz.prazicka.protocol.mmss

@Composable
fun RoastScreen(vm: AppViewModel, go: (Screen) -> Unit) {
    val status by vm.status.collectAsState()
    val link by vm.link.collectAsState()
    val record by vm.currentRecord.collectAsState()
    val live by vm.live.collectAsState()
    val liveMarks by vm.liveMarks.collectAsState()
    val preheat by vm.manualPreheat.collectAsState()
    val manualCooling by vm.manualCooling.collectAsState()
    val portentaRecipeJson by vm.portentaRecipe.collectAsState()
    val settings by vm.settings.collectAsState()
    val connected = link is LinkState.Connected
    val s = if (connected) status else null
    val portentaRecipe = remember(portentaRecipeJson) { portentaRecipeJson?.let { Recipe.fromJson(it) } }
    var showStart by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    // Rozvržení podle velikosti obrazovky: nic s pevnou šířkou, panely jen s mezemi.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 900.dp
        val low = maxHeight < 620.dp
        val gap = if (compact || low) 8.dp else 12.dp
        val valuesW = (maxWidth * 0.22f).coerceIn(190.dp, 290.dp)
        val bottomH = (maxHeight * 0.3f).coerceIn(176.dp, 236.dp)
        Column(Modifier.fillMaxSize().padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            Header(vm, s, link, record != null, preheat, compact, manualCooling)

            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                // graf
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    val samples = record?.samples ?: live
                    val roast = s?.roast
                    val marks = record?.marks ?: liveMarks
                    val chargeT = record?.chargeT ?: run {
                        val t = roast?.t
                        val last = samples.lastOrNull()?.t
                        if (roast != null && t != null && last != null && (roast.phase == "roast" || roast.phase == "cooling")) last - t
                        else liveMarks.lastOrNull { it.event == "charge" }?.t
                    }
                    val recipeOn = roast != null && (roast.phase == "roast" || roast.phase == "cooling") && portentaRecipe != null
                    val manualRoast = s != null && s.state.heating && roast?.running != true
                    val manualPreheat = preheat?.takeIf { manualRoast && it.phase != cz.prazicka.data.ManualPreheat.Phase.DONE }
                    Legend(roast?.phase == "preheat" || manualPreheat != null, recipeOn, settings.hiddenCurves, compact, onReset = if (manualRoast) ({ confirmReset = true }) else null)
                    RoastGraph(
                        samples = samples,
                        marks = marks,
                        chargeT = chargeT,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        recipeCurve = if (recipeOn) { t -> portentaRecipe!!.curveAt(t) } else null,
                        recipeEndT = portentaRecipe?.curve?.lastOrNull()?.t?.toDouble(),
                        bandTarget = if (roast?.phase == "preheat") portentaRecipe?.chargeBt else manualPreheat?.target,
                        hidden = settings.hiddenCurves,
                    )
                }
                ValuesPanel(vm, s, Modifier.width(valuesW).fillMaxHeight())
            }

            Row(Modifier.height(bottomH).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                FlameCard(vm, s, settings.lastManualSp, Modifier.weight(1f).widthIn(max = 380.dp).fillMaxHeight())
                VfdCard(vm, s, Modifier.weight(1f).widthIn(max = 380.dp).fillMaxHeight())
                EventsCard(vm, s, connected, (record?.marks ?: liveMarks).map { it.event }, portentaRecipe, manualCooling, onManualStart = { showStart = true }, go = go, modifier = Modifier.weight(1.3f).fillMaxHeight())
            }
        }
    }

    if (showStart) StartManualDialog(settings.lastManualSp, settings.lastManualPreheatMin, settings.recordManual, vm.client.hello?.has("pid") != false, onStart = { sp, rec, pre ->
        showStart = false
        vm.manualStart(sp, rec, pre)
    }, onCancel = { showStart = false })

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = C.surface,
            title = { Text("Resetovat graf?") },
            text = {
                Text(
                    "Dosavadní průběh se z grafu smaže a čas začne znovu od nuly." +
                        (if (record != null) " Z běžícího záznamu se odstraní i část před resetem, záznam pokračuje dál." else "") +
                        " Na pražičku to nemá žádný vliv.",
                )
            },
            // ochrana proti náhodnému dotyku: tlačítko je potřeba držet 2 s
            confirmButton = { HoldButton("Podrž 2 s – resetovat") { confirmReset = false; vm.resetGraph() } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Zrušit", color = C.text, fontWeight = FontWeight.Bold) } },
        )
    }
}

@Composable
private fun Header(vm: AppViewModel, s: Status?, link: LinkState, recording: Boolean, preheat: cz.prazicka.data.ManualPreheat.Ui?, compact: Boolean, manualCooling: Boolean) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (label, bg, fg) = when {
            s == null -> Triple("Nepřipojeno", C.surface2, C.muted)
            s.state == RoasterState.HALTED -> Triple("Chyba", C.stop, Color.White)
            s.roast?.phase == "preheat" -> Triple("Předehřev", C.heatBg, C.btSoft)
            s.roast?.phase == "cooling" || (manualCooling && !s.state.heating) -> Triple("Chlazení", C.coolBg, C.air)
            s.state == RoasterState.RUNNING -> Triple("Pražení", C.bt, C.bg)
            s.state.heating -> Triple(s.state.label, C.heatBg, C.btSoft)
            s.state == RoasterState.READY -> Triple("Připraveno", C.okBg, C.okText)
            else -> Triple(s.state.label, C.surface2, C.muted)
        }
        Chip(label.uppercase(), fg, bg)
        Column(Modifier.weight(1f)) {
            val roast = s?.roast
            Text(
                when {
                    s == null -> "Bez spojení s Portentou"
                    roast != null && roast.running -> roast.recipe
                    s.state.heating -> "Ruční pražení"
                    roast?.phase == "loaded" -> "Nahraná receptura: ${roast?.recipe ?: ""}"
                    else -> "Pražička"
                },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val manualPre = preheat?.takeIf { s != null && s.state.heating && roast?.running != true }
            Text(
                when {
                    s == null -> "Aplikace funguje i bez spojení – receptury, záznamy a nastavení"
                    roast?.phase == "preheat" && roast.preheatLeftS == null && !roast.chargeReady -> "Předehřev – ohřev na ${fmt0(s.ctrl.sp)} °C"
                    roast?.phase == "preheat" && !roast.chargeReady -> "Předehřev – výdrž na teplotě"
                    manualPre?.phase == cz.prazicka.data.ManualPreheat.Phase.HEATING -> "Předehřev – ohřev na ${fmt0(manualPre.target)} °C"
                    manualPre?.phase == cz.prazicka.data.ManualPreheat.Phase.HOLD -> "Předehřev – výdrž na ${fmt0(manualPre.target)} °C"
                    manualPre?.phase == cz.prazicka.data.ManualPreheat.Phase.DONE -> "Předehřev hotový – vsyp zrno"
                    roast != null && roast.running -> "Receptura · ${Texts.phase(roast.phase)} · ${Texts.mode(s.ctrl.mode)}"
                    s.state.heating && s.ctrl.mode == "pid" -> "Teplotu drží PID"
                    s.state.heating && s.ctrl.mode == "manual" -> "Plyn řízen ručně"
                    s.heatBlocked -> "Ohřev je po restartu zablokovaný"
                    else -> s.state.label
                },
                fontSize = 12.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val t = s?.roast?.t
        val preLeft = s?.roast?.preheatLeftS?.takeIf { s.roast?.phase == "preheat" }
            ?: preheat?.takeIf { s != null && s.state.heating && it.phase == cz.prazicka.data.ManualPreheat.Phase.HOLD }?.leftS
        if (preLeft != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text("zbývá", color = C.btSoft, fontSize = 11.sp)
                Text(mmss(preLeft), fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = C.btSoft)
            }
        } else {
            Text(if (t != null) mmss(t) else "—", fontFamily = Mono, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        if (s != null && (s.state.heating || manualCooling) && s.roast?.running != true) {
            if (recording) Chip("● Záznam", C.recFg, border = C.recBorder)
            else Box(Modifier.clickable { vm.startRecordingNow() }) { Chip("Bez záznamu · zapnout", C.muted, border = C.line) }
        }
        val (lt, lc) = when (link) {
            is LinkState.Connected -> (if (link.simulator) "Simulátor" else if (compact) "Spojeno" else "Spojeno · ${link.target}") to C.ok
            is LinkState.Connecting -> "Připojuji…" to C.warn
            is LinkState.Failed -> "Bez spojení" to C.danger
            LinkState.Replaced -> "Převzato" to C.warn
            LinkState.Idle -> "Nenastaveno" to C.faint
        }
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, C.line, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { Dot(lc); Text(lt, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        if (s != null) {
            Chip(if (s.stop) "STOP OK" else "STOP!", if (s.stop) C.okText else C.dangerText, if (s.stop) C.okBg else C.dangerBg)
            Chip(if (s.psu) "PSU OK" else "PSU!", if (s.psu) C.okText else C.dangerText, if (s.psu) C.okBg else C.dangerBg)
            val igniting = s.burner.ignition || s.state == RoasterState.IGNITION_SEQUENCE
            val (ft, ffg, fbg) = when {
                igniting -> Triple("ZAPALOVÁNÍ", C.warnText, C.warnBg)
                else -> when (s.burner.flame) {
                1 -> Triple(if (compact) "PLAMEN" else "PLAMEN OK", C.okText, C.okBg)
                2 -> Triple("PLAMEN NOK", C.dangerText, C.dangerBg)
                else -> Triple("PLAMEN VYP", C.muted, C.surface2)
                }
            }
            Chip(ft, ffg, fbg)
            if (s.sim != null) Chip(if (compact) "SIM" else "SIMULACE", C.warn, border = C.warn)
        }
    }
    if (link is LinkState.Failed && !link.retrying) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.dangerBg).padding(10.dp)) {
            Text(link.reason, color = C.dangerText, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Legend(preheat: Boolean, recipe: Boolean, hidden: Set<String>, compact: Boolean, onReset: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(C.bt, "Zrno")
        if (Curves.SP !in hidden) LegendItem(C.sp, if (preheat) "Cíl předehřevu" else if (recipe) "Křivka receptury" else "Cíl")
        if (Curves.AIR !in hidden) LegendItem(C.air, "Okolí")
        if (Curves.EXH !in hidden) LegendItem(C.exh, "Odtah")
        if (Curves.ROR !in hidden) LegendItem(C.ror, "RoR")
        if (Curves.GAS !in hidden) LegendItem(C.gasFill, "Plyn", 10.dp)
        Spacer1()
        if (!compact) Text("min", color = C.muted, fontSize = 12.sp)
        if (onReset != null) SecondaryButton("↺ Reset grafu", onReset, height = 32.dp)
    }
}

@Composable
private fun LegendItem(color: Color, label: String, h: Dp = 3.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(16.dp).height(h).background(color))
        Text(label, color = C.muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ValuesPanel(vm: AppViewModel, s: Status?, modifier: Modifier) {
    Panel(modifier.verticalScroll(rememberScrollState())) {
        Label("ZRNO")
        // požadovaná teplota malým písmem nad aktuální
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("požadovaná", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
            Text(if (s?.ctrl?.sp == null) "—" else "${fmt1(s?.ctrl?.sp)} °C", fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.sp)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fmt1(s?.temps?.bt), fontFamily = Mono, fontSize = 46.sp, fontWeight = FontWeight.SemiBold, color = C.bt, maxLines = 1)
            Text("°C", color = C.muted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.surface2).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RoR", color = C.muted, fontSize = 13.sp)
            Spacer1()
            Text(fmt1(s?.temps?.ror), fontFamily = Mono, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = C.ror)
            Text(" °C/min", color = C.muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueBox("Plyn", if (s?.burner?.power == true) fmt0(s?.burner?.gas) + " %" else "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueBox("Okolí", fmt1(s?.temps?.air), Modifier.weight(1f), color = C.airText)
            ValueBox("Odtah", fmt1(s?.temps?.exh), Modifier.weight(1f), color = C.exhText)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plamen ", color = C.muted, fontSize = 13.sp)
            Text(
                if (s?.burner?.ignition == true) "zapaluje se" else when (s?.burner?.flame) { 1 -> "hoří"; 2 -> "selhal"; else -> "nehoří" },
                color = when (s?.burner?.flame) { 1 -> C.okText; 2 -> C.dangerText; else -> C.muted },
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
        }
        SecondaryButton("Znovu zapálit", { vm.reignite() }, Modifier.fillMaxWidth(), enabled = s?.state == RoasterState.RUNNING, height = 40.dp)
    }
}

@Composable
private fun FlameCard(vm: AppViewModel, s: Status?, lastSp: Double, modifier: Modifier) {
    val running = s?.state == RoasterState.RUNNING
    val mode = s?.ctrl?.mode ?: "manual"
    val recipeRunning = s?.roast?.let { it.phase == "preheat" || it.phase == "roast" } == true
    val manualGas = running && mode == "manual"
    var pendingSp by remember { mutableStateOf<Double?>(null) }
    val shownSp = pendingSp ?: s?.ctrl?.sp ?: lastSp
    if (pendingSp != null && s?.ctrl?.sp == pendingSp) pendingSp = null

    Panel(modifier.verticalScroll(rememberScrollState()), border = if (manualGas) C.warn else C.line, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Plamen")
            Spacer1()
            val (ml, mbg, mfg) = when {
                !running -> Triple("Nehoří", C.surface2, C.muted)
                mode == "pid" -> Triple("Teplota (PID)", C.heatBg, C.btSoft)
                mode == "recipe" -> Triple("Receptura", C.heatBg, C.btSoft)
                else -> Triple("Ručně", C.warnBg, C.warnText)
            }
            Chip(ml, mfg, mbg)
        }
        if (manualGas) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.warnBg).border(1.dp, C.warn, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Plyn řídíš ručně", color = C.warnText, fontWeight = FontWeight.Bold)
                if (s?.roast?.let { it.phase == "preheat" || it.phase == "roast" } == true) {
                    PrimaryButton("Zpět na recepturu", { vm.modeSet("recipe") }, Modifier.fillMaxWidth(), container = C.bt)
                } else {
                    PrimaryButton("Řídit teplotou ${fmt0(shownSp)} °C", { vm.followTemperature() }, Modifier.fillMaxWidth(), container = C.bt)
                }
            }
        } else {
            val canSet = !recipeRunning && s != null && mode != "recipe"
            Row {
                Label("Cílová teplota zrna")
                Spacer1()
                Label(
                    when {
                        recipeRunning || mode == "recipe" -> "řídí křivka receptury"
                        running && mode == "pid" -> "PID ji drží"
                        else -> "nastavíš při spuštění"
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                fun change(d: Double) {
                    val v = (shownSp + d).coerceIn(50.0, 260.0)
                    pendingSp = v
                    vm.spSet(v)
                }
                StepBtn("−5", canSet && running) { change(-5.0) }
                Box(
                    Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(10.dp)).background(C.bg)
                        .border(1.dp, if (canSet && running) C.bt else C.line, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val v = if (recipeRunning || mode == "recipe") s?.ctrl?.sp else shownSp
                    Text("${fmt0(v)} °C", fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = if (canSet && running) C.bt else C.muted)
                }
                StepBtn("+5", canSet && running) { change(5.0) }
            }
        }
        // plyn: posunutí přepne na ruční plyn (API: gas_set → manual)
        var dragging by remember { mutableStateOf(false) }
        var local by remember { mutableFloatStateOf(30f) }
        val actual = (s?.burner?.gas ?: 10.0).toFloat().coerceIn(10f, 100f)
        val shown = if (dragging) local else actual
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plyn ", color = C.muted, fontSize = 12.sp)
            Text(if (s?.burner?.power == true || dragging) "${shown.toInt()} %" else "—", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer1()
            Label(if (manualGas) "řídíš ty" else if (running) "posunutím přepneš na ručně" else "")
        }
        Slider(
            value = shown,
            onValueChange = { dragging = true; local = it; vm.setGas(it.toDouble()) },
            onValueChangeFinished = { vm.setGas(local.toDouble()); dragging = false },
            valueRange = 10f..100f,
            enabled = running,
            colors = SliderDefaults.colors(
                thumbColor = if (manualGas) C.warnText else C.muted,
                activeTrackColor = if (manualGas) C.warn else C.gas,
                inactiveTrackColor = C.surface3,
            ),
        )
    }
}

@Composable
private fun StepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(42.dp).height(52.dp).clip(RoundedCornerShape(10.dp)).background(C.surface2)
            .border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) C.text else C.disabled, fontWeight = FontWeight.Bold) }
}

@Composable
private fun VfdCard(vm: AppViewModel, s: Status?, modifier: Modifier) {
    var edit by remember { mutableStateOf<Int?>(null) }
    Panel(modifier, padding = 12.dp) {
        SectionTitle("Měniče")
        for (row in 0..1) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    val v = s?.vfd?.getOrNull(i)
                    val override = s?.roast?.overrideVfd?.getOrNull(i) == true
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(C.surface2)
                            .border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable { edit = i }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row {
                            Text(Texts.vfdNames[i], fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer1()
                            if (override) Text("ručně", color = C.warn, fontSize = 11.sp)
                        }
                        val pct = v?.pct
                        Text(
                            when {
                                v == null -> "—"
                                pct == null -> "neodpovídá"
                                pct < 1.0 && (v.cmd ?: 0.0) <= 0.0 -> "stojí"
                                else -> fmt1(pct) + " %"
                            },
                            fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            color = if (v != null && pct == null) C.dangerText else C.text,
                        )
                        Text(
                            if (v == null) "" else "příkaz ${fmt0(v.cmd)} %" + (v.rpm?.let { " · ${fmt0(it)} ot/min" } ?: "") + (if ((v.err ?: 0) != 0) " · chyba ${v.err}" else ""),
                            color = C.muted, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    edit?.let { i ->
        VfdDialog(i, s?.vfd?.getOrNull(i)?.cmd ?: 0.0, s?.state?.heating == true && i <= 1, onSet = { vm.setVfd(i, it); edit = null }, onClose = { edit = null })
    }
}

@Composable
private fun VfdDialog(i: Int, current: Double, noStop: Boolean, onSet: (Int) -> Unit, onClose: () -> Unit) {
    var v by remember { mutableFloatStateOf(current.toFloat().coerceIn(0f, 100f)) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.surface,
        title = { Text(Texts.vfdNames[i]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (v < 10f) "STOP" else "${v.toInt()} %", fontFamily = Mono, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Slider(value = v, onValueChange = { v = if (it < 5f) 0f else it.coerceAtLeast(10f) }, valueRange = 0f..100f)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 10, 30, 50, 80, 100).forEach { q ->
                        SecondaryButton(if (q == 0) "STOP" else "$q", { v = q.toFloat() }, Modifier.weight(1f), height = 40.dp)
                    }
                }
                if (noStop) Text("Za ohřevu se buben a odtah nesmí zastavit – STOP Portenta neprovede.", color = C.warnText, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(v.toInt()) }) { Text("Nastavit", color = C.bt, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Zrušit", color = C.muted) } },
    )
}

private val markEvents = listOf("charge" to "Vsypání", "dry_end" to "Konec sušení", "fc_start" to "1. crack", "fc_end" to "1. crack konec", "sc_start" to "2. crack", "drop" to "Vysypání")

@Composable
private fun EventsCard(
    vm: AppViewModel,
    s: Status?,
    connected: Boolean,
    doneMarks: List<String>,
    portentaRecipe: Recipe?,
    manualCooling: Boolean,
    onManualStart: () -> Unit,
    go: (Screen) -> Unit,
    modifier: Modifier,
) {
    var confirmDrop by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    val roast = s?.roast
    val coolingManual = manualCooling && s != null && !s.state.heating && roast?.running != true
    val idle = s == null || (!s.state.heating && roast?.phase != "cooling" && !coolingManual)

    Panel(modifier, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(if (idle) "Spustit" else "Události")
            Spacer1()
            // fáze pražení – tady je víc vidět než pod tlačítkem Znovu zapálit
            val phaseText = when {
                roast != null && roast.running -> Texts.phase(roast.phase)
                coolingManual -> "Chlazení"
                s != null && s.state.heating -> if (doneMarks.contains("charge")) "Pražení" else "Předehřev"
                else -> null
            }
            if (phaseText != null) Chip("Fáze: $phaseText", C.btSoft, C.heatBg)
        }
        when {
            !connected -> {
                Text("Bez spojení s Portentou. Receptury, záznamy a nastavení fungují i tak.", color = C.muted)
                SecondaryButton("Otevřít nastavení připojení", { go(Screen.Settings) })
            }
            s != null && s.heatBlocked && s.state == RoasterState.READY -> {
                Text("Po restartu z chyby je ohřev zablokovaný. Potvrď, že je pražička v pořádku.", color = C.warnText)
                PrimaryButton("Odblokovat ohřev", { vm.heatStop() }, Modifier.fillMaxWidth(), container = C.warn)
            }
            coolingManual -> {
                Text("Chlazení po ručním pražení. Hořák je vypnutý, míchač a ventilátor běží, buben a odtah také.", color = C.sp)
                Text("Chlazení řídí aplikace – při výpadku spojení ho Portenta po 15 s zastaví.", color = C.muted, fontSize = 12.sp)
                PrimaryButton("Ukončit chlazení", { vm.endManualCooling() }, Modifier.fillMaxWidth())
            }
            roast?.phase == "cooling" -> {
                Text("Chlazení podle receptury. Míchač a ventilátor běží, hořák je vypnutý.", color = C.sp)
                PrimaryButton("Ukončit chlazení", { vm.recipeEnd() }, Modifier.fillMaxWidth())
            }
            idle -> {
                val ready = s?.state == RoasterState.READY
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigAction("Ruční pražení", "teplotu drží PID", C.bt, C.bg, ready, Modifier.weight(1f), onManualStart)
                    val loaded = roast?.phase == "loaded" && portentaRecipe != null
                    BigAction(
                        "Receptura",
                        if (loaded) "${roast?.recipe} · nahraná" else "vybrat v knihovně",
                        C.surface3, C.text, ready || !loaded, Modifier.weight(1f),
                    ) { if (loaded && ready) vm.recipeStart(portentaRecipe!!) else go(Screen.Recipes) }
                }
                if (!ready && s != null) Text("Spustit jde jen ve stavu Připraveno (teď: ${s.state.label}).", color = C.muted, fontSize = 12.sp)
            }
            else -> {
                val items = markEvents.map { (k, l) -> Triple(k, l, doneMarks.contains(k)) }
                for (row in 0..1) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0..3) {
                            val idx = row * 4 + col
                            when {
                                idx < items.size -> {
                                    val (key, label, done) = items[idx]
                                    val hot = key == "charge" && roast?.chargeReady == true || key == "drop" && roast?.dropReady == true
                                    MarkButton(label, if (done) "✓" else "", done, hot, Modifier.weight(1f)) {
                                        if (key == "drop") confirmDrop = true else vm.roastMark(key)
                                    }
                                }
                                idx == 6 -> MarkButton("Poznámka", "", false, false, Modifier.weight(1f)) { noteOpen = true }
                                else -> MarkButton("Smazat značku", "poslední", false, false, Modifier.weight(1f)) { vm.removeLastMark() }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDrop) {
        val recipe = roast?.phase == "roast"
        AlertDialog(
            onDismissRequest = { confirmDrop = false },
            containerColor = C.surface,
            title = { Text("Vysypáno?") },
            text = {
                Text(
                    if (recipe) "Portenta vypne hořák a spustí chlazení podle receptury."
                    else "Hořák se vypne a pražení skončí. Míchač a chladicí ventilátor se rozjedou, buben a odtah poběží dál. Chlazení ukončíš tlačítkem.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDrop = false; vm.roastMark("drop") }) { Text("Vysypáno", color = C.bt, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmDrop = false }) { Text("Zpět", color = C.muted) } },
        )
    }
    if (noteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            containerColor = C.surface,
            title = { Text("Poznámka do záznamu") },
            text = { Field(text, { text = it }, "Text", Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { noteOpen = false; vm.note(text) }) { Text("Uložit", color = C.bt) } },
            dismissButton = { TextButton(onClick = { noteOpen = false }) { Text("Zrušit", color = C.muted) } },
        )
    }
}

@Composable
private fun BigAction(title: String, sub: String, bg: Color, fg: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(if (enabled) bg else C.surface2)
            .clickable(enabled = enabled, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = if (enabled) fg else C.disabled, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = if (enabled) fg.copy(alpha = 0.8f) else C.disabled, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MarkButton(label: String, sub: String, done: Boolean, hot: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = when { hot -> C.bt; done -> C.surface2; else -> C.surface3 }
    val fg = when { hot -> C.bg; done -> C.muted; else -> C.text }
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(bg)
            .border(1.dp, if (hot) C.bt else C.line, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
        if (sub.isNotEmpty()) Text(sub, color = fg, fontSize = 11.sp, fontFamily = Mono)
    }
}

@Composable
private fun StartManualDialog(lastSp: Double, lastPreheatMin: Int, recordDefault: Boolean, pidAvailable: Boolean, onStart: (Double, Boolean, Int) -> Unit, onCancel: () -> Unit) {
    var sp by remember { mutableDoubleStateOf(lastSp.coerceIn(50.0, 260.0)) }
    var rec by remember { mutableStateOf(recordDefault) }
    var pre by remember { mutableIntStateOf(lastPreheatMin.coerceIn(0, 60)) }
    Modal(border = C.bt, width = 580.dp) {
        Text("Ruční pražení", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            if (pidAvailable) "Po zapálení drží teplotu zrna PID. Cíl můžeš kdykoli měnit, posunutím plynu převezmeš řízení ručně."
            else "Firmware zatím neumí PID – pražení začne s ručním plynem.",
            color = C.muted,
        )
        if (pidAvailable) {
            Label("Cílová teplota zrna", size = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-5.0, -1.0).forEach { d -> BigStep(if (d == -5.0) "−5" else "−1") { sp = (sp + d).coerceIn(50.0, 260.0) } }
                Box(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(12.dp)).background(C.bg).border(1.dp, C.bt, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("${fmt0(sp)} °C", fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = C.bt) }
                listOf(1.0, 5.0).forEach { d -> BigStep(if (d == 5.0) "+5" else "+1") { sp = (sp + d).coerceIn(50.0, 260.0) } }
            }
            Label("Předehřev – výdrž na cílové teplotě (prohřátí bubnu)", size = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BigStep("−1") { pre = (pre - 1).coerceIn(0, 60) }
                Box(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(12.dp)).background(C.bg).border(1.dp, C.line, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(if (pre == 0) "bez předehřevu" else "$pre min", fontFamily = Mono, fontSize = if (pre == 0) 20.sp else 30.sp, fontWeight = FontWeight.SemiBold) }
                BigStep("+1") { pre = (pre + 1).coerceIn(0, 60) }
                BigStep("+5") { pre = (pre + 5).coerceIn(0, 60) }
            }
            Label(if (pre > 0) "Z pokojové teploty na ${fmt0(sp)} °C, pak $pre min výdrž. Teprve potom se objeví výzva ke vsypání." else "Výzva ke vsypání se neobjeví – vsypání zadáš tlačítkem.")
        }
        Label("Zaznamenat toto pražení?", size = 13.sp)
        Segmented(listOf("yes" to "Ano, zaznamenat", "no" to "Ne"), if (rec) "yes" else "no", { rec = it == "yes" }, Modifier.fillMaxWidth(), height = 50.dp)
        Label("Výchozí odpověď nastavíš v Nastavení. Záznam jde zapnout i později během pražení.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (pre > 0) "Spustit předehřev" else "Spustit", { onStart(sp, rec, if (pidAvailable) pre else 0) }, Modifier.weight(1f), container = C.bt, height = 58.dp)
            SecondaryButton("Zrušit", onCancel, height = 58.dp)
        }
        Label("Buben a odtah se rozjedou na výchozí rychlost, pokud stojí. Pak kontrola a zapálení.")
    }
}

@Composable
private fun BigStep(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 60.dp, height = 64.dp).clip(RoundedCornerShape(12.dp)).background(C.surface2)
            .border(1.dp, C.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
}

/** Tlačítko, které provede akci až po podržení (ochrana proti náhodnému dotyku). */
@Composable
fun HoldButton(text: String, holdMs: Int = 2000, onDone: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(pressed) {
        if (pressed) {
            progress.animateTo(1f, androidx.compose.animation.core.tween(((1f - progress.value) * holdMs).toInt(), easing = androidx.compose.animation.core.LinearEasing))
            if (pressed) { pressed = false; progress.snapTo(0f); onDone() }
        } else {
            progress.animateTo(0f, androidx.compose.animation.core.tween(200))
        }
    }
    Box(
        Modifier.height(48.dp).widthIn(min = 200.dp).clip(RoundedCornerShape(10.dp)).background(C.surface3)
            .border(1.dp, C.bt, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(C.bt.copy(alpha = 0.55f)))
        Text(text, color = C.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
