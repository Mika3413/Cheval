package com.cheval.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Niveaux de compétition (repères FFE/FEI). */
object Levels {
    val CSO = arrayOf("Club 2 (0,95 m)", "Amateur 2 (1,10 m)", "Amateur Élite (1,20 m)", "Pro 2 (1,30 m)", "Pro Élite (1,45 m)", "Grand Prix CSI 5* (1,60 m)")
    val CSO_HEIGHT = floatArrayOf(0.95f, 1.10f, 1.20f, 1.30f, 1.45f, 1.60f)
    val DRESSAGE = arrayOf("Club", "Amateur 2", "Amateur Élite", "Pro 2 (Saint-Georges)", "Pro Élite (Intermédiaire I)", "Grand Prix")
    val COMPLET = arrayOf("Club", "Amateur 2", "Amateur Élite", "CCI 2*", "CCI 3*", "CCI 5* (Pau)")
    val COURSE = arrayOf("Course à réclamer", "Handicap", "Listed", "Groupe 3", "Groupe 2", "Groupe 1")
    val ENDURANCE = arrayOf("Club 20 km", "Amateur 40 km", "Amateur 90 km", "CEI 2* 120 km", "CEI 3* 160 km", "Championnat 160 km")
    val ATTELAGE = arrayOf("Club", "Amateur 2", "Amateur 1", "Pro 2", "Pro 1", "CAI 3*")
    val MODELE = arrayOf("Concours local", "Concours départemental", "Concours régional", "Finale nationale", "Championnat de France", "Championnat du monde des jeunes chevaux")
    val HUNTER = arrayOf("Club (0,80 m)", "Amateur 2 (0,95 m)", "Amateur 1 (1,05 m)", "Pro 2 (1,15 m)", "Pro 1 (1,20 m)", "Derby Hunter (1,30 m)")
    val HUNTER_HEIGHT = floatArrayOf(0.8f, 0.95f, 1.05f, 1.15f, 1.2f, 1.3f)
    val TROT = arrayOf("Course de province", "Prix de semi-classique", "Course européenne", "Groupe III — Vincennes", "Groupe II — Vincennes", "Groupe I — Prix d'Amérique")
    val WESTERN = arrayOf("Club", "Amateur", "Open", "Championnat régional", "Championnat de France", "Finale européenne")
    /** Niveau de compétence attendu par niveau (moyenne des concurrents). */
    val EXPECT = floatArrayOf(24f, 38f, 50f, 62f, 74f, 86f)
    val FEE = intArrayOf(25, 55, 110, 220, 450, 900)
    val PRIZE1 = intArrayOf(120, 450, 1400, 4000, 12000, 65000)
    /** Galop minimum du cavalier joueur. */
    val GALOP = intArrayOf(2, 4, 6, 7, 7, 7)

    fun name(d: Discipline, l: Int): String = when (d) {
        Discipline.CSO -> CSO[l]; Discipline.DRESSAGE -> DRESSAGE[l]; Discipline.COMPLET -> COMPLET[l]
        Discipline.COURSE -> COURSE[l]; Discipline.ENDURANCE -> ENDURANCE[l]; Discipline.ATTELAGE -> ATTELAGE[l]; Discipline.MODELE -> MODELE[l]
        Discipline.HUNTER -> HUNTER[l]; Discipline.TROT_ATTELE -> TROT[l]; Discipline.WESTERN -> WESTERN[l]
    }

    fun prizeMultiplier(d: Discipline) = when (d) { Discipline.COURSE -> 2.2f; Discipline.MODELE -> 0.4f; Discipline.ENDURANCE -> 0.7f; Discipline.ATTELAGE -> 0.6f; Discipline.TROT_ATTELE -> 1.8f; Discipline.HUNTER -> 0.6f; Discipline.WESTERN -> 0.7f; else -> 1f }
}

class CompEvent(
    val id: Int, val day: Int, val venue: String, val discipline: Discipline, val level: Int,
    val distance: Int, val field: Int, val km: Int,
) {
    val entries = ArrayList<Entry>()
    var done = false
    val name get() = "${Levels.name(discipline, level)} — $venue"
    val fee get() = (Levels.FEE[level] * (if (discipline == Discipline.COURSE || discipline == Discipline.TROT_ATTELE) 2f else if (discipline == Discipline.MODELE) 0.6f else 1f)).toInt()
    val firstPrize get() = (Levels.PRIZE1[level] * Levels.prizeMultiplier(discipline)).toInt()

    /** Conditions d'âge réalistes. */
    fun ageOk(h: Horse, today: Int): Boolean {
        val a = h.ageClass(today)
        return when (discipline) {
            Discipline.COURSE -> a in 2..9
            Discipline.TROT_ATTELE -> a in 2..10
            Discipline.WESTERN, Discipline.HUNTER -> a >= 4
            Discipline.MODELE -> a <= 3 || (h.mare && h.foalsBorn > 0)
            Discipline.ENDURANCE -> a >= (if (level >= 3) 7 else 5)
            Discipline.COMPLET -> a >= (if (level >= 3) 6 else 4)
            else -> a >= (if (level >= 4) 7 else 4)
        }
    }

    fun prize(rank: Int): Int {
        val paid = max(3, field / 4)
        if (rank > paid) return 0
        val share = floatArrayOf(1f, 0.55f, 0.36f, 0.26f, 0.2f, 0.16f, 0.13f, 0.11f, 0.1f, 0.09f)
        return (firstPrize * share[min(rank - 1, share.size - 1)]).toInt()
    }

    fun toJson() = mapOf("id" to id, "d" to day, "v" to venue, "di" to discipline.name, "l" to level, "ds" to distance, "f" to field, "km" to km,
        "e" to entries.map { it.toJson() }, "dn" to done)

    companion object {
        fun fromJson(o: JObj) = CompEvent(o.int("id"), o.int("d"), o.str("v"), o.enum("di", Discipline.CSO), o.int("l"), o.int("ds"), o.int("f"), o.int("km")).also { e ->
            e.entries.addAll(o.objs("e").map { Entry.fromJson(it) }); e.done = o.bool("dn")
        }
    }
}

/** Engagement d'un cheval : [riderStaffId] = -1 pour le joueur. [live] : le joueur montera lui-même en direct. */
class Entry(val horseId: Int, val riderStaffId: Int, var live: Boolean) {
    fun toJson() = mapOf("h" to horseId, "r" to riderStaffId, "lv" to live)
    companion object { fun fromJson(o: JObj) = Entry(o.int("h"), o.int("r", -1), o.bool("lv")) }
}

/** Résultat d'une épreuve jouée en direct par le joueur (fourni par l'application). */
class LivePerformance(val quality: Float, val faults: Int = 0, val timeSec: Float = 0f, val eliminated: Boolean = false, val detail: String = "")

object CompSim {
    /**
     * Note de performance d'un couple cheval/cavalier, sur l'échelle des niveaux ([Levels.EXPECT]).
     * Combine compétence entraînée, potentiel, forme du jour, cavalier, sellerie, terrain et un aléa tempéré par le calme.
     */
    fun performance(h: Horse, d: Discipline, riderSkill: Float, saddlery: Int, ground: Ground, today: Int, rng: Rng): Float {
        val skill = h.skill(d)
        val pot = d.potential(h) * h.ageFactor(today)
        var p = skill * 0.75f + pot * 0.25f
        p *= h.dayForm()
        p += (riderSkill - 50f) * 0.18f
        p += saddlery * 1.5f
        if (d == Discipline.COURSE || d == Discipline.COMPLET || d == Discipline.ENDURANCE) {
            p *= ground.speed
            // Myostatine : C/C sprinteur, T/T stayer
            val c = h.genome.count(Locus.MSTN)
            if (d == Discipline.COURSE) p += (c - 1) * 2f
            if (d == Discipline.ENDURANCE) p -= (c - 1) * 2.5f
        }
        val nerves = (1.2f - h.pot(Trait.CALME) / 100f).coerceIn(0.4f, 1.1f)
        var sd = 6f * nerves
        if (Personality.PEUREUX in h.personality) sd += 2.5f
        if (Personality.COURAGEUX in h.personality) p += 1.5f
        if (Personality.GENEREUX in h.personality) p += 1f
        return p + rng.gauss(0.0, sd.toDouble()).toFloat()
    }

    /** Transforme une note de performance en résultat lisible propre à la discipline. */
    fun scoreText(d: Discipline, perf: Float, level: Int, distance: Int, rng: Rng): Pair<String, Float> {
        val margin = perf - Levels.EXPECT[level]
        return when (d) {
            Discipline.CSO -> {
                val pRail = 1f / (1f + exp(margin / 4.5f))
                var faults = 0
                repeat(12) { if (rng.float() < pRail * 0.22f) faults += 4 }
                val elim = margin < -22f && rng.chance(0.5f)
                val time = 72f - margin * 0.12f + rng.range(-1.5f, 1.5f)
                if (elim) "Éliminé (2 refus)" to -1000f
                else "$faults pts — ${"%.2f".format(time)} s" to (-faults * 100f - time)
            }
            Discipline.DRESSAGE -> {
                val pct = (66f + margin * 0.45f + rng.range(-1f, 1f)).coerceIn(48f, 86f)
                "${"%.3f".format(pct)} %" to pct
            }
            Discipline.COMPLET -> {
                val pen = (35f - margin * 0.9f + rng.range(-3f, 3f)).coerceAtLeast(18f)
                val xc = if (margin < -12f && rng.chance(0.4f)) 20 else 0
                "${"%.1f".format(pen + xc)} pts" to -(pen + xc)
            }
            Discipline.COURSE -> {
                val speed = 15.6f + margin * 0.035f + rng.range(-0.1f, 0.1f)
                val t = distance / speed
                "${(t / 60).toInt()}'${"%05.2f".format(t % 60)}" to -t
            }
            Discipline.ENDURANCE -> {
                val km = Levels.ENDURANCE[level].filter { it.isDigit() }.toIntOrNull() ?: 40
                if (margin < -14f && rng.chance(0.5f)) "Éliminé au contrôle vétérinaire (fréquence cardiaque)" to -1000f
                else { val v = (13.5f + margin * 0.12f).coerceIn(10f, 22f); "${km} km à ${"%.1f".format(v)} km/h" to v }
            }
            Discipline.ATTELAGE -> {
                val pen = (60f - margin * 1.2f).coerceAtLeast(30f) + rng.range(-4f, 4f)
                "${"%.1f".format(pen)} pts" to -pen
            }
            Discipline.HUNTER -> {
                val note = (70f + margin * 0.6f + rng.range(-2f, 2f)).coerceIn(30f, 98f)
                val faults = if (margin < -10f && rng.chance(0.4f)) 1 else 0
                if (faults > 0) "Barre — note ${(note * 0.6f).toInt()}/100" to note * 0.6f else "Note ${note.toInt()}/100" to note
            }
            Discipline.TROT_ATTELE -> {
                if (margin < -16f && rng.chance(0.35f)) "Disqualifié (allure irrégulière)" to -1000f
                else {
                    val red = (78.5f - margin * 0.06f + rng.range(-0.3f, 0.3f)).coerceIn(69f, 84f) // réduction kilométrique (s/km)
                    "Réd. km 1'${"%04.1f".format(red - 60f)}" to -red
                }
            }
            Discipline.WESTERN -> {
                val t = (16.2f - margin * 0.05f + rng.range(-0.2f, 0.2f)).coerceIn(13.6f, 22f)
                val pen = if (margin < -10f && rng.chance(0.3f)) 5f else 0f
                "${"%.3f".format(t + pen)} s${if (pen > 0) " (tonneau renversé)" else ""}" to -(t + pen)
            }
            Discipline.MODELE -> {
                val note = (13f + margin * 0.12f + rng.range(-0.4f, 0.4f)).coerceIn(9f, 19.5f)
                "${"%.2f".format(note)}/20" to note
            }
        }
    }

    /** Classe le cheval du joueur dans un peloton simulé ; retourne (rang, partants). */
    fun rank(ownScore: Float, d: Discipline, level: Int, field: Int, distance: Int, rng: Rng): Pair<Int, Int> {
        var better = 0
        for (i in 1 until field) {
            val p = Levels.EXPECT[level] + rng.gauss(0.0, 9.0).toFloat()
            val (_, s) = scoreText(d, p, level, distance, rng)
            if (s > ownScore) better++
        }
        return (better + 1) to field
    }

    /** Génère les épreuves d'une journée (2 à 4 concours par jour, davantage le week-end). */
    fun generateDay(day: Int, nextId: () -> Int, rng: Rng): List<CompEvent> {
        val out = ArrayList<CompEvent>()
        val weekend = Cal.isWeekend(day)
        val m = Cal.month(day)
        val n = if (weekend) rng.range(3, 5) else rng.range(0, 2)
        repeat(n) {
            val dWeights = doubleArrayOf(3.5, 2.2, 1.2, if (m in 2..10) 1.6 else 0.6, if (m in 3..9) 0.8 else 0.2, 0.6, if (m in 4..8) 0.9 else 0.0, 1.0, 1.2, 0.8)
            val d = Discipline.values()[rng.weighted(dWeights)]
            val level = rng.weighted(doubleArrayOf(3.0, 3.0, 2.2, 1.4, 0.8, if (rng.chance(0.25f)) 0.4 else 0.05))
            val venue = when (d) {
                Discipline.COURSE -> rng.pick(Names.RACECOURSES)
                Discipline.TROT_ATTELE -> rng.pick(listOf("Vincennes", "Enghien", "Caen", "Cabourg", "Laval", "Cagnes-sur-Mer", "Graignes"))
                else -> rng.pick(Names.VENUES)
            }
            val distance = when (d) { Discipline.COURSE -> rng.pick(listOf(1200, 1400, 1600, 2000, 2400, 3000)); Discipline.TROT_ATTELE -> rng.pick(listOf(2100, 2700, 2850)); else -> 0 }
            val field = when (d) { Discipline.COURSE, Discipline.TROT_ATTELE -> rng.range(8, 16); Discipline.MODELE -> rng.range(10, 30); else -> rng.range(15, 60) - level * 4 }
            out += CompEvent(nextId(), day, venue, d, level, distance, max(6, field), rng.range(20, 420))
        }
        return out
    }
}
