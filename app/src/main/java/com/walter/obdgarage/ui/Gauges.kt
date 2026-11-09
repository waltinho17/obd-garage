package com.walter.obdgarage.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.walter.obdgarage.obd.Sensor
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val ptBr: Locale = Locale.forLanguageTag("pt-BR")

fun Sensor.format(value: Double?): String =
    if (value == null) "--" else String.format(ptBr, "%.${decimals}f", value)

fun Sensor.fraction(value: Double?): Float =
    if (value == null) 0f else ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)

fun Sensor.isWarning(value: Double?) = value != null && warnFrom != null && value >= warnFrom

@Composable
private fun Sensor.valueText(value: Double?): AnnotatedString {
    val number = displayValue(value)
    val unitText = displayUnit()
    return buildAnnotatedString {
        append(number)
        withStyle(SpanStyle(fontSize = 0.45.em, color = DashColors.TextSecondary, fontWeight = FontWeight.Normal)) {
            append(" $unitText")
        }
    }
}

/**
 * Texto de uma linha só que reduz a fonte até caber na largura disponível, em vez de
 * quebrar. O espaço horizontal precisa ser limitado por quem chama (weight, width, fillMaxWidth).
 */
@Composable
fun FitText(
    text: AnnotatedString,
    maxSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = DashColors.TextPrimary,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    minSize: TextUnit = 8.sp,
) {
    // Chaveado pelo tamanho do texto (não pelo conteúdo) para não recalcular a cada leitura.
    var size by remember(text.length, maxSize) { mutableStateOf(maxSize) }
    var ready by remember(text.length, maxSize) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        color = color,
        fontSize = size,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = TextStyle(fontFeatureSettings = "tnum"),
        onTextLayout = { result ->
            if (result.didOverflowWidth && size.value > minSize.value) {
                size = (size.value * 0.92f).coerceAtLeast(minSize.value).sp
            } else {
                ready = true
            }
        },
    )
}

@Composable
fun FitText(
    text: String,
    maxSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = DashColors.TextPrimary,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    minSize: TextUnit = 8.sp,
) = FitText(AnnotatedString(text), maxSize, modifier, color, fontWeight, textAlign, minSize)


/** Rótulo curto em maiúsculas, para quando o card é estreito. */
fun Sensor.shortLabel(): String = when (id) {
    "rpm" -> "RPM"
    "boost" -> "BOOST"
    "map" -> "MAP"
    "load" -> "CARGA"
    "absload" -> "CARGA ABS."
    "throttle" -> "BORBOLETA"
    "timing" -> "AVANÇO"
    "speed" -> "VELOCIDADE"
    "coolant" -> "ARREFEC."
    "oil" -> "ÓLEO"
    "intake" -> "AR ADM."
    "stft" -> "TRIM CURTO"
    "ltft" -> "TRIM LONGO"
    "baro" -> "BAROMÉTRICA"
    "lambda_cmd" -> "LAMBDA"
    "fuel_rail" -> "TRILHO"
    "voltage" -> "TENSÃO"
    "ambient" -> "AMBIENTE"
    "pedal" -> "PEDAL"
    "ethanol" -> "ETANOL"
    else -> label.uppercase()
}

/** Rótulo completo, usado quando há largura de sobra. */
fun Sensor.fullLabel(): String = when (id) {
    "rpm" -> "ROTAÇÃO DO MOTOR"
    "boost" -> "PRESSÃO DO TURBO"
    "map" -> "PRESSÃO NO COLETOR"
    "load" -> "CARGA DO MOTOR"
    "absload" -> "CARGA ABSOLUTA"
    "throttle" -> "BORBOLETA"
    "timing" -> "AVANÇO DE IGNIÇÃO"
    "speed" -> "VELOCIDADE"
    "coolant" -> "TEMP. ARREFECIMENTO"
    "oil" -> "TEMP. DO ÓLEO"
    "intake" -> "TEMP. DO AR"
    "stft" -> "TRIM CURTO"
    "ltft" -> "TRIM LONGO"
    "baro" -> "PRESSÃO BAROMÉTRICA"
    "lambda_cmd" -> "LAMBDA PEDIDO"
    "fuel_rail" -> "PRESSÃO DO TRILHO"
    "voltage" -> "TENSÃO DA BATERIA"
    "ambient" -> "TEMP. AMBIENTE"
    "pedal" -> "PEDAL"
    "ethanol" -> "TEOR DE ETANOL"
    else -> label.uppercase()
}

/**
 * Como os rótulos de um grupo de cards são escritos: o MESMO tamanho de fonte para todos e, se couber, o nome
 * completo ("TENSÃO DA BATERIA"); se não couber, o curto ("TENSÃO"). Quanto mais espaço, mais completo.
 */
class LabelPlan(val size: TextUnit, val full: Boolean) {
    fun text(s: Sensor) = if (full) s.fullLabel() else s.shortLabel()
}

private const val LABEL_TARGET_SP = 16f

fun planLabels(sensors: List<Sensor>, cardWidth: Dp, cardHeight: Dp): LabelPlan {
    fun fit(maxChars: Int) = minOf(
        LABEL_TARGET_SP,
        (cardWidth.value - 20f) / (maxChars.coerceAtLeast(1) * 0.68f),
        cardHeight.value * 0.25f,
    )
    val full = fit(sensors.maxOfOrNull { it.fullLabel().length } ?: 8)
    if (full >= LABEL_TARGET_SP) return LabelPlan(full.sp, true)
    return LabelPlan(fit(sensors.maxOfOrNull { it.shortLabel().length } ?: 8).coerceAtLeast(11f).sp, false)
}

/** Card sem decoração: fundo arredondado e conteúdo. */
@Composable
fun DashCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DashColors.Surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        content = content,
    )
}

/**
 * O único estilo de card do app: uma faixa colorida no topo com o rótulo e, embaixo, o conteúdo usando todo o
 * espaço que sobra. A faixa fica âmbar quando o valor entra em alerta.
 */
@Composable
fun LabeledCard(
    label: String,
    labelSize: TextUnit,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val stripHeight = with(androidx.compose.ui.platform.LocalDensity.current) { labelSize.toDp() } * 1.65f
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(DashColors.Surface)) {
        Box(
            Modifier.fillMaxWidth().height(stripHeight).background(if (warn) DashColors.Warn else DashColors.Accent).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                label, fontSize = labelSize, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                color = if (warn && DashColors.dark) Color(0xFF111111) else Color.White,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), content = content)
    }
}

/** Card de um sensor: rótulo na faixa de cima, número grande embaixo e, se houver altura, uma barra de nível. */
@Composable
fun SensorCard(sensor: Sensor, value: Double?, modifier: Modifier = Modifier, showBar: Boolean = false, plan: LabelPlan? = null) {
    val warn = sensor.isWarning(value)
    val fraction by animateFloatAsState(sensor.fraction(value), tween(150), label = "bar")
    BoxWithConstraints(modifier) {
        val p = plan ?: planLabels(listOf(sensor), maxWidth, maxHeight)
        LabeledCard(p.text(sensor), p.size, Modifier.fillMaxSize(), warn) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bar = showBar && maxHeight > 64.dp
                val vh = maxHeight.value
                val valueSize = ((if (bar) vh - 14f else vh) * 0.8f).coerceIn(18f, 150f).sp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    FitText(
                        sensor.valueText(value), valueSize, Modifier.fillMaxWidth(),
                        color = if (warn) DashColors.Warn else DashColors.TextPrimary, fontWeight = FontWeight.Bold,
                    )
                    if (bar) {
                        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(8.dp).clip(RoundedCornerShape(50)).background(DashColors.Track)) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(50))
                                    .background(if (warn) DashColors.Warn else DashColors.Accent),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Medidor horizontal no mesmo estilo dos cards: rótulo na faixa de cima e, embaixo, o número à esquerda e uma
 * barra larga à direita, com marca branca no pico se houver. Aproveita bem a largura da tela.
 */
@Composable
fun BarGauge(sensor: Sensor, value: Double?, modifier: Modifier = Modifier, peak: Double? = null, plan: LabelPlan? = null) {
    val warn = sensor.isWarning(value)
    val fraction by animateFloatAsState(sensor.fraction(value), tween(150), label = "bargauge")
    val warnStart = sensor.warnFrom?.let { sensor.fraction(it) }
    val peakFraction = peak?.let { sensor.fraction(it) }
    val cycleUnit = LocalCycleBoostUnit.current
    BoxWithConstraints(if (sensor.id == "boost") modifier.clickable(onClick = cycleUnit) else modifier) {
        val p = plan ?: planLabels(listOf(sensor), maxWidth * 0.6f, maxHeight)
        LabeledCard(p.text(sensor), p.size, Modifier.fillMaxSize(), warn) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val vh = maxHeight.value
                val valueSize = (vh * 0.8f).coerceIn(18f, 110f).sp
                val barH = (vh * 0.5f).coerceIn(10f, 30f).dp
                val accent = if (warn) DashColors.Warn else DashColors.Accent
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FitText(
                        sensor.valueText(value), valueSize, Modifier.weight(1f),
                        color = if (warn) DashColors.Warn else DashColors.TextPrimary, fontWeight = FontWeight.Bold,
                    )
                    Canvas(Modifier.weight(1.3f).height(barH)) {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(DashColors.Track, size = size, cornerRadius = r)
                        if (warnStart != null) {
                            drawRect(
                                DashColors.Warn.copy(alpha = 0.35f), Offset(size.width * warnStart, 0f),
                                Size(size.width * (1 - warnStart), size.height),
                            )
                        }
                        if (fraction > 0.003f) drawRoundRect(accent, size = Size(size.width * fraction, size.height), cornerRadius = r)
                        if (peakFraction != null) {
                            val x = size.width * peakFraction
                            drawLine(DashColors.TextPrimary, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), strokeWidth = 3.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}

/** Gauge circular de 240° com marcas, zona de alerta e valor no centro. */
@Composable
fun CircularGauge(sensor: Sensor, value: Double?, modifier: Modifier = Modifier, peak: Double? = null) {
    val fraction by animateFloatAsState(sensor.fraction(value), tween(150), label = "gauge")
    val warn = sensor.isWarning(value)
    val color = if (warn) DashColors.Warn else DashColors.Accent
    val warnStart = sensor.warnFrom?.let { sensor.fraction(it) }

    val cycleUnit = LocalCycleBoostUnit.current
    BoxWithConstraints(
        if (sensor.id == "boost") modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = cycleUnit) else modifier,
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.075f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                val start = 150f
                val sweep = 240f

                drawArc(DashColors.Track, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (warnStart != null) {
                    drawArc(
                        DashColors.Warn.copy(alpha = 0.4f), start + sweep * warnStart, sweep * (1 - warnStart),
                        false, topLeft, arcSize, style = Stroke(stroke * 0.45f),
                    )
                }
                if (fraction > 0.003f) {
                    drawArc(color, start, sweep * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }

                val center = Offset(size.width / 2, size.height / 2)

                // Marcador do valor de pico: traço branco atravessando o arco.
                if (peak != null) {
                    val angle = (start + sweep * sensor.fraction(peak)) * PI.toFloat() / 180f
                    val mid = (size.minDimension - stroke) / 2
                    drawLine(
                        Color.White,
                        Offset(center.x + cos(angle) * (mid - stroke * 0.55f), center.y + sin(angle) * (mid - stroke * 0.55f)),
                        Offset(center.x + cos(angle) * (mid + stroke * 0.9f), center.y + sin(angle) * (mid + stroke * 0.9f)),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    )
                }

                // Marcas internas, no estilo de painel de instrumentos.
                val outer = size.minDimension / 2 - stroke * 1.5f
                for (i in 0..20) {
                    val major = i % 5 == 0
                    val angle = (start + sweep * i / 20f) * PI.toFloat() / 180f
                    val inner = outer - stroke * (if (major) 0.9f else 0.5f)
                    drawLine(
                        DashColors.Steel.copy(alpha = if (major) 0.8f else 0.35f),
                        Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                        Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                        strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                    )
                }
            }
            Column(Modifier.width(side * 0.58f), horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(
                    sensor.displayValue(value), (side.value * (if (peak != null) 0.26f else 0.29f)).coerceAtLeast(18f).sp, Modifier.fillMaxWidth(),
                    color = if (warn) DashColors.Warn else DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                FitText(
                    sensor.displayUnit(), (side.value * 0.12f).coerceAtLeast(14f).sp, Modifier.fillMaxWidth(),
                    color = DashColors.TextSecondary, textAlign = TextAlign.Center,
                )
                // RPM e velocidade já dizem o que são pela unidade ("rpm", "km/h"): sem rótulo repetido.
                if (sensor.id != "rpm" && sensor.id != "speed") {
                    FitText(
                        sensor.shortLabel(), (side.value * 0.12f).coerceAtLeast(14f).sp, Modifier.fillMaxWidth(),
                        color = DashColors.TextSecondary, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (peak != null) {
                    FitText(
                        "pico ${sensor.displayValue(peak)}", (side.value * 0.11f).coerceAtLeast(14f).sp,
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Barra de segmentos estilo shift light: vermelha, e âmbar na zona de alerta. */
@Composable
fun ShiftBar(sensor: Sensor, value: Double?, modifier: Modifier = Modifier, segments: Int = 28) {
    val fraction by animateFloatAsState(sensor.fraction(value), tween(100), label = "shift")
    val warnStart = sensor.warnFrom?.let { sensor.fraction(it) } ?: 1f
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val lit = (fraction * segments).toInt()
        for (i in 0 until segments) {
            val color = when {
                i >= lit -> DashColors.Track
                i.toFloat() / segments >= warnStart -> DashColors.Warn
                else -> DashColors.Accent
            }
            drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
        }
    }
}

/** Valor para exibição: a pressão do turbo segue a unidade escolhida (bar, kgf/cm², psi, kPa); o resto não muda. */
@Composable
fun Sensor.displayValue(value: Double?): String {
    if (id != "boost") return format(value)
    val unit = LocalBoostUnit.current
    return if (value == null) "--" else String.format(ptBr, "%.${unit.decimals}f", unit.fromBar(value))
}

@Composable
fun Sensor.displayUnit(): String = if (id == "boost") LocalBoostUnit.current.label else unit
