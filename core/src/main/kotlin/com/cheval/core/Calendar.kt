package com.cheval.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Calendrier simplifié de 365 jours. Le jour 0 est le 1er mars de [START_YEAR]. */
object Cal {
    const val START_YEAR = 2027
    val MONTHS = arrayOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
    val MONTHS_SHORT = arrayOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
    private val LEN = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    val WEEKDAYS = arrayOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    /** Le 1er mars 2027 est un lundi. */
    private const val DAY0_DOW = 0
    private const val OFFSET = 59 // jours du 1er janvier au 1er mars

    data class Date(val year: Int, val month: Int, val dom: Int, val dayOfYear: Int)

    fun date(day: Int): Date {
        val abs = day + OFFSET
        val year = START_YEAR + Math.floorDiv(abs, 365)
        var doy = Math.floorMod(abs, 365)
        val dayOfYear = doy
        var m = 0
        while (doy >= LEN[m]) { doy -= LEN[m]; m++ }
        return Date(year, m, doy + 1, dayOfYear)
    }

    /** Jour de jeu correspondant à une date (mois 0..11). */
    fun dayOf(year: Int, month: Int, dom: Int): Int {
        var doy = dom - 1
        for (i in 0 until month) doy += LEN[i]
        return (year - START_YEAR) * 365 + doy - OFFSET
    }

    fun month(day: Int) = date(day).month
    fun weekday(day: Int) = WEEKDAYS[Math.floorMod(day + DAY0_DOW, 7)]
    fun isWeekend(day: Int) = Math.floorMod(day + DAY0_DOW, 7) >= 5

    fun format(day: Int): String { val d = date(day); return "${d.dom} ${MONTHS[d.month]} ${d.year}" }
    fun formatShort(day: Int): String { val d = date(day); return "${d.dom} ${MONTHS_SHORT[d.month]}" }

    fun season(day: Int): Season = when (month(day)) {
        2, 3, 4 -> Season.PRINTEMPS
        5, 6, 7 -> Season.ETE
        8, 9, 10 -> Season.AUTOMNE
        else -> Season.HIVER
    }

    /** Durée du jour (heures) : lever et coucher du soleil approximatifs (latitude ~49°N). */
    fun sunrise(day: Int): Float { val d = date(day).dayOfYear; return 6.4f + 1.9f * cos(2 * PI * (d + 10) / 365.0).toFloat() }
    fun sunset(day: Int): Float { val d = date(day).dayOfYear; return 18.3f - 2.4f * cos(2 * PI * (d + 10) / 365.0).toFloat() }

    fun ageYears(birthDay: Int, today: Int): Float = (today - birthDay) / 365f

    /** Âge « administratif » des chevaux : tous vieillissent au 1er janvier (règle des stud-books). */
    fun ageClass(birthDay: Int, today: Int): Int = date(today).year - date(birthDay).year

    fun ageText(birthDay: Int, today: Int): String {
        val days = today - birthDay
        return when {
            days < 31 -> if (days <= 1) "nouveau-né" else "$days jours"
            days < 365 -> "${days / 30} mois"
            else -> { val y = days / 365; val m = (days % 365) / 30; if (y < 4 && m > 0) "$y an${if (y > 1) "s" else ""} $m mois" else "$y an${if (y > 1) "s" else ""}" }
        }
    }
}

enum class Season(val label: String) { PRINTEMPS("Printemps"), ETE("Été"), AUTOMNE("Automne"), HIVER("Hiver") }

enum class Sky(val label: String) {
    SOLEIL("Ensoleillé"), NUAGEUX("Nuageux"), PLUIE("Pluie"), ORAGE("Orage"), NEIGE("Neige"), BROUILLARD("Brouillard"), TEMPETE("Tempête")
}

enum class Ground(val label: String, val injury: Float, val speed: Float) {
    GELE("Gelé", 1.9f, 0.9f), DUR("Dur", 1.4f, 1.03f), BON("Bon", 1f, 1f), SOUPLE("Souple", 0.9f, 0.96f), LOURD("Lourd", 1.25f, 0.88f)
}

/** Météo du jour, générée de façon réaliste (climat océanique de baie normande). */
class Weather(
    var sky: Sky = Sky.SOLEIL,
    var tempMin: Float = 6f,
    var tempMax: Float = 14f,
    var wind: Float = 15f,
    var soil: Float = 50f, // humidité du sol 0..100
) {
    val ground: Ground
        get() = when {
            tempMax < 1f || (tempMin < -2f && tempMax < 4f) -> Ground.GELE
            soil < 18f -> Ground.DUR
            soil < 48f -> Ground.BON
            soil < 72f -> Ground.SOUPLE
            else -> Ground.LOURD
        }
    val rainy get() = sky == Sky.PLUIE || sky == Sky.ORAGE || sky == Sky.TEMPETE
    val harsh get() = sky == Sky.ORAGE || sky == Sky.TEMPETE || sky == Sky.NEIGE || tempMax > 31f || tempMin < -4f

    fun tempAt(hour: Float): Float {
        // minimum à l'aube, maximum vers 15 h
        val t = ((hour - 15f) / 24f) * 2 * PI
        return tempMin + (tempMax - tempMin) * (0.5f + 0.5f * cos(t).toFloat())
    }

    fun next(day: Int, rng: Rng) {
        val m = Cal.month(day)
        val meanMax = MEAN_MAX[m]; val meanMin = MEAN_MIN[m]
        // persistance : la journée ressemble à la veille
        val anomaly = ((tempMax - MEAN_MAX[Math.floorMod(m, 12)]) * 0.6f + rng.gauss(0.0, 2.6).toFloat())
        tempMax = meanMax + anomaly
        tempMin = min(tempMax - 3f, meanMin + anomaly * 0.8f + rng.gauss(0.0, 1.2).toFloat())
        val wasRainy = rainy
        val pRain = RAIN[m] * (if (wasRainy) 1.45f else 0.8f)
        val r = rng.float()
        sky = when {
            r < pRain -> when {
                tempMax < 2.5f -> Sky.NEIGE
                m in 5..7 && tempMax > 23f && rng.chance(0.35f) -> Sky.ORAGE
                m in listOf(9, 10, 11, 0, 1) && rng.chance(0.08f) -> Sky.TEMPETE
                else -> Sky.PLUIE
            }
            r < pRain + 0.2f -> Sky.NUAGEUX
            m in listOf(9, 10, 11, 0) && rng.chance(0.12f) -> Sky.BROUILLARD
            else -> Sky.SOLEIL
        }
        if (sky == Sky.SOLEIL && m in 5..7) tempMax += 2f
        wind = when (sky) { Sky.TEMPETE -> rng.range(60f, 95f); Sky.ORAGE -> rng.range(30f, 55f); else -> rng.range(5f, 30f) }
        val rainMm = when (sky) { Sky.PLUIE -> rng.range(3f, 14f); Sky.ORAGE -> rng.range(8f, 30f); Sky.TEMPETE -> rng.range(10f, 30f); Sky.NEIGE -> 6f; Sky.BROUILLARD -> 0.5f; else -> 0f }
        val evap = max(0f, (tempMax - 4f) * 0.28f) * (if (sky == Sky.SOLEIL) 1.4f else 0.8f) + 0.6f
        soil = (soil + rainMm * 2.2f - evap * 2f).coerceIn(0f, 100f)
    }

    fun toJson(): Map<String, Any?> = mapOf("sky" to sky.name, "tmin" to tempMin, "tmax" to tempMax, "wind" to wind, "soil" to soil)

    companion object {
        val MEAN_MAX = floatArrayOf(8f, 8.6f, 11.5f, 14f, 17.3f, 20.2f, 22.4f, 22.6f, 20.3f, 16.3f, 11.7f, 8.7f)
        val MEAN_MIN = floatArrayOf(2.6f, 2.4f, 4f, 5.6f, 8.8f, 11.5f, 13.5f, 13.4f, 11.3f, 8.8f, 5.3f, 3.2f)
        val RAIN = floatArrayOf(0.42f, 0.36f, 0.34f, 0.32f, 0.3f, 0.26f, 0.22f, 0.24f, 0.28f, 0.38f, 0.44f, 0.45f)
        fun fromJson(o: JObj) = Weather(o.enum("sky", Sky.SOLEIL), o.float("tmin"), o.float("tmax"), o.float("wind"), o.float("soil", 50f))
    }
}
