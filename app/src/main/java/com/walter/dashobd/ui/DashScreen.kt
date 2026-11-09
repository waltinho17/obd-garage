package com.walter.dashobd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.walter.dashobd.EngineKind
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walter.dashobd.PeakBoost
import com.walter.dashobd.log.Sample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.walter.dashobd.obd.PressureUnit
import java.util.Locale

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

/** Instante dentro do log com décimos, ex.: 0:08,3. */
fun formatClock(ms: Long): String =
    String.format(Locale.forLanguageTag("pt-BR"), "%d:%02d,%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10)

/** Data e hora local com segundos, ex.: 02/11/2026 19:21:03. */
fun formatDateTime(epochMs: Long): String =
    java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("pt-BR")).format(java.util.Date(epochMs))

@Composable
fun DashScreen(
    values: Map<String, Double>,
    history: StateFlow<List<Sample>>,
    peakBoost: PeakBoost?,
    onResetPeak: () -> Unit,
    statusLine: String,
    initialPage: Int,
    recordingSince: Long?,
    onToggleRecording: () -> Unit,
    onOpenLogs: () -> Unit,
    onDisconnect: () -> Unit,
    boostUnit: PressureUnit,
    onCycleBoostUnit: () -> Unit,
    supportedIds: Set<String>?,
    engineKind: EngineKind,
    vehicleName: String,
    editableVehicleName: String,
    onSaveVehicleName: (String) -> Unit,
) {
    // Tela sempre ligada enquanto o dash está aberto.
    val view = LocalView.current
    val settings = LocalSettings.current
    DisposableEffect(view, settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Num carro aspirado a aba Turbo não faz sentido: some.
    val dashes = remember(engineKind) { DefaultDashes.filter { !(it.id == "turbo" && engineKind == EngineKind.ASPIRADO) } }
    val startIndex = if (initialPage > 0) initialPage else dashes.indexOfFirst { it.id == settings.startDash }.coerceAtLeast(0)
    val pager = rememberPagerState(startIndex.coerceIn(0, dashes.size - 1)) { dashes.size }
    val scope = rememberCoroutineScope()
    val data = DashData(values, history, peakBoost, onResetPeak, supportedIds, engineKind)
    var editingName by remember { mutableStateOf(false) }

    val tabs: @Composable (Modifier) -> Unit = { m ->
        // Um tamanho de fonte só para todas as abas, calculado pela largura do botão e pelo nome mais comprido.
        BoxWithConstraints(m) {
            val longest = dashes.maxOf { it.name.length }
            val chipWidth = (maxWidth - 6.dp * (dashes.size - 1)) / dashes.size
            val tabSize = ((chipWidth - 10.dp).value / (longest * 0.74f)).coerceIn(12f, 21f).sp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dashes.forEachIndexed { i, dash ->
                    val selected = i == pager.currentPage
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) DashColors.Accent else DashColors.Surface)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            dash.name, fontSize = tabSize, maxLines = 1, softWrap = false,
                            color = if (selected) Color.White else DashColors.TextSecondary,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    val actions: @Composable (Modifier) -> Unit = { m ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RecordButton(recordingSince, onToggleRecording, Modifier.weight(1.3f).fillMaxHeight())
            HeaderButton("Logs", onOpenLogs, Modifier.weight(1f).fillMaxHeight())
            HeaderButton("Sair", onDisconnect, Modifier.weight(1f).fillMaxHeight())
        }
    }

    CompositionLocalProvider(LocalBoostUnit provides boostUnit, LocalCycleBoostUnit provides onCycleBoostUnit) {
        Column(Modifier.fillMaxSize().background(DashColors.Background)) {
            // Faixa vermelha fina, como a da grade do GTI.
            Box(Modifier.fillMaxWidth().height(3.dp).background(DashColors.Accent))

            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (maxWidth > maxHeight * 1.5f || maxWidth > 560.dp) {
                    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        tabs(Modifier.weight(1.5f).fillMaxHeight())
                        actions(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tabs(Modifier.fillMaxWidth().height(48.dp))
                        actions(Modifier.fillMaxWidth().height(52.dp))
                    }
                }
            }

            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                Box(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 4.dp)) {
                    dashes[page].content(data)
                }
            }

            // Rodapé: nome do carro (toque para editar) e estado da leitura.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FitText(
                    vehicleName.ifEmpty { "NOMEAR CARRO" }, 17.sp,
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { editingName = true }.padding(vertical = 3.dp),
                    color = if (vehicleName.isEmpty()) DashColors.TextSecondary else DashColors.Steel,
                    fontWeight = FontWeight.Bold, minSize = 11.sp,
                )
                FitText(
                    statusLine, 15.sp, Modifier.weight(1.6f),
                    color = DashColors.TextSecondary, textAlign = TextAlign.End, minSize = 10.sp,
                )
            }
        }
    }

    if (editingName) VehicleNameDialog(editableVehicleName, onSaveVehicleName) { editingName = false }
}

@Composable
private fun VehicleNameDialog(current: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Nome do carro") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it.take(24) }, singleLine = true,
                label = { Text("Ex.: POLO EA211") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                ),
            )
        },
        confirmButton = { androidx.compose.material3.TextButton({ onSave(text); onClose() }) { Text("Salvar") } },
        dismissButton = { androidx.compose.material3.TextButton(onClose) { Text("Cancelar") } },
    )
}

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, DashColors.Steel.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) { Text(text, color = DashColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
}

@Composable
private fun RecordButton(recordingSince: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recordingSince) {
        while (recordingSince != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val padding = PaddingValues(horizontal = 6.dp)
    val shape = RoundedCornerShape(12.dp)
    if (recordingSince == null) {
        OutlinedButton(
            onClick, modifier, shape = shape, contentPadding = padding,
            border = BorderStroke(2.dp, DashColors.Accent),
        ) { Text("● Gravar", color = DashColors.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
    } else {
        Button(
            onClick, modifier, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = Color.White),
        ) {
            Text("■ ${formatDuration((now - recordingSince).coerceAtLeast(0))}", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}
