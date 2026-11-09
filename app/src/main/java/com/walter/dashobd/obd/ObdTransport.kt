package com.walter.dashobd.obd

/**
 * Transporte de texto para um adaptador ELM327. Quem implementa só move bytes/linhas;
 * a lógica do protocolo fica em [Elm327Client].
 */
interface ObdTransport {
    val name: String

    suspend fun open()

    suspend fun close()

    /** Envia um comando (sem `\r`) e devolve a resposta bruta, sem o prompt `>`. */
    suspend fun request(command: String): String
}

class ObdException(message: String) : Exception(message)
