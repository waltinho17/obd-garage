package com.walter.obdgarage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta inspirada no Golf GTI: preto, vermelho Tornado, cinza-aço do tartan. Há também um modo claro.
 * As cores leem um estado global ([dark]), então trocar o tema recompõe tudo sem passar parâmetros.
 */
object DashColors {
    var dark by mutableStateOf(true)

    val Background get() = if (dark) Color(0xFF0A0A0B) else Color(0xFFF1F2F4)
    val Surface get() = if (dark) Color(0xFF16171A) else Color(0xFFFFFFFF)
    val Track get() = if (dark) Color(0xFF2B2D33) else Color(0xFFD6D9DF)
    val Accent = Color(0xFFE0121F)
    val Steel get() = if (dark) Color(0xFFB8BCC4) else Color(0xFF5A6070)
    val Warn get() = if (dark) Color(0xFFFFB400) else Color(0xFFB36B00)
    val TextPrimary get() = if (dark) Color(0xFFF4F4F5) else Color(0xFF121316)
    val TextSecondary get() = if (dark) Color(0xFFA9AFB8) else Color(0xFF555B66)

    /** Cores das séries nos gráficos (a primeira é o vermelho GTI). */
    val Series: List<Color>
        get() = listOf(
            Accent,
            TextPrimary,
            Warn,
            Color(0xFF5AA9E6),
            Color(0xFF5ED38C),
            Color(0xFFB388FF),
        )
}

private fun scheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = DashColors.Accent,
        onPrimary = Color.White,
        background = Color(0xFF0A0A0B),
        surface = Color(0xFF16171A),
        onBackground = Color(0xFFF4F4F5),
        onSurface = Color(0xFFF4F4F5),
        secondaryContainer = Color(0xFF4A0A10),
        onSecondaryContainer = Color(0xFFF4F4F5),
    )
} else {
    lightColorScheme(
        primary = DashColors.Accent,
        onPrimary = Color.White,
        background = Color(0xFFF1F2F4),
        surface = Color.White,
        onBackground = Color(0xFF121316),
        onSurface = Color(0xFF121316),
        secondaryContainer = Color(0xFFFFD9DC),
        onSecondaryContainer = Color(0xFF121316),
    )
}

/**
 * O app é olhado de longe, com o carro parado e o celular no painel: a escala de texto base é maior que a
 * padrão do Material (corpo 18-20 sp em vez de 14-16 sp; botões 20 sp).
 */
private val bigTypography = Typography(
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun DashTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    if (DashColors.dark != dark) DashColors.dark = dark
    MaterialTheme(colorScheme = scheme(dark), typography = bigTypography, content = content)
}
