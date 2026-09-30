package com.example.aiagent

/**
 * High-performance parser converting Polish spoken/written numerals into integers.
 * Handles compound numbers up to thousands, Polish inflections, and extracts numbers
 * from phrases like "osiemdziesiąt pięć procent" -> 85.
 */
object PolishWordToNumberParser {

    private val wordValues = mapOf(
        // Zero
        "zero" to 0,

        // Jedności i odmiany
        "jeden" to 1, "jedna" to 1, "jedno" to 1, "jednego" to 1, "jednej" to 1,
        "dwa" to 2, "dwie" to 2, "dwóch" to 2, "dwaj" to 2, "dwoma" to 2, "dwiema" to 2,
        "trzy" to 3, "trzech" to 3, "trzema" to 3,
        "cztery" to 4, "czterech" to 4, "czterema" to 4,
        "pięć" to 5, "pięciu" to 5, "pięcioma" to 5,
        "sześć" to 6, "sześciu" to 6, "sześcioma" to 6,
        "siedem" to 7, "siedmiu" to 7, "siedmioma" to 7,
        "osiem" to 8, "ośmiu" to 8, "ośmioma" to 8,
        "dziewięć" to 9, "dziewięciu" to 9, "dziewięcioma" to 9,

        // Nastki
        "dziesięć" to 10, "dziesięciu" to 10,
        "jedenaście" to 11, "jedenastu" to 11,
        "dwanaście" to 12, "dwunastu" to 12,
        "trzynaście" to 13, "trzynastu" to 13,
        "czternaście" to 14, "czternastu" to 14,
        "piętnaście" to 15, "piętnastu" to 15,
        "szesnaście" to 16, "szesnastu" to 16,
        "siedemnaście" to 17, "siedemnastu" to 17,
        "osiemnaście" to 18, "osiemnastu" to 18,
        "dziewiętnaście" to 19, "dziewiętnastu" to 19,

        // Dziesiątki
        "dwadzieścia" to 20, "dwudziestu" to 20,
        "trzydzieści" to 30, "trzydziestu" to 30,
        "czterdzieści" to 40, "czterdziestu" to 40,
        "pięćdziesiąt" to 50, "pięćdziesięciu" to 50,
        "sześćdziesiąt" to 60, "sześćdziesięciu" to 60,
        "siedemdziesiąt" to 70, "siedemdziesięciu" to 70,
        "osiemdziesiąt" to 80, "osiemdziesięciu" to 80,
        "dziewięćdziesiąt" to 90, "dziewięćdziesięciu" to 90,

        // Setki
        "sto" to 100, "stu" to 100,
        "dwieście" to 200, "dwustu" to 200,
        "trzysta" to 300, "trzystu" to 300,
        "czterysta" to 400, "czterystu" to 400,
        "pięćset" to 500, "pięciuset" to 500,
        "sześćset" to 600, "sześciuset" to 600,
        "siedemset" to 700, "siedmiuset" to 700,
        "osiemset" to 800, "ośmiuset" to 800,
        "dziewięćset" to 900, "dziewięciuset" to 900,

        // Tysiące
        "tysiąc" to 1000, "tysiące" to 1000, "tysięcy" to 1000
    )

    /**
     * Extracts a numeric value from arbitrary text (either as digits or Polish numeral words).
     */
    fun extractNumberFromText(text: String): Int? {
        // 1. Direct digits
        val digitMatch = Regex("(\\d+)").find(text)
        if (digitMatch != null) {
            return digitMatch.groupValues[1].toIntOrNull()
        }

        // 2. Tokenize and parse Polish words
        val cleanWords = text.lowercase()
            .replace(Regex("[.,!?;%]"), " ")
            .split(Regex("\\s+"))

        var currentTotal = 0
        var currentSection = 0
        var foundAny = false

        for (word in cleanWords) {
            val value = wordValues[word]
            if (value != null) {
                foundAny = true
                if (value == 1000) {
                    if (currentSection == 0) currentSection = 1
                    currentTotal += currentSection * 1000
                    currentSection = 0
                } else {
                    currentSection += value
                }
            } else if (foundAny && word !in listOf("i", "procent", "procenta", "procenty")) {
                // Number block ended
                break
            }
        }

        val total = currentTotal + currentSection
        return if (foundAny) total else null
    }

    /**
     * Parses a pure numeral string (e.g. "osiemdziesiąt pięć" -> 85).
     */
    fun parse(numeralPhrase: String): Int? {
        return extractNumberFromText(numeralPhrase)
    }
}
