package com.walter.dashobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.walter.dashobd.log.LogEntry
import com.walter.dashobd.log.LogSession
import com.walter.dashobd.log.Pull
import com.walter.dashobd.log.SensorStats
import com.walter.dashobd.log.curveByRpm
import com.walter.dashobd.log.pulls
import com.walter.dashobd.log.stats
import com.walter.dashobd.obd.Sensors
import java.util.Locale

private val ptBr: Locale = Locale.forLanguageTag("pt-BR")

private fun pullWord(n: Int) = if (n == 1) "1 pull" else "$n pulls"

// ---------------------------------------------------------------- Lista

@Composable
fun LogListScreen(entries: List<LogEntry>, onOpen: (LogEntry) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(DashColors.Background).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Voltar") }
            Column(Modifier.weight(1f)) {
                FitText("Logs gravados", 26.sp, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                FitText("${entries.size} no aparelho · mais recente primeiro", 16.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary)
            }
        }
        if (entries.isEmpty()) {
            Text(
                "Nenhum log ainda. No dash, toque em ● Gravar, faça o pull e toque em ■ Parar.",
                color = DashColors.TextSecondary, modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { e -> LogRow(e, onClick = { onOpen(e) }) }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry, onClick: () -> Unit) {
    val peaks = buildList {
        e.maxRpm?.let { add("RPM máx ${Sensors.rpm.format(it)}") }
        e.maxBoost?.let { add("Boost máx ${Sensors.boost.format(it)} bar") }
        e.maxIntake?.let { add("IAT máx ${Sensors.intake.format(it)} °C") }
    }.joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DashColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        FitText(e.name.ifBlank { formatDateTime(e.startedAtMs) }, 24.sp, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
        if (e.name.isNotBlank()) {
            FitText(formatDateTime(e.startedAtMs), 17.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary)
        }
        if (e.vehicle.isNotBlank()) {
            FitText(e.vehicle, 17.sp, Modifier.fillMaxWidth(), color = DashColors.Steel, fontWeight = FontWeight.Bold)
        }
        FitText(
            "${formatDuration(e.durationMs)} · ${e.sampleCount} amostras · ${pullWord(e.pullCount)}",
            17.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary,
        )
        if (peaks.isNotEmpty()) FitText(peaks, 17.sp, Modifier.fillMaxWidth(), color = DashColors.Accent)
        if (e.note.isNotBlank()) {
            Text(e.note, color = DashColors.TextSecondary, fontSize = 16.sp, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------- Detalhe

@Composable
fun LogScreen(
    entry: LogEntry,
    session: LogSession,
    path: String,
    onBack: () -> Unit,
    onEdit: (name: String, note: String) -> Unit,
    onDelete: () -> Unit,
    initialTab: Int = 0,
) {
    BackHandler(onBack = onBack)
    val stats = remember(session) { session.stats() }
    val pulls = remember(session) { session.pulls() }
    var tab by remember { mutableIntStateOf(initialTab) }
    var pullIndex by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(DashColors.Background).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onBack) { Text("Voltar") }
            Column(Modifier.weight(1f)) {
                FitText(entry.name.ifBlank { "Log gravado" }, 24.sp, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                FitText(
                    (if (session.vehicle.isNotBlank()) "${session.vehicle} · " else "") +
                        "${formatDateTime(session.startedAtMs)} · ${formatDuration(session.durationMs)} · " +
                        "${session.samples.size} amostras (${String.format(ptBr, "%.1f", session.sampleRateHz)}/s)",
                    16.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary, minSize = 9.sp,
                )
            }
            TextButton(onClick = { editing = true }) { Text("Editar") }
            TextButton(onClick = { confirmDelete = true }) { Text("Excluir", color = DashColors.TextSecondary) }
        }
        if (entry.note.isNotBlank()) {
            Text(entry.note, color = DashColors.TextSecondary, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(bottom = 4.dp))
        }

        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = DashColors.Accent) {
            val off = DashColors.TextSecondary
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Resumo") }, unselectedContentColor = off)
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Pulls (${pulls.size})") }, unselectedContentColor = off)
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Gráfico") }, unselectedContentColor = off)
        }

        when (tab) {
            0 -> SummaryTab(stats, path, Modifier.weight(1f).fillMaxWidth())
            1 -> PullsTab(pulls, Modifier.weight(1f).fillMaxWidth()) { index ->
                pullIndex = index
                tab = 2
            }
            else -> ChartTab(session, pulls, pullIndex, { pullIndex = it }, Modifier.weight(1f).fillMaxWidth())
        }
    }

    if (editing) EditDialog(entry, onDismiss = { editing = false }, onSave = { n, o -> editing = false; onEdit(n, o) })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir este log?") },
            text = { Text("O arquivo será apagado do aparelho e não dá para desfazer.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun EditDialog(entry: LogEntry, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(entry.name) }
    var note by remember { mutableStateOf(entry.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Identificar log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Nome") }, singleLine = true,
                    placeholder = { Text("Ex.: Stage 1, 3ª marcha") },
                )
                OutlinedTextField(
                    note, { note = it }, label = { Text("Observações") }, minLines = 2,
                    placeholder = { Text("Ex.: 98 octanas, 28 °C, subida") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, note) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun valueWithUnit(stat: SensorStats, value: Double): AnnotatedString = buildAnnotatedString {
    append(stat.sensor.format(value))
    withStyle(SpanStyle(fontSize = 0.6.em, color = DashColors.TextSecondary, fontWeight = FontWeight.Normal)) {
        append(" ${stat.sensor.unit}")
    }
}

private fun hint(rpm: Double?, tMs: Long) =
    if (rpm != null) "a ${Sensors.rpm.format(rpm)} rpm · ${formatClock(tMs)}" else "em ${formatClock(tMs)}"

@Composable
private fun SummaryTab(stats: List<SensorStats>, path: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Parâmetro", Modifier.weight(1.5f), color = DashColors.TextSecondary, fontSize = 15.sp, maxLines = 1)
            for (h in listOf("Mín", "Máx")) {
                Text(h, Modifier.weight(1.2f), color = DashColors.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.End, maxLines = 1)
            }
            Text("Média", Modifier.weight(0.9f), color = DashColors.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.End, maxLines = 1)
        }
        HorizontalDivider(color = DashColors.Track)
        LazyColumn(Modifier.fillMaxSize()) {
            items(stats, key = { it.sensor.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().height(76.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1.5f)) {
                        FitText(s.sensor.label, 20.sp, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                        FitText(s.sensor.name, 13.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary, minSize = 7.sp)
                    }
                    Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.End) {
                        FitText(valueWithUnit(s, s.min), 20.sp, Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                        FitText(hint(s.rpmAtMin, s.tAtMinMs), 13.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary, textAlign = TextAlign.End, minSize = 7.sp)
                    }
                    Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.End) {
                        FitText(valueWithUnit(s, s.max), 20.sp, Modifier.fillMaxWidth(), color = DashColors.Accent, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                        FitText(hint(s.rpmAtMax, s.tAtMaxMs), 13.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary, textAlign = TextAlign.End, minSize = 7.sp)
                    }
                    FitText(valueWithUnit(s, s.avg), 20.sp, Modifier.weight(0.9f), textAlign = TextAlign.End)
                }
                HorizontalDivider(color = DashColors.Track.copy(alpha = 0.5f))
            }
            item {
                Text("Arquivo: $path", color = DashColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun PullsTab(pulls: List<Pull>, modifier: Modifier = Modifier, onOpenChart: (Int) -> Unit) {
    if (pulls.isEmpty()) {
        Text(
            "Nenhum pull detectado. Conta como pull um trecho com borboleta a partir de 80 % por pelo menos 1,5 s " +
                "e ganho de 500 rpm.",
            color = DashColors.TextSecondary, modifier = modifier.padding(top = 16.dp),
        )
        return
    }
    LazyColumn(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pulls.size) { i ->
            val p = pulls[i]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DashColors.Surface)
                    .clickable { onOpenChart(i) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                FitText(
                    "Pull ${i + 1} · início ${formatClock(p.startMs)} · ${String.format(ptBr, "%.1f", p.durationMs / 1000.0)} s",
                    16.sp, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold,
                )
                if (p.rpmStart != null && p.rpmEnd != null) {
                    PullLine("RPM ${Sensors.rpm.format(p.rpmStart)} → ${Sensors.rpm.format(p.rpmEnd)}")
                }
                p.boostPeak?.let { PullLine("Boost máx ${Sensors.boost.format(it)} bar" + atRpm(p.rpmAtBoostPeak), DashColors.Accent) }
                p.timingMin?.let { PullLine("Avanço mín ${Sensors.timing.format(it)}°" + atRpm(p.rpmAtTimingMin)) }
                val extras = listOfNotNull(
                    p.loadMax?.let { "Carga máx ${Sensors.load.format(it)} %" },
                    p.intakeMax?.let { "IAT máx ${Sensors.intake.format(it)} °C" },
                ).joinToString(" · ")
                if (extras.isNotEmpty()) PullLine(extras)
                PullLine("Toque para ver o gráfico deste pull", DashColors.TextSecondary, 11.sp)
            }
        }
    }
}

private fun atRpm(rpm: Double?) = if (rpm != null) " a ${Sensors.rpm.format(rpm)} rpm" else ""

@Composable
private fun PullLine(text: String, color: Color = DashColors.TextPrimary, size: androidx.compose.ui.unit.TextUnit = 14.sp) {
    FitText(text, size, Modifier.fillMaxWidth(), color = color, minSize = 8.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartTab(
    session: LogSession,
    pulls: List<Pull>,
    pullIndex: Int?,
    onPullChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var byRpm by remember { mutableStateOf(true) }
    val range = pullIndex?.let { pulls.getOrNull(it)?.range }
    val stats = remember(session, range) { session.stats(range = range) }
    val statsById = remember(stats) { stats.associateBy { it.sensor.id } }
    // Cada modo tem a sua seleção; trocar de modo volta ao padrão do modo.
    var selected by remember(byRpm) {
        mutableStateOf(
            (if (byRpm) listOf("boost", "load", "timing") else listOf("rpm", "boost", "throttle"))
                .filter { it in statsById }.toSet(),
        )
    }
    val candidates = stats.map { it.sensor }.filter { !(byRpm && it.id == "rpm") }

    val series = remember(session, byRpm, selected, range, stats) {
        candidates.filter { it.id in selected }.map { sensor ->
            val st = statsById.getValue(sensor.id)
            val points = if (byRpm) {
                session.curveByRpm(sensor.id, range = range).map { (rpm, v) -> Offset(rpm.toFloat(), v.toFloat()) }
            } else {
                session.samplesIn(range).mapNotNull { s -> s.values[sensor.id]?.let { Offset(s.tMs / 1000f, it.toFloat()) } }
            }
            ChartSeries(sensor, DashColors.Series[Sensors.all.indexOf(sensor) % DashColors.Series.size], points, st.min, st.max)
        }
    }
    val xMin: Float
    val xMax: Float
    if (byRpm) {
        val xs = series.flatMap { s -> s.points.map { it.x } }
        xMin = xs.minOrNull() ?: 0f
        xMax = xs.maxOrNull() ?: 1f
    } else {
        xMin = (range?.first ?: 0L) / 1000f
        xMax = (range?.last ?: session.durationMs) / 1000f
    }

    val controls: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(byRpm, { byRpm = true }, label = { Text("Por RPM (estilo dyno)", maxLines = 1) })
                FilterChip(!byRpm, { byRpm = false }, label = { Text("Por tempo", maxLines = 1) })
            }
            if (pulls.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(pullIndex == null, { onPullChange(null) }, label = { Text("Log inteiro", maxLines = 1) })
                    pulls.indices.forEach { i ->
                        FilterChip(pullIndex == i, { onPullChange(i) }, label = { Text("Pull ${i + 1}", maxLines = 1) })
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sensor in candidates) {
                    val color = DashColors.Series[Sensors.all.indexOf(sensor) % DashColors.Series.size]
                    FilterChip(
                        selected = sensor.id in selected,
                        onClick = { selected = if (sensor.id in selected) selected - sensor.id else selected + sensor.id },
                        label = { Text(sensor.label, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }
    val chart: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChartLegend(series)
            LineChart(
                series, xMin, xMax,
                xLabel = { if (byRpm) it.toInt().toString() else "${String.format(ptBr, "%.1f", it)} s" },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Text(
                (if (byRpm) "Média por faixa de 100 rpm. " else "") +
                    "Cada curva usa a própria escala (mín. a máx. do trecho mostrado).",
                color = DashColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }

    BoxWithConstraints(modifier.padding(top = 8.dp)) {
        if (maxWidth > maxHeight) {
            // Paisagem: controles ao lado, para o gráfico ter altura.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                controls(Modifier.weight(0.8f).verticalScroll(rememberScrollState()))
                chart(Modifier.weight(1.4f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                controls(Modifier.fillMaxWidth())
                chart(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
