package com.walter.obdgarage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walter.obdgarage.OrientationMode
import com.walter.obdgarage.Settings
import com.walter.obdgarage.TextSize
import com.walter.obdgarage.ThemeMode
import com.walter.obdgarage.obd.PressureUnit

/** Configurações escolhidas antes de abrir o dash. Cada linha é um grupo de botões de escolha única. */
@Composable
fun SettingsScreen(
    settings: Settings,
    boostUnit: PressureUnit,
    onChange: ((Settings) -> Settings) -> Unit,
    onBoostUnit: (PressureUnit) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigOutlinedButton("Voltar", onBack, Modifier.weight(0.45f), height = 56.dp)
            Text("Configurações", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DashColors.TextPrimary, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Group("Tema") {
                Choice(ThemeMode.entries.map { it.label }, settings.theme.ordinal) { i -> onChange { it.copy(theme = ThemeMode.entries[i]) } }
            }
            Group("Unidade da pressão do turbo") {
                Choice(PressureUnit.entries.map { it.label }, boostUnit.ordinal) { i -> onBoostUnit(PressureUnit.entries[i]) }
            }
            Group("Tamanho do texto") {
                Choice(TextSize.entries.map { it.label }, settings.textSize.ordinal) { i -> onChange { it.copy(textSize = TextSize.entries[i]) } }
            }
            Group("Orientação da tela") {
                Choice(OrientationMode.entries.map { it.label }, settings.orientation.ordinal) { i -> onChange { it.copy(orientation = OrientationMode.entries[i]) } }
            }
            Group("Aba ao conectar") {
                val ids = DefaultDashes.map { it.id }
                Choice(DefaultDashes.map { it.name }, ids.indexOf(settings.startDash).coerceAtLeast(0)) { i -> onChange { it.copy(startDash = ids[i]) } }
            }
            Group("Manter a tela ligada com o dash aberto") {
                Choice(listOf("Sim", "Não"), if (settings.keepScreenOn) 0 else 1) { i -> onChange { it.copy(keepScreenOn = i == 0) } }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 18.sp, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** Botões de escolha única, todos com o mesmo tamanho de fonte. */
@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) DashColors.Accent else DashColors.Surface)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 17.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    color = if (on) Color.White else DashColors.TextPrimary,
                )
            }
        }
    }
}
