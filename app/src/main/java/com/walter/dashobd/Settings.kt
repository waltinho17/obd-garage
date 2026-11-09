package com.walter.dashobd

enum class ThemeMode(val label: String) { DARK("Escuro"), LIGHT("Claro"), AUTO("Automático") }

enum class OrientationMode(val label: String) { AUTO("Automática"), PORTRAIT("Retrato"), LANDSCAPE("Paisagem") }

/** Escala do texto do app inteiro, para ler de mais longe. */
enum class TextSize(val label: String, val scale: Float) {
    NORMAL("Normal", 1f),
    LARGE("Grande", 1.15f),
    XLARGE("Maior", 1.3f),
}

/** Preferências escolhidas antes de abrir o dash. */
data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val textSize: TextSize = TextSize.NORMAL,
    val orientation: OrientationMode = OrientationMode.AUTO,
    /** Id da aba aberta ao conectar (sport, turbo, cluster, data). */
    val startDash: String = "sport",
    val keepScreenOn: Boolean = true,
)

inline fun <reified E : Enum<E>> parseEnum(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
