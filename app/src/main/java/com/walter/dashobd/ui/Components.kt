package com.walter.dashobd.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walter.dashobd.obd.PressureUnit

/** Unidade escolhida para a pressão do turbo e como trocar para a próxima (toque no valor). */
val LocalBoostUnit = compositionLocalOf { PressureUnit.BAR }
val LocalCycleBoostUnit = compositionLocalOf<() -> Unit> { {} }

/** Preferências do usuário, disponíveis em qualquer tela. */
val LocalSettings = androidx.compose.runtime.staticCompositionLocalOf { com.walter.dashobd.Settings() }

/** Altura mínima de qualquer botão do app: dedo grande, leitura de longe. */
val BigButtonHeight: Dp = 72.dp

/** Botão principal: largo, alto e com texto grande. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = BigButtonHeight) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = height),
    ) { Text(text, fontSize = (height.value * 0.33f).coerceIn(18f, 24f).sp, maxLines = 2, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, textAlign = TextAlign.Center) }
}

/** Botão secundário (contorno), do mesmo tamanho do principal. */
@Composable
fun BigOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color = DashColors.TextPrimary,
    height: Dp = BigButtonHeight,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, if (enabled) DashColors.Steel.copy(alpha = 0.6f) else DashColors.Track),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
        modifier = modifier.fillMaxWidth().heightIn(min = height),
    ) { Text(text, fontSize = (height.value * 0.3f).coerceIn(17f, 22f).sp, maxLines = 2, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = TextAlign.Center) }
}
