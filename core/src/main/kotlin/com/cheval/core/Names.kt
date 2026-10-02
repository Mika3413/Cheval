package com.cheval.core

object Names {
    /** Lettre de l'année de naissance (SIRE/IFCE) : cycle de 20 lettres sans K, Q, W, X, Y, Z ; 2010 = A. */
    private const val LETTERS = "ABCDEFGHIJLMNOPRSTUV"
    fun yearLetter(year: Int): Char = LETTERS[Math.floorMod(year - 2010, LETTERS.length)]

    private val ROOTS = mapOf(
        'A' to listOf("Altesse", "Apollon", "Azur", "Arpège", "Aquila", "Ambre", "Atlas", "Aube"),
        'B' to listOf("Baladin", "Bijou", "Bohème", "Brise", "Baccara", "Bucéphale", "Belle Étoile", "Boréal"),
        'C' to listOf("Calypso", "Cassis", "Comète", "Corsaire", "Cyrano", "Célestine", "Cobalt", "Capucine"),
        'D' to listOf("Diablotin", "Dune", "Domino", "Danaé", "Duc", "Dentelle", "Diamant", "Delta"),
        'E' to listOf("Éclipse", "Écho", "Elfe", "Embrun", "Ébène", "Étincelle", "Eros", "Escale"),
        'F' to listOf("Faucon", "Flamme", "Folie", "Fidji", "Farandole", "Fleur de Sel", "Fougueux", "Falaise"),
        'G' to listOf("Galopin", "Gitane", "Grand Cru", "Gaïa", "Gavroche", "Granit", "Grenade", "Glycine"),
        'H' to listOf("Hermès", "Harmonie", "Hidalgo", "Houle", "Hélios", "Havane", "Hirondelle", "Hussard"),
        'I' to listOf("Iris", "Ivoire", "Icare", "Ingrid", "Indigo", "Isba", "Impérial", "Ilona"),
        'J' to listOf("Jasmin", "Jolie Cœur", "Jupiter", "Java", "Joker", "Jade", "Jazz", "Junon"),
        'L' to listOf("Lune", "Lancelot", "Lagune", "Lys", "Lascar", "Lumière", "Loustic", "Libellule"),
        'M' to listOf("Mistral", "Mirabelle", "Magnum", "Marée", "Mélodie", "Merlin", "Mousson", "Myrtille"),
        'N' to listOf("Nougat", "Nymphe", "Narval", "Neige", "Nectar", "Nomade", "Nausicaa", "Nuage"),
        'O' to listOf("Onyx", "Opale", "Orage", "Odyssée", "Ouragan", "Olympe", "Osmose", "Oural"),
        'P' to listOf("Perle", "Pacha", "Prélude", "Pirate", "Praline", "Phénix", "Paprika", "Pégase"),
        'R' to listOf("Rafale", "Rubis", "Rêveur", "Roseau", "Ricochet", "Reine", "Rodéo", "Rivage"),
        'S' to listOf("Sirocco", "Saphir", "Sirène", "Soleil", "Sésame", "Symphonie", "Samouraï", "Sable"),
        'T' to listOf("Tempête", "Topaze", "Tornade", "Talisman", "Tulipe", "Titan", "Tsar", "Tamaris"),
        'U' to listOf("Ultime", "Ulysse", "Uranie", "Utopie", "Ubac", "Unique", "Uriel", "Ukulélé"),
        'V' to listOf("Vagabond", "Vanille", "Velours", "Vent du Large", "Vulcain", "Valse", "Viking", "Victoire"),
    )
    private val SUFFIXES = listOf("du Val", "de l'Aube", "des Prés", "du Bois", "des Dunes", "de la Lande", "du Moulin", "des Forges",
        "du Rouet", "de Kerguelen", "des Hauts", "du Manoir", "de la Mare", "du Cèdre", "des Sources", "de Bréville", "du Lys", "d'Ivry")
    private val EN_NAMES = listOf("Silver Arrow", "Night Fury", "Golden Sky", "Royal Flush", "Lucky Star", "Desert Rose", "Thunderbolt",
        "Sea Breeze", "Kings Ransom", "Wild Card", "Midnight Run", "Summer Song", "Dancing Brave", "Iron Duke", "Blue Moon", "Fire Dance")
    private val AR_NAMES = listOf("Al Sahra", "Shaheen", "Nadir", "Zahra", "Kalil", "Amira", "Rihan", "Farah", "Najm", "Samira", "Tariq", "Yasmina")

    fun horseName(rng: Rng, birthYear: Int, breed: Breed, affix: String? = null): String {
        val letter = yearLetter(birthYear)
        val base = when (breed) {
            Breed.PUR_SANG, Breed.QUARTER_HORSE, Breed.PAINT_HORSE, Breed.APPALOOSA ->
                EN_NAMES.filter { it.first() == letter }.ifEmpty { listOf(rng.pick(EN_NAMES)) }.let { rng.pick(it) }
            Breed.ARABE -> AR_NAMES.filter { it.first() == letter }.ifEmpty { listOf(rng.pick(AR_NAMES)) }.let { rng.pick(it) }
            else -> rng.pick(ROOTS[letter] ?: ROOTS['A']!!)
        }
        val needsLetter = breed in setOf(Breed.SELLE_FRANCAIS, Breed.TROTTEUR, Breed.CONNEMARA, Breed.PERCHERON, Breed.CROISE)
        val first = if (needsLetter && base.first() != letter) rng.pick(ROOTS[letter]!!) else base
        return when {
            affix != null -> "$first $affix"
            breed == Breed.PUR_SANG || breed == Breed.ARABE -> first
            rng.chance(0.75f) -> "$first ${rng.pick(SUFFIXES)}"
            else -> first
        }
    }

    private val FIRST = listOf("Camille", "Léa", "Hugo", "Inès", "Lucas", "Manon", "Théo", "Chloé", "Louis", "Zoé", "Jules", "Clara", "Yanis",
        "Margaux", "Pierre", "Anaïs", "Nathan", "Élise", "Maël", "Sarah", "Baptiste", "Juliette", "Antoine", "Lou", "Mathis", "Nina")
    private val LAST = listOf("Martin", "Durand", "Lefèvre", "Moreau", "Laurent", "Garnier", "Rousseau", "Fontaine", "Chevalier", "Robin",
        "Mercier", "Blanchard", "Guérin", "Boyer", "Lemoine", "Dupuis", "Perrin", "Marchand", "Carpentier", "Delaunay")
    fun person(rng: Rng) = "${rng.pick(FIRST)} ${rng.pick(LAST)}"

    val VENUES = listOf(
        "Deauville", "Fontainebleau", "Saumur", "Lamotte-Beuvron", "Chantilly", "La Baule", "Pau", "Vichy", "Compiègne",
        "Le Pin-au-Haras", "Lanaken", "Dinard", "Cabourg", "Lisieux", "Saint-Lô", "Caen", "Le Mans", "Rambouillet", "Le Touquet",
        "Granville", "Cagnes-sur-Mer", "Auteuil", "Longchamp", "Bordeaux", "Lyon", "Avenches", "Haras du Pin", "Pompadour",
    )
    val RACECOURSES = listOf("Deauville", "Chantilly", "Longchamp", "Saint-Cloud", "Cabourg", "Clairefontaine", "Lyon-Parilly", "Pau", "Compiègne", "Le Lion-d'Angers")

    private val STALLION_PREFIX = listOf("Quintal", "Kerbrat", "Diamond Hill", "Corvette", "Mylan", "Balthazar", "Jaguar", "Darwin", "Lord Tasman", "Nabucco",
        "Gaspard", "Ultramarin", "Rocamadour", "Tobago", "Kingsley", "Santorin", "Zénith", "Dakota", "Gentilhomme", "Orion")
    fun stallionName(rng: Rng, breed: Breed, year: Int): String =
        if (rng.chance(0.5f)) horseName(rng, year, breed) else "${rng.pick(STALLION_PREFIX)} ${listOf("du Verger", "de Kerhoas", "des Isles", "d'Orne", "Star", "Z", "de la Pommeraie", "du Lys", "II", "Royal").let { rng.pick(it) }}"
}
