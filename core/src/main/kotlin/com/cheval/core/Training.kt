package com.cheval.core

/** Séances de travail. [gains] : discipline → poids de progression. */
enum class Exercise(
    val label: String,
    val minutes: Int,
    val gains: Map<Discipline, Float>,
    val fitness: Float,
    val energy: Float,
    val injury: Float,
    val minAge: Float,
    val ridden: Boolean,
    val facility: BuildingType?,
    val desc: String,
) {
    MANIPULATION("Éducation du poulain", 20, emptyMap(), 0f, 4f, 0f, 0f, false, null,
        "Licol, donner les pieds, marcher en main, s'habituer à la brosse : un poulain bien manipulé deviendra un adulte confiant."),
    LONGE("Travail à la longe", 30, mapOf(Discipline.DRESSAGE to 0.3f, Discipline.ATTELAGE to 0.3f), 1.2f, 14f, 0.002f, 2f, false, null,
        "Détente, équilibre et obéissance à la voix, sans cavalier."),
    DEBOURRAGE("Débourrage", 40, emptyMap(), 0.8f, 16f, 0.004f, 2.5f, false, null,
        "Apprendre au jeune cheval à accepter la selle, le filet puis le cavalier."),
    PLAT("Travail sur le plat", 45, mapOf(Discipline.DRESSAGE to 1f, Discipline.CSO to 0.35f, Discipline.COMPLET to 0.35f, Discipline.ATTELAGE to 0.2f), 1.6f, 22f, 0.003f, 3f, true, BuildingType.CARRIERE,
        "Transitions, cercles, incurvation : la base de tout."),
    DRESSAGE("Reprise de dressage", 50, mapOf(Discipline.DRESSAGE to 1.7f, Discipline.COMPLET to 0.3f), 1.5f, 26f, 0.003f, 4f, true, BuildingType.CARRIERE,
        "Figures de la reprise, appuyers, changements de pied, rassembler."),
    GYMNASTIQUE("Gymnastique à l'obstacle", 40, mapOf(Discipline.CSO to 1.3f, Discipline.COMPLET to 0.5f), 1.8f, 28f, 0.006f, 4f, true, BuildingType.CARRIERE,
        "Lignes de cavalettis et de petits sauts : technique et respect."),
    PARCOURS("Parcours d'obstacles", 40, mapOf(Discipline.CSO to 1.7f, Discipline.COMPLET to 0.4f), 2f, 34f, 0.009f, 4f, true, BuildingType.CARRIERE,
        "Enchaîner un parcours complet comme en concours."),
    CROSS("Entraînement de cross", 50, mapOf(Discipline.COMPLET to 1.8f, Discipline.CSO to 0.2f, Discipline.ENDURANCE to 0.3f), 2.6f, 38f, 0.011f, 5f, true, BuildingType.CROSS,
        "Troncs, gué, talus et fossés au galop."),
    GALOP("Galop d'entraînement", 30, mapOf(Discipline.COURSE to 1.8f, Discipline.COMPLET to 0.3f, Discipline.ENDURANCE to 0.3f), 2.8f, 36f, 0.012f, 2f, true, BuildingType.PISTE,
        "Canters et galops rapides sur la piste."),
    EXTERIEUR("Balade en extérieur", 75, mapOf(Discipline.ENDURANCE to 0.9f, Discipline.COMPLET to 0.2f), 1.6f, 20f, 0.003f, 3f, true, null,
        "Chemins, forêt et plage : excellent pour le moral et le fond."),
    FOND("Travail de fond", 120, mapOf(Discipline.ENDURANCE to 1.8f, Discipline.COMPLET to 0.3f), 3f, 42f, 0.008f, 5f, true, null,
        "Longues sorties à allure régulière, contrôle de la fréquence cardiaque."),
    ATTELAGE("Travail à l'attelage", 60, mapOf(Discipline.ATTELAGE to 1.8f), 1.8f, 26f, 0.004f, 4f, false, BuildingType.CARRIERE,
        "Menés à la voiture : dressage attelé, maniabilité et marathon.");

    companion object {
        /** Exercice principal à programmer pour une discipline. */
        fun forDiscipline(d: Discipline): Exercise = when (d) {
            Discipline.CSO -> PARCOURS; Discipline.DRESSAGE -> DRESSAGE; Discipline.COMPLET -> CROSS
            Discipline.COURSE -> GALOP; Discipline.ENDURANCE -> FOND; Discipline.ATTELAGE -> ATTELAGE; Discipline.MODELE -> LONGE
        }
    }
}
