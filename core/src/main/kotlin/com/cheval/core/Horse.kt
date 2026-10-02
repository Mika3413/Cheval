package com.cheval.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class Sex(val label: String) { JUMENT("Jument"), ETALON("Étalon"), HONGRE("Hongre") }

enum class Place(val label: String) { BOX("Au box"), PRE("Au pré"), DEPLACEMENT("En déplacement") }

/** Sortie au pré : réglage individuel. */
enum class Turnout(val label: String) { BOX("Box seulement"), JOUR("Pré la journée"), PERMANENT("Pré 24 h/24") }

enum class Discipline(val label: String, val short: String) {
    CSO("Saut d'obstacles", "CSO"),
    DRESSAGE("Dressage", "Dressage"),
    COMPLET("Concours complet", "Complet"),
    COURSE("Course de plat", "Course"),
    ENDURANCE("Endurance", "Endurance"),
    ATTELAGE("Attelage", "Attelage"),
    MODELE("Modèle et allures", "Modèle");

    /** Potentiel génétique pour la discipline (pondération des caractères). */
    fun potential(h: Horse): Float = when (this) {
        CSO -> h.pot(Trait.SAUT) * 0.38f + h.pot(Trait.TECHNIQUE) * 0.3f + h.pot(Trait.COURAGE) * 0.12f + h.pot(Trait.FORCE) * 0.1f + h.pot(Trait.VITESSE) * 0.1f
        DRESSAGE -> h.pot(Trait.ALLURES) * 0.45f + h.pot(Trait.APPRENTISSAGE) * 0.2f + h.pot(Trait.CALME) * 0.15f + h.pot(Trait.MODELE) * 0.1f + h.pot(Trait.FORCE) * 0.1f
        COMPLET -> h.pot(Trait.ENDURANCE) * 0.25f + h.pot(Trait.SAUT) * 0.2f + h.pot(Trait.COURAGE) * 0.2f + h.pot(Trait.VITESSE) * 0.15f + h.pot(Trait.TECHNIQUE) * 0.1f + h.pot(Trait.ALLURES) * 0.1f
        COURSE -> h.pot(Trait.VITESSE) * 0.6f + h.pot(Trait.ENDURANCE) * 0.15f + h.pot(Trait.COURAGE) * 0.15f + h.pot(Trait.FORCE) * 0.1f
        ENDURANCE -> h.pot(Trait.ENDURANCE) * 0.55f + h.pot(Trait.ROBUSTESSE) * 0.2f + h.pot(Trait.CALME) * 0.15f + h.pot(Trait.VITESSE) * 0.1f
        ATTELAGE -> h.pot(Trait.FORCE) * 0.35f + h.pot(Trait.CALME) * 0.25f + h.pot(Trait.ALLURES) * 0.2f + h.pot(Trait.ENDURANCE) * 0.2f
        MODELE -> h.pot(Trait.MODELE) * 0.6f + h.pot(Trait.ALLURES) * 0.4f
    }

    val trainable get() = this != MODELE
}

/** Traits de caractère visibles (dérivés des gènes de tempérament et du hasard). */
enum class Personality(val label: String, val desc: String) {
    CALIN("Câlin", "Adore les caresses et le pansage."),
    GOURMAND("Gourmand", "Ne pense qu'à manger : surveiller son état corporel."),
    PEUREUX("Peureux", "Sursaute facilement, en extérieur comme en concours."),
    JOUEUR("Joueur", "Toujours prêt à faire des bêtises au pré."),
    TETU("Têtu", "Négocie chaque demande : progresse moins vite."),
    COURAGEUX("Courageux", "Ne refuse jamais rien, même sous pression."),
    DOMINANT("Dominant", "Chef de troupeau : peut blesser ses congénères au pré."),
    SENSIBLE("Sensible", "Réagit au moindre geste : génial avec un bon cavalier."),
    PARESSEUX("Paresseux", "Il faut le motiver, mais il ne se fatigue pas pour rien."),
    SOCIABLE("Sociable", "Déprime vite s'il est seul."),
    FRILEUX("Frileux", "Supporte mal le froid et la pluie."),
    GENEREUX("Généreux", "Donne tout ce qu'il a, parfois trop."),
}

class CompResult(
    val day: Int, val eventName: String, val discipline: Discipline, val level: Int, val rank: Int, val of: Int,
    val score: String, val prize: Int, val rider: String,
) {
    fun toJson() = mapOf("d" to day, "n" to eventName, "di" to discipline.name, "l" to level, "r" to rank, "o" to of, "s" to score, "p" to prize, "ri" to rider)
    companion object {
        fun fromJson(o: JObj) = CompResult(o.int("d"), o.str("n"), o.enum("di", Discipline.CSO), o.int("l"), o.int("r"), o.int("o"), o.str("s"), o.int("p"), o.str("ri"))
    }
}

class Pregnancy(val sireId: Int, val conceivedDay: Int, val dueDay: Int, var confirmed: Boolean) {
    fun toJson() = mapOf("s" to sireId, "c" to conceivedDay, "d" to dueDay, "k" to confirmed)
    companion object {
        fun fromJson(o: JObj) = Pregnancy(o.int("s"), o.int("c"), o.int("d"), o.bool("k"))
    }
}

/** Ration journalière (kg par jour). */
class Ration(var hay: Float = 9f, var feed: Float = 2f, var minerals: Boolean = true) {
    fun toJson() = mapOf("h" to hay, "f" to feed, "m" to minerals)
    companion object { fun fromJson(o: JObj?) = if (o == null) Ration() else Ration(o.float("h", 9f), o.float("f", 2f), o.bool("m", true)) }
}

/** Un cheval (possédé, à vendre, étalon extérieur ou ancêtre). */
class Horse(
    val id: Int,
    var name: String,
    var sex: Sex,
    val birthDay: Int,
    val breed: Breed,
    val genome: Genome,
    val sireId: Int = -1,
    val damId: Int = -1,
    val morpho: Morpho = breed.morpho,
) {
    var owned = false
    var alive = true
    var deathDay = -1
    var deathCause = ""
    /** Coefficient de consanguinité (Wright), calculé à la naissance. */
    var inbreeding = 0f
    var personality: List<Personality> = emptyList()
    var dnaTested = false

    // --- Besoins (0 = critique, 100 = parfait)
    var satiety = 80f
    var hydration = 90f
    var cleanliness = 70f
    var litter = 100f
    var morale = 75f
    var energy = 90f
    var hooves = 85f
    var teeth = 85f
    var bond = 20f
    var stress = 10f
    /** Note d'état corporel (échelle de Henneke 1 à 9, idéal 5). */
    var bcs = 5f
    var fitness = 30f
    var muscle = 40f

    // --- Soins préventifs (jour du dernier acte, -9999 = jamais)
    var lastFarrier = -9999
    var lastDentist = -9999
    var lastVaccine = -9999
    var lastDeworm = -9999
    var shod = false
    var rugged = false
    var clipped = false

    // --- Vie au domaine
    var place = Place.BOX
    var turnout = Turnout.JOUR
    var ration = Ration()
    var ailments = ArrayList<Ailment>()
    var skills = FloatArray(Discipline.values().size)
    /** Débourré (accepte la selle et le cavalier). */
    var backed = false
    var backingProgress = 0f
    /** Éducation du poulain (manipulations, licol, pieds). */
    var handling = 0f
    var weaned = true
    var clubHorse = false
    var studFee = 0       // > 0 : étalon proposé à la saillie extérieure
    var pregnancy: Pregnancy? = null
    var foalsBorn = 0
    var cycleOffset = 0
    var lastCovered = -9999
    var workToday = 0f
    var trainedDays = 0
    var lastTrainDay = -9999
    var lastGroomDay = -9999
    var lastTreatDay = -9999
    var treatsToday = 0
    var careHourToday = 0
    var results = ArrayList<CompResult>()
    var earnings = 0
    var purchasePrice = 0
    var acquiredDay = 0
    var forSale = false
    var askingPrice = 0
    var notes = ""
    var titles = ArrayList<String>()
    /** Foin restant dans le râtelier (kg) et eau du seau (litres). */
    var hayRack = 4f
    var bucket = 30f
    /** Risque de colique accumulé (repas trop riches, jeûne, déshydratation). */
    var riskAcc = 0f
    var eatHay = 0f
    var eatFeed = 0f
    var eatGrass = 0f
    var hungryHours = 0
    /** Programme de travail confié au cavalier salarié. */
    var plan: Discipline? = null
    var planSessions = 4
    var lastExercise = ""
    var sessionsToday = 0

    fun pot(t: Trait): Float {
        val v = genome.value(t)
        return if (t in Trait.FITNESS) v * (1f - inbreeding * 0.9f) else v
    }

    val heightCm: Int get() = genome.value(Trait.TAILLE).toInt()
    fun age(today: Int): Float = Cal.ageYears(birthDay, today)
    fun ageClass(today: Int) = Cal.ageClass(birthDay, today)
    fun isFoal(today: Int) = today - birthDay < 365
    val stallion get() = sex == Sex.ETALON
    val mare get() = sex == Sex.JUMENT

    /** Poids estimé (kg) selon la taille, le modèle de la race et l'état corporel. */
    fun weight(today: Int): Float {
        val adult = 520f * (genome.value(Trait.TAILLE) / 165f).pow(3) * morpho.mass
        val a = age(today)
        val growth = if (a >= 4f) 1f else 0.1f + 0.9f * (1f - (1f - a / 4f).pow(2.2f))
        return adult * growth * (1f + (bcs - 5f) * 0.055f)
    }

    /** Taille réelle à l'âge donné (croissance jusqu'à ~5 ans). */
    fun heightAt(today: Int): Float {
        val a = age(today)
        val h = genome.value(Trait.TAILLE)
        if (a >= 5f) return h
        return h * (0.62f + 0.38f * (1f - (1f - a / 5f).pow(2.6f)))
    }

    fun coatName(today: Int) = Coat.name(genome, age(today))
    fun look(today: Int): CoatLook {
        val shine = ((cleanliness / 100f) * 0.45f + (1f - abs(bcs - 5.2f) / 4f) * 0.35f + (if (hasAilment(AilmentType.PARASITES)) 0f else 0.2f)).coerceIn(0f, 1f)
        return Coat.look(genome, age(today), shine)
    }

    fun hasAilment(t: AilmentType) = ailments.any { it.type == t }
    val injured get() = ailments.any { !it.type.work }
    val pain: Float get() = ailments.maxOfOrNull { it.type.pain * it.severity } ?: 0f
    val urgent get() = ailments.any { it.type.urgent && !it.treated }

    /** Santé globale 0..100 affichée. */
    val health: Float get() {
        var h = 100f - pain * 70f
        if (satiety < 25) h -= (25 - satiety)
        if (hydration < 30) h -= (30 - hydration)
        h -= abs(bcs - 5f).let { if (it > 1.5f) (it - 1.5f) * 12f else 0f }
        return h.coerceIn(0f, 100f)
    }

    /** Facteur d'âge sur les performances : progression jusqu'à 8 ans, plateau, déclin après 16 ans. */
    fun ageFactor(today: Int): Float {
        val a = age(today)
        return when {
            a < 2f -> 0.25f
            a < 4f -> 0.45f + (a - 2f) * 0.15f
            a < 8f -> 0.75f + (a - 4f) * 0.0625f
            a < 16f -> 1f
            else -> max(0.35f, 1f - (a - 16f) * 0.07f)
        }
    }

    fun skill(d: Discipline): Float = if (d == Discipline.MODELE) modelScore() else skills[d.ordinal]

    /** Note modèle-allures : génétique + présentation (état, propreté, musculature). */
    fun modelScore(): Float {
        val pres = (cleanliness / 100f) * 0.4f + (1f - abs(bcs - 5.2f) / 3f).coerceIn(0f, 1f) * 0.35f + muscle / 100f * 0.25f
        return (Discipline.MODELE.potential(this) * 0.8f + pres * 25f).coerceIn(0f, 110f)
    }

    /** Plafond actuel de progression dans une discipline. */
    fun skillCap(d: Discipline, today: Int): Float = d.potential(this) * 1.15f * ageFactor(today)

    fun bestDiscipline(): Discipline = Discipline.values().filter { it.trainable }.maxBy { it.potential(this) }

    /** Jours restants avant la prochaine visite recommandée. */
    fun farrierDue(today: Int) = (lastFarrier + (if (shod) 42 else 56)) - today
    fun dentistDue(today: Int) = lastDentist + 365 - today
    fun vaccineDue(today: Int) = lastVaccine + 365 - today
    fun dewormDue(today: Int) = lastDeworm + 120 - today

    /** La jument est-elle en chaleur ? (cycles de 21 jours, saison de reproduction mars–septembre) */
    fun inHeat(today: Int): Boolean {
        if (!mare || pregnancy != null || age(today) < 2.5f || !alive) return false
        val m = Cal.month(today)
        if (m !in 2..8) return false
        return ((today + cycleOffset).mod(21)) < 6
    }

    fun nextHeat(today: Int): Int {
        for (d in 0..400) if (inHeat(today + d)) return d
        return -1
    }

    /** Points de forme du jour pour les concours (fatigue, moral, condition). */
    fun dayForm(): Float {
        val e = (energy / 100f).coerceIn(0f, 1f)
        val m = (morale / 100f).coerceIn(0f, 1f)
        val f = fitness / 100f
        val body = 1f - (abs(bcs - 5.2f) / 4f).coerceIn(0f, 0.5f)
        return (0.55f + e * 0.15f + m * 0.1f + f * 0.2f) * body * (1f - pain * 0.6f)
    }

    fun summaryStatus(today: Int): String = when {
        !alive -> "Décédé"
        urgent -> "URGENT : ${ailments.first { it.type.urgent && !it.treated }.type.label}"
        injured -> ailments.first { !it.type.work }.type.label
        pregnancy?.confirmed == true -> "Pleine (terme ${Cal.formatShort(pregnancy!!.dueDay)})"
        satiety < 25 -> "A faim"
        hydration < 30 -> "A soif"
        energy < 25 -> "Épuisé"
        place == Place.DEPLACEMENT -> "En concours"
        isFoal(today) && !weaned -> "Sous la mère"
        else -> if (morale > 75) "En pleine forme" else if (morale > 45) "Bien" else "Morose"
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "n" to name, "sx" to sex.name, "b" to birthDay, "br" to breed.name, "g" to genome.toJson(),
        "si" to sireId, "da" to damId,
        "mo" to listOf(morpho.bodyLength, morpho.legLength, morpho.neckLength, morpho.neckArch, morpho.headSize, morpho.headProfile, morpho.mass, morpho.feather, morpho.maneLength, morpho.tailSet, morpho.earSize),
        "ow" to owned, "al" to alive, "dd" to deathDay, "dc" to deathCause, "f" to inbreeding,
        "pe" to personality.map { it.name }, "dna" to dnaTested,
        "need" to listOf(satiety, hydration, cleanliness, litter, morale, energy, hooves, teeth, bond, stress, bcs, fitness, muscle),
        "care" to listOf(lastFarrier, lastDentist, lastVaccine, lastDeworm),
        "shod" to shod, "rug" to rugged, "clip" to clipped,
        "pl" to place.name, "to" to turnout.name, "ra" to ration.toJson(), "ai" to ailments.map { it.toJson() },
        "sk" to skills.toList(), "bk" to backed, "bkp" to backingProgress, "hd" to handling, "wn" to weaned, "club" to clubHorse,
        "fee" to studFee, "pg" to pregnancy?.toJson(), "fb" to foalsBorn, "cy" to cycleOffset, "lc" to lastCovered,
        "wt" to workToday, "td" to trainedDays, "ltd" to lastTrainDay, "lg" to lastGroomDay, "ltr" to lastTreatDay, "tt" to treatsToday,
        "res" to results.map { it.toJson() }, "earn" to earnings, "pp" to purchasePrice, "acq" to acquiredDay,
        "fs" to forSale, "ap" to askingPrice, "no" to notes, "ti" to titles,
        "eat" to listOf(hayRack, bucket, riskAcc, eatHay, eatFeed, eatGrass, hungryHours.toFloat()),
        "plan" to plan?.name, "ps" to planSessions, "lx" to lastExercise, "st" to sessionsToday,
    )

    companion object {
        fun fromJson(o: JObj): Horse {
            val mo = o.floats("mo")
            val breed = o.enum("br", Breed.CROISE)
            val morpho = if (mo.size >= 11) Morpho(mo[0], mo[1], mo[2], mo[3], mo[4], mo[5], mo[6], mo[7], mo[8], mo[9], mo[10]) else breed.morpho
            val h = Horse(o.int("id"), o.str("n"), o.enum("sx", Sex.JUMENT), o.int("b"), breed, Genome.fromJson(o.obj("g")!!), o.int("si", -1), o.int("da", -1), morpho)
            h.owned = o.bool("ow"); h.alive = o.bool("al", true); h.deathDay = o.int("dd", -1); h.deathCause = o.str("dc"); h.inbreeding = o.float("f")
            h.personality = o.list("pe").mapNotNull { n -> Personality.values().firstOrNull { it.name == n } }
            h.dnaTested = o.bool("dna")
            val n = o.floats("need")
            if (n.size >= 13) { h.satiety = n[0]; h.hydration = n[1]; h.cleanliness = n[2]; h.litter = n[3]; h.morale = n[4]; h.energy = n[5]; h.hooves = n[6]; h.teeth = n[7]; h.bond = n[8]; h.stress = n[9]; h.bcs = n[10]; h.fitness = n[11]; h.muscle = n[12] }
            val c = o.ints("care")
            if (c.size >= 4) { h.lastFarrier = c[0]; h.lastDentist = c[1]; h.lastVaccine = c[2]; h.lastDeworm = c[3] }
            h.shod = o.bool("shod"); h.rugged = o.bool("rug"); h.clipped = o.bool("clip")
            h.place = o.enum("pl", Place.BOX); h.turnout = o.enum("to", Turnout.JOUR); h.ration = Ration.fromJson(o.obj("ra"))
            h.ailments = ArrayList(o.objs("ai").map { Ailment.fromJson(it) })
            val sk = o.floats("sk"); sk.copyInto(h.skills, 0, 0, min(sk.size, h.skills.size))
            h.backed = o.bool("bk"); h.backingProgress = o.float("bkp"); h.handling = o.float("hd"); h.weaned = o.bool("wn", true); h.clubHorse = o.bool("club")
            h.studFee = o.int("fee"); h.pregnancy = o.obj("pg")?.let { Pregnancy.fromJson(it) }; h.foalsBorn = o.int("fb"); h.cycleOffset = o.int("cy"); h.lastCovered = o.int("lc", -9999)
            h.workToday = o.float("wt"); h.trainedDays = o.int("td"); h.lastTrainDay = o.int("ltd", -9999); h.lastGroomDay = o.int("lg", -9999); h.lastTreatDay = o.int("ltr", -9999); h.treatsToday = o.int("tt")
            h.results = ArrayList(o.objs("res").map { CompResult.fromJson(it) }); h.earnings = o.int("earn"); h.purchasePrice = o.int("pp"); h.acquiredDay = o.int("acq")
            h.forSale = o.bool("fs"); h.askingPrice = o.int("ap"); h.notes = o.str("no")
            h.titles = ArrayList(o.list("ti").filterIsInstance<String>())
            val e = o.floats("eat")
            if (e.size >= 7) { h.hayRack = e[0]; h.bucket = e[1]; h.riskAcc = e[2]; h.eatHay = e[3]; h.eatFeed = e[4]; h.eatGrass = e[5]; h.hungryHours = e[6].toInt() }
            h.plan = o.strOrNull("plan")?.let { n -> Discipline.values().firstOrNull { it.name == n } }
            h.planSessions = o.int("ps", 4); h.lastExercise = o.str("lx"); h.sessionsToday = o.int("st")
            return h
        }

        fun rollPersonality(g: Genome, rng: Rng): List<Personality> {
            val out = ArrayList<Personality>()
            val calm = g.value(Trait.CALME); val courage = g.value(Trait.COURAGE)
            if (calm < 45 && rng.chance(0.6f)) out += Personality.PEUREUX
            if (courage > 68 && rng.chance(0.6f)) out += Personality.COURAGEUX
            if (calm < 50 && courage > 60 && rng.chance(0.4f)) out += Personality.SENSIBLE
            val pool = Personality.values().filter { it !in out && it != Personality.PEUREUX && it != Personality.COURAGEUX }
            while (out.size < 2) {
                val p = rng.pick(pool)
                if (p !in out) out += p
            }
            return out
        }
    }
}
