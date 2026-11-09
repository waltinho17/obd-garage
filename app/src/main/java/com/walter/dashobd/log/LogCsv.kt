package com.walter.dashobd.log

import com.walter.dashobd.obd.Sensor
import com.walter.dashobd.obd.Sensors
import java.util.Locale
import kotlin.math.roundToLong

private const val FORMAT_TAG = "dashobd-log 1"

/**
 * CSV com cabeçalho de metadados em linhas `# chave=valor` (ignoradas por planilhas que
 * tratam `#` como comentário; em outras, são só linhas extras no topo).
 */
fun LogSession.toCsv(sensors: List<Sensor> = Sensors.all): String = buildString {
    append("# ").append(FORMAT_TAG).append('\n')
    append("# inicio_ms=").append(startedAtMs).append('\n')
    append("# nome=").append(name.singleLine()).append('\n')
    append("# nota=").append(note.singleLine()).append('\n')
    append("# carro=").append(vehicle.singleLine()).append('\n')
    append("# vin=").append(vin.singleLine()).append('\n')
    append("tempo_s")
    sensors.forEach { append(',').append(it.id) }
    append('\n')
    for (s in samples) {
        append(String.format(Locale.ROOT, "%.1f", s.tMs / 1000.0))
        for (sensor in sensors) {
            append(',')
            s.values[sensor.id]?.let { append(String.format(Locale.ROOT, "%.3f", it)) }
        }
        append('\n')
    }
}

/** Lê um CSV gravado por [toCsv]; devolve null se o arquivo não tiver o formato esperado. */
fun parseLogCsv(text: String): LogSession? {
    val meta = mutableMapOf<String, String>()
    var ids: List<String>? = null
    val samples = mutableListOf<Sample>()
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        if (line.startsWith("#")) {
            val body = line.removePrefix("#").trim()
            val eq = body.indexOf('=')
            if (eq > 0) meta[body.substring(0, eq)] = body.substring(eq + 1)
            continue
        }
        val cells = line.split(',')
        if (ids == null) {
            if (cells.firstOrNull() != "tempo_s") return null
            ids = cells.drop(1)
            continue
        }
        val t = cells[0].toDoubleOrNull() ?: continue
        val values = HashMap<String, Double>()
        for ((i, id) in ids.withIndex()) {
            cells.getOrNull(i + 1)?.toDoubleOrNull()?.let { values[id] = it }
        }
        samples += Sample((t * 1000).roundToLong(), values)
    }
    if (ids == null || samples.isEmpty()) return null
    return LogSession(
        startedAtMs = meta["inicio_ms"]?.toLongOrNull() ?: 0L,
        samples = samples,
        name = meta["nome"].orEmpty(),
        note = meta["nota"].orEmpty(),
        vehicle = meta["carro"].orEmpty(),
        vin = meta["vin"].orEmpty(),
    )
}

private fun String.singleLine() = replace('\n', ' ').replace('\r', ' ').trim()
