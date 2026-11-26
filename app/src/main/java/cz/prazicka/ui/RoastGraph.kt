package cz.prazicka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.prazicka.data.Mark
import cz.prazicka.data.Sample
import cz.prazicka.protocol.Texts
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Které křivky lze skrýt v nastavení. */
object Curves {
    const val BT = "bt"
    const val SP = "sp"
    const val AIR = "air"
    const val EXH = "exh"
    const val ROR = "ror"
    const val GAS = "gas"
    val all = listOf(BT to "Zrno", SP to "Křivka / cíl", AIR to "Vzduch", EXH to "Odtah", ROR to "RoR", GAS to "Plyn")
}

/**
 * Graf pražení: teploty (levá osa), RoR (pravá osa), plyn v pásu dole, značky událostí.
 * Čas na ose X je od vsypání (když je známé), jinak od začátku.
 */
@Composable
fun RoastGraph(
    samples: List<Sample>,
    marks: List<Mark>,
    chargeT: Double?,
    modifier: Modifier = Modifier,
    recipeCurve: ((Double) -> Double)? = null,
    recipeEndT: Double? = null,
    bandTarget: Double? = null,
    hidden: Set<String> = emptySet(),
    minSpan: Double = 720.0,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val left = 40.dp.toPx()
        val right = size.width - 30.dp.toPx()
        val top = 8.dp.toPx()
        val gasH = size.height * 0.15f
        val plotBottom = size.height - gasH - 26.dp.toPx()
        val gasTop = plotBottom + 22.dp.toPx()
        val gasBottom = size.height - 2.dp.toPx()
        if (right <= left || plotBottom <= top) return@Canvas

        val origin = chargeT ?: (samples.firstOrNull()?.t ?: 0.0)
        val first = (samples.firstOrNull()?.t ?: origin) - origin
        val last = (samples.lastOrNull()?.t ?: origin) - origin
        val tMin = min(0.0, floor(first / 60.0) * 60.0)
        val tMax = max(tMin + minSpan, ceil((last + 60.0) / 60.0) * 60.0)
        val tLo = 20.0
        val tHi = 260.0

        fun x(t: Double) = left + ((t - tMin) / (tMax - tMin)).toFloat() * (right - left)
        fun yT(v: Double) = top + (1f - ((v.coerceIn(tLo, tHi) - tLo) / (tHi - tLo)).toFloat()) * (plotBottom - top)
        fun yR(v: Double) = top + (1f - (v.coerceIn(0.0, 30.0) / 30.0).toFloat()) * (plotBottom - top)
        fun yG(v: Double) = gasBottom - (v.coerceIn(0.0, 100.0) / 100.0).toFloat() * (gasBottom - gasTop)

        val label = TextStyle(color = C.muted, fontSize = 10.sp)

        // mřížka teplot
        var v = 50.0
        while (v <= 250.0) {
            val y = yT(v)
            drawLine(C.surface3, Offset(left, y), Offset(right, y), 1f)
            text(tm, v.toInt().toString(), Offset(2f, y - 7.dp.toPx()), label)
            v += 50.0
        }
        // RoR osa vpravo
        if (Curves.ROR !in hidden) {
            for (r in listOf(0, 10, 20, 30)) text(tm, r.toString(), Offset(right + 4.dp.toPx(), yR(r.toDouble()) - 7.dp.toPx()), label.copy(color = C.ror))
        }
        // časová mřížka po 2 minutách
        val stepT = if (tMax - tMin > 1500) 240.0 else 120.0
        var tt = ceil(tMin / stepT) * stepT
        while (tt <= tMax) {
            val xx = x(tt)
            drawLine(C.surface3, Offset(xx, top), Offset(xx, plotBottom), 1f)
            text(tm, (tt / 60).toInt().toString(), Offset(xx - 4.dp.toPx(), plotBottom + 3.dp.toPx()), label)
            tt += stepT
        }
        drawLine(C.surface3, Offset(left, gasTop), Offset(right, gasTop), 1f)
        drawLine(C.surface3, Offset(left, gasBottom), Offset(right, gasBottom), 1f)
        text(tm, "plyn", Offset(2f, gasTop + 2.dp.toPx()), label)

        // pásmo předehřevu ±3 °C
        if (bandTarget != null) {
            val y1 = yT(bandTarget + 3)
            val y2 = yT(bandTarget - 3)
            drawRect(C.bt.copy(alpha = 0.14f), Offset(left, y1), Size(right - left, y2 - y1))
        }

        // plyn
        if (Curves.GAS !in hidden) {
            val pts = samples.mapNotNull { s -> s.gas?.let { (s.t - origin) to it } }
            if (pts.size >= 2) {
                val area = Path()
                area.moveTo(x(pts.first().first), gasBottom)
                pts.forEach { (t, g) -> area.lineTo(x(t), yG(g)) }
                area.lineTo(x(pts.last().first), gasBottom)
                area.close()
                drawPath(area, C.gasFill)
            }
        }

        // křivka receptury nebo cíl
        if (Curves.SP !in hidden) {
            if (recipeCurve != null) {
                val p = Path()
                var t = 0.0
                val end = min(tMax, max(recipeEndT ?: tMax, 0.0) + 120.0)
                var firstPt = true
                while (t <= end) {
                    val px = x(t)
                    val py = yT(recipeCurve(t))
                    if (firstPt) p.moveTo(px, py) else p.lineTo(px, py)
                    firstPt = false
                    t += 5.0
                }
                drawPath(p, C.sp.copy(alpha = 0.75f), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            } else {
                series(samples, origin, { it.sp }, ::x, ::yT, C.sp.copy(alpha = 0.75f), 1.5.dp.toPx(), dashed = true)
            }
        }

        if (Curves.EXH !in hidden) series(samples, origin, { it.exh }, ::x, ::yT, C.exh, 2.dp.toPx())
        if (Curves.AIR !in hidden) series(samples, origin, { it.air }, ::x, ::yT, C.air, 2.dp.toPx())
        if (Curves.ROR !in hidden) series(samples, origin, { it.ror }, ::x, ::yR, C.ror, 2.dp.toPx())
        if (Curves.BT !in hidden) series(samples, origin, { it.bt }, ::x, ::yT, C.bt, 3.dp.toPx())

        // značky
        marks.forEachIndexed { i, m ->
            val xx = x(m.t - origin)
            val col = when (m.event) {
                "fc_start", "fc_end", "sc_start" -> C.ror
                "note" -> C.purple
                else -> C.muted
            }
            drawLine(col, Offset(xx, top), Offset(xx, plotBottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val name = if (m.event == "note" && m.text.isNotBlank()) m.text.take(18) else Texts.roastEvent(m.event)
            text(tm, name, Offset(xx + 4.dp.toPx(), top + 4.dp.toPx() + (i % 3) * 16.dp.toPx()), TextStyle(color = col, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
        }

        // aktuální bod
        samples.lastOrNull()?.let { s ->
            val b = s.bt
            if (b != null && Curves.BT !in hidden) {
                drawCircle(C.bt, 5.dp.toPx(), Offset(x(s.t - origin), yT(b)))
                drawCircle(C.bg, 5.dp.toPx(), Offset(x(s.t - origin), yT(b)), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.text(tm: TextMeasurer, s: String, at: Offset, style: TextStyle) {
    drawText(textMeasurer = tm, text = s, topLeft = at, style = style)
}

private fun DrawScope.series(
    samples: List<Sample>,
    origin: Double,
    value: (Sample) -> Double?,
    x: (Double) -> Float,
    y: (Double) -> Float,
    color: Color,
    width: Float,
    dashed: Boolean = false,
) {
    val p = Path()
    var penDown = false
    var lastT = Double.NaN
    for (s in samples) {
        val v = value(s)
        val gap = !lastT.isNaN() && s.t - lastT > 3.0
        if (v == null || gap) penDown = false
        if (v != null) {
            val px = x(s.t - origin)
            val py = y(v)
            if (penDown) p.lineTo(px, py) else p.moveTo(px, py)
            penDown = true
        }
        lastT = s.t
    }
    drawPath(
        p, color,
        style = Stroke(width = width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null),
    )
}
