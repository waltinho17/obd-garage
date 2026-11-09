package com.walter.dashobd.log

import com.walter.dashobd.obd.Sensor
import com.walter.dashobd.obd.Sensors

/** Foto dos valores mais recentes de todos os sensores num instante. */
data class Sample(val tMs: Long, val values: Map<String, Double>)

/** Log gravado; os tempos das amostras são relativos ao início da gravação. */
class LogSession(
    val startedAtMs: Long,
    val samples: List<Sample>,
    val name: String = "",
    val note: String = "",
    /** Carro em que o log foi gravado (nome mostrado no app) e o VIN, quando o carro o informou. */
    val vehicle: String = "",
    val vin: String = "",
) {
    val durationMs: Long get() = samples.lastOrNull()?.tMs ?: 0L

    /** Amostras por segundo efetivas da gravação. */
    val sampleRateHz: Double get() = if (durationMs > 0) (samples.size - 1) * 1000.0 / durationMs else 0.0

    fun samplesIn(range: LongRange?): List<Sample> = if (range == null) samples else samples.filter { it.tMs in range }

    fun withMeta(name: String, note: String) = LogSession(startedAtMs, samples, name, note, vehicle, vin)

    fun withVehicle(vehicle: String, vin: String) = LogSession(startedAtMs, samples, name, note, vehicle, vin)
}

class LogRecorder(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Relógio monotônico para os tempos das amostras: não pula se o celular acertar a hora no meio da gravação. */
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var startedAt: Long? = null
    private var startedMono = 0L
    private val samples = mutableListOf<Sample>()

    val isRecording: Boolean get() = startedAt != null

    fun start(): Long {
        samples.clear()
        startedMono = monotonic()
        return clock().also { startedAt = it }
    }

    fun add(values: Map<String, Double>) {
        if (startedAt == null) return
        samples += Sample(monotonic() - startedMono, values)
    }

    /** Encerra a gravação; devolve null se nada foi gravado. */
    fun stop(): LogSession? {
        val start = startedAt ?: return null
        startedAt = null
        return if (samples.isEmpty()) null else LogSession(start, samples.toList())
    }
}

data class SensorStats(
    val sensor: Sensor,
    val min: Double,
    val max: Double,
    val avg: Double,
    /** Instante (ms desde o início do log) do mínimo e do máximo. */
    val tAtMinMs: Long,
    val tAtMaxMs: Long,
    /** RPM no instante do mínimo/máximo (null para o próprio RPM ou se não houve leitura). */
    val rpmAtMin: Double?,
    val rpmAtMax: Double?,
)

fun LogSession.stats(sensors: List<Sensor> = Sensors.all, range: LongRange? = null): List<SensorStats> {
    val window = samplesIn(range)
    return sensors.mapNotNull { sensor ->
        var count = 0
        var sum = 0.0
        var minSample: Sample? = null
        var maxSample: Sample? = null
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (s in window) {
            val v = s.values[sensor.id] ?: continue
            count++
            sum += v
            if (v < min) { min = v; minSample = s }
            if (v > max) { max = v; maxSample = s }
        }
        if (count == 0) return@mapNotNull null
        val isRpm = sensor.id == Sensors.rpm.id
        SensorStats(
            sensor, min, max, sum / count,
            tAtMinMs = minSample!!.tMs,
            tAtMaxMs = maxSample!!.tMs,
            rpmAtMin = if (isRpm) null else minSample.values[Sensors.rpm.id],
            rpmAtMax = if (isRpm) null else maxSample.values[Sensors.rpm.id],
        )
    }
}

/**
 * Curva estilo dinamômetro: média do sensor por faixa de RPM, ordenada por RPM.
 * Cada ponto é (centro da faixa em rpm, valor médio). [range] restringe a um trecho do log.
 */
fun LogSession.curveByRpm(sensorId: String, binRpm: Int = 100, range: LongRange? = null): List<Pair<Double, Double>> =
    samplesIn(range).mapNotNull { s ->
        val rpm = s.values[Sensors.rpm.id] ?: return@mapNotNull null
        val v = s.values[sensorId] ?: return@mapNotNull null
        rpm to v
    }
        .groupBy { (rpm, _) -> (rpm / binRpm).toInt() }
        .toSortedMap()
        .map { (bin, points) -> (bin * binRpm + binRpm / 2.0) to points.map { it.second }.average() }
