package com.walter.dashobd.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadSchedulerTest {

    private suspend fun scheduler(transport: SimulatedObdTransport): Pair<ReadScheduler, Elm327Client> {
        transport.open()
        val client = Elm327Client(transport)
        val info = client.initialize()
        return ReadScheduler(client, Sensors.all.filter { it.pid in info.supportedPids }) to client
    }

    @Test
    fun usa_requisicao_multipla_e_preenche_todos_os_sensores() = runTest {
        val (scheduler, _) = scheduler(SimulatedObdTransport(latencyMs = 1))
        val job = launch { scheduler.run() }
        advanceTimeBy(5_000)
        job.cancel()
        assertEquals(ReadMode.MULTI, scheduler.mode.value)
        val v = scheduler.values.value
        for (id in listOf("rpm", "map", "load", "throttle", "timing", "speed", "coolant", "intake", "baro", "fuel_rail", "voltage")) {
            assertNotNull("faltou $id", v[id])
        }
        assertNotNull(v["boost"])
    }

    @Test
    fun desce_para_leitura_rapida_individual_se_o_carro_nao_aceita_multiplos_pids() = runTest {
        val (scheduler, _) = scheduler(SimulatedObdTransport(latencyMs = 1, supportsMultiPid = false))
        val job = launch { scheduler.run() }
        advanceTimeBy(5_000)
        job.cancel()
        assertEquals(ReadMode.SINGLE_FAST, scheduler.mode.value)
        assertNotNull(scheduler.values.value["rpm"])
        assertNotNull(scheduler.values.value["boost"])
    }

    @Test
    fun ignicao_desligada_vira_mensagem_clara() = runTest {
        val transport = SimulatedObdTransport(latencyMs = 1)
        val (scheduler, _) = scheduler(transport)
        transport.ignitionOn = false
        try {
            scheduler.run()
            fail("deveria falhar")
        } catch (e: ObdException) {
            assertTrue(e.message!!, e.message!!.contains("ignição"))
        }
    }
}
