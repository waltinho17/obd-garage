package com.walter.dashobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walter.dashobd.EngineKind
import com.walter.dashobd.PeakBoost
import com.walter.dashobd.log.Sample
import com.walter.dashobd.obd.Sensor
import com.walter.dashobd.obd.Sensors
import kotlinx.coroutines.flow.StateFlow

class DashData(
    val values: Map<String, Double>,
    val history: StateFlow<List<Sample>>,
    val peakBoost: PeakBoost?,
    val onResetPeak: () -> Unit,
    /** Ids dos sensores que o carro suporta (null = ainda não se sabe, vale todos). */
    val supported: Set<String>? = null,
    val engine: EngineKind = EngineKind.UNKNOWN,
) {
    fun has(s: Sensor) = supported == null || s.id in supported

    /** Boost só faz sentido se o carro tem MAP e não foi identificado como aspirado. */
    val hasBoost: Boolean get() = has(Sensors.boost) && engine != EngineKind.ASPIRADO

    /** Primeiros [n] sensores da lista que o carro suporta, na ordem dada, sem os de [except]. */
    fun pick(n: Int, vararg candidates: Sensor, except: Sensor? = null): List<Sensor> =
        candidates.filter { has(it) && it != except }.take(n)
}

class DashDef(val id: String, val name: String, val content: @Composable (DashData) -> Unit)

/** Nomes de aba curtos e em maiúsculas, todos com o mesmo tamanho de fonte. */
val DefaultDashes = listOf(
    DashDef("sport", "ESPORTE") { SportDash(it) },
    DashDef("turbo", "TURBO") { TurboDash(it) },
    DashDef("cluster", "PAINEL") { ClusterDash(it) },
    DashDef("data", "DADOS") { DataDash(it) },
)

private val gap = 8.dp

@Composable
private fun CardGrid(
    sensors: List<Sensor>,
    values: Map<String, Double>,
    columns: Int,
    modifier: Modifier = Modifier,
    showBar: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        // Mesmo tamanho de rótulo em todos os cards da grade, calculado pelo card mais apertado e pelo rótulo mais longo.
        val rows = (sensors.size + columns - 1) / columns
        val cardW = (maxWidth - gap * (columns - 1)) / columns
        val cardH = (maxHeight - gap * (rows - 1).coerceAtLeast(0)) / rows.coerceAtLeast(1)
        val plan = planLabels(sensors, cardW, cardH)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in sensors.chunked(columns)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (s in row) SensorCard(s, values[s.id], Modifier.weight(1f).fillMaxHeight(), showBar, plan)
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 1. Dois gauges grandes (RPM e boost) e cards de apoio. */
@Composable
private fun SportDash(d: DashData) {
    val v = d.values
    // Sem turbo (ou sem MAP), o segundo gauge mostra a carga do motor no lugar do boost.
    val second = if (d.hasBoost) Sensors.boost else Sensors.load
    val secondPeak = if (d.hasBoost) d.peakBoost?.value else null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val panel: @Composable (Modifier) -> Unit = { m ->
            if (landscape) {
                CardGrid(
                    d.pick(
                        6, Sensors.coolant, Sensors.oil, Sensors.intake, Sensors.timing, Sensors.speed,
                        Sensors.load, Sensors.throttle, Sensors.voltage, Sensors.map, except = second,
                    ),
                    v, 2, m, showBar = true,
                )
            } else {
                val bars = d.pick(2, Sensors.load, Sensors.throttle, Sensors.pedal, Sensors.voltage, except = second)
                Column(m, verticalArrangement = Arrangement.spacedBy(gap)) {
                    CardGrid(
                        d.pick(4, Sensors.coolant, Sensors.oil, Sensors.intake, Sensors.timing, Sensors.speed, Sensors.map, except = second),
                        v, 2, Modifier.weight(1f),
                    )
                    for (s in bars) SensorCard(s, v[s.id], Modifier.fillMaxWidth().height(84.dp), showBar = true)
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                CircularGauge(Sensors.rpm, v["rpm"], Modifier.weight(1f).fillMaxHeight())
                CircularGauge(second, v[second.id], Modifier.weight(1f).fillMaxHeight(), peak = secondPeak)
                panel(Modifier.weight(1.1f).fillMaxHeight())
            }
        } else {
            val gaugeH = ((maxWidth - gap) / 2 * 0.95f).coerceAtMost(maxHeight * 0.42f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.height(gaugeH).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CircularGauge(Sensors.rpm, v["rpm"], Modifier.weight(1f).fillMaxHeight())
                    CircularGauge(second, v[second.id], Modifier.weight(1f).fillMaxHeight(), peak = secondPeak)
                }
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** 2. Shift light no topo e o boost como protagonista, com pico retido. */
@Composable
private fun TurboDash(d: DashData) {
    val v = d.values

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hero: @Composable (Modifier) -> Unit = { m -> BoostHero(d, m) }
        val grid = d.pick(4, Sensors.coolant, Sensors.oil, Sensors.intake, Sensors.timing, Sensors.load)
        val landscape = maxWidth > maxHeight

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            // O número do RPM fica DENTRO da barra de troca de marcha, que ocupa toda a largura.
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                ShiftBar(Sensors.rpm, v["rpm"], Modifier.fillMaxSize())
                Text(
                    "${Sensors.rpm.format(v["rpm"])} rpm", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum",
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 10f),
                    ),
                )
            }
            if (d.has(Sensors.ethanol)) EthanolStrip(v["ethanol"], Modifier.fillMaxWidth().height(if (landscape) 66.dp else 92.dp))
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    hero(Modifier.weight(1.3f).fillMaxHeight())
                    CardGrid(grid, v, 2, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                hero(Modifier.weight(1.9f).fillMaxWidth())
                CardGrid(grid, v, 2, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}


/**
 * Teor de etanol no combustível, lido da ECU (PID 52) e logo abaixo da barra de rpm no dash Turbo, para ver de
 * relance a mistura ANTES de puxar: número, barra de 0 a 100% com marcas em E30 (gasolina do posto), E50 e E85,
 * e o nome da mistura ("E50") com a fração de gasolina. A ECU estima o teor depois do abastecimento, então o valor
 * leva alguns minutos rodando para assentar.
 */
@Composable
private fun EthanolStrip(value: Double?, modifier: Modifier = Modifier) {
    val sensor = Sensors.ethanol
    val fraction by androidx.compose.animation.core.animateFloatAsState(sensor.fraction(value), androidx.compose.animation.core.tween(300), label = "ethanol")
    BoxWithConstraints(modifier) {
        LabeledCard(sensor.fullLabel(), 16.sp, Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val vh = maxHeight.value
                val valueSize = (vh * 0.85f).coerceIn(20f, 64f).sp
                val barH = (vh * 0.5f).coerceIn(12f, 28f).dp
                val track = DashColors.Track
                val accent = DashColors.Accent
                val tick = DashColors.TextPrimary
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FitText(
                        sensor.format(value) + " %", valueSize, Modifier.weight(1f),
                        color = DashColors.TextPrimary, fontWeight = FontWeight.Bold,
                    )
                    androidx.compose.foundation.Canvas(Modifier.weight(1.3f).height(barH)) {
                        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                        drawRoundRect(track, size = size, cornerRadius = r)
                        if (fraction > 0.003f) {
                            drawRoundRect(accent, size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height), cornerRadius = r)
                        }
                        for (mark in listOf(0.30f, 0.50f, 0.85f)) {
                            val x = size.width * mark
                            drawLine(tick.copy(alpha = 0.75f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                        }
                    }
                    Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
                        FitText(
                            Sensors.ethanolName(value) ?: "--", (vh * 0.55f).coerceIn(18f, 40f).sp, Modifier.fillMaxWidth(),
                            color = DashColors.Accent, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                        )
                        FitText(
                            Sensors.gasolinePercent(value)?.let { "$it% gasolina" } ?: "sem leitura", (vh * 0.3f).coerceIn(12f, 18f).sp,
                            Modifier.fillMaxWidth(), color = DashColors.TextSecondary, textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}
/**
 * Pressão do turbo atual e, ao lado (ou abaixo, em retrato), o bloco de pico com o RPM em que ocorreu.
 * Toque no valor troca a unidade (bar, kgf/cm², psi, kPa). O número é a pressão RELATIVA: quanto o turbo
 * empurra acima da atmosfera; a absoluta no coletor (a que o sensor MAP mede) aparece embaixo.
 */
@Composable
private fun BoostHero(d: DashData, modifier: Modifier) {
    val boost = d.values["boost"]
    val map = d.values["map"]
    val unit = LocalBoostUnit.current
    LabeledCard("PRESSÃO DO TURBO", 16.sp, modifier, Sensors.boost.isWarning(boost)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight * 1.3f
            val blockHeight = if (wide) maxHeight.value else maxHeight.value / 2
            val warn = Sensors.boost.isWarning(boost)
            val current: @Composable (Modifier) -> Unit = { m ->
                Column(m.clickable(onClick = LocalCycleBoostUnit.current), verticalArrangement = Arrangement.SpaceBetween) {
                    FitText(
                        Sensors.boost.displayValue(boost), ((blockHeight - 70f) * 0.9f).coerceIn(34f, 220f).sp, Modifier.fillMaxWidth(),
                        color = if (warn) DashColors.Warn else DashColors.TextPrimary, fontWeight = FontWeight.Bold,
                    )
                    FitText("${unit.label} relativa · toque p/ trocar", 18.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary)
                    FitText(
                        map?.let { "no coletor: ${it.toInt()} kPa absoluta" } ?: "no coletor: --", 18.sp, Modifier.fillMaxWidth(),
                        color = DashColors.TextSecondary,
                    )
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    current(Modifier.weight(1.2f).fillMaxHeight())
                    PeakBlock(d.peakBoost, d.onResetPeak, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current(Modifier.weight(1f).fillMaxWidth())
                    PeakBlock(d.peakBoost, d.onResetPeak, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** Pico em destaque: número grande, RPM em que ocorreu e botão grande para zerar. */
@Composable
private fun PeakBlock(peak: PeakBoost?, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val warn = peak != null && Sensors.boost.isWarning(peak.value)
    val unit = LocalBoostUnit.current
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DashColors.Accent.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val valueSize = ((maxHeight.value - 92f) * 0.78f).coerceIn(30f, 170f).sp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FitText("PICO", 22.sp, Modifier.weight(1f), color = DashColors.Accent, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = onReset,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(48.dp),
                    ) { Text("Zerar", color = DashColors.TextPrimary, fontSize = 20.sp) }
                }
                FitText(
                    peak?.let { Sensors.boost.displayValue(it.value) } ?: "--", valueSize, Modifier.fillMaxWidth(),
                    color = if (warn) DashColors.Warn else DashColors.TextPrimary, fontWeight = FontWeight.Bold,
                )
                FitText(
                    if (peak?.rpm != null) "${unit.label} · a ${Sensors.rpm.format(peak.rpm)} rpm" else unit.label,
                    19.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * 3. Painel de instrumentos: RPM e velocidade grandes e três gauges de apoio (arrefecimento, óleo ou ar de
 * admissão, e boost ou carga). Em paisagem os de apoio ficam numa coluna central; em retrato, numa fila.
 */
@Composable
private fun ClusterDash(d: DashData) {
    val v = d.values
    val candidates = listOfNotNull(
        Sensors.coolant, Sensors.oil, if (d.hasBoost) Sensors.boost else null,
        Sensors.intake, Sensors.load, Sensors.voltage, Sensors.throttle,
    ).filter { d.has(it) }.distinct()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val bars = candidates.take(if (landscape) 4 else 5)
        val barColumn: @Composable (Modifier) -> Unit = { m ->
            BoxWithConstraints(m) {
                val barH = (maxHeight - gap * (bars.size - 1).coerceAtLeast(0)) / bars.size.coerceAtLeast(1)
                // Rótulo do mesmo tamanho em todas as barras; completo se couber ("TENSÃO DA BATERIA"), curto se não.
                val plan = planLabels(bars, maxWidth, barH)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (s in bars) {
                        BarGauge(
                            s, v[s.id], Modifier.weight(1f).fillMaxWidth(),
                            peak = if (s == Sensors.boost) d.peakBoost?.value else null, plan = plan,
                        )
                    }
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                CircularGauge(Sensors.rpm, v["rpm"], Modifier.weight(1f).fillMaxHeight())
                barColumn(Modifier.weight(1.25f).fillMaxHeight())
                CircularGauge(Sensors.speed, v["speed"], Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // A altura da fila de gauges é a do próprio círculo (largura / 2), sem espaço vazio em volta;
            // o resto da tela vai para as barras.
            val gaugeRow = ((maxWidth - gap) / 2 * 0.92f).coerceAtMost(maxHeight * 0.42f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.height(gaugeRow).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CircularGauge(Sensors.rpm, v["rpm"], Modifier.weight(1f).fillMaxHeight())
                    CircularGauge(Sensors.speed, v["speed"], Modifier.weight(1f).fillMaxHeight())
                }
                barColumn(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** 4. Gráfico ao vivo (últimos 60 s) e grade densa de parâmetros com barras. */
@Composable
private fun DataDash(d: DashData) {
    val v = d.values
    val history by d.history.collectAsState()
    val liveSensors = listOf(if (d.hasBoost) Sensors.boost else Sensors.load, Sensors.rpm)

    val chart: @Composable (Modifier) -> Unit = { m ->
        LabeledCard("ÚLTIMOS 60 SEGUNDOS", 16.sp, m) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val now = history.lastOrNull()?.tMs ?: 0L
                val series = liveSensors.mapIndexed { i, sensor ->
                    val points = history.mapNotNull { s ->
                        s.values[sensor.id]?.let { Offset((s.tMs - now) / 1000f, it.toFloat()) }
                    }
                    ChartSeries(sensor, DashColors.Series[i], points, sensor.min, sensor.max)
                }
                ChartLegend(series)
                LineChart(series, -60f, 0f, { "${(-it).toInt()} s" }, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    // Em paisagem (tela baixa) cabem menos cards sem ficar apertado.
    val landscapeNow = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val grid = d.pick(
        if (landscapeNow) 6 else 8, Sensors.load, Sensors.ethanol, Sensors.throttle, Sensors.lambdaCmd, Sensors.fuelRail, Sensors.timing, Sensors.coolant,
        Sensors.oil, Sensors.stft, Sensors.ltft, Sensors.voltage, Sensors.map, Sensors.intake,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                chart(Modifier.weight(1.2f).fillMaxHeight())
                CardGrid(grid, v, 2, Modifier.weight(1f).fillMaxHeight(), showBar = true)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                chart(Modifier.weight(1f).fillMaxWidth())
                CardGrid(grid, v, 2, Modifier.weight(1.5f).fillMaxWidth(), showBar = true)
            }
        }
    }
}
