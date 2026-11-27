package cz.prazicka.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.AppViewModel
import cz.prazicka.data.Backup
import cz.prazicka.data.CurvePoint
import cz.prazicka.data.Recipe
import cz.prazicka.data.RecipeValidator
import cz.prazicka.data.VfdPoint
import cz.prazicka.protocol.mmss
import cz.prazicka.protocol.RoasterState
import java.util.UUID

/** Rozpracovaná receptura v editoru (texty z polí). */
data class RecipeDraft(
    val id: String,
    val name: String,
    val chargeBt: String,
    val preheat: String,
    val chargeGas: String,
    val pidDelay: String,
    val curve: List<Pair<String, String>>,
    val vfd: List<List<String>>,
    val dropBt: String,
    val dropTMax: String,
    val cooler: String,
    val coolfan: String,
    val notes: String,
)

private fun n(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

fun Recipe.toDraft() = RecipeDraft(
    id = id, name = name, chargeBt = n(chargeBt), preheat = mmss(preheatS.toDouble()), chargeGas = n(chargeGas), pidDelay = mmss(pidDelayS.toDouble()),
    curve = curve.map { mmss(it.t.toDouble()) to n(it.bt) },
    vfd = vfd.map { listOf(mmss(it.t.toDouble()), it.drum.toString(), it.exhaust.toString(), it.cooler.toString(), it.coolfan.toString()) },
    dropBt = n(dropBt), dropTMax = mmss(dropTMax.toDouble()), cooler = coolCooler.toString(), coolfan = coolFan.toString(), notes = notes,
)

/** Převod na recepturu; při chybě vrátí text chyby. */
fun RecipeDraft.parse(): Pair<Recipe?, String?> {
    fun num(s: String, what: String) = parseNum(s) ?: throw IllegalArgumentException("$what: neplatné číslo")
    fun time(s: String, what: String) = parseTime(s) ?: throw IllegalArgumentException("$what: čas zadej jako mm:ss nebo sekundy")
    fun int(s: String, what: String) = s.trim().toIntOrNull() ?: throw IllegalArgumentException("$what: zadej celé číslo")
    return try {
        Recipe(
            id = id,
            name = name.trim(),
            chargeBt = num(chargeBt, "Vsypání při"),
            preheatS = time(preheat, "Výdrž předehřevu"),
            chargeGas = num(chargeGas, "Plyn po vsypání"),
            pidDelayS = time(pidDelay, "PID převezme po"),
            curve = curve.mapIndexed { i, (t, bt) -> CurvePoint(time(t, "Bod ${i + 1}"), num(bt, "Bod ${i + 1}")) },
            vfd = vfd.mapIndexed { i, r -> VfdPoint(time(r[0], "Řádek ${i + 1}"), int(r[1], "Řádek ${i + 1}"), int(r[2], "Řádek ${i + 1}"), int(r[3], "Řádek ${i + 1}"), int(r[4], "Řádek ${i + 1}")) },
            dropBt = num(dropBt, "Vysypání při"),
            dropTMax = time(dropTMax, "Vysypání nejpozději"),
            coolCooler = int(cooler, "Míchač"),
            coolFan = int(coolfan, "Ventilátor"),
            notes = notes,
        ) to null
    } catch (e: IllegalArgumentException) {
        null to e.message
    }
}

@Composable
fun RecipesScreen(vm: AppViewModel, go: (Screen) -> Unit) {
    val recipes by vm.recipes.collectAsState()
    val status by vm.status.collectAsState()
    val openId by vm.openRecipeId.collectAsState()
    val context = LocalContext.current
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<RecipeDraft?>(null) }

    LaunchedEffect(openId) {
        if (openId != null) { selectedId = openId; vm.openRecipeId.value = null; draft = null }
    }
    LaunchedEffect(recipes, selectedId) {
        if (selectedId == null) selectedId = recipes.firstOrNull()?.id
        if (draft == null || draft?.id != selectedId) recipes.firstOrNull { it.id == selectedId }?.let { draft = it.toDraft() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val parsed = readText(context, uri)?.let { Backup.parse(it) }
            if (parsed == null) vm.toast("Soubor nejde načíst") else vm.importData(parsed.first, parsed.second)
        }
    }

    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.width(300.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Receptury", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer1()
                SecondaryButton("Ze záznamu", { go(Screen.Records) }, height = 40.dp)
                PrimaryButton("+ Nová", {
                    val r = Recipe.sample().copy(id = UUID.randomUUID().toString().take(8), name = "Nová receptura")
                    selectedId = r.id
                    draft = r.toDraft()
                }, height = 40.dp)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(recipes, key = { it.id }) { r ->
                    val sel = r.id == selectedId
                    val loaded = status?.roast?.recipeId == r.id
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) C.surface3 else C.surface2)
                            .border(1.dp, if (sel) C.bt else C.line, RoundedCornerShape(10.dp))
                            .clickable { selectedId = r.id; draft = r.toDraft() }.padding(10.dp),
                    ) {
                        Row {
                            Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                            if (loaded) Text("nahraná", color = C.bt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("${mmss(r.curve.lastOrNull()?.t?.toDouble())} · vysypání ${n(r.dropBt)} °C", color = C.muted, fontSize = 12.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Import", { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.weight(1f))
                SecondaryButton("Export", {
                    val r = recipes.firstOrNull { it.id == selectedId }
                    if (r == null) vm.toast("Vyber recepturu") else shareFile(context, "receptura-${r.name}.json", "application/json", r.toStorageJson().toString())
                }, Modifier.weight(1f))
            }
        }

        val d = draft
        if (d == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("Vyber nebo vytvoř recepturu", color = C.muted) }
        } else {
            RecipeEditor(
                d, { draft = it }, Modifier.weight(1f).fillMaxHeight(),
                saved = recipes.any { it.id == d.id },
                canStart = status?.state == RoasterState.READY && status?.heatBlocked != true,
                onSave = { r -> vm.saveRecipe(r) },
                onLoad = { r -> vm.recipeLoad(r) },
                onStart = { r -> vm.saveRecipe(r); vm.recipeStart(r); go(Screen.Roast) },
                onDelete = { vm.deleteRecipe(d.id); selectedId = null; draft = null },
                onDuplicate = { r ->
                    val copy = r.copy(id = UUID.randomUUID().toString().take(8), name = (r.name + " kopie").take(32))
                    vm.saveRecipe(copy)
                    selectedId = copy.id
                    draft = copy.toDraft()
                },
            )
        }
    }
}

@Composable
private fun RecipeEditor(
    d: RecipeDraft,
    set: (RecipeDraft) -> Unit,
    modifier: Modifier,
    saved: Boolean,
    canStart: Boolean,
    onSave: (Recipe) -> Unit,
    onLoad: (Recipe) -> Unit,
    onStart: (Recipe) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: (Recipe) -> Unit,
) {
    val (recipe, parseErr) = d.parse()
    val validation = recipe?.let { RecipeValidator.validate(it.toApiJson())?.msg }
    val error = parseErr ?: validation
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(d.name, { set(d.copy(name = it.take(32))) }, "Název", Modifier.weight(1.6f))
                Field(d.chargeBt, { set(d.copy(chargeBt = it)) }, "Předehřev na (°C)", Modifier.weight(1f), numeric = true, mono = true)
                Field(d.preheat, { set(d.copy(preheat = it)) }, "Výdrž (mm:ss)", Modifier.weight(1f), mono = true)
                Field(d.chargeGas, { set(d.copy(chargeGas = it)) }, "Plyn po vsypání (%)", Modifier.weight(1f), numeric = true, mono = true)
                Field(d.pidDelay, { set(d.copy(pidDelay = it)) }, "PID převezme po", Modifier.weight(1f), mono = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f).height(330.dp)) {
                    SectionTitle("Křivka teploty zrna")
                    CurvePreview(recipe, Modifier.weight(1f).fillMaxWidth())
                }
                Panel(Modifier.width(280.dp).height(300.dp)) {
                    SectionTitle("Body křivky (${d.curve.size} z 32)")
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.curve.forEachIndexed { i, (t, bt) ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Field(t, { v -> set(d.copy(curve = d.curve.toMutableList().also { it[i] = v to bt })) }, "čas", Modifier.weight(1f), mono = true)
                                Field(bt, { v -> set(d.copy(curve = d.curve.toMutableList().also { it[i] = t to v })) }, "°C", Modifier.weight(1f), numeric = true, mono = true)
                                RemoveBtn(enabled = d.curve.size > 2) { set(d.copy(curve = d.curve.toMutableList().also { it.removeAt(i) })) }
                            }
                        }
                    }
                    SecondaryButton("+ Přidat bod", {
                        val last = d.curve.lastOrNull()
                        val t = (last?.first?.let { parseTime(it) } ?: 0) + 60
                        set(d.copy(curve = d.curve + (mmss(t.toDouble()) to (last?.second ?: "200"))))
                    }, Modifier.fillMaxWidth(), enabled = d.curve.size < 32, height = 40.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f)) {
                    SectionTitle("Rozpis měničů (%)")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Od času", "Buben", "Odtah", "Míchač", "Ventilátor").forEach { Label(it, modifierWeight = true) }
                        Box(Modifier.width(40.dp))
                    }
                    d.vfd.forEachIndexed { i, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            row.forEachIndexed { k, v ->
                                Field(v, { nv -> set(d.copy(vfd = d.vfd.toMutableList().also { it[i] = row.toMutableList().also { r -> r[k] = nv } })) }, "", Modifier.weight(1f), numeric = k > 0, mono = true)
                            }
                            RemoveBtn(enabled = d.vfd.size > 1) { set(d.copy(vfd = d.vfd.toMutableList().also { it.removeAt(i) })) }
                        }
                    }
                    SecondaryButton("+ Přidat řádek", {
                        val last = d.vfd.lastOrNull() ?: listOf("00:00", "80", "50", "0", "0")
                        val t = (parseTime(last[0]) ?: 0) + 60
                        set(d.copy(vfd = d.vfd + listOf(listOf(mmss(t.toDouble())) + last.drop(1))))
                    }, Modifier.fillMaxWidth(), enabled = d.vfd.size < 16, height = 40.dp)
                    Label("Hodnota platí od svého času do dalšího řádku. Buben a odtah nejméně 10 %, za ohřevu nesmí stát.")
                }
                Panel(Modifier.width(280.dp)) {
                    SectionTitle("Vysypání a chlazení")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(d.dropBt, { set(d.copy(dropBt = it)) }, "Zrno °C", Modifier.weight(1f), numeric = true, mono = true)
                        Field(d.dropTMax, { set(d.copy(dropTMax = it)) }, "nebo po", Modifier.weight(1f), mono = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(d.cooler, { set(d.copy(cooler = it)) }, "Míchač %", Modifier.weight(1f), numeric = true, mono = true)
                        Field(d.coolfan, { set(d.copy(coolfan = it)) }, "Ventilátor %", Modifier.weight(1f), numeric = true, mono = true)
                    }
                    Label("Portenta jen ohlásí „připraveno k vysypání“, vysypáváš ty.")
                }
            }
            Field(d.notes, { set(d.copy(notes = it)) }, "Poznámka", Modifier.fillMaxWidth())
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Dot(if (error == null) C.ok else C.danger, 10.dp)
            Column(Modifier.weight(1f)) {
                Text(if (error == null) "Receptura je v pořádku" else error, fontWeight = FontWeight.SemiBold, color = if (error == null) C.text else C.dangerText, maxLines = 2)
                Label(if (saved) "Zkontrolováno stejnými pravidly jako v Portentě" else "Zatím neuloženo")
            }
            SecondaryButton("Smazat", { confirmDelete = true }, enabled = saved, color = C.dangerText)
            SecondaryButton("Duplikovat", { recipe?.let(onDuplicate) }, enabled = recipe != null)
            SecondaryButton("Uložit", { recipe?.let(onSave) }, enabled = recipe != null)
            SecondaryButton("Nahrát do Portenty", { recipe?.let(onLoad) }, enabled = recipe != null && error == null)
            PrimaryButton("Spustit předehřev", { recipe?.let(onStart) }, enabled = recipe != null && error == null && canStart, container = C.bt)
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = C.surface,
            title = { Text("Smazat recepturu?") },
            text = { Text("„${d.name}“ se smaže z knihovny v tabletu. Nejde to vrátit.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Smazat", color = C.dangerText) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Zrušit", color = C.muted) } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Label(text: String, modifierWeight: Boolean) {
    Text(text, color = C.muted, fontSize = 12.sp, modifier = if (modifierWeight) Modifier.weight(1f) else Modifier)
}

@Composable
private fun RemoveBtn(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(40.dp).height(40.dp).clip(RoundedCornerShape(8.dp)).background(C.surface2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("×", color = if (enabled) C.muted else C.disabled, fontSize = 20.sp) }
}

@Composable
private fun CurvePreview(r: Recipe?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // legenda
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(16.dp).height(3.dp).background(C.bt)); Text("Křivka zrna", color = C.muted, fontSize = 11.sp)
            Box(Modifier.width(16.dp).height(2.dp).background(C.faint)); Text("Vysypání", color = C.muted, fontSize = 11.sp)
            Box(Modifier.width(16.dp).height(10.dp).background(C.gas.copy(alpha = 0.25f))); Text("Pevný plyn do převzetí PID", color = C.muted, fontSize = 11.sp)
        }
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val label = TextStyle(color = C.muted, fontSize = 10.sp)
            val left = 34.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 30.dp.toPx()
            if (right <= left || bottom <= top) return@Canvas
            val tMax = maxOf(720.0, ((r?.dropTMax ?: 720) + 60).toDouble())
            fun x(t: Double) = left + (t / tMax).toFloat() * (right - left)
            fun y(v: Double) = top + (1f - ((v.coerceIn(50.0, 260.0) - 50.0) / 210.0).toFloat()) * (bottom - top)
            // osa Y: teplota zrna
            var g = 50.0
            while (g <= 250.0) {
                drawLine(C.surface3, Offset(left, y(g)), Offset(right, y(g)), 1f)
                drawText(tm, g.toInt().toString(), Offset(2f, y(g) - 7.dp.toPx()), label)
                g += 50.0
            }
            // osa X: čas od vsypání (min)
            val step = if (tMax > 1200) 240.0 else 120.0
            var t = 0.0
            while (t <= tMax) {
                drawLine(C.surface3, Offset(x(t), top), Offset(x(t), bottom), 1f)
                drawText(tm, (t / 60).toInt().toString(), Offset(x(t) - 3.dp.toPx(), bottom + 2.dp.toPx()), label)
                t += step
            }
            drawText(tm, "°C", Offset(2f, bottom + 2.dp.toPx()), label)
            drawText(tm, "čas od vsypání (min)", Offset((left + right) / 2 - 50.dp.toPx(), bottom + 15.dp.toPx()), label)
            if (r == null || r.curve.size < 2) return@Canvas
            val pidX = x(r.pidDelayS.toDouble())
            drawRect(C.gas.copy(alpha = 0.18f), Offset(x(0.0), top), Size(pidX - x(0.0), bottom - top))
            drawText(tm, "PID od ${mmss(r.pidDelayS.toDouble())}", Offset(pidX + 3.dp.toPx(), bottom - 16.dp.toPx()), label)
            drawLine(C.faint, Offset(left, y(r.dropBt)), Offset(right, y(r.dropBt)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            drawText(tm, "vysypání ${r.dropBt.toInt()} °C", Offset(right - 110.dp.toPx(), y(r.dropBt) - 16.dp.toPx()), label)
            val p = Path()
            r.curve.forEachIndexed { i, c -> if (i == 0) p.moveTo(x(c.t.toDouble()), y(c.bt)) else p.lineTo(x(c.t.toDouble()), y(c.bt)) }
            p.lineTo(x(tMax), y(r.curve.last().bt))
            drawPath(p, C.bt, style = Stroke(width = 5f))
            r.curve.forEach { c ->
                drawCircle(C.bg, 9f, Offset(x(c.t.toDouble()), y(c.bt)))
                drawCircle(C.bt, 9f, Offset(x(c.t.toDouble()), y(c.bt)), style = Stroke(4f))
            }
        }
    }
}
