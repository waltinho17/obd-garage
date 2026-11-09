package com.walter.obdgarage.obd

/** Com que frequência o sensor é lido: a cada N ciclos do agendador. */
enum class Priority(val everyNCycles: Int) {
    FAST(1),
    MEDIUM(4),
    SLOW(16),
}

data class Sensor(
    val id: String,
    /** Nome técnico completo, usado em relatórios e no log. */
    val name: String,
    /** Rótulo curto para cards e gauges. */
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    /** PID do modo 01 (2 dígitos hex). Sensores com o mesmo PID compartilham uma única leitura. */
    val pid: String,
    val priority: Priority,
    val decimals: Int = 0,
    /** A partir deste valor o widget entra em alerta. */
    val warnFrom: Double? = null,
    /** Bytes de dados do PID na resposta (necessário para separar PIDs numa requisição múltipla). */
    val length: Int = 1,
    val decode: (List<Int>) -> Double,
)

object Sensors {
    val rpm = Sensor("rpm", "Rotação do motor", "RPM", "rpm", 0.0, 8000.0, "0C", Priority.FAST, warnFrom = 6300.0, length = 2) {
        (256 * it[0] + it[1]) / 4.0
    }

    // Pressão do turbo RELATIVA (o "boost"): MAP absoluto menos a pressão atmosférica. Aqui vale a conta
    // com 101,3 kPa; com a barométrica medida (PID 33) o [derive] refaz com o valor real. Não é o sensor
    // de pressão de carga em si; a leitura nominal x real do VAG fica para a etapa 6.
    val boost = Sensor("boost", "Pressão do turbo, relativa (MAP menos atmosférica)", "Boost", "bar", -1.0, 2.0, "0B", Priority.FAST, decimals = 2, warnFrom = 1.4) {
        (it[0] - 101.3) / 100.0
    }
    val map = Sensor("map", "Pressão absoluta no coletor (MAP)", "MAP", "kPa", 0.0, 255.0, "0B", Priority.FAST) { it[0].toDouble() }
    val load = Sensor("load", "Carga calculada do motor", "Carga", "%", 0.0, 100.0, "04", Priority.FAST) { it[0] * 100.0 / 255.0 }
    val throttle = Sensor("throttle", "Posição da borboleta", "Borboleta", "%", 0.0, 100.0, "11", Priority.FAST) { it[0] * 100.0 / 255.0 }
    val timing = Sensor("timing", "Avanço de ignição", "Avanço", "°", -64.0, 63.5, "0E", Priority.MEDIUM, decimals = 1) { it[0] / 2.0 - 64.0 }
    val speed = Sensor("speed", "Velocidade do veículo", "Velocidade", "km/h", 0.0, 240.0, "0D", Priority.MEDIUM) { it[0].toDouble() }
    val coolant = Sensor("coolant", "Temp. do líquido de arrefecimento (ECT)", "Arrefecimento", "°C", -40.0, 140.0, "05", Priority.SLOW, warnFrom = 105.0) { it[0] - 40.0 }
    val intake = Sensor("intake", "Temp. do ar de admissão (IAT)", "Ar de admissão", "°C", -40.0, 140.0, "0F", Priority.SLOW, warnFrom = 60.0) { it[0] - 40.0 }
    val stft = Sensor("stft", "Correção de combustível de curto prazo (STFT)", "Trim curto", "%", -25.0, 25.0, "06", Priority.SLOW, decimals = 1) { it[0] * 100.0 / 128.0 - 100.0 }
    val ltft = Sensor("ltft", "Correção de combustível de longo prazo (LTFT)", "Trim longo", "%", -25.0, 25.0, "07", Priority.SLOW, decimals = 1) { it[0] * 100.0 / 128.0 - 100.0 }

    // Os PIDs abaixo constam no mapa de suportados lido do motor do Polo (rastreio de campo).
    val baro = Sensor("baro", "Pressão barométrica", "Barométrica", "kPa", 60.0, 110.0, "33", Priority.SLOW) { it[0].toDouble() }
    val absLoad = Sensor("absload", "Carga absoluta do motor", "Carga absoluta", "%", 0.0, 250.0, "43", Priority.MEDIUM, length = 2) {
        (256 * it[0] + it[1]) * 100.0 / 255.0
    }
    val lambdaCmd = Sensor("lambda_cmd", "Lambda pedido pela ECU (λ)", "Lambda pedido", "λ", 0.6, 1.4, "44", Priority.MEDIUM, decimals = 2, length = 2) {
        (256 * it[0] + it[1]) / 32768.0
    }
    val fuelRail = Sensor("fuel_rail", "Pressão do trilho de combustível (alta pressão)", "Trilho comb.", "bar", 0.0, 250.0, "23", Priority.MEDIUM, length = 2) {
        (256 * it[0] + it[1]) / 10.0
    }
    val voltage = Sensor("voltage", "Tensão do módulo de controle", "Tensão", "V", 8.0, 16.0, "42", Priority.SLOW, decimals = 1, length = 2) {
        (256 * it[0] + it[1]) / 1000.0
    }
    val ambient = Sensor("ambient", "Temp. do ar ambiente", "Ar ambiente", "°C", -40.0, 60.0, "46", Priority.SLOW) { it[0] - 40.0 }
    val pedal = Sensor("pedal", "Posição do pedal do acelerador", "Pedal", "%", 0.0, 100.0, "49", Priority.FAST) { it[0] * 100.0 / 255.0 }
    val ethanol = Sensor("ethanol", "Teor de etanol no combustível", "Etanol", "%", 0.0, 100.0, "52", Priority.SLOW) { it[0] * 100.0 / 255.0 }

    // PID padrão 5C. O motor do Polo não o declara (só o suporte do carro decide); em outros carros aparece sozinho.
    val oil = Sensor("oil", "Temp. do óleo do motor", "Óleo", "°C", -40.0, 160.0, "5C", Priority.SLOW, warnFrom = 125.0) { it[0] - 40.0 }

    val all = listOf(
        rpm, boost, map, load, throttle, timing, speed, coolant, intake, stft, ltft,
        baro, absLoad, lambdaCmd, fuelRail, voltage, ambient, pedal, ethanol, oil,
    )
    val byId = all.associateBy { it.id }

    /** Tamanho em bytes dos dados de cada PID conhecido. */
    val lengthByPid: Map<String, Int> = all.associate { it.pid to it.length }

    /** Nome da mistura pelo teor de etanol, como o povo fala: 32% vira "E32", 50% vira "E50". Null se não há leitura. */
    fun ethanolName(percent: Double?): String? = percent?.let { "E${Math.round(it)}" }

    /** Complemento do etanol: o resto do tanque é gasolina (o teor já inclui o etanol da gasolina do posto). */
    fun gasolinePercent(percent: Double?): Long? = percent?.let { 100 - Math.round(it) }

    /**
     * Valores que dependem de mais de um PID. O boost sai de MAP menos a pressão barométrica medida
     * (PID 33), que corrige altitude e clima; sem ela, fica a conta com 101,3 kPa de [boost].
     */
    fun derive(values: Map<String, Double>): Map<String, Double> {
        val map = values[Sensors.map.id] ?: return values
        val baro = values[baro.id] ?: return values
        return values + (boost.id to (map - baro) / 100.0)
    }
}
