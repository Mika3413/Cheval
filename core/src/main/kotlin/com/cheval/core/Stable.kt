package com.cheval.core

/** Bâtiments et installations du domaine. [costs] : prix de chaque niveau. */
enum class BuildingType(val label: String, val maxLevel: Int, val costs: IntArray, val upkeep: Int, val desc: String) {
    ECURIE("Écurie", 4, intArrayOf(6000, 18000, 32000, 55000), 60, "Le vieil abri (2 boxes) devient une vraie écurie : 6 boxes paillés par niveau."),
    PRE("Prairies", 4, intArrayOf(0, 9000, 16000, 28000), 25, "Herbages clôturés avec abri : 4 hectares par niveau. Plus d'herbe, moins de boue."),
    CARRIERE("Carrière", 3, intArrayOf(7500, 22000, 45000), 30, "Sol sablé drainé pour le travail. Sans elle, le travail se fait au pré (moins efficace, plus risqué)."),
    MANEGE("Manège couvert", 2, intArrayOf(65000, 120000), 80, "Travailler par tous les temps, même sous l'orage ou la neige."),
    MARCHEUR("Marcheur", 1, intArrayOf(12000), 15, "Détend les chevaux chaque jour : forme, moral, récupération des blessés."),
    PISTE("Piste de galop", 2, intArrayOf(30000, 60000), 30, "Piste en sable fibré pour l'entraînement des chevaux de course."),
    CROSS("Parcours de cross", 2, intArrayOf(22000, 45000), 20, "Obstacles naturels : troncs, gué, talus, fossés."),
    GRENIER("Grange", 3, intArrayOf(0, 8000, 18000), 10, "Stockage du foin, de la paille et des aliments."),
    ABREUVOIRS("Abreuvoirs automatiques", 1, intArrayOf(4500), 5, "Eau fraîche à volonté dans chaque box."),
    POULINAGE("Box de poulinage", 1, intArrayOf(9000), 10, "Grand box sous caméra : poulinages bien plus sûrs."),
    INFIRMERIE("Infirmerie", 1, intArrayOf(15000), 20, "Travail, douche froide et box de soins : convalescences plus rapides."),
    DOUCHE("Douche et solarium", 1, intArrayOf(7000), 10, "Douche chaude et lampes chauffantes : propreté et récupération."),
    SELLERIE("Sellerie de compétition", 2, intArrayOf(6000, 15000), 5, "Selles sur mesure, protections, embouchures adaptées : meilleures performances."),
    CLUB_HOUSE("Club-house", 2, intArrayOf(15000, 45000), 40, "Accueil des cavaliers de club et des propriétaires en pension."),
    CAMION("Camion 4 places", 1, intArrayOf(38000), 40, "Transport des chevaux : frais de déplacement en concours fortement réduits.");

    fun cost(nextLevel: Int): Int = costs[(nextLevel - 1).coerceIn(0, costs.size - 1)]
}

enum class Role(val label: String, val baseSalary: Int, val desc: String) {
    PALEFRENIER("Palefrenier", 1150, "Distribue les repas, l'eau, cure les boxes et sort les chevaux au pré (≈ 8 chevaux)."),
    SOIGNEUR("Soigneur", 1300, "Panse, cure les pieds, surveille la santé, fait les soins (≈ 10 chevaux)."),
    CAVALIER("Cavalier", 1600, "Travaille les chevaux selon leur programme et les monte en concours."),
    MONITEUR("Moniteur", 1400, "Donne des cours d'équitation avec les chevaux de club."),
    LAD("Lad-jockey", 1300, "Entraîne et monte les chevaux de course.");
}

class Staff(val id: Int, val name: String, val role: Role, var skill: Int, val salary: Int, var discipline: Discipline? = null) {
    fun toJson() = mapOf("id" to id, "n" to name, "r" to role.name, "s" to skill, "sa" to salary, "d" to discipline?.name)
    companion object {
        fun fromJson(o: JObj) = Staff(o.int("id"), o.str("n"), o.enum("r", Role.PALEFRENIER), o.int("s", 2), o.int("sa"),
            o.strOrNull("d")?.let { n -> Discipline.values().firstOrNull { it.name == n } })
    }
}

enum class Item(val label: String, val unit: String, val price: Float, val pack: Int, val desc: String) {
    FOIN("Foin", "kg", 0.19f, 500, "Base de l'alimentation : 1,5 à 2 % du poids du cheval par jour."),
    GRANULES("Granulés", "kg", 0.62f, 100, "Aliment concentré énergétique pour les chevaux au travail, les juments et les poulains."),
    PAILLE("Paille", "botte", 4.5f, 20, "Litière des boxes : environ une botte par box et par jour."),
    CAROTTES("Carottes", "kg", 1.1f, 10, "Friandises : à donner avec modération."),
    MINERAUX("Minéraux", "dose", 0.45f, 60, "Complément minéral et vitaminé quotidien."),
}

enum class MsgKind { INFO, GOOD, BAD, URGENT }

class Msg(val day: Int, val hour: Int, val text: String, val kind: MsgKind, val horseId: Int = -1) {
    fun toJson() = mapOf("d" to day, "h" to hour, "t" to text, "k" to kind.name, "hi" to horseId)
    companion object { fun fromJson(o: JObj) = Msg(o.int("d"), o.int("h"), o.str("t"), o.enum("k", MsgKind.INFO), o.int("hi", -1)) }
}

class Txn(val day: Int, val label: String, val amount: Int, val cat: String) {
    fun toJson() = mapOf("d" to day, "l" to label, "a" to amount, "c" to cat)
    companion object { fun fromJson(o: JObj) = Txn(o.int("d"), o.str("l"), o.int("a"), o.str("c")) }
}

/** Le cavalier joueur : expérience par discipline et Galop fédéral obtenu. */
class Rider(var name: String = "Vous") {
    val xp = FloatArray(Discipline.values().size)
    var galop = 2
    var hoursRidden = 0f
    fun skill(d: Discipline): Float = (20f + galop * 6f + xp[d.ordinal]).coerceAtMost(100f)
    val overall get() = (xp.average().toFloat() + galop * 6f + 20f).coerceAtMost(100f)
    /** Expérience requise pour présenter l'examen du Galop suivant. */
    fun examReady(): Boolean = galop < 7 && hoursRidden >= GALOP_HOURS[galop]
    fun toJson() = mapOf("n" to name, "xp" to xp.toList(), "g" to galop, "h" to hoursRidden)

    companion object {
        val GALOP_HOURS = floatArrayOf(0f, 3f, 8f, 16f, 28f, 45f, 70f, 999f)
        fun fromJson(o: JObj?): Rider {
            val r = Rider(o?.str("n", "Vous") ?: "Vous")
            if (o != null) {
                val x = o.floats("xp"); x.copyInto(r.xp, 0, 0, minOf(x.size, r.xp.size))
                r.galop = o.int("g", 2); r.hoursRidden = o.float("h")
            }
            return r
        }
    }
}
