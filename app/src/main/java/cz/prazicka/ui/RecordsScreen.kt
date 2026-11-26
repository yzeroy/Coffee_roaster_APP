package cz.prazicka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.AppViewModel
import cz.prazicka.data.CsvExport
import cz.prazicka.data.RoastRecord
import cz.prazicka.protocol.Texts
import cz.prazicka.protocol.fmt0
import cz.prazicka.protocol.fmt1
import cz.prazicka.protocol.mmss
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("d. M. yyyy HH:mm", Locale("cs"))

@Composable
fun RecordsScreen(vm: AppViewModel, go: (Screen) -> Unit) {
    val records by vm.records.collectAsState()
    val current by vm.currentRecord.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val list = records.filter { filter == "all" || (filter == "manual") == it.manual }
    val selected = records.firstOrNull { it.id == selectedId } ?: list.firstOrNull()

    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.width(300.dp).fillMaxHeight()) {
            Text("Záznamy", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Segmented(listOf("all" to "Vše", "manual" to "Ručně", "recipe" to "Receptura"), filter, { filter = it }, Modifier.fillMaxWidth(), height = 36.dp)
            current?.let {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface2).border(1.dp, C.danger, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("● Právě se zaznamenává: ${it.title}", color = C.recFg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (list.isEmpty()) Text("Zatím žádné záznamy.", color = C.muted)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { r ->
                    val sel = r.id == selected?.id
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) C.surface3 else C.surface2)
                            .border(1.dp, if (sel) C.bt else C.line, RoundedCornerShape(10.dp))
                            .clickable { selectedId = r.id }.padding(10.dp),
                    ) {
                        Row {
                            Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(
                                if (r.gaps > 0) "mezera" else if (r.manual) "ručně" else "receptura",
                                color = if (r.gaps > 0) C.dangerText else if (r.manual) C.warn else C.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "${dateFmt.format(Date(r.startedAt))} · ${mmss(r.durationS)}" + (r.dropT?.let { t -> " · ${fmt0(r.marks.lastOrNull { it.event == "drop" }?.bt)} °C" } ?: ""),
                            color = C.muted, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                }
            }
        }
        if (selected == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Záznam vznikne při pražení. U ručního pražení se aplikace zeptá, zda zaznamenávat.", color = C.muted)
            }
        } else {
            RecordDetail(vm, selected, go, Modifier.weight(1f).fillMaxHeight())
        }
    }
}


@Composable
private fun RecordDetail(vm: AppViewModel, r: RoastRecord, go: (Screen) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var notes by remember(r.id) { mutableStateOf(r.notes) }
    var wIn by remember(r.id) { mutableStateOf(r.weightIn?.let { fmt1(it) }?.replace(",0", "") ?: "") }
    var wOut by remember(r.id) { mutableStateOf(r.weightOut?.let { fmt1(it) }?.replace(",0", "") ?: "") }
    var recipeName by remember(r.id) { mutableStateOf(r.title.take(32)) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(r.title + if (r.manual) " · ruční pražení" else "", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Label("${dateFmt.format(Date(r.startedAt))} · firmware ${r.fw.ifEmpty { "?" }} · aplikace ${r.appVersion}" + if (r.gaps > 0) " · mezery ve spojení: ${r.gaps}" else "")
            }
            SecondaryButton("Export CSV", { shareFile(context, "prazeni-${r.id}.csv", "text/csv", CsvExport.record(r)) })
            SecondaryButton("Smazat", { confirmDelete = true }, color = C.dangerText)
        }
        Panel(Modifier.weight(1f).fillMaxWidth()) {
            RoastGraph(r.samples, r.marks, r.chargeT, Modifier.fillMaxSize(), minSpan = 600.0)
        }
        Row(Modifier.height(250.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                SectionTitle("Události a poznámky")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val c = r.chargeT ?: 0.0
                    if (r.marks.isEmpty()) Label("Bez značek")
                    r.marks.forEach { m ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (m.event == "note") "Poznámka: ${m.text}" else Texts.roastEvent(m.event), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(mmss(m.t - c), fontFamily = Mono, fontSize = 13.sp)
                            Text(if (m.bt == null) "" else "${fmt0(m.bt)} °C", fontFamily = Mono, fontSize = 13.sp, color = C.btSoft)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(wIn, { wIn = it }, "Před (kg)", Modifier.width(110.dp), numeric = true, mono = true)
                    Field(wOut, { wOut = it }, "Po (kg)", Modifier.width(110.dp), numeric = true, mono = true)
                    val loss = r.copy(weightIn = parseNum(wIn), weightOut = parseNum(wOut)).lossPct
                    Text("Úbytek ${if (loss == null) "—" else fmt1(loss) + " %"}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(notes, { notes = it }, "Poznámka", Modifier.weight(1f))
                    SecondaryButton("Uložit", { vm.saveRecord(r.copy(notes = notes, weightIn = parseNum(wIn), weightOut = parseNum(wOut))); vm.toast("Záznam uložen") })
                }
            }
            Panel(Modifier.weight(0.7f).widthIn(max = 380.dp).fillMaxHeight(), border = C.bt) {
                SectionTitle("Receptura z tohoto záznamu", C.btSoft)
                Field(recipeName, { recipeName = it.take(32) }, "Název", Modifier.fillMaxWidth())
                Text(
                    "Vezme teplotu při vsypání, plyn do bodu zvratu, zjednodušenou křivku zrna (max. 32 bodů) a změny měničů. " +
                        "Ruční kroky plynu se nepřenášejí – v receptuře plyn řídí PID podle křivky.",
                    color = C.muted, fontSize = 12.sp,
                )
                Grow()
                PrimaryButton("Vytvořit a otevřít v editoru", {
                    vm.recipeFromRecord(r, recipeName.ifBlank { r.title }) { go(Screen.Recipes) }
                }, Modifier.fillMaxWidth(), container = C.bt, enabled = r.samples.size >= 20)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = C.surface,
            title = { Text("Smazat záznam?") },
            text = { Text("Záznam „${r.title}“ z ${dateFmt.format(Date(r.startedAt))} se smaže. Nejde to vrátit.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteRecord(r.id) }) { Text("Smazat", color = C.dangerText) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit", color = C.muted) } },
        )
    }
}
