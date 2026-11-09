package com.walter.dashobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPidTest {
    private val lengths = Sensors.lengthByPid

    @Test
    fun resposta_multipla_em_varios_quadros_e_remontada() {
        // 010C0B1104 -> 41 0C 2B90 0B DE 11 E1 04 EE (10 bytes): o ELM327 mostra o tamanho e quadros numerados.
        val raw = "00A\r0:410C2B900BDE\r1:11E104EE"
        val r = Elm327Client.parseMultiMode01(raw, listOf("0C", "0B", "11", "04"), lengths)
        assertEquals(listOf(0x2B, 0x90), r["0C"])
        assertEquals(listOf(0xDE), r["0B"])
        assertEquals(listOf(0xE1), r["11"])
        assertEquals(listOf(0xEE), r["04"])
    }

    @Test
    fun resposta_multipla_em_um_quadro_so() {
        val r = Elm327Client.parseMultiMode01("410C2B900BDE", listOf("0C", "0B"), lengths)
        assertEquals(mapOf("0C" to listOf(0x2B, 0x90), "0B" to listOf(0xDE)), r)
    }

    @Test
    fun recusas_e_ruido_nao_geram_dados() {
        assertTrue(Elm327Client.parseMultiMode01("NO DATA", listOf("0C", "0B"), lengths).isEmpty())
        assertTrue(Elm327Client.parseMultiMode01("7F0122", listOf("0C", "0B"), lengths).isEmpty())
        assertTrue(Elm327Client.parseMultiMode01("SEARCHING...\r?", listOf("0C"), lengths).isEmpty())
    }

    @Test
    fun resposta_cortada_devolve_so_o_que_veio_inteiro() {
        // o segundo PID (0C, 2 bytes) veio com 1 byte só
        val r = Elm327Client.parseMultiMode01("410B DE 0C 2B".replace(" ", ""), listOf("0B", "0C"), lengths)
        assertEquals(mapOf("0B" to listOf(0xDE)), r)
    }


    @Test
    fun cliente_aceita_ate_6_pids_e_continua_bloqueando_escrita() = kotlinx.coroutines.test.runTest {
        val client = Elm327Client(SimulatedObdTransport(latencyMs = 0).also { it.open() })
        for (cmd in listOf("010C", "010C1", "010C0B1104", "010C0B11044905")) client.send(cmd)
        for (cmd in listOf("010C0B1104490506", "0300", "04", "2201AB", "0902", "ATZ;04")) {
            try {
                client.send(cmd)
                org.junit.Assert.fail("deveria bloquear $cmd")
            } catch (_: ObdException) {
            }
        }
    }
}
