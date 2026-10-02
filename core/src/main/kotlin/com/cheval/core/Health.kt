package com.cheval.core

/**
 * Affections courantes du cheval. [days] : durée typique de convalescence avec soins,
 * [vet] : coût moyen d'intervention, [work] : peut-on travailler le cheval, [urgent] : à traiter immédiatement.
 */
enum class AilmentType(
    val label: String,
    val days: Int,
    val vet: Int,
    val work: Boolean,
    val urgent: Boolean,
    val contagious: Boolean,
    val pain: Float,
    val advice: String,
) {
    COLIQUE("Colique", 3, 280, false, true, false, 0.9f,
        "Douleur abdominale : le cheval gratte le sol, se regarde le flanc, se roule. Appeler le vétérinaire sans attendre, retirer la nourriture, marcher en main."),
    BOITERIE("Boiterie", 18, 180, false, false, false, 0.5f,
        "Repos au box, marcheur doux, glace sur les membres. Reprise progressive."),
    TENDINITE("Tendinite", 90, 650, false, false, false, 0.6f,
        "Lésion du tendon fléchisseur : 3 mois de repos, échographies de contrôle, reprise très progressive."),
    ABCES("Abcès de pied", 7, 120, false, false, false, 0.7f,
        "Le maréchal ou le vétérinaire ouvre l'abcès, puis pansement et bain de pied quotidien."),
    FOURBURE("Fourbure", 45, 450, false, true, false, 0.85f,
        "Inflammation du pied liée à l'excès d'herbe riche, de céréales ou à l'obésité. Box paillé épais, ferrure orthopédique, régime strict."),
    GOURME("Gourme", 21, 220, false, false, true, 0.5f,
        "Infection contagieuse des ganglions. Isoler le cheval, désinfecter le matériel."),
    GRIPPE("Grippe équine", 14, 150, false, false, true, 0.35f,
        "Toux, fièvre. Repos complet d'une semaine par jour de fièvre. La vaccination l'évite."),
    GALE_DE_BOUE("Gale de boue", 10, 80, true, false, false, 0.25f,
        "Dermite des paturons due à la boue et l'humidité : tondre, nettoyer, sécher, crème cicatrisante."),
    PLAIE("Plaie", 8, 140, false, false, false, 0.4f,
        "Blessure au pré (coup de pied, clôture). Nettoyage, désinfection. Rappel tétanos indispensable."),
    ULCERES("Ulcères gastriques", 28, 380, true, false, false, 0.35f,
        "Stress, jeûne prolongé et rations riches en céréales. Traitement à l'oméprazole, foin à volonté, sorties au pré."),
    PARASITES("Parasitisme", 10, 60, true, false, false, 0.2f,
        "Amaigrissement et poil terne : coprologie puis vermifugation raisonnée."),
    COUP_DE_CHALEUR("Coup de chaleur", 3, 120, false, true, false, 0.6f,
        "Doucher abondamment, mettre à l'ombre, faire boire de petites quantités souvent."),
    TETANOS("Tétanos", 30, 900, false, true, false, 0.95f,
        "Maladie souvent mortelle suite à une plaie chez un cheval non vacciné."),
    ARTHROSE("Arthrose", 0, 220, true, false, false, 0.3f,
        "Usure articulaire liée à l'âge ou au travail : maladie chronique, à gérer (compléments, travail régulier et doux)."),
    MELANOME("Mélanome", 0, 300, true, false, false, 0.15f,
        "Tumeurs fréquentes chez les chevaux gris âgés, souvent sous la queue. Surveillance vétérinaire.");

    val chronic get() = days == 0
}

class Ailment(val type: AilmentType, var daysLeft: Float, var treated: Boolean, val sinceDay: Int, var severity: Float = 1f) {
    fun toJson() = mapOf("t" to type.name, "d" to daysLeft, "tr" to treated, "s" to sinceDay, "v" to severity)
    companion object {
        fun fromJson(o: JObj) = Ailment(o.enum("t", AilmentType.BOITERIE), o.float("d"), o.bool("tr"), o.int("s"), o.float("v", 1f))
    }
}
