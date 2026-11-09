package com.walter.obdgarage.obd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Elm327ClientTest {

    @Test
    fun parseia_resposta_sem_espacos() {
        assertEquals(listOf(0x1A, 0xF8), Elm327Client.parseMode01("410C1AF8", "0C"))
    }

    @Test
    fun parseia_resposta_com_espacos_e_ruido() {
        assertEquals(listOf(0x7B), Elm327Client.parseMode01("SEARCHING...\r41 05 7B\r", "05"))
    }

    @Test
    fun sem_dados_devolve_null() {
        assertNull(Elm327Client.parseMode01("NO DATA", "0C"))
        assertNull(Elm327Client.parseMode01("410C1AF", "0C"))
    }

    @Test
    fun rpm_decodifica_corretamente() {
        // 0x1AF8 = 6904 -> 1726 rpm
        assertEquals(1726.0, Sensors.rpm.decode(listOf(0x1A, 0xF8)), 0.001)
    }

    @Test
    fun bloqueia_comandos_que_nao_sao_leitura() = runTest {
        val client = Elm327Client(SimulatedObdTransport().also { it.open() })
        for (cmd in listOf("04", "0300", "14", "2201AB", "ATZ;04", "09 02")) {
            try {
                client.send(cmd)
                fail("deveria bloquear $cmd")
            } catch (_: ObdException) {
            }
        }
    }

    @Test
    fun mapa_de_pids_suportados_e_lido_em_blocos() = runTest {
        val client = Elm327Client(SimulatedObdTransport(latencyMs = 0).also { it.open() })
        client.send("ATSH7E0") // só o motor responde
        val supported = client.supportedPids()
        assertTrue(supported.containsAll(listOf("04", "05", "06", "07", "0B", "0C", "0D", "0E", "0F", "11", "23", "33", "42", "44", "46")))
    }

    @Test
    fun boost_usa_a_barometrica_medida_quando_existe() {
        // MAP 199 kPa com barométrica 91 kPa (altitude): boost = 1,08 bar, não 0,98 (que assume 101,3).
        val values = mapOf("map" to 199.0, "baro" to 91.0)
        assertEquals(1.08, Sensors.derive(values)["boost"]!!, 1e-9)
        // sem barométrica, mantém o valor calculado com pressão fixa
        assertEquals(mapOf("map" to 199.0), Sensors.derive(mapOf("map" to 199.0)))
    }

    @Test
    fun formulas_dos_novos_pids() {
        assertEquals(1.0, Sensors.lambdaCmd.decode(listOf(0x80, 0x00)), 1e-9) // 32768 -> λ 1,00
        assertEquals(150.0, Sensors.fuelRail.decode(listOf(0x05, 0xDC)), 1e-9) // 1500 -> 150 bar
        assertEquals(14.1, Sensors.voltage.decode(listOf(0x37, 0x14)), 1e-9) // 14100 mV
        assertEquals(100.0, Sensors.ethanol.decode(listOf(255)), 1e-9)
    }

    @Test
    fun init_escolhe_a_ecu_do_motor_e_nao_a_do_cambio() = runTest {
        // Regressão do defeito encontrado no teste de campo: em broadcast o câmbio responde primeiro,
        // com poucos PIDs, e o app passava a ler só esses. Aqui o simulador reproduz as duas ECUs.
        val client = Elm327Client(SimulatedObdTransport(latencyMs = 0).also { it.open() })
        val info = client.initialize()
        assertEquals("7E8", info.ecu)
        assertEquals(setOf("7E9"), info.otherEcus)
        assertTrue(info.supportedPids.containsAll(listOf("0B", "0E", "0F", "06", "07")))
        // depois do init, só o motor responde: uma linha por PID, e PIDs só do motor funcionam
        assertEquals(1, client.readPid("0B")!!.size)
        assertEquals(2, client.readPid("0C")!!.size)
    }

    @Test
    fun parse_de_respostas_com_cabecalho_separa_as_ecus() {
        val raw = "SEARCHING...\r7E906410098188001\r7E806410 0BE3EB813\r".replace(" ", "")
        val replies = Elm327Client.parseHeaderedReplies(raw, "00")
        assertEquals(listOf("7E9", "7E8"), replies.map { it.id })
        assertEquals(listOf(0xBE, 0x3E, 0xB8, 0x13), replies[1].bytes)
        // sem cabeçalho, linhas de dados puras não são confundidas com respostas de ECU
        assertTrue(Elm327Client.parseHeaderedReplies("410C1AF8", "0C").isEmpty())
    }

    @Test
    fun bytes_reais_do_polo_o_motor_suporta_mais_pids_que_o_cambio() = runTest {
        // Mapas lidos no carro (rastreio de campo): ECU A = 98188001 / 00000001..., ECU B = BE3EB813 / A007A011 / FED0C000.
        val broadcast = mapOf(
            "0100" to "7E906410098188001\r7E806410 0BE3EB813",
            "0120" to "7E9064120 00000001\r7E80641 20A007A011",
            "0140" to "7E9064140E0800000\r7E8064140FED0C000",
            "0160" to "NO DATA",
        ).mapValues { it.value.replace(" ", "") }
        val sent = mutableListOf<String>()
        val transport = object : ObdTransport {
            override val name = "polo"
            override suspend fun open() {}
            override suspend fun close() {}
            override suspend fun request(command: String): String {
                sent += command
                return when {
                    command == "ATZ" -> "ELM327 v2.3"
                    command == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
                    command.startsWith("AT") -> "OK"
                    else -> broadcast[command] ?: "NO DATA"
                }
            }
        }
        val info = Elm327Client(transport).initialize()
        assertEquals("7E8", info.ecu)
        assertTrue(info.supportedPids.containsAll(listOf("04", "05", "06", "07", "0B", "0C", "0D", "0E", "0F", "11")))
        assertTrue("ATSH7E0" in sent)
        assertTrue(sent.indexOf("ATSH7E0") < sent.lastIndexOf("ATH0")) // cabeçalhos voltam a ficar desligados
    }

    @Test
    fun mapa_decodifica_bits_e_segue_para_o_bloco_seguinte() = runTest {
        // 0100: PIDs 01 e 20 (bit mais alto e mais baixo) -> indica que existe 0120
        // 0120: PID 21 -> não indica 0140
        val replies = mapOf("0100" to "4100 80 00 00 01", "0120" to "4120 80 00 00 00")
        val transport = object : ObdTransport {
            override val name = "fake"
            override suspend fun open() {}
            override suspend fun close() {}
            override suspend fun request(command: String) = replies[command] ?: "NO DATA"
        }
        assertEquals(setOf("01", "20", "21"), Elm327Client(transport).supportedPids())
    }

    @Test
    fun init_falha_com_mensagem_clara_se_o_carro_nao_responde() = runTest {
        val transport = object : ObdTransport {
            override val name = "mudo"
            override suspend fun open() {}
            override suspend fun close() {}
            override suspend fun request(command: String) = if (command.startsWith("AT")) "OK" else "UNABLE TO CONNECT"
        }
        try {
            Elm327Client(transport).initialize()
            fail("deveria falhar")
        } catch (e: ObdException) {
            assertTrue(e.message!!.contains("ignição"))
        }
    }

    @Test
    fun simulador_responde_pelo_cliente() = runTest {
        val transport = SimulatedObdTransport(latencyMs = 0).also { it.open() }
        val client = Elm327Client(transport)
        val info = client.initialize()
        assertTrue(info.version.contains("ELM327"))
        assertTrue(info.protocol.contains("CAN"))
        val rpm = client.readPid("0C")!!
        assertEquals(2, rpm.size)
        assertNull(client.readPid("FF"))
    }
}

class EthanolMixTest {
    @org.junit.Test
    fun `nome da mistura e fracao de gasolina`() {
        org.junit.Assert.assertEquals("E32", Sensors.ethanolName(31.765)) // leitura real do Polo (0x51)
        org.junit.Assert.assertEquals("E50", Sensors.ethanolName(50.2))
        org.junit.Assert.assertEquals(68L, Sensors.gasolinePercent(31.765))
        org.junit.Assert.assertNull(Sensors.ethanolName(null))
        org.junit.Assert.assertEquals(31.765, Sensors.ethanol.decode(listOf(0x51)), 1e-3)
    }
}
