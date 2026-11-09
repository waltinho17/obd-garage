package com.walter.obdgarage.ble

import java.util.UUID

data class BleDevice(val name: String?, val address: String, val rssi: Int = 0)

data class CharInfo(val uuid: UUID, val properties: Int)

data class ServiceInfo(val uuid: UUID, val characteristics: List<CharInfo>)

/** Características escolhidas para falar com o ELM327 (um "UART" sobre BLE). */
data class UartChars(
    val service: UUID,
    val notify: UUID,
    val write: UUID,
    /** Verdadeiro se a característica de escrita só aceita escrita sem resposta. */
    val writeNoResponse: Boolean,
    val indicate: Boolean,
)

object GattProfile {
    private const val PROP_WRITE_NO_RESPONSE = 0x04
    private const val PROP_WRITE = 0x08
    private const val PROP_NOTIFY = 0x10
    private const val PROP_INDICATE = 0x20

    // Serviços padrão do Bluetooth que nunca são o canal do adaptador.
    private val STANDARD_IGNORED = setOf("1800", "1801", "180a", "180f")
    private val BASE_UUID = Regex("0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb")

    fun shortId(uuid: UUID): String? = BASE_UUID.matchEntire(uuid.toString().lowercase())?.groupValues?.get(1)

    /**
     * Procura um serviço com uma característica que notifica e outra (ou a mesma) que aceita
     * escrita. Não depende de UUIDs conhecidos: adaptadores ELM327 BLE variam (FFF0, 18F0,
     * FFE0, UUIDs proprietários...).
     */
    fun pick(services: List<ServiceInfo>): UartChars? {
        for (service in services) {
            if (shortId(service.uuid) in STANDARD_IGNORED) continue
            val chars = service.characteristics
            val notify = chars.firstOrNull { it.properties and (PROP_NOTIFY or PROP_INDICATE) != 0 } ?: continue
            val write = chars.firstOrNull { it.uuid != notify.uuid && it.canWrite() }
                ?: notify.takeIf { it.canWrite() }
                ?: continue
            return UartChars(
                service = service.uuid,
                notify = notify.uuid,
                write = write.uuid,
                writeNoResponse = write.properties and PROP_WRITE == 0,
                indicate = notify.properties and PROP_NOTIFY == 0,
            )
        }
        return null
    }

    private fun CharInfo.canWrite() = properties and (PROP_WRITE or PROP_WRITE_NO_RESPONSE) != 0
}

/**
 * Junta os pedaços de texto que chegam por notificação até o prompt `>` do ELM327,
 * que marca o fim de uma resposta.
 */
class ResponseAssembler {
    private val buffer = StringBuilder()

    /** Devolve a resposta limpa quando o prompt chegou, ou null se ainda falta. */
    @Synchronized
    fun append(chunk: String): String? {
        buffer.append(chunk)
        val end = buffer.indexOf(">")
        if (end < 0) return null
        val raw = buffer.substring(0, end)
        buffer.setLength(0)
        return clean(raw)
    }

    @Synchronized
    fun reset() = buffer.setLength(0)

    companion object {
        /** Normaliza quebras de linha (`\r`) e remove linhas vazias. */
        fun clean(raw: String): String =
            raw.replace('\r', '\n').lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
