package com.walter.obdgarage.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GattProfileTest {
    private fun u(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    private val genericAccess = ServiceInfo(u("1800"), listOf(CharInfo(u("2a00"), 0x02)))

    @Test
    fun escolhe_servico_com_notify_e_write_separados() {
        val vendor = ServiceInfo(u("fff0"), listOf(CharInfo(u("fff1"), 0x10 or 0x02), CharInfo(u("fff2"), 0x08 or 0x04)))
        val pick = GattProfile.pick(listOf(genericAccess, vendor))!!
        assertEquals(u("fff0"), pick.service)
        assertEquals(u("fff1"), pick.notify)
        assertEquals(u("fff2"), pick.write)
        assertFalse(pick.writeNoResponse)
    }

    @Test
    fun aceita_uma_unica_caracteristica_para_os_dois_sentidos() {
        val uart = ServiceInfo(u("ffe0"), listOf(CharInfo(u("ffe1"), 0x10 or 0x04)))
        val pick = GattProfile.pick(listOf(uart))!!
        assertEquals(pick.notify, pick.write)
        assertTrue(pick.writeNoResponse)
    }

    @Test
    fun usa_indicate_quando_nao_ha_notify() {
        val svc = ServiceInfo(u("18f0"), listOf(CharInfo(u("2af0"), 0x20), CharInfo(u("2af1"), 0x08)))
        assertTrue(GattProfile.pick(listOf(svc))!!.indicate)
    }

    @Test
    fun ignora_servicos_padrao_e_devolve_null_sem_candidato() {
        assertNull(GattProfile.pick(listOf(genericAccess)))
        assertNull(GattProfile.pick(emptyList()))
    }

    @Test
    fun assembler_junta_pedacos_ate_o_prompt() {
        val a = ResponseAssembler()
        assertNull(a.append("41 0C "))
        assertNull(a.append("1A F8\r"))
        assertEquals("41 0C 1A F8", a.append("\r>"))
        // o próximo comando começa do zero
        assertEquals("OK", a.append("OK\r\r>"))
    }

    @Test
    fun clean_remove_linhas_vazias_e_normaliza_cr() {
        assertEquals("SEARCHING...\n410C1AF8", ResponseAssembler.clean("\r\rSEARCHING...\r410C1AF8\r\r"))
    }
}
