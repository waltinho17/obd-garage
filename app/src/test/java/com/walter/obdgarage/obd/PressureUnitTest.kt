package com.walter.obdgarage.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class PressureUnitTest {
    @Test
    fun conversoes_do_boost_relativo() {
        assertEquals(1.43, PressureUnit.BAR.fromBar(1.43), 1e-9)
        assertEquals(143.0, PressureUnit.KPA.fromBar(1.43), 1e-9)
        assertEquals(20.74, PressureUnit.PSI.fromBar(1.43), 0.01)
        // "quilo" (kgf/cm²) é ~2% maior que o bar
        assertEquals(1.458, PressureUnit.KGF.fromBar(1.43), 0.001)
    }

    @Test
    fun alterna_em_ciclo_e_le_preferencia_invalida_como_bar() {
        assertEquals(PressureUnit.BAR, PressureUnit.KPA.next())
        assertEquals(PressureUnit.KGF, PressureUnit.BAR.next())
        assertEquals(PressureUnit.BAR, PressureUnit.parse(null))
        assertEquals(PressureUnit.PSI, PressureUnit.parse("PSI"))
    }
}
