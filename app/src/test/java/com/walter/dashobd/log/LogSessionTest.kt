package com.walter.dashobd.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSessionTest {

    private fun session() = LogSession(
        0,
        listOf(
            Sample(0, mapOf("rpm" to 2000.0, "boost" to 0.1)),
            Sample(100, mapOf("rpm" to 3000.0, "boost" to 0.9)),
            Sample(200, mapOf("rpm" to 3040.0, "boost" to 1.1)),
            Sample(300, mapOf("rpm" to 5000.0, "boost" to 0.8)),
        ),
    )

    @Test
    fun stats_calcula_min_max_media_e_rpm_do_pico() {
        val boost = session().stats().first { it.sensor.id == "boost" }
        assertEquals(0.1, boost.min, 1e-9)
        assertEquals(1.1, boost.max, 1e-9)
        assertEquals(0.725, boost.avg, 1e-9)
        assertEquals(3040.0, boost.rpmAtMax!!, 1e-9)
        assertEquals(2000.0, boost.rpmAtMin!!, 1e-9)
    }

    @Test
    fun stats_ignora_sensores_sem_leitura() {
        assertTrue(session().stats().none { it.sensor.id == "coolant" })
    }

    @Test
    fun curva_por_rpm_agrupa_em_faixas() {
        val curve = session().curveByRpm("boost", binRpm = 100)
        assertEquals(listOf(2050.0, 3050.0, 5050.0), curve.map { it.first })
        assertEquals(1.0, curve[1].second, 1e-9) // média de 0,9 e 1,1
    }

    @Test
    fun recorder_grava_so_depois_do_start() {
        var now = 1000L
        val rec = LogRecorder { now }
        rec.add(mapOf("rpm" to 1.0))
        assertNull(rec.stop())

        rec.start()
        assertTrue(rec.isRecording)
        now = 1500
        rec.add(mapOf("rpm" to 2.0))
        val log = rec.stop()!!
        assertFalse(rec.isRecording)
        assertEquals(500L, log.durationMs)
        assertEquals(1, log.samples.size)
    }

    @Test
    fun csv_tem_cabecalho_e_campos_vazios_para_sensor_ausente() {
        val lines = session().toCsv().lines().filter { !it.startsWith("#") }
        assertTrue(lines[0].startsWith("tempo_s,rpm,boost,"))
        assertTrue(lines[1].startsWith("0.0,2000.000,0.100,"))
        assertTrue(lines[1].endsWith(",,,,,,,,,")) // restante dos sensores sem leitura
    }

    @Test
    fun csv_vai_e_volta_com_metadados() {
        val original = LogSession(1_700_000_000_000, session().samples, name = "Stage 1", note = "98 octanas\n3ª marcha")
        val back = parseLogCsv(original.toCsv())!!
        assertEquals(1_700_000_000_000, back.startedAtMs)
        assertEquals("Stage 1", back.name)
        assertEquals("98 octanas 3ª marcha", back.note)
        assertEquals(original.samples.size, back.samples.size)
        assertEquals(3040.0, back.samples[2].values["rpm"]!!, 1e-9)
        assertEquals(200L, back.samples[2].tMs)
        assertNull(back.samples[0].values["coolant"])
    }

    @Test
    fun csv_invalido_devolve_null() {
        assertNull(parseLogCsv("qualquer coisa"))
        assertNull(parseLogCsv(""))
    }

    private fun pullLog(): LogSession {
        val samples = (0..60).map { i ->
            val t = i * 100L
            val inPull = i in 10..30 // 2 s de borboleta aberta
            val blip = i in 45..47 // 0,2 s: não é pull
            Sample(
                t,
                mapOf(
                    "throttle" to if (inPull || blip) 95.0 else 15.0,
                    "rpm" to if (inPull) 3000.0 + (i - 10) * 100 else 1500.0,
                    "boost" to if (inPull) 0.5 + (i - 10) * 0.03 else 0.0,
                    "timing" to if (inPull) 30.0 - (i - 10) * 0.5 else 35.0,
                ),
            )
        }
        return LogSession(0, samples)
    }

    @Test
    fun detecta_pull_e_ignora_toque_curto() {
        val pulls = pullLog().pulls()
        assertEquals(1, pulls.size)
        val p = pulls[0]
        assertEquals(1000L, p.startMs)
        assertEquals(3000L, p.endMs)
        assertEquals(3000.0, p.rpmStart!!, 1e-9)
        assertEquals(5000.0, p.rpmEnd!!, 1e-9)
        assertEquals(1.1, p.boostPeak!!, 1e-9)
        assertEquals(5000.0, p.rpmAtBoostPeak!!, 1e-9)
        assertEquals(20.0, p.timingMin!!, 1e-9)
    }

    @Test
    fun pull_que_termina_com_rpm_menor_que_o_inicio_ainda_conta() {
        // borboleta aberta por 2 s: rpm 5600 -> 6500 -> 5600
        val samples = (0..30).map { i ->
            val rpm = if (i <= 15) 5600.0 + i * 60 else 5600.0 + (30 - i) * 60
            Sample(i * 100L, mapOf("throttle" to 92.0, "rpm" to rpm))
        }
        assertEquals(1, LogSession(0, samples).pulls().size)
    }

    @Test
    fun borboleta_aberta_sem_variacao_de_rpm_nao_e_pull() {
        val samples = (0..30).map { Sample(it * 100L, mapOf("throttle" to 92.0, "rpm" to 3000.0)) }
        assertTrue(LogSession(0, samples).pulls().isEmpty())
    }

    @Test
    fun stats_e_curva_respeitam_o_trecho() {
        val log = pullLog()
        val range = log.pulls()[0].range
        val rpm = log.stats(range = range).first { it.sensor.id == "rpm" }
        assertEquals(3000.0, rpm.min, 1e-9) // sem os 1500 rpm de fora do pull
        assertTrue(log.curveByRpm("boost", range = range).all { it.first >= 3000.0 })
    }

    @Test
    fun store_salva_lista_renomeia_e_apaga() {
        val dir = java.nio.file.Files.createTempDirectory("logs").toFile()
        val store = LogStore(dir)
        val a = store.save(LogSession(1_700_000_000_000, pullLog().samples))
        val b = store.save(LogSession(1_700_000_100_000, pullLog().samples))
        assertEquals(1, a.pullCount)
        assertEquals(listOf(b.id, a.id), store.list().map { it.id }) // mais recente primeiro

        val renamed = store.update(a.id, "Stage 2", "E30")!!
        assertEquals("Stage 2", renamed.name)
        assertEquals("E30", store.load(a.id)!!.note)

        store.delete(a.id)
        assertEquals(listOf(b.id), store.list().map { it.id })
        dir.deleteRecursively()
    }
}
