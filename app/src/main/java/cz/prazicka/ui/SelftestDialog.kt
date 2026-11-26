package cz.prazicka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.data.Selftest
import cz.prazicka.protocol.Texts

/** Dialog „Probíhá selftest“ – kontrola zařízení při startu Portenty nebo připojení. */
@Composable
fun SelftestDialog(u: Selftest.Ui, onOk: () -> Unit) {
    val border = when {
        u.running -> C.line
        u.failed -> C.danger
        else -> C.ok
    }
    Modal(border = border, width = 620.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (u.running) CircularProgressIndicator(Modifier.size(26.dp), color = C.bt, trackColor = C.surface3, strokeWidth = 3.dp)
            Text(
                when {
                    u.running -> "Probíhá selftest"
                    u.failed -> "Selftest neprošel"
                    else -> "Selftest v pořádku"
                },
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
        }
        Text(
            if (u.live) "Portenta kontroluje zařízení: tlačítko STOP, napájení, čidla teploty a rozjezd i zastavení měničů. Nic nestiskej, dokud kontrola neskončí."
            else "Kontrola po připojení k pražičce.",
            color = C.muted, fontSize = 13.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckRow("Tlačítko STOP", u.stop)
            CheckRow("Napájení PSU", u.psu)
            CheckRow("Teploty", u.temps)
            CheckRow("Měniče", u.vfd) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    u.vfdEach.forEachIndexed { i, c ->
                        val (fg, bg) = colors(c)
                        Chip("${mark(c)} ${Texts.vfdNames[i]}", fg, bg)
                    }
                }
            }
        }
        u.note?.let { Text(it, color = if (u.failed) C.dangerText else C.muted, fontSize = 13.sp) }
        PrimaryButton(
            if (u.running) "Čekej na dokončení…" else "OK",
            onOk,
            Modifier.fillMaxWidth(),
            enabled = !u.running,
            container = if (u.failed) C.danger else C.ok,
            content = C.bg,
            height = 58.dp,
        )
    }
}

private fun mark(c: Selftest.Check) = when (c) {
    Selftest.Check.OK -> "✓"
    Selftest.Check.FAIL -> "✗"
    Selftest.Check.PENDING -> "…"
}

private fun colors(c: Selftest.Check): Pair<Color, Color> = when (c) {
    Selftest.Check.OK -> C.okText to C.okBg
    Selftest.Check.FAIL -> C.dangerText to C.dangerBg
    Selftest.Check.PENDING -> C.muted to C.surface2
}

@Composable
private fun CheckRow(title: String, item: Selftest.Item, extra: (@Composable () -> Unit)? = null) {
    val (fg, bg) = colors(item.check)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface2)
            .border(1.dp, if (item.check == Selftest.Check.FAIL) C.danger else C.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(item.detail, color = C.muted, fontSize = 12.sp)
            extra?.invoke()
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            if (item.check == Selftest.Check.PENDING) CircularProgressIndicator(Modifier.size(22.dp), color = C.muted, trackColor = C.surface3, strokeWidth = 2.dp)
            else Text(mark(item.check), color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
