package com.cheval.core

/** Morphologie d'une race, utilisée par le rendu (rapports relatifs à un cheval de selle « moyen » = 1). */
class Morpho(
    val bodyLength: Float = 1f,
    val legLength: Float = 1f,
    val neckLength: Float = 1f,
    val neckArch: Float = 0.5f,
    val headSize: Float = 1f,
    val headProfile: Float = 0f, // -1 concave (arabe), +1 busqué (ibérique, trait)
    val mass: Float = 1f,        // épaisseur, musculature
    val feather: Float = 0f,     // fanons
    val maneLength: Float = 0.4f,
    val tailSet: Float = 0.5f,   // port de queue
    val earSize: Float = 1f,
)

/**
 * Races, avec leurs moyennes de caractères et fréquences alléliques de robe (ordres de grandeur réels).
 * [prices] : prix de base d'un cheval adulte non dressé de qualité moyenne.
 */
enum class Breed(
    val label: String,
    val origin: String,
    val pony: Boolean,
    val basePrice: Int,
    private val height: Float,
    private val traits: IntArray, // VITESSE, ENDURANCE, SAUT, TECHNIQUE, ALLURES, FORCE, MODELE, CALME, COURAGE, APPRENTISSAGE, ROBUSTESSE, FERTILITE
    private val freqs: Map<Locus, Float>,
    val morpho: Morpho,
    val nuance: Float = 50f,
    val flaxen: Float = 30f,
    val whiteHead: Float = 35f,
    val whiteLegs: Float = 35f,
    val lifespan: Float = 27f,
    val description: String,
) {
    SELLE_FRANCAIS("Selle Français", "France (Normandie)", false, 9000, 166f,
        intArrayOf(56, 55, 70, 66, 60, 58, 60, 52, 64, 60, 60, 60),
        mapOf(Locus.EXTENSION to 0.55f, Locus.AGOUTI to 0.62f, Locus.GREY to 0.07f, Locus.TOBIANO to 0.01f, Locus.SABINO to 0.01f, Locus.MSTN to 0.4f),
        Morpho(neckArch = 0.55f), whiteHead = 42f, whiteLegs = 40f,
        description = "Le cheval de saut d'obstacles français par excellence, né en Normandie. Puissant, respectueux et courageux."),
    PUR_SANG("Pur-sang anglais", "Angleterre", false, 7000, 164f,
        intArrayOf(82, 64, 55, 50, 52, 50, 62, 34, 70, 54, 50, 58),
        mapOf(Locus.EXTENSION to 0.58f, Locus.AGOUTI to 0.7f, Locus.GREY to 0.04f, Locus.ROAN to 0.004f, Locus.MSTN to 0.55f),
        Morpho(bodyLength = 1.02f, legLength = 1.06f, neckLength = 1.05f, neckArch = 0.35f, headSize = 0.96f, mass = 0.88f, maneLength = 0.3f), whiteHead = 40f, whiteLegs = 36f,
        description = "Le cheval de course : le plus rapide au galop, sensible et généreux. Améliorateur de nombreuses races de sport."),
    ARABE("Pur-sang arabe", "Péninsule arabique", false, 8000, 152f,
        intArrayOf(62, 86, 44, 46, 56, 44, 66, 44, 62, 70, 76, 62),
        mapOf(Locus.EXTENSION to 0.6f, Locus.AGOUTI to 0.62f, Locus.GREY to 0.28f, Locus.SABINO to 0.02f, Locus.MSTN to 0.3f),
        Morpho(bodyLength = 0.95f, neckArch = 0.75f, headSize = 0.88f, headProfile = -1f, mass = 0.82f, maneLength = 0.45f, tailSet = 1f, earSize = 0.85f), whiteHead = 44f, whiteLegs = 40f, lifespan = 30f,
        description = "La plus ancienne race de selle : tête concave, queue portée haut, endurance et rusticité légendaires."),
    KWPN("KWPN (Cheval de sport néerlandais)", "Pays-Bas", false, 12000, 168f,
        intArrayOf(54, 54, 70, 64, 70, 60, 64, 54, 60, 62, 56, 58),
        mapOf(Locus.EXTENSION to 0.6f, Locus.AGOUTI to 0.55f, Locus.GREY to 0.06f, Locus.TOBIANO to 0.01f, Locus.MSTN to 0.35f),
        Morpho(legLength = 1.03f, neckLength = 1.06f, neckArch = 0.65f, mass = 1.02f, maneLength = 0.3f), nuance = 60f, whiteHead = 42f, whiteLegs = 40f,
        description = "Athlète moderne sélectionné pour le saut et le dressage de haut niveau."),
    HANOVRIEN("Hanovrien", "Allemagne", false, 11000, 167f,
        intArrayOf(52, 55, 64, 62, 74, 62, 64, 58, 58, 64, 58, 58),
        mapOf(Locus.EXTENSION to 0.6f, Locus.AGOUTI to 0.6f, Locus.GREY to 0.05f, Locus.MSTN to 0.35f),
        Morpho(neckLength = 1.05f, neckArch = 0.65f, mass = 1.04f, maneLength = 0.28f), nuance = 58f,
        description = "Cheval de sport allemand réputé pour ses allures amples, roi des rectangles de dressage."),
    LUSITANIEN("Lusitanien", "Portugal", false, 10000, 158f,
        intArrayOf(50, 56, 54, 56, 68, 64, 64, 66, 68, 74, 64, 60),
        mapOf(Locus.EXTENSION to 0.65f, Locus.AGOUTI to 0.5f, Locus.GREY to 0.42f, Locus.CREAM to 0.05f, Locus.DUN to 0.01f, Locus.MSTN to 0.4f),
        Morpho(bodyLength = 0.96f, legLength = 0.96f, neckArch = 0.85f, headProfile = 0.7f, mass = 1.08f, maneLength = 0.9f, tailSet = 0.35f), nuance = 55f,
        description = "Cheval ibérique au profil busqué, rassemblé et brave : tauromachie, dressage classique et équitation de travail."),
    FRISON("Frison", "Pays-Bas (Frise)", false, 11000, 162f,
        intArrayOf(40, 50, 44, 46, 62, 70, 62, 64, 52, 62, 46, 54),
        mapOf(Locus.EXTENSION to 0.96f, Locus.AGOUTI to 0.0f, Locus.MSTN to 0.4f),
        Morpho(bodyLength = 0.98f, legLength = 0.97f, neckLength = 1.04f, neckArch = 0.95f, headSize = 1.04f, mass = 1.15f, feather = 0.9f, maneLength = 1f, tailSet = 0.4f), whiteHead = 18f, whiteLegs = 15f, lifespan = 22f,
        description = "Le « cheval noir » de Frise : crins abondants, fanons, actions relevées. Très demandé à l'attelage et en spectacle."),
    QUARTER_HORSE("Quarter Horse", "États-Unis", false, 7000, 152f,
        intArrayOf(70, 56, 48, 50, 52, 72, 62, 76, 60, 72, 66, 62),
        mapOf(Locus.EXTENSION to 0.4f, Locus.AGOUTI to 0.55f, Locus.GREY to 0.06f, Locus.CREAM to 0.08f, Locus.DUN to 0.06f, Locus.ROAN to 0.05f, Locus.SILVER to 0.005f, Locus.CHAMPAGNE to 0.01f, Locus.MSTN to 0.85f),
        Morpho(bodyLength = 0.96f, legLength = 0.92f, neckLength = 0.9f, neckArch = 0.35f, headSize = 0.95f, mass = 1.12f, maneLength = 0.18f), whiteHead = 40f,
        description = "Le cheval du quart de mile : explosif, musclé et très calme. Roi de l'équitation western."),
    PAINT_HORSE("Paint Horse", "États-Unis", false, 7500, 153f,
        intArrayOf(66, 56, 48, 50, 52, 70, 60, 72, 60, 70, 64, 60),
        mapOf(Locus.EXTENSION to 0.45f, Locus.AGOUTI to 0.55f, Locus.TOBIANO to 0.35f, Locus.FRAME to 0.1f, Locus.SABINO to 0.05f, Locus.CREAM to 0.06f, Locus.DUN to 0.04f, Locus.MSTN to 0.8f),
        Morpho(bodyLength = 0.96f, legLength = 0.93f, neckLength = 0.92f, neckArch = 0.35f, mass = 1.1f, maneLength = 0.2f), whiteHead = 52f, whiteLegs = 48f,
        description = "Cheval de type Quarter à robe pie. Attention aux porteurs Frame overo en élevage."),
    APPALOOSA("Appaloosa", "États-Unis", false, 6500, 152f,
        intArrayOf(58, 66, 50, 50, 52, 62, 58, 66, 62, 64, 72, 60),
        mapOf(Locus.EXTENSION to 0.5f, Locus.AGOUTI to 0.55f, Locus.LEOPARD to 0.55f, Locus.PATN1 to 0.35f, Locus.CREAM to 0.03f, Locus.DUN to 0.03f, Locus.ROAN to 0.03f, Locus.MSTN to 0.6f),
        Morpho(bodyLength = 0.97f, legLength = 0.96f, neckArch = 0.35f, mass = 1.02f, maneLength = 0.15f, tailSet = 0.4f),
        description = "Le cheval tacheté des Nez-Percés : robes léopard et à couverture, sclérotique blanche et sabots striés."),
    CONNEMARA("Poney Connemara", "Irlande", true, 5000, 144f,
        intArrayOf(50, 64, 66, 60, 58, 58, 60, 66, 66, 66, 80, 64),
        mapOf(Locus.EXTENSION to 0.6f, Locus.AGOUTI to 0.6f, Locus.GREY to 0.42f, Locus.DUN to 0.12f, Locus.CREAM to 0.05f, Locus.MSTN to 0.35f),
        Morpho(bodyLength = 0.96f, legLength = 0.9f, neckLength = 0.92f, headSize = 1.04f, mass = 1.05f, maneLength = 0.55f, earSize = 0.9f), lifespan = 31f,
        description = "Poney irlandais rustique et sauteur, idéal pour les jeunes cavaliers et le complet."),
    HAFLINGER("Haflinger", "Autriche (Tyrol)", true, 4500, 145f,
        intArrayOf(42, 62, 44, 46, 54, 66, 58, 80, 56, 66, 78, 64),
        mapOf(Locus.EXTENSION to 0f, Locus.AGOUTI to 0.5f, Locus.MSTN to 0.35f),
        Morpho(bodyLength = 0.97f, legLength = 0.88f, neckLength = 0.9f, headSize = 1.06f, mass = 1.12f, maneLength = 0.75f, earSize = 0.92f), nuance = 42f, flaxen = 95f, whiteHead = 36f, whiteLegs = 20f, lifespan = 31f,
        description = "Petit cheval de montagne alezan aux crins lavés : costaud, sûr et très gentil."),
    PERCHERON("Percheron", "France (Perche)", false, 4500, 168f,
        intArrayOf(32, 54, 34, 40, 44, 94, 58, 74, 54, 58, 66, 60),
        mapOf(Locus.EXTENSION to 0.95f, Locus.AGOUTI to 0.12f, Locus.GREY to 0.6f, Locus.MSTN to 0.25f),
        Morpho(bodyLength = 1.05f, legLength = 0.9f, neckLength = 0.9f, neckArch = 0.6f, headSize = 1.08f, headProfile = 0.25f, mass = 1.45f, feather = 0.25f, maneLength = 0.6f, tailSet = 0.4f), whiteHead = 25f, whiteLegs = 20f, lifespan = 25f,
        description = "Le trait français élégant, gris ou noir : force tranquille de l'attelage et du débardage."),
    TROTTEUR("Trotteur Français", "France", false, 5000, 162f,
        intArrayOf(72, 72, 50, 50, 56, 60, 56, 56, 66, 58, 66, 58),
        mapOf(Locus.EXTENSION to 0.6f, Locus.AGOUTI to 0.68f, Locus.MSTN to 0.4f),
        Morpho(bodyLength = 1.03f, legLength = 1.02f, neckArch = 0.35f, headSize = 1.02f, mass = 0.95f, maneLength = 0.3f), nuance = 60f,
        description = "Le cheval des courses au trot (Vincennes) : allant, endurant, souvent reconverti en selle."),
    ISLANDAIS("Cheval islandais", "Islande", true, 6000, 136f,
        intArrayOf(50, 72, 40, 46, 64, 62, 56, 68, 64, 64, 90, 66),
        mapOf(Locus.EXTENSION to 0.5f, Locus.AGOUTI to 0.5f, Locus.GREY to 0.08f, Locus.CREAM to 0.08f, Locus.DUN to 0.15f, Locus.SILVER to 0.12f, Locus.ROAN to 0.04f, Locus.TOBIANO to 0.04f, Locus.MSTN to 0.4f),
        Morpho(bodyLength = 1f, legLength = 0.84f, neckLength = 0.9f, headSize = 1.08f, mass = 1.08f, maneLength = 0.95f, earSize = 0.88f), lifespan = 32f,
        description = "Petit cheval des sagas, à cinq allures (dont le tölt) et toutes les robes possibles."),
    CROISE("Origine non constatée", "—", false, 3500, 160f,
        intArrayOf(54, 58, 54, 52, 52, 60, 50, 62, 58, 58, 64, 60),
        mapOf(Locus.EXTENSION to 0.55f, Locus.AGOUTI to 0.55f, Locus.GREY to 0.1f, Locus.CREAM to 0.04f, Locus.DUN to 0.03f, Locus.TOBIANO to 0.06f, Locus.ROAN to 0.02f, Locus.MSTN to 0.45f),
        Morpho(), description = "Cheval sans papiers d'origine : souvent un excellent compagnon de loisir.");

    fun mean(t: Trait): Float = when (t) {
        Trait.TAILLE -> height
        Trait.NUANCE -> nuance
        Trait.CRINS_LAVES -> flaxen
        Trait.BLANC_TETE -> whiteHead
        Trait.BLANC_MEMBRES -> whiteLegs
        else -> traits[t.ordinal - 1].toFloat()
    }

    fun freq(l: Locus): Float = freqs[l] ?: 0f

    companion object {
        /** Races proposées sur le marché (le croisé est fréquent aussi). */
        val MARKET = values().toList()

        /** Race du produit : même race, ou règles d'admission des stud-books (ex. le SF accepte PS et AA). */
        fun offspring(sire: Breed, dam: Breed): Breed = when {
            sire == dam -> sire
            dam == SELLE_FRANCAIS && sire in setOf(PUR_SANG, ARABE, KWPN, HANOVRIEN) -> SELLE_FRANCAIS
            sire == SELLE_FRANCAIS && dam in setOf(PUR_SANG, ARABE) -> SELLE_FRANCAIS
            setOf(sire, dam) == setOf(QUARTER_HORSE, PAINT_HORSE) -> PAINT_HORSE
            sire == PUR_SANG && dam == QUARTER_HORSE -> QUARTER_HORSE
            else -> CROISE
        }

        /** Morphologie d'un croisé : moyenne de ses parents (approximée par ses caractères). */
        fun blendMorpho(a: Morpho, b: Morpho) = Morpho(
            (a.bodyLength + b.bodyLength) / 2, (a.legLength + b.legLength) / 2, (a.neckLength + b.neckLength) / 2,
            (a.neckArch + b.neckArch) / 2, (a.headSize + b.headSize) / 2, (a.headProfile + b.headProfile) / 2,
            (a.mass + b.mass) / 2, (a.feather + b.feather) / 2, (a.maneLength + b.maneLength) / 2,
            (a.tailSet + b.tailSet) / 2, (a.earSize + b.earSize) / 2,
        )
    }
}
