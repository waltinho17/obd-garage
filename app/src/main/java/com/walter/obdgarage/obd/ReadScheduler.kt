package com.walter.obdgarage.obd

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * Como os PIDs são pedidos ao adaptador, do mais rápido ao mais lento. O agendador começa no mais rápido e
 * desce um degrau se o carro/adaptador não responder naquele formato (sem nunca ter respondido antes).
 */
enum class ReadMode(val label: String) {
    /** Até 6 PIDs numa requisição só (ISO 15031-5). */
    MULTI("múltiplo"),

    /** Um PID por requisição, com o adaptador avisado de que só uma ECU responde. */
    SINGLE_FAST("rápido"),

    /** Um PID por requisição, no formato mais compatível. */
    SINGLE("simples"),
}

/**
 * Lê os sensores em ciclos. A cada ciclo só entram os sensores "devidos" segundo a
 * prioridade (FAST todo ciclo, MEDIUM a cada 4, SLOW a cada 16). Sensores que
 * compartilham o mesmo PID geram uma única leitura.
 */
class ReadScheduler(
    private val client: Elm327Client,
    private val sensors: List<Sensor>,
    private val maxConsecutiveFailures: Int = 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(sensors.isNotEmpty()) { "Nenhum sensor para ler" }
    }

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values

    /** Ciclos completos por segundo: é a taxa de atualização dos sensores rápidos (RPM, MAP, carga...). */
    private val _updatesPerSecond = MutableStateFlow(0.0)
    val updatesPerSecond: StateFlow<Double> = _updatesPerSecond

    private val _mode = MutableStateFlow(ReadMode.MULTI)
    val mode: StateFlow<ReadMode> = _mode

    private val worked = mutableSetOf<ReadMode>()
    private var partialBatches = 0

    /** Roda até o coroutine ser cancelado ou até a ECU parar de responder (lança [ObdException]). */
    suspend fun run() {
        var cycle = 0
        var failures = 0
        var windowStart = clock()
        var windowCycles = 0

        while (currentCoroutineContext().isActive) {
            val due = sensors.filter { cycle % it.priority.everyNCycles == 0 }.groupBy { it.pid }
            val pids = due.keys.toList()
            val batches = if (_mode.value == ReadMode.MULTI) pids.chunked(Elm327Client.MAX_PIDS_PER_REQUEST) else pids.map { listOf(it) }

            for (batch in batches) {
                val got = readBatch(batch)
                if (got.isEmpty()) {
                    if (++failures >= maxConsecutiveFailures) {
                        throw ObdException(
                            if (client.lastWasRefusal) "A ECU recusou os pedidos: ignição desligada ou motor parado?"
                            else "A ECU parou de responder",
                        )
                    }
                    continue
                }
                failures = 0
                var update = _values.value
                for ((pid, bytes) in got) for (s in due.getValue(pid)) update = update + (s.id to s.decode(bytes))
                _values.value = Sensors.derive(update)
            }

            windowCycles++
            val now = clock()
            if (now - windowStart >= 1000) {
                _updatesPerSecond.value = windowCycles * 1000.0 / (now - windowStart)
                windowStart = now
                windowCycles = 0
            }
            cycle++
        }
    }

    /**
     * Lê um grupo de PIDs no modo atual. Se nada vier e este modo nunca funcionou, desce para o próximo e tenta
     * de novo; se o modo múltiplo devolver sempre menos PIDs do que o pedido, desce também, e os que faltaram
     * são lidos um a um.
     */
    private suspend fun readBatch(batch: List<String>): Map<String, List<Int>> {
        var got = readIn(_mode.value, batch)
        while (got.isEmpty() && _mode.value !in worked && _mode.value != ReadMode.SINGLE) {
            _mode.value = ReadMode.entries[_mode.value.ordinal + 1]
            got = readIn(_mode.value, batch)
        }
        if (got.isEmpty()) return got
        worked += _mode.value

        if (_mode.value == ReadMode.MULTI && got.size < batch.size) {
            if (++partialBatches >= PARTIAL_LIMIT) _mode.value = ReadMode.SINGLE_FAST
            val missing = batch.filter { it !in got }
            return got + readIn(ReadMode.SINGLE_FAST, missing)
        }
        if (_mode.value == ReadMode.MULTI) partialBatches = 0
        return got
    }

    private suspend fun readIn(mode: ReadMode, batch: List<String>): Map<String, List<Int>> = when (mode) {
        ReadMode.MULTI -> client.readPids(batch)
        ReadMode.SINGLE_FAST -> readEach(batch) { client.readPidExpectingOne(it) }
        ReadMode.SINGLE -> readEach(batch) { client.readPid(it) }
    }

    private suspend fun readEach(batch: List<String>, read: suspend (String) -> List<Int>?): Map<String, List<Int>> {
        val result = LinkedHashMap<String, List<Int>>()
        for (pid in batch) read(pid)?.takeIf { it.isNotEmpty() }?.let { result[pid] = it }
        return result
    }

    private companion object {
        /** Lotes seguidos com PIDs faltando no modo múltiplo, até desistir dele. */
        const val PARTIAL_LIMIT = 3
    }
}
