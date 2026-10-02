package com.cheval.core

/** Statistiques suivies pour les objectifs. */
object St {
    const val FEED = "feed"; const val GROOM = "groom"; const val JUNK = "junk"; const val LESSON = "lesson"
    const val ENTRY = "entry"; const val PODIUM = "podium"; const val WIN = "win"; const val FOAL = "foal"
    const val SALE = "sale"; const val BUY = "buy"; const val HIRE = "hire"; const val BUILD = "build"
    const val COVER = "cover"; const val RIDE = "ride"; const val TRAIN = "train"; const val DAYS = "days"
    const val WIN_AMATEUR = "winAm"; const val WIN_PRO = "winPro"; const val WIN_GP = "winGp"; const val CARE = "care"
}

/** Un objectif : une étape de la reconstruction du domaine, avec sa récompense. */
class Goal(val id: String, val title: String, val desc: String, val reward: Int, val rep: Float, val done: (Game) -> Boolean)

object Goals {
    private fun s(g: Game, k: String) = g.stats[k] ?: 0

    val ALL = listOf(
        Goal("soins", "Premiers soins", "Nourris et abreuve ton cheval.", 80, 0.5f) { s(it, St.FEED) >= 1 },
        Goal("pansage", "Un poil qui brille", "Panse ton cheval de la tête aux sabots.", 100, 0.5f) { s(it, St.GROOM) >= 1 },
        Goal("menage", "Grand ménage", "Débarrasse 3 tas de déchets du domaine.", 250, 1f) { s(it, St.JUNK) >= 3 },
        Goal("balade", "Premier galop", "Monte ton cheval (balade ou séance de travail).", 120, 0.5f) { s(it, St.RIDE) >= 1 },
        Goal("cours", "Moniteur d'un jour", "Donne ton premier cours d'équitation.", 150, 1f) { s(it, St.LESSON) >= 1 },
        Goal("ecurie", "Un toit pour tous", "Restaure la vieille écurie (6 boxes).", 600, 2f) { it.level(BuildingType.ECURIE) >= 1 },
        Goal("menage2", "Domaine propre", "Débarrasse tous les déchets.", 500, 2f) { it.junk.isEmpty() },
        Goal("concours", "Premier concours", "Engage un cheval dans une épreuve.", 200, 1f) { s(it, St.ENTRY) >= 1 },
        Goal("carriere", "Le terrain de jeu", "Construis une carrière.", 800, 2f) { it.level(BuildingType.CARRIERE) >= 1 },
        Goal("cheval2", "La bande s'agrandit", "Achète un deuxième cheval.", 300, 1f) { it.owned().size >= 2 },
        Goal("cours10", "École d'équitation", "Donne 10 cours.", 600, 3f) { s(it, St.LESSON) >= 10 },
        Goal("podium", "Sur le podium", "Termine dans les trois premiers d'un concours.", 500, 2f) { s(it, St.PODIUM) >= 1 },
        Goal("equipe", "L'équipe", "Embauche un palefrenier.", 400, 1f) { it.staff.any { s -> s.role == Role.PALEFRENIER } },
        Goal("saillie", "Projet d'élevage", "Fais saillir une jument.", 400, 1f) { s(it, St.COVER) >= 1 },
        Goal("poulain", "Naissance", "Fais naître ton premier poulain.", 1500, 4f) { s(it, St.FOAL) >= 1 },
        Goal("vente", "Le commerce", "Vends un cheval.", 500, 1f) { s(it, St.SALE) >= 1 },
        Goal("victoire", "Victoire !", "Gagne une épreuve.", 1000, 3f) { s(it, St.WIN) >= 1 },
        Goal("clubhouse", "Le club", "Construis le club-house pour accueillir élèves et pensionnaires.", 1500, 3f) { it.level(BuildingType.CLUB_HOUSE) >= 1 },
        Goal("amateur", "Le niveau Amateur", "Gagne une épreuve Amateur ou plus.", 2500, 4f) { s(it, St.WIN_AMATEUR) >= 1 },
        Goal("reputation", "Un nom qui compte", "Atteins 40 de réputation.", 3000, 0f) { it.reputation >= 40f },
        Goal("manege", "Par tous les temps", "Construis le manège couvert.", 5000, 4f) { it.level(BuildingType.MANEGE) >= 1 },
        Goal("pro", "Chez les pros", "Gagne une épreuve Pro.", 8000, 6f) { s(it, St.WIN_PRO) >= 1 },
        Goal("gp", "La consécration", "Gagne un Grand Prix (ou un Groupe 1).", 30000, 10f) { s(it, St.WIN_GP) >= 1 },
    )

    /** Les objectifs en cours : les trois premiers non réclamés. */
    fun active(g: Game): List<Goal> = ALL.filter { it.id !in g.goalsDone }.take(3)
}

/** Un tas de déchets à débarrasser au début de la partie (position relative sur la carte). */
class Junk(val id: Int, val kind: Int, val hours: Float, val x: Float, val y: Float) {
    val label get() = KINDS[kind]
    fun toJson() = mapOf("id" to id, "k" to kind, "h" to hours, "x" to x, "y" to y)
    companion object {
        val KINDS = listOf("Vieux pneus", "Tas de ferraille", "Branchages", "Bâches et fûts", "Carcasse de voiture", "Clôture effondrée")
        fun fromJson(o: JObj) = Junk(o.int("id"), o.int("k"), o.float("h", 1f), o.float("x"), o.float("y"))
    }
}
