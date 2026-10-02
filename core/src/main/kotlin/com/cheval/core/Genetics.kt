package com.cheval.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Locus de couleur de robe (génétique mendélienne réelle du cheval).
 * L'allèle 1 est l'allèle « variant » noté [variant], l'allèle 0 l'allèle [wild].
 */
enum class Locus(val label: String, val variant: String, val wild: String) {
    EXTENSION("Extension (MC1R)", "E", "e"),
    AGOUTI("Agouti (ASIP)", "A", "a"),
    CREAM("Crème (SLC45A2)", "Cr", "n"),
    DUN("Dun (TBX3)", "D", "d"),
    GREY("Gris (STX17)", "G", "n"),
    SILVER("Silver (PMEL17)", "Z", "n"),
    CHAMPAGNE("Champagne (SLC36A1)", "Ch", "n"),
    TOBIANO("Tobiano (KIT)", "To", "n"),
    FRAME("Frame overo (EDNRB)", "O", "n"),
    SABINO("Sabino 1 (KIT)", "Sb1", "n"),
    LEOPARD("Léopard (TRPM1)", "Lp", "n"),
    PATN1("Pattern 1 (RFWD3)", "Patn1", "n"),
    ROAN("Rouan (KIT)", "Rn", "n"),
    MSTN("Myostatine « gène de la vitesse »", "C", "T");

    /** Notation conventionnelle : EE / Ee / ee, n/Cr, Cr/Cr, C/C, C/T... */
    fun notation(count: Int): String = when (this) {
        EXTENSION, AGOUTI -> when (count) { 2 -> "$variant$variant"; 1 -> "$variant$wild"; else -> "$wild$wild" }
        MSTN -> when (count) { 2 -> "C/C"; 1 -> "C/T"; else -> "T/T" }
        else -> when (count) { 2 -> "$variant/$variant"; 1 -> "$wild/$variant"; else -> "$wild/$wild" }
    }
}

/** Caractères quantitatifs (polygéniques) avec leur héritabilité h² et leur écart-type phénotypique. */
enum class Trait(val label: String, val h2: Float, val sd: Float, val short: String) {
    TAILLE("Taille au garrot", 0.6f, 4.5f, "Taille"),
    VITESSE("Vitesse", 0.35f, 11f, "Vit."),
    ENDURANCE("Endurance", 0.3f, 11f, "End."),
    SAUT("Moyens à l'obstacle", 0.4f, 11f, "Saut"),
    TECHNIQUE("Respect des barres", 0.3f, 11f, "Tech."),
    ALLURES("Qualité des allures", 0.4f, 11f, "Allures"),
    FORCE("Force", 0.4f, 11f, "Force"),
    MODELE("Modèle", 0.3f, 10f, "Modèle"),
    CALME("Calme", 0.25f, 12f, "Calme"),
    COURAGE("Courage", 0.25f, 12f, "Courage"),
    APPRENTISSAGE("Facilité d'apprentissage", 0.2f, 12f, "Appr."),
    ROBUSTESSE("Robustesse", 0.15f, 12f, "Robust."),
    FERTILITE("Fertilité", 0.1f, 10f, "Fertil."),
    // Caractères d'apparence polygéniques
    NUANCE("Nuance de robe", 0.5f, 18f, "Nuance"),
    CRINS_LAVES("Crins lavés", 0.5f, 20f, "Crins"),
    BLANC_TETE("Blanc en tête", 0.6f, 20f, "Tête"),
    BLANC_MEMBRES("Balzanes", 0.6f, 20f, "Balz.");

    val va get() = h2 * sd * sd
    val ve get() = (1 - h2) * sd * sd

    companion object {
        /** Caractères sportifs/utilitaires affichés sur la fiche. */
        val SPORT = listOf(VITESSE, ENDURANCE, SAUT, TECHNIQUE, ALLURES, FORCE, MODELE, CALME, COURAGE, APPRENTISSAGE, ROBUSTESSE)
        /** Caractères pénalisés par la consanguinité (dépression de consanguinité). */
        val FITNESS = setOf(ENDURANCE, ROBUSTESSE, FERTILITE, VITESSE, FORCE)
    }
}

/**
 * Génome d'un cheval : allèles de robe ([alleles], 2 par locus), valeurs génétiques additives
 * des caractères quantitatifs ([breeding]) et part environnementale propre à l'individu ([env]).
 */
class Genome(
    val alleles: IntArray = IntArray(Locus.values().size * 2),
    val breeding: FloatArray = FloatArray(Trait.values().size),
    val env: FloatArray = FloatArray(Trait.values().size),
    /** Graine pour la disposition des taches et marques (propre à l'individu). */
    val patternSeed: Long = 0L,
) {
    fun count(l: Locus): Int = alleles[l.ordinal * 2] + alleles[l.ordinal * 2 + 1]
    fun has(l: Locus) = count(l) > 0
    fun notation(l: Locus) = l.notation(count(l))

    /** Valeur exprimée (potentiel) du caractère, avant la dépression de consanguinité. */
    fun value(t: Trait): Float = breeding[t.ordinal] + env[t.ordinal]

    fun genotypeString(): String = listOf(Locus.EXTENSION, Locus.AGOUTI, Locus.CREAM, Locus.DUN, Locus.GREY, Locus.SILVER,
        Locus.CHAMPAGNE, Locus.TOBIANO, Locus.FRAME, Locus.SABINO, Locus.LEOPARD, Locus.PATN1, Locus.ROAN)
        .filter { it == Locus.EXTENSION || it == Locus.AGOUTI || has(it) }
        .joinToString(" ") { notation(it) }

    /** Le poulain est-il atteint d'une anomalie génétique létale ? (syndrome du poulain blanc létal : O/O) */
    val lethal get() = count(Locus.FRAME) == 2

    fun toJson(): Map<String, Any?> = mapOf("a" to alleles.toList(), "b" to breeding.toList(), "e" to env.toList(), "s" to patternSeed.toString())

    companion object {
        fun fromJson(o: JObj): Genome {
            val a = o.ints("a"); val b = o.floats("b"); val e = o.floats("e")
            val g = Genome(IntArray(Locus.values().size * 2), FloatArray(Trait.values().size), FloatArray(Trait.values().size), o.long("s"))
            a.copyInto(g.alleles, 0, 0, min(a.size, g.alleles.size))
            b.copyInto(g.breeding, 0, 0, min(b.size, g.breeding.size))
            e.copyInto(g.env, 0, 0, min(e.size, g.env.size))
            return g
        }

        /** Génome d'un cheval de race tiré dans la population (fréquences alléliques et moyennes de la race). */
        fun random(breed: Breed, rng: Rng): Genome {
            val g = Genome(patternSeed = rng.nextLong())
            for (l in Locus.values()) {
                val p = breed.freq(l)
                g.alleles[l.ordinal * 2] = if (rng.chance(p)) 1 else 0
                g.alleles[l.ordinal * 2 + 1] = if (rng.chance(p)) 1 else 0
            }
            // Un cheval adulte O/O n'existe pas (létal) : on le ramène à n/O.
            if (g.count(Locus.FRAME) == 2) g.alleles[Locus.FRAME.ordinal * 2] = 0
            for (t in Trait.values()) {
                g.breeding[t.ordinal] = (breed.mean(t) + rng.gauss(0.0, sqrt(t.va.toDouble()))).toFloat()
                g.env[t.ordinal] = rng.gauss(0.0, sqrt(t.ve.toDouble())).toFloat()
            }
            return g
        }

        /**
         * Croisement : chaque parent transmet un allèle tiré au hasard par locus (méiose),
         * et la valeur génétique du produit est la moyenne des parents plus l'aléa mendélien,
         * réduit par la consanguinité des parents.
         */
        fun cross(sire: Genome, dam: Genome, sireF: Float, damF: Float, rng: Rng): Genome {
            val g = Genome(patternSeed = rng.nextLong())
            for (l in Locus.values()) {
                g.alleles[l.ordinal * 2] = sire.alleles[l.ordinal * 2 + rng.int(2)]
                g.alleles[l.ordinal * 2 + 1] = dam.alleles[l.ordinal * 2 + rng.int(2)]
            }
            val fParents = (sireF + damF) / 2f
            for (t in Trait.values()) {
                val mid = (sire.breeding[t.ordinal] + dam.breeding[t.ordinal]) / 2f
                val mendel = rng.gauss(0.0, sqrt(t.va / 2.0 * (1.0 - fParents))).toFloat()
                g.breeding[t.ordinal] = mid + mendel
                g.env[t.ordinal] = rng.gauss(0.0, sqrt(t.ve.toDouble())).toFloat()
            }
            return g
        }
    }
}

/** Couleurs et motifs calculés à partir du génotype, utilisés par le rendu et pour nommer la robe. */
class CoatLook(
    val body: Int,
    val points: Int,      // bas des membres
    val mane: Int,        // crinière et queue
    val skin: Int,        // peau du bout du nez
    val hoof: Int,
    val blueEyes: Boolean,
    val greyLevel: Float, // 0 = aucun gris, 1 = blanc
    val dapples: Float,   // pommelures
    val roan: Boolean,
    val dunStripe: Int,   // 0 = aucune raie de mulet, sinon couleur
    val tobiano: Boolean,
    val frame: Boolean,
    val sabino: Int,      // 0, 1 (hétérozygote), 2 (quasi blanc)
    val leopard: Int,     // 0 aucun, 1 couverture, 2 léopard, 3 peu de taches, 4 couverture neigeuse
    val faceWhite: Int,   // 0 rien, 1 étoile, 2 liste, 3 belle face
    val legWhite: IntArray, // par membre (AG, AD, PG, PD) : 0..4
    val shine: Float,     // 0..1 brillance du poil
    val seed: Long,
)

object Coat {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return rgb((r(a) + (r(b) - r(a)) * k).toInt(), (g(a) + (g(b) - g(a)) * k).toInt(), (Coat.b(a) + (Coat.b(b) - Coat.b(a)) * k).toInt())
    }

    private val BLACK_POINTS = rgb(28, 24, 23)
    private val WHITE = rgb(240, 238, 232)

    /** Niveau de grisonnement selon l'âge : les gris naissent foncés et blanchissent en 6 à 10 ans. */
    fun greyLevel(g: Genome, age: Float): Float {
        if (!g.has(Locus.GREY)) return 0f
        val speed = if (g.count(Locus.GREY) == 2) 1.5f else 1f
        return ((age * speed - 0.4f) / 7.5f).coerceIn(0f, 1f)
    }

    fun look(g: Genome, age: Float, shine: Float = 0.6f): CoatLook {
        val shade = ((g.value(Trait.NUANCE) - 50f) / 30f).coerceIn(-1f, 1f) // -1 clair, +1 foncé/charbonné
        val black = g.count(Locus.EXTENSION) > 0 && !g.has(Locus.AGOUTI)
        val chestnut = g.count(Locus.EXTENSION) == 0
        var body: Int
        var points: Int
        var mane: Int
        when {
            chestnut -> {
                body = if (shade < 0) mix(rgb(166, 86, 40), rgb(204, 120, 62), -shade) else mix(rgb(166, 86, 40), rgb(92, 46, 26), shade)
                points = body
                val flaxen = ((g.value(Trait.CRINS_LAVES) - 50f) / 25f).coerceIn(0f, 1f)
                mane = mix(mix(body, rgb(120, 60, 30), 0.25f), rgb(236, 214, 170), flaxen)
            }
            black -> { body = mix(rgb(36, 31, 30), rgb(58, 46, 40), (-shade).coerceAtLeast(0f) * 0.6f); points = body; mane = rgb(26, 23, 22) }
            else -> {
                body = if (shade < 0) mix(rgb(146, 80, 40), rgb(182, 114, 62), -shade) else mix(rgb(146, 80, 40), rgb(70, 40, 26), shade)
                points = BLACK_POINTS; mane = BLACK_POINTS
            }
        }
        var skin = rgb(40, 36, 36)
        var blue = false
        var hoof = rgb(52, 46, 42)
        val cr = g.count(Locus.CREAM)
        if (cr == 1) {
            when {
                chestnut -> { body = mix(rgb(226, 182, 104), rgb(196, 146, 72), (shade + 1) / 2); mane = rgb(244, 232, 204) }
                black -> body = mix(body, rgb(70, 56, 46), 0.6f)
                else -> body = mix(rgb(222, 184, 116), rgb(170, 126, 70), (shade + 1) / 2)
            }
        } else if (cr == 2) {
            body = if (chestnut) rgb(242, 230, 204) else rgb(236, 220, 190)
            points = if (chestnut) body else rgb(222, 198, 162)
            mane = if (chestnut) rgb(246, 238, 220) else rgb(226, 206, 172)
            skin = rgb(226, 160, 150); blue = true; hoof = rgb(210, 190, 160)
        }
        if (g.has(Locus.CHAMPAGNE)) {
            body = when { chestnut -> rgb(224, 184, 114); black -> rgb(134, 112, 96); else -> rgb(204, 160, 100) }
            if (!chestnut) points = mix(points, rgb(120, 92, 66), 0.75f)
            if (black) mane = rgb(110, 90, 76)
            skin = rgb(190, 140, 130); hoof = rgb(150, 120, 96)
        }
        if (g.has(Locus.SILVER) && !chestnut) {
            if (black) body = rgb(92, 62, 46)
            points = mix(points, rgb(100, 70, 52), 0.65f)
            mane = rgb(222, 214, 200)
        }
        var stripe = 0
        if (g.has(Locus.DUN)) {
            stripe = mix(points, rgb(40, 30, 24), 0.4f)
            body = when {
                chestnut -> { stripe = rgb(150, 82, 48); mix(body, rgb(222, 178, 136), 0.55f) }
                black -> rgb(126, 118, 108)
                else -> mix(body, rgb(214, 182, 128), 0.55f)
            }
        }
        if (chestnut) points = body
        val grey = greyLevel(g, age)
        val dapples = if (grey in 0.15f..0.75f) 1f - kotlin.math.abs(grey - 0.45f) / 0.3f else 0f
        if (grey > 0f) {
            body = mix(body, WHITE, grey)
            points = mix(points, rgb(110, 108, 106), grey * 0.85f)
            mane = mix(mane, rgb(226, 224, 220), grey * 0.95f)
        }
        // Marques blanches : polygéniques, fortement renforcées par sabino et le complexe pie.
        val sb = g.count(Locus.SABINO)
        var face = ((g.value(Trait.BLANC_TETE) - 38f) / 16f).toInt().coerceIn(0, 3)
        var legBase = (g.value(Trait.BLANC_MEMBRES) - 40f) / 14f
        if (sb == 1) { face = max(face, 2); legBase += 2f }
        if (g.has(Locus.FRAME)) face = max(face, 2)
        if (g.has(Locus.TOBIANO)) legBase += 2.2f
        val rr = Rng(g.patternSeed xor 0x5EED)
        val legs = IntArray(4) { i -> (legBase + (if (i >= 2) 0.6f else 0f) + rr.range(-1.2f, 1.2f)).toInt().coerceIn(0, 4) }
        val leo = when {
            !g.has(Locus.LEOPARD) -> 0
            g.has(Locus.PATN1) -> if (g.count(Locus.LEOPARD) == 2) 3 else 2
            else -> if (g.count(Locus.LEOPARD) == 2) 4 else 1
        }
        if (sb == 2) face = 3
        return CoatLook(
            body = body, points = points, mane = mane, skin = if (face >= 3 || sb == 2) rgb(222, 164, 156) else skin,
            hoof = if (legs.any { it >= 2 }) hoof else hoof, blueEyes = blue || (g.has(Locus.FRAME) && face == 3 && rr.chance(0.4f)) || sb == 2 && rr.chance(0.5f),
            greyLevel = grey, dapples = dapples, roan = g.has(Locus.ROAN), dunStripe = stripe,
            tobiano = g.has(Locus.TOBIANO), frame = g.has(Locus.FRAME), sabino = sb, leopard = leo,
            faceWhite = face, legWhite = legs, shine = shine, seed = g.patternSeed,
        )
    }

    /** Nom français de la robe, tel qu'il figurerait sur un livret SIRE. */
    fun name(g: Genome, age: Float): String {
        val chestnut = g.count(Locus.EXTENSION) == 0
        val black = !chestnut && !g.has(Locus.AGOUTI)
        val shade = g.value(Trait.NUANCE)
        val cr = g.count(Locus.CREAM)
        var base = when {
            cr == 2 -> if (chestnut) "crème (cremello)" else if (black) "crème (smoky cream)" else "crème (perlino)"
            g.has(Locus.CHAMPAGNE) -> if (chestnut) "champagne doré" else if (black) "champagne classique" else "champagne ambré"
            g.has(Locus.DUN) && chestnut -> "alezan dun (louvet)"
            g.has(Locus.DUN) && black -> "souris"
            g.has(Locus.DUN) -> if (cr == 1) "isabelle dun" else "bai dun"
            cr == 1 -> if (chestnut) "palomino" else if (black) "noir fumé" else "isabelle"
            g.has(Locus.SILVER) && black -> "noir silver (chocolat)"
            g.has(Locus.SILVER) && !chestnut -> "bai silver"
            chestnut -> when { shade > 68 -> "alezan brûlé"; shade < 34 -> "alezan clair"; else -> if (g.value(Trait.CRINS_LAVES) > 70) "alezan crins lavés" else "alezan" }
            black -> "noir"
            else -> when { shade > 68 -> "bai brun"; shade < 34 -> "bai clair"; else -> "bai" }
        }
        if (g.has(Locus.ROAN)) base = when { chestnut && cr == 0 -> "aubère"; black && cr == 0 -> "rouan bleu"; else -> "rouan ($base)" }
        if (g.has(Locus.GREY)) {
            val gl = greyLevel(g, age)
            base = when {
                gl < 0.15f -> "gris (né $base)"
                gl < 0.6f -> "gris pommelé"
                gl < 0.9f -> "gris clair"
                else -> if (age > 12) "gris truité" else "gris blanc"
            }
        }
        val leo = when {
            !g.has(Locus.LEOPARD) -> null
            g.has(Locus.PATN1) -> if (g.count(Locus.LEOPARD) == 2) "appaloosa peu tacheté" else "appaloosa léopard"
            else -> if (g.count(Locus.LEOPARD) == 2) "appaloosa à couverture neigeuse" else "appaloosa à couverture"
        }
        val pie = when {
            g.count(Locus.SABINO) == 2 -> "blanc (sabino)"
            g.has(Locus.TOBIANO) && g.has(Locus.FRAME) -> "pie tovero"
            g.has(Locus.TOBIANO) -> "pie tobiano"
            g.has(Locus.FRAME) -> "pie overo"
            g.has(Locus.SABINO) -> "sabino"
            else -> null
        }
        return listOfNotNull(base, pie, leo).joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    /** Remarques de santé liées aux gènes de robe (connues en élevage). */
    fun geneticNotes(g: Genome): List<String> {
        val n = ArrayList<String>()
        if (g.lethal) n += "O/O : syndrome du poulain blanc létal"
        else if (g.has(Locus.FRAME)) n += "Porteur Frame overo : ne jamais croiser avec un autre porteur (25 % de poulains O/O non viables)"
        if (g.count(Locus.LEOPARD) == 2) n += "Lp/Lp : cécité nocturne congénitale (CSNB)"
        if (g.has(Locus.GREY)) n += "Gris : risque accru de mélanomes avec l'âge"
        if (g.has(Locus.SILVER)) n += "Silver : surveiller les anomalies oculaires (MCOA)"
        if (g.count(Locus.CREAM) == 2) n += "Double dilution : peau rose sensible au soleil"
        if (g.count(Locus.SABINO) >= 1 || g.has(Locus.TOBIANO)) n += "Peau dépigmentée sous le blanc : crème solaire en été"
        return n
    }
}
