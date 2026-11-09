package com.walter.obdgarage.obd

import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Finge ser um ELM327 conectado a um carro: responde comandos AT e PIDs do modo 01
 * em hexadecimal, como o adaptador de verdade faria. Assim o [Elm327Client] e o
 * agendador rodam exatamente o mesmo caminho de código que vão rodar no carro.
 */
class SimulatedObdTransport(
    private val latencyMs: Long = 35,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Falso imita um carro/adaptador que não aceita vários PIDs numa requisição (responde NO DATA). */
    private val supportsMultiPid: Boolean = true,
) : ObdTransport {

    /** Falso imita a ignição desligada: a ECU recusa tudo com 7F 01 22. */
    @Volatile
    var ignitionOn = true

    override val name = "Simulador"

    private var openedAt = 0L
    private var isOpen = false

    override suspend fun open() {
        delay(300)
        openedAt = clock()
        isOpen = true
    }

    override suspend fun close() {
        isOpen = false
    }

    /** Uma ECU simulada: endereço de requisição/resposta e os PIDs que ela suporta. */
    private class Ecu(val requestId: String, val responseId: String, val supported: Set<Int>)

    // Como no Polo real (rastreio de campo): o câmbio responde antes e suporta poucos PIDs;
    // o motor responde depois e suporta o conjunto completo. Em broadcast, as duas respondem.
    private val ecus = listOf(
        Ecu("7E1", "7E9", setOf(0x04, 0x05, 0x0C, 0x0D, 0x11, 0x20)),
        Ecu("7E0", "7E8", SUPPORTED),
    )

    private var headersOn = false
    private var requestHeader: String? = null

    override suspend fun request(command: String): String {
        check(isOpen) { "Transporte fechado" }
        delay(latencyMs)
        val cmd = command.uppercase().filter { !it.isWhitespace() }
        return when {
            cmd == "ATZ" -> {
                headersOn = false
                requestHeader = null
                "\r\rELM327 v1.5 (simulado)"
            }
            cmd == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
            cmd == "ATH1" -> "OK".also { headersOn = true }
            cmd == "ATH0" -> "OK".also { headersOn = false }
            cmd.startsWith("ATSH") -> "OK".also { requestHeader = cmd.removePrefix("ATSH") }
            cmd.startsWith("AT") -> "OK"
            cmd == "0902" -> "014\r0:490201394257\r1:4142343555384A\r2:54313233343536" // VIN simulado 9BWAB45U8JT123456
            cmd.startsWith("01") && cmd.length >= 4 -> {
                // Depois dos PIDs (2 dígitos cada) pode vir um dígito de contagem de respostas.
                val body = cmd.substring(2).let { if (it.length % 2 == 1) it.dropLast(1) else it }
                val pids = body.chunked(2)
                when {
                    !ignitionOn -> "7F0122"
                    pids.size == 1 -> mode01(pids[0])
                    supportsMultiPid -> modeMulti(pids)
                    else -> "NO DATA"
                }
            }
            else -> "?"
        }
    }

    /**
     * Requisição de vários PIDs: uma só resposta `41 PID dados PID dados...`. Acima de 7 bytes o ELM327 mostra o
     * tamanho (`014`) e os quadros numerados (`0:`, `1:`...), como no adaptador de verdade (ATS0, ATH0).
     */
    private fun modeMulti(pids: List<String>): String {
        val ecu = ecus.firstOrNull { requestHeader == null || it.requestId == requestHeader } ?: return "NO DATA"
        val parts = pids.mapNotNull { pid -> if (pid.toInt(16) in ecu.supported) value(pid)?.drop(2) else null }
        if (parts.isEmpty()) return "NO DATA"
        val bytes = "41" + parts.joinToString("")
        if (bytes.length / 2 <= 7) return bytes
        val frames = mutableListOf(bytes.take(12))
        frames += bytes.drop(12).chunked(14)
        return hex(bytes.length / 2, 3) + "\r" + frames.mapIndexed { i, f -> "${i.toString(16).uppercase()}:$f" }.joinToString("\r")
    }

    /** Monta as respostas das ECUs endereçadas (todas, se não houver ATSH), uma linha por ECU. */
    private fun mode01(pid: String): String {
        val lines = ecus
            .filter { requestHeader == null || it.requestId == requestHeader }
            .mapNotNull { ecu -> payload(ecu, pid)?.let { if (headersOn) ecu.responseId + hex(it.length / 2, 2) + it else it } }
        return if (lines.isEmpty()) "NO DATA" else lines.joinToString("\r")
    }

    private fun payload(ecu: Ecu, pid: String): String? {
        if (pid in listOf("00", "20", "40", "60")) return "41$pid" + supportBitmap(pid.toInt(16), ecu.supported)
        return if (pid.toInt(16) in ecu.supported) value(pid) else null
    }

    private fun value(pid: String): String? {
        val t = (clock() - openedAt) / 1000.0
        // Pé no acelerador oscilando em ciclos de ~14 s; o quadrado dá partes de alívio mais longas.
        val thr = ((sin(t * 2 * PI / 14.0) + 1) / 2).let { it * it }

        return when (pid) {
            "0C" -> "410C" + hex((900 + thr * 5600 + 30 * sin(t * 9)).toInt() * 4, 4)
            "0B" -> "410B" + hex((35 + thr * 165).toInt(), 2)
            "04" -> "4104" + hex(((20 + thr * 75) * 255 / 100).toInt(), 2)
            "11" -> "4111" + hex(((12 + thr * 80) * 255 / 100).toInt(), 2)
            "0E" -> "410E" + hex(((35 - thr * 28 + 64) * 2).toInt(), 2)
            "0D" -> "410D" + hex((20 + thr * 100).toInt(), 2)
            "05" -> "4105" + hex((min(92.0, 40 + t * 0.8 + 2 * sin(t / 3)) + 40).toInt(), 2)
            "0F" -> "410F" + hex((28 + thr * 9 + 40).toInt(), 2)
            "06" -> "4106" + hex(((3 * sin(t * 1.3) + 100) * 128 / 100).toInt(), 2)
            "07" -> "4107" + hex(((1.5 + 100) * 128 / 100).toInt(), 2)
            "33" -> "4133" + hex(101, 2)
            "23" -> "4123" + hex(((50 + thr * 100) * 10).toInt(), 4)
            "44" -> "4144" + hex((32768 * (if (thr > 0.8) 0.86 else 1.0)).toInt(), 4)
            "42" -> "4142" + hex(14100, 4)
            "46" -> "4146" + hex(28 + 40, 2)
            "52" -> "4152" + hex(128, 2) // 50,2%: E50, como o Walter vai usar
            else -> null
        }
    }

    /** Mapa de PIDs suportados (32 bits) do bloco que começa depois de [base]. */
    private fun supportBitmap(base: Int, supported: Set<Int>): String {
        var bits = 0L
        for (i in 0 until 32) if (base + 1 + i in supported) bits = bits or (1L shl (31 - i))
        return bits.toString(16).uppercase().padStart(8, '0')
    }

    private companion object {
        // 0x20 e 0x40 indicam que existem os mapas seguintes, como numa ECU de verdade.
        val SUPPORTED = setOf(
            0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x20,
            0x23, 0x33, 0x40, 0x42, 0x44, 0x46, 0x52,
        )
    }

    private fun hex(value: Int, digits: Int) =
        value.coerceAtLeast(0).toString(16).uppercase().padStart(digits, '0').takeLast(digits)
}
