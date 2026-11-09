package com.walter.dashobd.obd

/**
 * Unidades para mostrar a pressão do turbo. O valor interno é sempre bar RELATIVO (o "boost": pressão no
 * coletor menos a pressão atmosférica do dia); aqui só se converte para exibição.
 *
 * "Quilo" no dia a dia é kgf/cm², que vale 0,980665 bar: a diferença para o bar é de ~2%.
 */
enum class PressureUnit(val label: String, val perBar: Double, val decimals: Int) {
    BAR("bar", 1.0, 2),
    KGF("kgf/cm²", 1.0 / 0.980665, 2),
    PSI("psi", 14.5037738, 1),
    KPA("kPa", 100.0, 0),
    ;

    fun fromBar(bar: Double): Double = bar * perBar

    fun next(): PressureUnit = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(name: String?): PressureUnit = entries.firstOrNull { it.name == name } ?: BAR
    }
}
