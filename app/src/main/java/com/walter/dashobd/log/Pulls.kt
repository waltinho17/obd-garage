package com.walter.dashobd.log

import com.walter.dashobd.obd.Sensors

/** Uma aceleração a fundo, com os números que importam para avaliar o carro. */
data class Pull(
    val startMs: Long,
    val endMs: Long,
    val rpmStart: Double?,
    val rpmEnd: Double?,
    val boostPeak: Double?,
    val rpmAtBoostPeak: Double?,
    val timingMin: Double?,
    val rpmAtTimingMin: Double?,
    val loadMax: Double?,
    val intakeMax: Double?,
) {
    val durationMs: Long get() = endMs - startMs
    val range: LongRange get() = startMs..endMs
}

/**
 * Detecta pulls: trechos com borboleta aberta (>= [throttleMin] %), com pelo menos
 * [minDurationMs] e ganho de rotação de [minRpmGain]. Falhas curtas de até [mergeGapMs]
 * dentro do trecho não o dividem.
 */
fun LogSession.pulls(
    throttleMin: Double = 80.0,
    minDurationMs: Long = 1500,
    mergeGapMs: Long = 500,
    minRpmGain: Double = 500.0,
): List<Pull> {
    // 1) trechos contínuos de amostras com borboleta aberta
    val segments = mutableListOf<MutableList<Sample>>()
    var current: MutableList<Sample>? = null
    for (s in samples) {
        val open = (s.values[Sensors.throttle.id] ?: -1.0) >= throttleMin
        if (open) {
            if (current == null) current = mutableListOf<Sample>().also { segments += it }
            current += s
        } else {
            current = null
        }
    }
    // 2) junta trechos separados por falhas curtas
    val merged = mutableListOf<MutableList<Sample>>()
    for (seg in segments) {
        val last = merged.lastOrNull()
        if (last != null && seg.first().tMs - last.last().tMs <= mergeGapMs) last += seg else merged += seg
    }
    // 3) filtra e resume
    return merged.mapNotNull { seg ->
        val start = seg.first().tMs
        val end = seg.last().tMs
        if (end - start < minDurationMs) return@mapNotNull null
        val rpmStart = seg.firstNotNullOfOrNull { it.values[Sensors.rpm.id] }
        val rpmEnd = seg.lastNotNullOfOrNull { it.values[Sensors.rpm.id] }
        // Variação dentro do trecho (e não fim - início), para não descartar um pull que termina
        // com o pé levantado depois de passar pelo pico de rotação.
        val rpms = seg.mapNotNull { it.values[Sensors.rpm.id] }
        if (rpms.isNotEmpty() && rpms.max() - rpms.min() < minRpmGain) return@mapNotNull null

        val boostAt = seg.filter { it.values[Sensors.boost.id] != null }.maxByOrNull { it.values.getValue(Sensors.boost.id) }
        val timingAt = seg.filter { it.values[Sensors.timing.id] != null }.minByOrNull { it.values.getValue(Sensors.timing.id) }
        Pull(
            startMs = start,
            endMs = end,
            rpmStart = rpmStart,
            rpmEnd = rpmEnd,
            boostPeak = boostAt?.values?.get(Sensors.boost.id),
            rpmAtBoostPeak = boostAt?.values?.get(Sensors.rpm.id),
            timingMin = timingAt?.values?.get(Sensors.timing.id),
            rpmAtTimingMin = timingAt?.values?.get(Sensors.rpm.id),
            loadMax = seg.mapNotNull { it.values[Sensors.load.id] }.maxOrNull(),
            intakeMax = seg.mapNotNull { it.values[Sensors.intake.id] }.maxOrNull(),
        )
    }
}

private inline fun <T, R : Any> List<T>.lastNotNullOfOrNull(transform: (T) -> R?): R? {
    for (i in indices.reversed()) transform(this[i])?.let { return it }
    return null
}
