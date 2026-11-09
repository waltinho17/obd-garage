package com.walter.obdgarage.obd

import kotlinx.coroutines.delay

/**
 * Cliente ELM327 sobre um [ObdTransport]. Somente leitura: só aceita comandos AT
 * (configuração do adaptador) e requisições do modo 01 (dados atuais).
 */
class Elm327Client(private val transport: ObdTransport) {

    /** Resposta de uma ECU a um PID, com o identificador CAN de resposta (ex.: 7E8). */
    data class EcuReply(val id: String, val bytes: List<Int>)

    data class AdapterInfo(
        val version: String,
        val protocol: String,
        val supportedPids: Set<String>,
        /** Identificador CAN de resposta da ECU escolhida (ex.: 7E8), ou null se não deu para identificar. */
        val ecu: String? = null,
        /** Outras ECUs que responderam à busca (ex.: câmbio), ignoradas na leitura. */
        val otherEcus: Set<String> = emptySet(),
    ) {
        val description: String
            get() = listOfNotNull(version, protocol, ecu?.let { "ECU $it" }, "${supportedPids.size} PIDs").joinToString(" · ")
    }

    /**
     * Roda a sequência de init, detecta o protocolo, identifica as ECUs que respondem e passa a
     * falar só com a do motor. Falha com [ObdException] se nenhuma ECU responder (ignição desligada).
     *
     * Os PIDs do modo 01 são enviados em broadcast; num VAG o motor e o câmbio respondem juntos, cada um com
     * a sua lista de PIDs suportados. Por isso o init liga os cabeçalhos CAN (ATH1), mede quantos PIDs cada ECU
     * suporta e fixa o endereço de requisição na mais completa (ATSH), que é o motor.
     */
    suspend fun initialize(): AdapterInfo {
        val version = send("ATZ")
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATSP0")) send(cmd)

        // O primeiro contato com a ECU dispara a busca de protocolo e às vezes falha; tenta de novo.
        send("ATH1")
        var ecus = emptyMap<String, Set<String>>()
        repeat(3) {
            if (ecus.isEmpty()) {
                ecus = discoverEcus()
                if (ecus.isEmpty()) delay(500)
            }
        }

        var chosen: String? = null
        var supported: Set<String>
        if (ecus.isNotEmpty()) {
            val best = ecus.entries.sortedWith(
                compareByDescending<Map.Entry<String, Set<String>>> { it.value.size }.thenBy { if (it.key == ENGINE_RESPONSE_ID) 0 else 1 },
            ).first()
            chosen = best.key
            supported = best.value
            // Endereço de requisição = endereço de resposta - 8 (7E8 -> 7E0). Só a ECU escolhida responde.
            send("ATSH" + (best.key.toInt(16) - 8).toString(16).uppercase())
            send("ATH0")
        } else {
            // Cabeçalhos não reconhecidos: volta ao modo simples e lê a primeira ECU que responder.
            send("ATH0")
            supported = emptySet()
            repeat(3) {
                if (supported.isEmpty()) {
                    supported = supportedPids()
                    if (supported.isEmpty()) delay(500)
                }
            }
        }
        if (supported.isEmpty()) throw ObdException("O carro não respondeu. Confira se a ignição está ligada.")

        val protocol = send("ATDP")
        return AdapterInfo(version.lines().last().trim(), protocol.trim(), supported, chosen, ecus.keys - setOfNotNull(chosen))
    }

    /** Com cabeçalhos ligados, lê os mapas de PIDs suportados de cada ECU que responde. */
    private suspend fun discoverEcus(): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        var base = 0
        while (base <= 0x60) {
            val pid = "%02X".format(base)
            val replies = parseHeaderedReplies(send("01$pid"), pid)
            if (replies.isEmpty()) break
            var hasNext = false
            for (reply in replies) {
                if (reply.bytes.size < 4) continue
                val set = result.getOrPut(reply.id) { mutableSetOf() }
                for (i in 0 until 32) {
                    if (reply.bytes[i / 8] and (0x80 shr (i % 8)) != 0) set += "%02X".format(base + 1 + i)
                }
                // O último bit do mapa indica se existe o mapa seguinte.
                if ("%02X".format(base + 0x20) in set) hasNext = true
            }
            if (!hasNext) break
            base += 0x20
        }
        return result
    }

    /** Lê os mapas de PIDs suportados (00, 20, 40, 60) da primeira ECU que responder, sem cabeçalhos. */
    suspend fun supportedPids(): Set<String> {
        val result = mutableSetOf<String>()
        var base = 0
        while (base <= 0x60) {
            val bytes = readPid("%02X".format(base)) ?: break
            if (bytes.size < 4) break
            for (i in 0 until 32) {
                if (bytes[i / 8] and (0x80 shr (i % 8)) != 0) result += "%02X".format(base + 1 + i)
            }
            // O último bit do mapa indica se existe o mapa seguinte.
            if ("%02X".format(base + 0x20) !in result) break
            base += 0x20
        }
        return result
    }

    /** Lê um PID do modo 01 e devolve os bytes de dados, ou null se a ECU não respondeu. */
    suspend fun readPid(pid: String): List<Int>? = parseMode01(send("01$pid"), pid)

    /**
     * Igual a [readPid], mas avisa o adaptador de que só uma ECU vai responder (sufixo "1"): ele não espera o
     * tempo extra por outras respostas e a leitura fica ~40% mais rápida. Só vale depois do ATSH.
     */
    suspend fun readPidExpectingOne(pid: String): List<Int>? = parseMode01(send("01${pid}1"), pid)

    /**
     * Lê vários PIDs do modo 01 numa única requisição (até [MAX_PIDS_PER_REQUEST], conforme ISO 15031-5).
     * Devolve os PIDs que vieram na resposta; vazio se a ECU não respondeu ou não aceitou o formato.
     */
    suspend fun readPids(pids: List<String>): Map<String, List<Int>> {
        require(pids.size in 1..MAX_PIDS_PER_REQUEST) { "Entre 1 e $MAX_PIDS_PER_REQUEST PIDs por requisição" }
        val raw = send("01" + pids.joinToString(""))
        return parseMultiMode01(raw, pids, Sensors.lengthByPid)
    }

    /** Verdadeiro se a última requisição do modo 01 foi recusada pela ECU (7F 01 xx), como com a ignição desligada. */
    var lastWasRefusal: Boolean = false
        private set

    /** Lê o VIN (chassi) pelo modo 09 PID 02, só leitura. Devolve null se o carro não responder. */
    suspend fun readVin(): String? {
        val raw = transport.request("0902")
        return Vehicle.parseVin(raw)
    }

    internal suspend fun send(command: String): String {
        val normalized = command.uppercase().filter { !it.isWhitespace() }
        if (!ALLOWED.matches(normalized)) {
            throw ObdException("Comando bloqueado (app somente leitura): $command")
        }
        val reply = transport.request(normalized)
        if (normalized.startsWith("01")) {
            // 7F 01 xx = a ECU recusou o pedido (com a ignição desligada, responde 7F0122 a tudo).
            lastWasRefusal = reply.lines().any { it.filter { c -> !c.isWhitespace() }.uppercase().startsWith("7F01") }
        }
        return reply
    }

    companion object {
        /** Identificador de resposta padrão da ECU do motor em CAN 11 bits. */
        private const val ENGINE_RESPONSE_ID = "7E8"

        // ATSH só muda para quem o pedido é endereçado; continua sendo só leitura.
        // Modo 01 com 1 a 6 PIDs e, opcionalmente, o dígito de contagem de respostas esperadas.
        private val ALLOWED = Regex("^(AT[A-Z0-9]*|01([0-9A-F]{2}){1,6}[0-9A-F]?)$")

        /** Máximo de PIDs por requisição no modo 01 (ISO 15031-5). */
        const val MAX_PIDS_PER_REQUEST = 6

        /**
         * Lê a resposta a uma requisição de vários PIDs: `41 PID1 dados1 PID2 dados2 ...`. Quando passa de um
         * quadro CAN, o ELM327 devolve uma linha de tamanho (`014`) e linhas numeradas (`0:` `1:`), que são
         * remontadas aqui. Se um PID desconhecido aparece, a leitura para nele (não dá para saber o tamanho).
         */
        fun parseMultiMode01(raw: String, requested: List<String>, lengthOf: Map<String, Int>): Map<String, List<Int>> {
            val hex = StringBuilder()
            for (line in raw.lines()) {
                var c = line.filter { !it.isWhitespace() }.uppercase()
                if (c.length >= 2 && c[1] == ':') c = c.substring(2)
                else if (c.length == 3) continue // linha de tamanho do ELM
                if (c.isEmpty() || !c.all { it in '0'..'9' || it in 'A'..'F' }) continue
                hex.append(c)
            }
            val text = hex.toString()
            if (!text.startsWith("41")) return emptyMap()
            val result = LinkedHashMap<String, List<Int>>()
            var i = 2
            while (i + 2 <= text.length) {
                val pid = text.substring(i, i + 2)
                val len = lengthOf[pid]
                if (pid !in requested || len == null || i + 2 + len * 2 > text.length) break
                result[pid] = text.substring(i + 2, i + 2 + len * 2).chunked(2).map { it.toInt(16) }
                i += 2 + len * 2
            }
            return result
        }

        fun parseMode01(raw: String, pid: String): List<Int>? {
            val prefix = "41${pid.uppercase()}"
            for (line in raw.lines()) {
                val compact = line.filter { !it.isWhitespace() }.uppercase()
                if (!compact.startsWith(prefix)) continue
                val hex = compact.removePrefix(prefix)
                if (hex.isEmpty() || hex.length % 2 != 0) return null
                val bytes = hex.chunked(2).map { it.toIntOrNull(16) ?: return null }
                return bytes
            }
            return null
        }

        /**
         * Lê respostas com cabeçalho CAN de 11 bits: `7E8` + byte de tamanho + `41` + PID + dados.
         * Devolve uma entrada por ECU que respondeu.
         */
        fun parseHeaderedReplies(raw: String, pid: String): List<EcuReply> {
            val prefix = "41${pid.uppercase()}"
            return raw.lines().mapNotNull { line ->
                val c = line.filter { !it.isWhitespace() }.uppercase()
                if (c.length < 5 + prefix.length) return@mapNotNull null
                val id = c.substring(0, 3)
                if (id.toIntOrNull(16) == null) return@mapNotNull null
                val payload = c.substring(5)
                if (!payload.startsWith(prefix)) return@mapNotNull null
                val hex = payload.removePrefix(prefix)
                if (hex.isEmpty() || hex.length % 2 != 0) return@mapNotNull null
                val bytes = hex.chunked(2).map { it.toIntOrNull(16) ?: return@mapNotNull null }
                EcuReply(id, bytes)
            }
        }
    }
}
