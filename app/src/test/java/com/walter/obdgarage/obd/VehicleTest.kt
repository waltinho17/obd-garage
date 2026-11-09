package com.walter.obdgarage.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleTest {
    private val raw = "014\r0:490201394257\r1:4142343555384A\r2:54313233343536"

    @Test
    fun le_o_vin_em_varios_quadros() {
        assertEquals("9BWAB45U8JT123456", Vehicle.parseVin(raw))
    }

    @Test
    fun resposta_sem_vin_devolve_nulo() {
        assertNull(Vehicle.parseVin("NO DATA"))
        assertNull(Vehicle.parseVin("7F0922"))
    }

    @Test
    fun marca_e_ano_pelo_vin() {
        assertEquals(2018, Vehicle.modelYear("9BWAB45U8JT123456"))
        assertEquals("VOLKSWAGEN 2018", Vehicle.defaultName("9BWAB45U8JT123456"))
        assertNull(Vehicle.defaultName("XXXAB45U8JT123456"))
        assertNull(Vehicle.defaultName(null))
    }

    @Test
    fun oleo_e_decodificado_do_pid_5c() {
        assertEquals(90.0, Sensors.oil.decode(listOf(130)), 0.001)
    }
}
