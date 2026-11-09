package com.walter.dashobd.obd

/**
 * Identificação do carro a partir do VIN (chassi, modo 09 PID 02, só leitura). O VIN diz a marca (3 primeiros
 * caracteres, WMI) e o ano-modelo (10º caractere); o modelo e o motor não estão nele, por isso o app deixa o
 * usuário dar um nome (ex.: "POLO EA211").
 */
object Vehicle {
    /** Extrai o VIN (17 caracteres) da resposta do ELM327 ao `0902`, com ou sem linhas numeradas (`0:`, `1:`). */
    fun parseVin(raw: String): String? {
        val hex = StringBuilder()
        for (line in raw.lines()) {
            var c = line.filter { !it.isWhitespace() }.uppercase()
            if (c.length >= 2 && c[1] == ':') c = c.substring(2)
            else if (c.length == 3) continue // linha de tamanho
            if (c.isEmpty() || !c.all { it in '0'..'9' || it in 'A'..'F' }) continue
            hex.append(c)
        }
        val text = hex.toString()
        val at = text.indexOf("4902")
        if (at < 0) return null
        // Depois de 49 02 vem o contador de itens (01) e os 17 caracteres ASCII.
        val data = text.substring(at + 4).let { if (it.startsWith("01")) it.drop(2) else it }
        if (data.length < 34) return null
        val vin = data.take(34).chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        return vin.takeIf { v -> v.length == 17 && v.all { it.isLetterOrDigit() } }
    }

    private val makes = mapOf(
        "9BW" to "VOLKSWAGEN", "WVW" to "VOLKSWAGEN", "WV1" to "VOLKSWAGEN", "WV2" to "VOLKSWAGEN",
        "3VW" to "VOLKSWAGEN", "8AW" to "VOLKSWAGEN", "AAV" to "VOLKSWAGEN",
        "WAU" to "AUDI", "WUA" to "AUDI", "TRU" to "AUDI", "93U" to "AUDI",
        "KMH" to "HYUNDAI", "9BH" to "HYUNDAI", "5NP" to "HYUNDAI", "KM8" to "HYUNDAI",
        "KNA" to "KIA", "KND" to "KIA",
        "9BG" to "CHEVROLET", "9BD" to "FIAT", "9BF" to "FORD", "93Y" to "RENAULT",
        "9BR" to "TOYOTA", "93H" to "HONDA", "9C2" to "HONDA", "WBA" to "BMW", "WDD" to "MERCEDES",
        "VF1" to "RENAULT", "VF3" to "PEUGEOT", "JHM" to "HONDA", "JTD" to "TOYOTA", "JN1" to "NISSAN",
    )

    /** Ano-modelo pelo 10º caractere do VIN (ciclo de 30 anos; assume 2010 a 2039). */
    fun modelYear(vin: String): Int? {
        val code = "ABCDEFGHJKLMNPRSTVWXY123456789"
        val i = code.indexOf(vin.getOrNull(9) ?: return null)
        return if (i < 0) null else 2010 + i
    }

    /** Nome padrão quando o usuário não deu um: marca e ano, em maiúsculas (ex.: "VOLKSWAGEN 2018"). */
    fun defaultName(vin: String?): String? {
        if (vin == null) return null
        val make = makes[vin.take(3)] ?: return null
        return listOfNotNull(make, modelYear(vin)?.toString()).joinToString(" ")
    }
}
