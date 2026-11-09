package com.walter.obdgarage.log

import com.walter.obdgarage.obd.Sensors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resumo de um log para a lista, calculado ao ler o arquivo. */
data class LogEntry(
    val id: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val name: String,
    val note: String,
    val vehicle: String,
    val maxRpm: Double?,
    val maxBoost: Double?,
    val maxIntake: Double?,
    val pullCount: Int,
)

/** Guarda cada log como um CSV em [dir]. O id de um log é o nome do arquivo. */
class LogStore(private val dir: File) {

    fun save(session: LogSession): LogEntry {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(session.startedAtMs))
        val file = uniqueFile("log_$stamp")
        file.writeText(session.toCsv())
        return entryOf(file.name, session)
    }

    /** Logs gravados, do mais recente para o mais antigo. */
    fun list(): List<LogEntry> =
        dir.listFiles { f -> f.extension == "csv" }.orEmpty()
            .mapNotNull { f -> load(f.name)?.let { entryOf(f.name, it) } }
            .sortedByDescending { it.startedAtMs }

    fun load(id: String): LogSession? =
        fileFor(id).takeIf { it.isFile }?.let { parseLogCsv(it.readText()) }

    fun update(id: String, name: String, note: String): LogEntry? {
        val session = load(id)?.withMeta(name.trim(), note.trim()) ?: return null
        fileFor(id).writeText(session.toCsv())
        return entryOf(id, session)
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    fun path(id: String): String = fileFor(id).absolutePath

    private fun fileFor(id: String) = File(dir, File(id).name) // File(id).name descarta qualquer caminho

    private fun uniqueFile(base: String): File {
        var file = File(dir, "$base.csv")
        var n = 2
        while (file.exists()) file = File(dir, "${base}_${n++}.csv")
        return file
    }

    private fun entryOf(id: String, s: LogSession): LogEntry {
        val stats = s.stats().associateBy { it.sensor.id }
        return LogEntry(
            id = id,
            startedAtMs = s.startedAtMs,
            durationMs = s.durationMs,
            sampleCount = s.samples.size,
            name = s.name,
            note = s.note,
            vehicle = s.vehicle,
            maxRpm = stats[Sensors.rpm.id]?.max,
            maxBoost = stats[Sensors.boost.id]?.max,
            maxIntake = stats[Sensors.intake.id]?.max,
            pullCount = s.pulls().size,
        )
    }
}
