package cz.prazicka.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sada barev jednoho motivu. */
class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val disabled: Color,
    val bt: Color,
    val btSoft: Color,
    val sp: Color,
    val air: Color,
    val exh: Color,
    val ror: Color,
    val gas: Color,
    val gasFill: Color,
    val ok: Color,
    val okText: Color,
    val okBg: Color,
    val warn: Color,
    val warnText: Color,
    val warnBg: Color,
    val danger: Color,
    val dangerText: Color,
    val dangerBg: Color,
    val stop: Color,
    val purple: Color,
    val purpleText: Color,
    val purpleBg: Color,
    val heatBg: Color,
    val coolBg: Color,
    val airText: Color,
    val exhText: Color,
    val recFg: Color,
    val recBorder: Color,
    val linkText: Color,
    val modalDangerBg: Color,
    val scrim: Color,
    val okPanelBg: Color,
    val testActiveBg: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0F1215),
    surface = Color(0xFF171B20),
    surface2 = Color(0xFF1F252C),
    surface3 = Color(0xFF262D35),
    line = Color(0xFF2B333C),
    text = Color(0xFFE9EDF1),
    muted = Color(0xFFA3ADB8),
    faint = Color(0xFF7D8894),
    disabled = Color(0xFF5F6A75),
    bt = Color(0xFFFF8A3D),
    btSoft = Color(0xFFFFB27A),
    sp = Color(0xFFC9D1D9),
    air = Color(0xFF57B4FF),
    exh = Color(0xFFC39BFF),
    ror = Color(0xFFF2D14B),
    gas = Color(0xFF8A96A3),
    gasFill = Color(0xFF3A434D),
    ok = Color(0xFF46C98B),
    okText = Color(0xFF7FE0B0),
    okBg = Color(0xFF1B2A22),
    warn = Color(0xFFF2B84B),
    warnText = Color(0xFFF7D58E),
    warnBg = Color(0xFF2E2614),
    danger = Color(0xFFFF5A5A),
    dangerText = Color(0xFFFF9C9C),
    dangerBg = Color(0xFF3A1A1C),
    stop = Color(0xFFC9302C),
    purple = Color(0xFFB48CFF),
    purpleText = Color(0xFFCDB8FF),
    purpleBg = Color(0xFF2A2540),
    heatBg = Color(0xFF3A2A1A),
    coolBg = Color(0xFF16263A),
    airText = Color(0xFF8CCBFF),
    exhText = Color(0xFFD6BEFF),
    recFg = Color(0xFFFFB3B3),
    recBorder = Color(0xFFFF8080),
    linkText = Color(0xFFE0B4B4),
    modalDangerBg = Color(0xFF1C1416),
    scrim = Color(0xA0050709),
    okPanelBg = Color(0xFF15211B),
    testActiveBg = Color(0xFF3A2E12),
)

val LightPalette = Palette(
    bg = Color(0xFFEEF1F4),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF3F5F7),
    surface3 = Color(0xFFE3E7EC),
    line = Color(0xFFD0D7DF),
    text = Color(0xFF141A20),
    muted = Color(0xFF55606B),
    faint = Color(0xFF6C7782),
    disabled = Color(0xFFA3ACB5),
    bt = Color(0xFFE0661A),
    btSoft = Color(0xFFB4500F),
    sp = Color(0xFF4A5561),
    air = Color(0xFF1A73C8),
    exh = Color(0xFF7A47D1),
    ror = Color(0xFFA67F00),
    gas = Color(0xFF6B7681),
    gasFill = Color(0xFFCBD2DA),
    ok = Color(0xFF1E9E63),
    okText = Color(0xFF137347),
    okBg = Color(0xFFDDF3E8),
    warn = Color(0xFFC98A12),
    warnText = Color(0xFF7D5400),
    warnBg = Color(0xFFFBF0D6),
    danger = Color(0xFFD63030),
    dangerText = Color(0xFFB01E1E),
    dangerBg = Color(0xFFFBE3E3),
    stop = Color(0xFFC9302C),
    purple = Color(0xFF7A47D1),
    purpleText = Color(0xFF5B33A8),
    purpleBg = Color(0xFFEEE6FB),
    heatBg = Color(0xFFFDEBDD),
    coolBg = Color(0xFFDFEDFB),
    airText = Color(0xFF1A6BB8),
    exhText = Color(0xFF6A3CC0),
    recFg = Color(0xFFB01E1E),
    recBorder = Color(0xFFD63030),
    linkText = Color(0xFF7A2E2E),
    modalDangerBg = Color(0xFFFFF5F5),
    scrim = Color(0x80202830),
    okPanelBg = Color(0xFFE3F5EC),
    testActiveBg = Color(0xFFFBF0D6),
)

/**
 * Barvy aktuálního motivu. Hodnota je stav Compose, takže změna motivu překreslí vše, co barvy čte.
 */
object C {
    var p: Palette by mutableStateOf(DarkPalette)
    val bg: Color get() = p.bg
    val surface: Color get() = p.surface
    val surface2: Color get() = p.surface2
    val surface3: Color get() = p.surface3
    val line: Color get() = p.line
    val text: Color get() = p.text
    val muted: Color get() = p.muted
    val faint: Color get() = p.faint
    val disabled: Color get() = p.disabled
    val bt: Color get() = p.bt
    val btSoft: Color get() = p.btSoft
    val sp: Color get() = p.sp
    val air: Color get() = p.air
    val exh: Color get() = p.exh
    val ror: Color get() = p.ror
    val gas: Color get() = p.gas
    val gasFill: Color get() = p.gasFill
    val ok: Color get() = p.ok
    val okText: Color get() = p.okText
    val okBg: Color get() = p.okBg
    val warn: Color get() = p.warn
    val warnText: Color get() = p.warnText
    val warnBg: Color get() = p.warnBg
    val danger: Color get() = p.danger
    val dangerText: Color get() = p.dangerText
    val dangerBg: Color get() = p.dangerBg
    val stop: Color get() = p.stop
    val purple: Color get() = p.purple
    val purpleText: Color get() = p.purpleText
    val purpleBg: Color get() = p.purpleBg
    val heatBg: Color get() = p.heatBg
    val coolBg: Color get() = p.coolBg
    val airText: Color get() = p.airText
    val exhText: Color get() = p.exhText
    val recFg: Color get() = p.recFg
    val recBorder: Color get() = p.recBorder
    val linkText: Color get() = p.linkText
    val modalDangerBg: Color get() = p.modalDangerBg
    val scrim: Color get() = p.scrim
    val okPanelBg: Color get() = p.okPanelBg
    val testActiveBg: Color get() = p.testActiveBg
}

val Mono = FontFamily.Monospace

/** Motiv: "dark", "light", "system". Měřítko rozhraní 0.9–1.2. */
@Composable
fun PrazickaTheme(theme: String = "dark", scale: Float = 1f, content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val palette = if (dark) DarkPalette else LightPalette
    SideEffect { if (C.p !== palette) C.p = palette }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
        MaterialTheme(
            colorScheme = base.copy(
                primary = palette.text,
                onPrimary = palette.bg,
                secondary = palette.bt,
                background = palette.bg,
                onBackground = palette.text,
                surface = palette.surface,
                onSurface = palette.text,
                surfaceVariant = palette.surface2,
                onSurfaceVariant = palette.muted,
                outline = palette.line,
                error = palette.danger,
            ),
            content = content,
        )
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    border: Color = C.line,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(C.surface)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, color: Color = C.muted) {
    Text(text.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
fun Label(text: String, color: Color = C.muted, size: TextUnit = 12.sp) {
    Text(text, color = color, fontSize = size)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = C.text,
    content: Color = C.bg,
    height: Dp = 48.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = C.surface2,
            disabledContentColor = C.disabled,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) { Text(text, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = C.text,
    border: Color = C.line,
    height: Dp = 44.dp,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (enabled) border else C.surface3),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = C.surface2, contentColor = color, disabledContentColor = C.disabled),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** Přepínač z několika voleb (Ručně / PID / Receptura …). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 40.dp) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(C.bg)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (key, label) ->
            val sel = key == selected
            Button(
                onClick = { onSelect(key) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(height),
                shape = RoundedCornerShape(7.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (sel) C.text else Color.Transparent,
                    contentColor = if (sel) C.bg else C.muted,
                    disabledContainerColor = if (sel) C.surface3 else Color.Transparent,
                    disabledContentColor = C.disabled,
                ),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { Text(label, fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
fun Chip(text: String, fg: Color, bg: Color = Color.Transparent, border: Color? = null) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .let { if (border != null) it.border(1.dp, border, RoundedCornerShape(8.dp)) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun ValueBox(label: String, value: String, modifier: Modifier = Modifier, color: Color = C.text, size: TextUnit = 20.sp) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(C.surface2)
            .padding(10.dp),
    ) {
        Label(label)
        Text(value, color = color, fontFamily = Mono, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = C.text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = C.muted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    password: Boolean = false,
    mono: Boolean = false,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        modifier = modifier,
        textStyle = TextStyle(color = C.text, fontSize = 15.sp, fontFamily = if (mono) Mono else FontFamily.Default),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else if (password) KeyboardType.Password else KeyboardType.Text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.bt,
            unfocusedBorderColor = C.line,
            focusedLabelColor = C.btSoft,
            unfocusedLabelColor = C.muted,
            cursorColor = C.bt,
            focusedContainerColor = C.bg,
            unfocusedContainerColor = C.bg,
            disabledContainerColor = C.bg,
            disabledTextColor = C.disabled,
        ),
    )
}

@Composable
fun Dot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
}

/** Ikona plamene (kreslená, bez knihovny ikon). */
@Composable
fun FlameIcon(color: Color, size: Dp = 22.dp, crossed: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.1f)
            cubicTo(w * 0.55f, h * 0.3f, w * 0.75f, h * 0.38f, w * 0.75f, h * 0.6f)
            cubicTo(w * 0.75f, h * 0.8f, w * 0.62f, h * 0.92f, w * 0.5f, h * 0.92f)
            cubicTo(w * 0.38f, h * 0.92f, w * 0.25f, h * 0.8f, w * 0.25f, h * 0.6f)
            cubicTo(w * 0.25f, h * 0.48f, w * 0.32f, h * 0.4f, w * 0.38f, h * 0.34f)
            cubicTo(w * 0.44f, h * 0.28f, w * 0.48f, h * 0.2f, w * 0.5f, h * 0.1f)
            close()
        }
        drawPath(p, color, style = Stroke(width = w * 0.08f))
        if (crossed) drawLine(color, Offset(w * 0.12f, h * 0.12f), Offset(w * 0.88f, h * 0.88f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    }
}

@Composable
fun RowScope.Spacer1() { Box(Modifier.weight(1f)) }

/** Krátký zvuk pro výzvy (vsypání, vysypání, ztráta spojení). */
object Beeper {
    private var tone: ToneGenerator? = null
    fun beep(alarm: Boolean = false) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).also { tone = it }
            t.startTone(if (alarm) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2, if (alarm) 600 else 300)
        } catch (_: Exception) {
        }
    }
}

fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** „mm:ss“ nebo sekundy → sekundy. */
fun parseTime(s: String): Int? {
    val t = s.trim()
    if (t.contains(':')) {
        val parts = t.split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val sec = parts[1].toIntOrNull() ?: return null
        if (sec !in 0..59 || m < 0) return null
        return m * 60 + sec
    }
    return t.toIntOrNull()
}

@Composable
fun ColumnScope.Grow() { Box(Modifier.weight(1f)) }
