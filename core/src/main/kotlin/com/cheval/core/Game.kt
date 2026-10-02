package com.cheval.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class Res(val ok: Boolean, val msg: String) {
    companion object {
        fun ok(m: String) = Res(true, m)
        fun no(m: String) = Res(false, m)
    }
}

class Listing(val horseId: Int, var price: Int, val seller: String, val expires: Int) {
    fun toJson() = mapOf("h" to horseId, "p" to price, "s" to seller, "e" to expires)
    companion object { fun fromJson(o: JObj) = Listing(o.int("h"), o.int("p"), o.str("s"), o.int("e")) }
}

class Offer(val horseId: Int, val buyer: String, val amount: Int, val expires: Int) {
    fun toJson() = mapOf("h" to horseId, "b" to buyer, "a" to amount, "e" to expires)
    companion object { fun fromJson(o: JObj) = Offer(o.int("h"), o.str("b"), o.int("a"), o.int("e")) }
}

/**
 * Partie complète : le domaine, ses chevaux et le monde autour (marché, étalons, concours, météo).
 * Le temps avance heure par heure ; toutes les règles sont ici, l'application ne fait qu'afficher et appeler les actions.
 */
class Game(seed: Long, var stableName: String = "Haras de la Baie") {
    var rng = Rng(seed)
    /** Temps écoulé en heures depuis le 1er mars, 0 h, de la première année. */
    var time = 7.0
    val hour get() = time.toInt()
    val day get() = (time / 24.0).toInt()
    val hourOfDay get() = (time - day * 24.0).toFloat()

    var money = 30000
    var reputation = 10f
    val horses = LinkedHashMap<Int, Horse>()
    val market = ArrayList<Listing>()
    val studs = ArrayList<Int>()
    val levels = IntArray(BuildingType.values().size)
    val staff = ArrayList<Staff>()
    val candidates = ArrayList<Staff>()
    val stock = FloatArray(Item.values().size)
    val events = ArrayList<CompEvent>()
    val messages = ArrayList<Msg>()
    val ledger = ArrayList<Txn>()
    val offers = ArrayList<Offer>()
    var weather = Weather()
    var grass = 70f
    var rider = Rider()
    var boarders = 0
    var nextId = 1
    var generatedUntil = -1
    var wins = 0
    var unread = 0
    /** Compteurs pour les objectifs, objectifs réclamés, déchets restants, cours donnés aujourd'hui. */
    val stats = HashMap<String, Int>()
    val goalsDone = HashSet<String>()
    val junk = ArrayList<Junk>()
    var lessonsToday = 0
    fun stat(k: String, n: Int = 1) { stats[k] = (stats[k] ?: 0) + n }

    /** Concours du jour où le joueur doit monter en direct : (eventId, horseId). */
    val pendingLive = ArrayList<Pair<Int, Int>>()

    val affix get() = stableName.removePrefix("Haras ").removePrefix("Écurie ").removePrefix("Élevage ").let { if (it.startsWith("de ") || it.startsWith("du ") || it.startsWith("des ") || it.startsWith("d'")) it else "de $it" }

    fun owned(): List<Horse> = horses.values.filter { it.owned && it.alive }
    fun level(b: BuildingType) = levels[b.ordinal]
    /** Niveau 0 : le vieil abri de 2 boxes trouvé à l'arrivée. */
    fun boxes() = if (level(BuildingType.ECURIE) == 0) 2 else level(BuildingType.ECURIE) * 6
    fun hectares() = level(BuildingType.PRE) * 4
    fun stockCap(): Float = 4000f + level(BuildingType.GRENIER) * 6000f
    fun staffOf(r: Role) = staff.filter { it.role == r }
    fun horse(id: Int) = horses[id]
    fun count(i: Item) = stock[i.ordinal]

    /** Chevaux qui occupent un box (les poulains non sevrés partagent celui de leur mère). */
    fun boxUsers() = owned().count { it.weaned } + boarders

    // ===================================================================== Création

    /**
     * Nouvelle partie : un domaine à l'abandon racheté pour une bouchée de pain. Une caravane pour dormir,
     * un vieil abri de deux boxes, une prairie envahie de déchets… et tout à reconstruire.
     */
    fun newGameSetup(withStaff: Boolean = false) {
        money = 6000
        levels[BuildingType.ECURIE.ordinal] = 0
        levels[BuildingType.PRE.ordinal] = 1
        levels[BuildingType.GRENIER.ordinal] = 1
        stock[Item.FOIN.ordinal] = 900f
        stock[Item.GRANULES.ordinal] = 80f
        stock[Item.PAILLE.ordinal] = 20f
        stock[Item.CAROTTES.ordinal] = 5f
        stock[Item.MINERAUX.ordinal] = 30f
        weather.next(0, rng)
        if (withStaff) staff += Staff(nextId++, Names.person(rng), Role.PALEFRENIER, 2, 1150)
        val spots = listOf(0.08f to 0.62f, 0.2f to 0.78f, 0.33f to 0.7f, 0.47f to 0.82f, 0.58f to 0.66f, 0.71f to 0.8f, 0.86f to 0.7f, 0.93f to 0.86f)
        for ((i, xy) in spots.withIndex()) junk += Junk(nextId++, i % Junk.KINDS.size, rng.range(1f, 2.5f), xy.first, xy.second)
        refreshCandidates()
        ensureEvents()
        refreshStuds(full = true)
        repeat(14) { addListing() }
        log("Bienvenue au $stableName ! Le domaine a besoin de bras : nettoyez, restaurez, et prenez soin de votre cheval.", MsgKind.GOOD)
    }

    // ===================================================================== Journée de travail
    val dayStart get() = 7f
    val dayEnd get() = 21f
    fun hoursLeft(): Float = (dayEnd - hourOfDay).coerceAtLeast(0f)

    /** Consomme du temps de la journée ; refuse si la journée est trop avancée. */
    fun spend(hours: Float): Boolean {
        if (hours <= 0f) return true
        if (hourOfDay + hours > dayEnd + 0.01f || hourOfDay < dayStart - 0.01f) return false
        advance(hours.toDouble())
        return true
    }

    fun cleanJunk(id: Int): Res {
        val j = junk.firstOrNull { it.id == id } ?: return Res.no("")
        if (!spend(j.hours)) return Res.no("Il ne reste pas assez de temps aujourd'hui (${fmt1(j.hours)} h nécessaires).")
        junk.remove(j)
        stat(St.JUNK)
        reputation = (reputation + 0.5f).coerceAtMost(100f)
        val loot = when (rng.int(5)) {
            0 -> { earn(rng.range(20, 120), "Ferraille revendue", "Divers"); " Vous revendez un peu de ferraille." }
            1 -> if (j.kind == 3) { stock[Item.PAILLE.ordinal] += 4f; " Il restait quelques bottes de paille utilisables !" } else ""
            2 -> if (j.kind == 0 || j.kind == 1) { earn(rng.range(60, 260), "Vieille selle retrouvée et revendue", "Divers"); " Sous la ferraille : une vieille selle, revendue !" } else ""
            else -> ""
        }
        return Res.ok("${j.label} : débarrassé en ${fmt1(j.hours)} h.$loot")
    }

    /** Élèves présents à un cours : selon la réputation, les installations et le nombre de chevaux. */
    fun lessonStudents(horses: Int): Int = (1 + (reputation / 12f).toInt() + level(BuildingType.CARRIERE) + level(BuildingType.CLUB_HOUSE) * 2).coerceAtMost(horses * 2).coerceAtLeast(1)

    fun lessonPrice(): Int = 18 + level(BuildingType.CARRIERE) * 4 + level(BuildingType.MANEGE) * 6 + level(BuildingType.CLUB_HOUSE) * 5

    /** Le joueur donne un cours d'équitation avec les chevaux choisis (1 h 30). */
    fun giveLesson(horses: List<Horse>): Res {
        if (horses.isEmpty()) return Res.no("Choisissez au moins un cheval.")
        if (lessonsToday >= 3) return Res.no("Trois cours par jour, c'est déjà beaucoup !")
        horses.firstOrNull { !it.backed || it.injured || it.energy < 25f || it.place == Place.DEPLACEMENT }?.let { return Res.no("${it.name} ne peut pas travailler (${if (!it.backed) "non débourré" else if (it.injured) "blessé" else "trop fatigué"}).") }
        if (weather.harsh && level(BuildingType.MANEGE) == 0) return Res.no("Personne ne vient monter par ce temps sans manège couvert.")
        if (!spend(1.5f)) return Res.no("Plus assez de temps aujourd'hui (1 h 30 nécessaire).")
        val students = lessonStudents(horses.size)
        val calm = horses.map { (it.pot(Trait.CALME) + it.confidence) / 2f }.average().toFloat()
        val satisfaction = (calm / 100f + reputation / 200f).coerceIn(0.2f, 1.2f)
        val income = (students * lessonPrice() * (0.8f + satisfaction * 0.3f)).toInt()
        earn(income, "Cours d'équitation ($students élèves)", "Club")
        for (h in horses) { h.energy -= 14f; h.workToday += 60f; h.fitness = min(80f, h.fitness + 0.5f); h.confidence = min(100f, h.confidence + 0.5f) }
        lessonsToday++
        stat(St.LESSON)
        rider.hoursRidden += 0.5f
        reputation = (reputation + 0.25f * satisfaction).coerceAtMost(100f)
        val mood = when { satisfaction > 0.9f -> "ravis"; satisfaction > 0.6f -> "contents"; else -> "un peu déçus" }
        return Res.ok("$students élèves, $mood : ${fmtMoney(income)} encaissés.")
    }

    fun claimGoal(goal: Goal): Res {
        if (goal.id in goalsDone || !goal.done(this)) return Res.no("Objectif pas encore atteint.")
        goalsDone += goal.id
        earn(goal.reward, "Objectif atteint : ${goal.title}", "Objectifs")
        reputation = (reputation + goal.rep).coerceAtMost(100f)
        log("Objectif accompli : ${goal.title} ! (+${fmtMoney(goal.reward)})", MsgKind.GOOD)
        return Res.ok("${goal.title} : +${fmtMoney(goal.reward)}")
    }

    /** Trois chevaux de départ proposés au joueur (il en choisit un, offert). */
    fun starterChoices(): List<Horse> {
        val r = rng.fork(77)
        val opts = listOf(
            Triple(Breed.SELLE_FRANCAIS, Sex.HONGRE, 7),
            Triple(Breed.CONNEMARA, Sex.JUMENT, 9),
            Triple(r.pick(listOf(Breed.LUSITANIEN, Breed.QUARTER_HORSE, Breed.ARABE, Breed.FRISON, Breed.APPALOOSA, Breed.HANOVRIEN)), Sex.JUMENT, 6),
        )
        return opts.map { (b, s, a) -> generateHorse(b, s, a, r, trained = 0.55f, withAncestors = true) }
    }

    fun takeStarter(h: Horse) {
        acquire(h, 0)
        h.bond = 45f
        log("${h.name} arrive au domaine : prenez-en bien soin !", MsgKind.GOOD, h.id)
    }

    // ===================================================================== Génération de chevaux

    private fun register(h: Horse): Horse { horses[h.id] = h; return h }

    /** Cheval complet, avec ascendance sur deux générations (génétique cohérente : parents → produit). */
    fun generateHorse(breed: Breed, sex: Sex?, ageYears: Int, r: Rng = rng, trained: Float = r.range(0f, 0.85f), withAncestors: Boolean = true): Horse {
        val birth = day - ageYears * 365 - r.int(200)
        val by = Cal.date(birth).year
        val genome: Genome
        var sireId = -1; var damId = -1
        if (withAncestors) {
            val gss = register(Horse(nextId++, Names.stallionName(r, breed, by - 18), Sex.ETALON, birth - 365 * r.range(14, 20), breed, Genome.random(breed, r)))
            val gsd = register(Horse(nextId++, Names.horseName(r, by - 16, breed), Sex.JUMENT, birth - 365 * r.range(13, 18), breed, Genome.random(breed, r)))
            val gds = register(Horse(nextId++, Names.stallionName(r, breed, by - 18), Sex.ETALON, birth - 365 * r.range(14, 20), breed, Genome.random(breed, r)))
            val gdd = register(Horse(nextId++, Names.horseName(r, by - 16, breed), Sex.JUMENT, birth - 365 * r.range(13, 18), breed, Genome.random(breed, r)))
            val sireBirth = birth - 365 * r.range(5, 14)
            val damBirth = birth - 365 * r.range(5, 14)
            val sire = register(Horse(nextId++, Names.stallionName(r, breed, Cal.date(sireBirth).year), Sex.ETALON, sireBirth, breed, Genome.cross(gss.genome, gsd.genome, 0f, 0f, r), gss.id, gsd.id))
            val dam = register(Horse(nextId++, Names.horseName(r, Cal.date(damBirth).year, breed), Sex.JUMENT, damBirth, breed, Genome.cross(gds.genome, gdd.genome, 0f, 0f, r), gds.id, gdd.id))
            // Les ancêtres « fondateurs » ont des valeurs génétiques de race ; on évite O/O chez les adultes.
            genome = Genome.cross(sire.genome, dam.genome, 0f, 0f, r)
            if (genome.lethal) genome.alleles[Locus.FRAME.ordinal * 2] = 0
            if (sire.genome.lethal) sire.genome.alleles[Locus.FRAME.ordinal * 2] = 0
            if (dam.genome.lethal) dam.genome.alleles[Locus.FRAME.ordinal * 2] = 0
            sireId = sire.id; damId = dam.id
        } else {
            genome = Genome.random(breed, r)
        }
        val s = sex ?: if (r.chance(0.5f)) Sex.JUMENT else if (ageYears >= 4 && r.chance(0.7f)) Sex.HONGRE else Sex.ETALON
        val h = register(Horse(nextId++, Names.horseName(r, by, breed), s, birth, breed, genome, sireId, damId))
        h.personality = Horse.rollPersonality(genome, r)
        h.cycleOffset = r.int(21)
        val a = h.age(day)
        h.bcs = r.range(4.3f, 6.2f)
        h.fitness = if (a >= 4) r.range(20f, 60f) else 15f
        h.muscle = h.fitness
        h.lastFarrier = day - r.int(50); h.lastDentist = day - r.int(300); h.lastVaccine = day - r.int(330); h.lastDeworm = day - r.int(100)
        h.handling = if (a >= 1) 100f else r.range(0f, 50f)
        if (a >= 3.5f) { h.backed = true; h.backingProgress = 100f }
        h.shod = h.backed && r.chance(0.6f)
        if (h.backed) {
            val main = h.bestDiscipline()
            for (d in Discipline.values()) if (d.trainable) {
                val share = if (d == main) trained else trained * r.range(0.05f, 0.45f)
                h.skills[d.ordinal] = h.skillCap(d, day) * share
            }
        }
        if (a < 0.6f) h.weaned = false
        return h
    }

    // ===================================================================== Marché

    fun addListing() {
        val breed = rng.pick(Breed.MARKET)
        val age = rng.weighted(doubleArrayOf(0.4, 1.2, 1.2, 1.4, 2.0, 2.0, 1.8, 1.6, 1.4, 1.2, 1.0, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2))
        val h = generateHorse(breed, null, age.coerceAtLeast(1))
        h.weaned = true
        if (h.mare && h.age(day) >= 5 && rng.chance(0.15f)) {
            h.pregnancy = Pregnancy(-1, day - rng.int(200), day + rng.range(60, 300), true)
        }
        val price = (value(h) * rng.range(0.9f, 1.3f)).roundTo(50)
        market += Listing(h.id, price, if (rng.chance(0.3f)) "Particulier — ${Names.person(rng)}" else "Élevage ${rng.pick(listOf("du Val", "des Prés", "de Kerguelen", "du Moulin", "des Sources", "du Manoir"))}", day + rng.range(6, 30))
    }

    private fun Float.roundTo(step: Int) = ((this / step).roundToInt() * step).coerceAtLeast(step)

    /** Valeur marchande estimée d'un cheval. */
    fun value(h: Horse): Int {
        if (!h.alive) return 0
        val best = Discipline.values().filter { it.trainable }.maxOf { it.potential(h) }
        val q = exp(((best - 55f) / 12f) * 0.6f)
        val a = h.age(day)
        val ageF = when {
            a < 1 -> 0.32f; a < 2 -> 0.45f; a < 3 -> 0.58f; a < 4 -> if (h.backed) 0.8f else 0.68f
            a < 13 -> 1f; a < 17 -> 0.7f; a < 21 -> 0.38f; else -> 0.15f
        }
        val bestSkill = h.skills.maxOrNull() ?: 0f
        val trainF = 1f + (bestSkill / 100f).pow(1.6f) * 5f
        var v = h.breed.basePrice * q * ageF * trainF + h.earnings * 1.2f
        if (h.stallion && best > 64 && a >= 3) v *= 1.35f
        if (h.sex == Sex.HONGRE) v *= 0.95f
        if (h.injured) v *= 0.6f
        if (h.ailments.any { it.type.chronic }) v *= 0.75f
        if (h.pregnancy?.confirmed == true) v *= 1.25f
        val g = h.genome
        if (g.has(Locus.CHAMPAGNE) || g.has(Locus.SILVER)) v *= 1.2f
        if (g.count(Locus.CREAM) == 2) v *= 1.15f
        if (h.breed == Breed.APPALOOSA && g.has(Locus.LEOPARD)) v *= 1.15f
        if (h.breed == Breed.PAINT_HORSE && g.has(Locus.TOBIANO)) v *= 1.15f
        if (h.breed == Breed.FRISON && !g.has(Locus.EXTENSION)) v *= 0.5f
        if (h.titles.isNotEmpty()) v *= 1.1f
        return max(150, (v / 50f).roundToInt() * 50)
    }

    fun buy(l: Listing): Res {
        val h = horse(l.horseId) ?: return Res.no("Annonce introuvable")
        if (money < l.price) return Res.no("Fonds insuffisants (${fmtMoney(l.price)})")
        if (boxUsers() >= boxes()) return Res.no("Plus de box libre : agrandissez l'écurie ou vendez un cheval.")
        market.remove(l)
        pay(l.price, "Achat de ${h.name}", "Chevaux")
        stat(St.BUY)
        acquire(h, l.price)
        h.place = Place.BOX
        log("${h.name} rejoint le domaine. Pensez à vérifier ses vaccins et son ferrage.", MsgKind.GOOD, h.id)
        return Res.ok("${h.name} est à vous !")
    }

    private fun acquire(h: Horse, price: Int) {
        h.owned = true
        h.purchasePrice = price
        h.acquiredDay = day
        h.hayRack = 4f; h.bucket = 30f; h.litter = 100f
        h.ration = defaultRation(h)
        h.stress = 40f
    }

    fun defaultRation(h: Horse): Ration {
        val w = h.weight(day)
        val hay = (w * 0.016f * (if (h.bcs > 6.5f) 0.85f else if (h.bcs < 4f) 1.15f else 1f)).coerceIn(3f, 14f)
        val feed = when {
            h.bcs > 6.8f -> 0f
            h.isFoal(day) -> 1.5f
            h.pregnancy != null -> 2.5f
            h.breed == Breed.PUR_SANG || h.breed == Breed.ARABE -> 2.5f
            h.breed.pony || h.breed == Breed.HAFLINGER -> 0.5f
            h.pot(Trait.CALME) > 70 -> 1f
            else -> 1.5f
        }
        return Ration((hay * 2).roundToInt() / 2f, feed, true)
    }

    fun sellToDealer(h: Horse): Res {
        if (!h.owned) return Res.no("")
        val p = (value(h) * 0.65f).roundTo(50)
        removeFromStable(h)
        earn(p, "Vente de ${h.name} à un marchand", "Chevaux")
        stat(St.SALE)
        log("${h.name} a été vendu à un marchand pour ${fmtMoney(p)}.", MsgKind.INFO, h.id)
        return Res.ok("Vendu ${fmtMoney(p)}")
    }

    fun listForSale(h: Horse, price: Int): Res {
        h.forSale = true; h.askingPrice = price
        return Res.ok("${h.name} est mis en vente à ${fmtMoney(price)}.")
    }

    fun unlist(h: Horse) { h.forSale = false; offers.removeAll { it.horseId == h.id } }

    fun acceptOffer(o: Offer): Res {
        val h = horse(o.horseId) ?: return Res.no("")
        offers.remove(o)
        removeFromStable(h)
        earn(o.amount, "Vente de ${h.name} à ${o.buyer}", "Chevaux")
        stat(St.SALE)
        reputation = (reputation + 1f).coerceAtMost(100f)
        log("${h.name} part chez ${o.buyer} (${fmtMoney(o.amount)}). Bonne route !", MsgKind.GOOD, h.id)
        return Res.ok("Vente conclue.")
    }

    fun declineOffer(o: Offer) { offers.remove(o) }

    private fun removeFromStable(h: Horse) {
        h.owned = false; h.forSale = false; h.clubHorse = false; h.studFee = 0
        offers.removeAll { it.horseId == h.id }
        events.forEach { e -> e.entries.removeAll { it.horseId == h.id } }
        // Le poulain non sevré suit sa mère.
        owned().filter { it.damId == h.id && !it.weaned }.forEach { removeFromStable(it) }
    }

    // ===================================================================== Étalons et reproduction

    fun refreshStuds(full: Boolean) {
        if (full) studs.clear() else if (studs.size > 4) repeat(3) { studs.removeAt(rng.int(studs.size)) }
        val breeds = listOf(Breed.SELLE_FRANCAIS, Breed.SELLE_FRANCAIS, Breed.PUR_SANG, Breed.KWPN, Breed.HANOVRIEN, Breed.ARABE, Breed.LUSITANIEN,
            Breed.FRISON, Breed.QUARTER_HORSE, Breed.PAINT_HORSE, Breed.APPALOOSA, Breed.CONNEMARA, Breed.HAFLINGER, Breed.PERCHERON, Breed.ISLANDAIS, Breed.TROTTEUR)
        while (studs.size < 14) {
            val b = rng.pick(breeds)
            val h = generateHorse(b, Sex.ETALON, rng.range(5, 18), trained = rng.range(0.5f, 0.98f))
            // Les étalons approuvés sont sélectionnés : on garde les meilleurs tirages.
            val boost = rng.range(4f, 14f)
            for (t in Trait.SPORT) h.genome.breeding[t.ordinal] += boost * if (t == Trait.CALME) 0.3f else 0.7f
            h.studFee = (value(h) * 0.06f).roundTo(50).coerceIn(300, 9000)
            h.earnings = (h.skills.maxOrNull() ?: 0f).let { (it * it * rng.range(5f, 30f)).toInt() }
            studs += h.id
        }
    }

    /** Étalons disponibles : catalogue extérieur + étalons du domaine. */
    fun availableStallions(): List<Horse> = studs.mapNotNull { horse(it) } + owned().filter { it.stallion && it.age(day) >= 3f }

    /** Coefficient de consanguinité d'un produit hypothétique (méthode tabulaire de parenté). */
    fun inbreedingOf(sireId: Int, damId: Int): Float {
        return coancestry(sireId, damId, HashMap(), 0)
    }

    private fun coancestry(a: Int, b: Int, memo: HashMap<Long, Float>, depth: Int): Float {
        if (a < 0 || b < 0 || depth > 12) return 0f
        val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        memo[key]?.let { return it }
        val ha = horse(a) ?: return 0f
        val hb = horse(b) ?: return 0f
        val r = if (a == b) {
            (1f + coancestry(ha.sireId, ha.damId, memo, depth + 1)) / 2f
        } else {
            // on remonte par le plus jeune des deux
            val (young, old) = if (ha.birthDay >= hb.birthDay) ha to hb else hb to ha
            if (young.sireId < 0 && young.damId < 0) 0f
            else 0.5f * (coancestry(young.sireId, old.id, memo, depth + 1) + coancestry(young.damId, old.id, memo, depth + 1))
        }
        memo[key] = r
        return r
    }

    fun coverCost(stallion: Horse) = if (stallion.owned) 0 else stallion.studFee

    fun cover(mare: Horse, stallion: Horse, followUp: Boolean): Res {
        if (!mare.mare || !mare.owned) return Res.no("Choisissez une jument du domaine.")
        if (mare.pregnancy != null) return Res.no("${mare.name} est déjà pleine.")
        if (mare.age(day) < 3f) return Res.no("${mare.name} est trop jeune (3 ans minimum).")
        if (mare.injured) return Res.no("${mare.name} doit d'abord guérir.")
        if (mare.lastCovered == day) return Res.no("Déjà présentée aujourd'hui.")
        val m = Cal.month(day)
        if (m !in 1..7) return Res.no("Hors saison de monte (février à août) : les juments ne cyclent pas en hiver.")
        val cost = coverCost(stallion) + if (followUp) 180 else 0
        if (money < cost) return Res.no("Fonds insuffisants (${fmtMoney(cost)}).")
        if (cost > 0) pay(cost, "Saillie ${stallion.name} × ${mare.name}${if (followUp) " + suivi gynéco" else ""}", "Élevage")
        mare.lastCovered = day
        stat(St.COVER)
        val heat = mare.inHeat(day)
        val ageF = when { mare.age(day) < 15 -> 1f; mare.age(day) < 20 -> 0.72f; else -> 0.4f }
        val bcsF = if (mare.bcs in 4.5f..7f) 1f else 0.75f
        val fert = (0.5f + mare.pot(Trait.FERTILITE) / 200f) * (0.5f + stallion.pot(Trait.FERTILITE) / 200f) * 2.2f
        val p = (if (followUp) 0.72f else if (heat) 0.55f else 0.08f) * ageF * bcsF * fert
        if (rng.chance(p.coerceIn(0.02f, 0.9f))) {
            val len = rng.gauss(340.0, 8.0).roundToInt().coerceIn(318, 368)
            mare.pregnancy = Pregnancy(stallion.id, day, day + len, false)
        }
        val note = if (!heat && !followUp) " (elle ne semblait pas en chaleur…)" else ""
        log("${mare.name} a été saillie par ${stallion.name}$note. Échographie de contrôle dans 16 jours.", MsgKind.INFO, mare.id)
        return Res.ok("Saillie effectuée$note")
    }

    private fun foaling(mare: Horse) {
        val pg = mare.pregnancy ?: return
        mare.pregnancy = null
        val sire = horse(pg.sireId)
        val sireGenome = sire?.genome ?: Genome.random(mare.breed, rng)
        val fSire = sire?.inbreeding ?: 0f
        val genome = Genome.cross(sireGenome, mare.genome, fSire, mare.inbreeding, rng)
        val breed = Breed.offspring(sire?.breed ?: mare.breed, mare.breed)
        val year = Cal.date(day).year
        val sex = if (rng.chance(0.5f)) Sex.JUMENT else Sex.ETALON
        val morpho = Breed.blendMorpho(sire?.morpho ?: mare.morpho, mare.morpho)
        val foal = register(Horse(nextId++, Names.horseName(rng, year, breed, affix), sex, day, breed, genome, pg.sireId, mare.id, morpho))
        foal.inbreeding = if (sire != null) inbreedingOf(sire.id, mare.id) else 0f
        foal.personality = Horse.rollPersonality(genome, rng)
        foal.cycleOffset = rng.int(21)
        foal.owned = true; foal.weaned = false; foal.place = mare.place
        foal.bcs = 5f; foal.fitness = 10f; foal.muscle = 10f; foal.handling = 0f; foal.bond = 10f
        foal.lastVaccine = -9999; foal.lastDeworm = day; foal.lastFarrier = day; foal.lastDentist = day
        foal.acquiredDay = day
        foal.ration = Ration(1f, 0.5f, true)
        mare.foalsBorn++
        stat(St.FOAL)
        // Poulinage : la nuit, comme dans la nature. Risque de dystocie réduit par le box de poulinage et la surveillance.
        val watched = level(BuildingType.POULINAGE) > 0 || staffOf(Role.SOIGNEUR).isNotEmpty()
        val dystocia = rng.chance(if (mare.foalsBorn == 1) 0.06f else 0.04f)
        val sexWord = if (sex == Sex.JUMENT) "une pouliche" else "un poulain"
        if (dystocia && !(watched && rng.chance(0.8f))) {
            killHorse(foal, "Poulinage difficile (dystocie)")
            log("Drame cette nuit : ${mare.name} a eu un poulinage difficile, $sexWord n'a pas survécu. Un box de poulinage sous caméra limite ce risque.", MsgKind.BAD, mare.id)
            mare.morale = 20f
            return
        }
        val coat = foal.coatName(day)
        log("Naissance ! ${mare.name} a donné naissance à $sexWord ${coat.lowercase()} : ${foal.name}${if (sire != null) " (par ${sire.name})" else ""}.", MsgKind.GOOD, foal.id)
        if (dystocia) log("Le poulinage a été difficile mais l'équipe était là : tout va bien.", MsgKind.INFO, mare.id)
        if (foal.inbreeding > 0.0625f) log("Attention : ${foal.name} est consanguin à ${"%.1f".format(foal.inbreeding * 100)} %.", MsgKind.BAD, foal.id)
        mare.ration = Ration(mare.ration.hay + 2f, max(mare.ration.feed, 3f), true)
    }

    fun wean(foal: Horse): Res {
        if (foal.weaned) return Res.no("Déjà sevré.")
        if (day - foal.birthDay < 150) return Res.no("Trop tôt : on sèvre vers 6 mois.")
        if (boxUsers() >= boxes()) return Res.no("Il faut un box libre pour le sevrage.")
        foal.weaned = true
        foal.stress = 60f; foal.morale -= 20f
        foal.ration = defaultRation(foal)
        horse(foal.damId)?.let { it.morale -= 15f; it.ration = defaultRation(it) }
        log("${foal.name} est sevré. Un compagnon au pré l'aidera à s'en remettre.", MsgKind.INFO, foal.id)
        return Res.ok("Sevrage effectué")
    }

    fun geld(h: Horse): Res {
        if (!h.stallion) return Res.no("")
        if (!payIfCan(350, "Castration de ${h.name}", "Vétérinaire")) return Res.no("Fonds insuffisants")
        h.sex = Sex.HONGRE; h.studFee = 0
        h.energy = 30f; h.stress += 20f
        h.genome.env[Trait.CALME.ordinal] += 6f
        log("${h.name} a été castré : il sera plus calme et pourra vivre au pré avec les autres.", MsgKind.INFO, h.id)
        return Res.ok("${h.name} est maintenant hongre.")
    }

    // ===================================================================== Soins

    fun feed(h: Horse): Res {
        if (!h.weaned) return Res.no("${h.name} tète encore sa mère.")
        val hay = h.ration.hay / 2f; val fd = h.ration.feed / 2f
        if (count(Item.FOIN) < hay) return Res.no("Plus assez de foin ! Commandez-en dans la gestion des stocks.")
        giveMeal(h, hay, fd)
        h.bond += 0.5f
        stat(St.FEED)
        return Res.ok("${h.name} mange : ${fmt1(hay)} kg de foin${if (fd > 0) " et ${fmt1(fd)} kg de granulés" else ""}.")
    }

    private fun giveMeal(h: Horse, hay: Float, feedKg: Float) {
        val hayGiven = min(hay, count(Item.FOIN))
        stock[Item.FOIN.ordinal] -= hayGiven
        h.hayRack = min(h.hayRack + hayGiven, 16f)
        val f = min(feedKg, count(Item.GRANULES))
        stock[Item.GRANULES.ordinal] -= f
        if (f > 0) {
            h.eatFeed += f
            h.satiety = min(100f, h.satiety + f * 4f)
            // Repas de concentrés trop gros : l'estomac du cheval est petit (~15 L)
            if (f > 2.5f) h.riskAcc += (f - 2.5f) * 0.05f
        }
        if (h.ration.minerals && count(Item.MINERAUX) >= 0.5f) stock[Item.MINERAUX.ordinal] -= 0.5f
    }

    fun water(h: Horse): Res { h.bucket = 40f; return Res.ok("Seau rempli d'eau fraîche.") }

    fun muck(h: Horse): Res {
        if (count(Item.PAILLE) < 1f) return Res.no("Plus de paille !")
        stock[Item.PAILLE.ordinal] -= 1f
        h.litter = 100f
        return Res.ok("Box curé et repaillé.")
    }

    /** Pansage : [quality] 0..1 selon la minutie (mini-jeu). */
    /**
     * Pansage. [quality] : propreté obtenue (0..1). [hoofPicks] : nombre de pieds curés.
     * [rough] : brusquerie (0 = gestes doux, 1 = cheval très contrarié), qui gâche le bénéfice relationnel.
     */
    fun groom(h: Horse, quality: Float, hoofPicks: Int = 4, rough: Float = 0f): Res {
        val q = quality.coerceIn(0f, 1f)
        val r = rough.coerceIn(0f, 1f)
        h.cleanliness = min(100f, h.cleanliness + 30f + 60f * q)
        val first = h.lastGroomDay != day
        h.lastGroomDay = day
        if (first) {
            h.bond = min(100f, h.bond + (2f + 3f * q * (if (Personality.CALIN in h.personality) 1.6f else 1f)) * (1f - r))
            h.morale = min(100f, h.morale + 6f * q * (1f - r * 0.7f))
            h.stress = max(0f, h.stress - 8f * (1f - r))
            if (r > 0.5f) h.stress = min(100f, h.stress + 6f * r)
        }
        stat(St.GROOM)
        h.confidence = min(100f, h.confidence + (1f + q) * (1f - r))
        // Curer les pieds chaque jour prévient abcès et pourriture de fourchette
        h.hooves = min(100f, h.hooves + 0.5f * hoofPicks.coerceIn(0, 4))
        rider.hoursRidden += 0.05f
        return Res.ok(when {
            r > 0.6f -> "Pansage terminé, mais ${h.name} a trouvé le moment désagréable."
            q > 0.85f -> "${h.name} brille comme un sou neuf !"
            else -> "Pansage terminé."
        })
    }

    fun treat(h: Horse): Res {
        if (count(Item.CAROTTES) < 0.25f) return Res.no("Plus de carottes.")
        stock[Item.CAROTTES.ordinal] -= 0.25f
        h.treatsToday++
        if (h.treatsToday > 4) {
            h.bcs += 0.01f
            return Res.ok("${h.name} en redemande… attention à ne pas le rendre mordeur !")
        }
        h.bond = min(100f, h.bond + if (Personality.GOURMAND in h.personality) 3f else 2f)
        h.morale = min(100f, h.morale + 4f)
        return Res.ok("${h.name} croque sa carotte avec plaisir.")
    }

    fun caress(h: Horse): Res {
        h.bond = min(100f, h.bond + if (Personality.CALIN in h.personality) 0.8f else 0.3f)
        h.stress = max(0f, h.stress - 3f)
        h.morale = min(100f, h.morale + 1f)
        return Res.ok(if (Personality.CALIN in h.personality) "${h.name} pose sa tête contre vous." else "${h.name} souffle doucement.")
    }

    fun setPlace(h: Horse, p: Place): Res {
        if (h.place == Place.DEPLACEMENT) return Res.no("${h.name} est en déplacement.")
        if (p == Place.PRE && h.hasAilment(AilmentType.FOURBURE)) return Res.no("Fourbure : interdit de pré (herbe trop riche).")
        if (p == Place.PRE && h.injured && !h.hasAilment(AilmentType.GALE_DE_BOUE)) return Res.no("Blessé : repos au box.")
        h.place = p
        owned().filter { it.damId == h.id && !it.weaned }.forEach { it.place = p }
        return Res.ok(if (p == Place.PRE) "${h.name} galope rejoindre ses copains au pré." else "${h.name} est rentré au box.")
    }

    fun callVet(h: Horse): Res {
        val todo = h.ailments.filter { !it.treated || it.type.chronic }
        if (todo.isEmpty()) {
            if (!payIfCan(90, "Visite de contrôle de ${h.name}", "Vétérinaire")) return Res.no("Fonds insuffisants")
            return Res.ok("Bilan de santé : RAS. ${h.name} va bien. (Note d'état ${fmt1(h.bcs)}/9, ${h.weight(day).toInt()} kg)")
        }
        val cost = todo.sumOf { it.type.vet } + 60
        if (!payIfCan(cost, "Vétérinaire pour ${h.name}", "Vétérinaire")) return Res.no("Fonds insuffisants (${fmtMoney(cost)})")
        for (a in todo) {
            a.treated = true
            if (a.type == AilmentType.COLIQUE) a.severity = min(a.severity, 0.6f)
        }
        h.stress = max(0f, h.stress - 10f)
        if (h.hasAilment(AilmentType.PARASITES)) h.lastDeworm = day
        return Res.ok("Le vétérinaire a soigné ${h.name} : ${todo.joinToString { it.type.label.lowercase() }}.")
    }

    fun farrier(h: Horse, shoe: Boolean): Res {
        val cost = if (shoe) 140 else 60
        if (!payIfCan(cost, "Maréchal-ferrant pour ${h.name}", "Soins")) return Res.no("Fonds insuffisants")
        h.hooves = 100f; h.shod = shoe; h.lastFarrier = day
        h.ailments.filter { it.type == AilmentType.ABCES }.forEach { it.treated = true }
        return Res.ok(if (shoe) "${h.name} est ferré des quatre pieds." else "Parage effectué : ${h.name} est pieds nus.")
    }

    fun dentist(h: Horse): Res {
        if (!payIfCan(95, "Dentiste équin pour ${h.name}", "Soins")) return Res.no("Fonds insuffisants")
        h.teeth = 100f; h.lastDentist = day
        return Res.ok("Les surdents de ${h.name} ont été râpées.")
    }

    fun vaccinate(h: Horse): Res {
        if (!payIfCan(80, "Vaccins grippe-tétanos de ${h.name}", "Soins")) return Res.no("Fonds insuffisants")
        h.lastVaccine = day
        return Res.ok("${h.name} est vacciné (grippe, tétanos, rhinopneumonie). Rappel dans un an.")
    }

    fun deworm(h: Horse): Res {
        if (!payIfCan(28, "Vermifuge pour ${h.name}", "Soins")) return Res.no("Fonds insuffisants")
        h.lastDeworm = day
        h.ailments.filter { it.type == AilmentType.PARASITES }.forEach { it.treated = true }
        return Res.ok("${h.name} est vermifugé.")
    }

    fun dnaTest(h: Horse): Res {
        if (h.dnaTested) return Res.no("Déjà testé")
        if (!payIfCan(65, "Test ADN de couleurs de ${h.name}", "Élevage")) return Res.no("Fonds insuffisants")
        h.dnaTested = true
        return Res.ok("Génotype de ${h.name} : ${h.genome.genotypeString()}")
    }

    fun toggleRug(h: Horse): Res { h.rugged = !h.rugged; return Res.ok(if (h.rugged) "Couverture mise." else "Couverture retirée.") }

    fun clip(h: Horse): Res {
        if (h.clipped) return Res.no("Déjà tondu : le poil repoussera au printemps.")
        if (!payIfCan(70, "Tonte de ${h.name}", "Soins")) return Res.no("Fonds insuffisants")
        h.clipped = true; h.rugged = true; h.cleanliness = 100f
        return Res.ok("${h.name} est tondu : il sèchera vite après le travail, mais il lui faut une couverture.")
    }

    // ===================================================================== Travail

    fun canTrain(h: Horse, ex: Exercise): String? {
        val a = h.age(day)
        return when {
            !h.alive || !h.owned -> "Indisponible"
            h.place == Place.DEPLACEMENT -> "En déplacement"
            h.injured -> "${h.name} est blessé : repos !"
            h.urgent -> "Urgence vétérinaire"
            a < ex.minAge -> "Trop jeune (${ex.minAge.toInt()} ans minimum)"
            ex == Exercise.MANIPULATION && h.handling >= 100f -> "Déjà parfaitement manipulé"
            ex == Exercise.DEBOURRAGE && h.backed -> "Déjà débourré"
            ex.ridden && !h.backed -> "Il faut d'abord le débourrer"
            h.pregnancy != null && h.pregnancy!!.dueDay - day < 90 && ex.ridden -> "Jument en fin de gestation : pas de travail monté"
            h.sessionsToday >= 2 -> "Assez travaillé pour aujourd'hui"
            h.energy < 12f -> "${h.name} est épuisé"
            weather.ground == Ground.GELE && level(BuildingType.MANEGE) == 0 && ex != Exercise.MANIPULATION -> "Sol gelé : trop dangereux sans manège"
            else -> null
        }
    }

    /**
     * Une séance de travail. [quality] vient du mini-jeu quand le joueur monte (0..1.2) ; [riderSkill] du cavalier.
     * Retourne le compte rendu de séance.
     */
    fun train(h: Horse, ex: Exercise, riderSkill: Float, byPlayer: Boolean, quality: Float = 1f, minutes: Int = ex.minutes): Res {
        canTrain(h, ex)?.let { return Res.no(it) }
        val facilityQ = when {
            ex.facility == null -> 1f
            level(ex.facility) > 0 -> 1f + (level(ex.facility) - 1) * 0.12f
            ex.facility == BuildingType.CARRIERE && level(BuildingType.MANEGE) > 0 -> 1.05f
            else -> 0.7f
        }
        val outdoorBad = weather.harsh || weather.rainy
        val indoor = level(BuildingType.MANEGE) > 0 && ex.facility == BuildingType.CARRIERE
        val weatherQ = if (outdoorBad && !indoor) 0.82f else 1f
        val tired = (1f - h.energy / 100f)
        val learn = (0.6f + h.pot(Trait.APPRENTISSAGE) / 100f * 0.8f) *
            (if (Personality.TETU in h.personality) 0.8f else 1f) *
            (if (Personality.SENSIBLE in h.personality) (if (riderSkill > 60) 1.12f else 0.85f) else 1f) *
            (if (Personality.PARESSEUX in h.personality) 0.9f else 1f)
        val riderF = 0.7f + riderSkill / 100f * 0.5f
        val q = quality * facilityQ * weatherQ * riderF * learn * (if (h.energy < 25) 0.6f else 1f) * (0.75f + h.morale / 400f)
        val dur = minutes / ex.minutes.toFloat()
        val report = StringBuilder()
        val before = h.skills.copyOf()
        for ((d, w) in ex.gains) {
            val cap = h.skillCap(d, day)
            val s = h.skills[d.ordinal]
            if (s < cap) h.skills[d.ordinal] = min(cap, s + w * 0.5f * q * dur * (1f - s / cap).pow(0.8f))
        }
        when (ex) {
            Exercise.MANIPULATION -> { h.handling = min(100f, h.handling + 14f * learn * quality); h.bond = min(100f, h.bond + 2.5f); report.append("Éducation : ${h.handling.toInt()} %. ") }
            Exercise.DEBOURRAGE -> {
                h.backingProgress = min(100f, h.backingProgress + 9f * learn * quality * (0.6f + h.handling / 250f))
                if (h.backingProgress >= 100f && !h.backed) { h.backed = true; report.append("${h.name} est débourré : il accepte désormais son cavalier ! ") ; log("${h.name} est débourré.", MsgKind.GOOD, h.id) }
                else report.append("Débourrage : ${h.backingProgress.toInt()} %. ")
            }
            else -> {}
        }
        val fit0 = h.fitness
        h.fitness = min(100f, h.fitness + ex.fitness * dur * (1f - h.fitness / 110f) * 1.4f)
        h.muscle = min(100f, h.muscle + ex.fitness * 0.6f * dur * (1f - h.muscle / 110f))
        h.energy = max(0f, h.energy - ex.energy * dur * (1.3f - h.fitness / 200f) * (if (weather.tempMax > 28) 1.3f else 1f))
        h.cleanliness = max(0f, h.cleanliness - 10f * dur)
        h.workToday += minutes
        h.sessionsToday++
        h.trainedDays++
        h.lastTrainDay = day
        if (h.lastExercise != ex.name) h.morale = min(100f, h.morale + 3f)
        h.lastExercise = ex.name
        if (ex == Exercise.EXTERIEUR) { h.morale = min(100f, h.morale + 10f); h.stress = max(0f, h.stress - 10f) }
        stat(St.TRAIN)
        when (ex) {
            Exercise.TRAVAIL_PIED -> h.confidence = min(100f, h.confidence + 4f * quality)
            Exercise.MANIPULATION, Exercise.LONGE -> h.confidence = min(100f, h.confidence + 1.5f * quality)
            else -> h.confidence = (h.confidence + (quality - 0.55f) * 3f).coerceIn(0f, 100f)
        }
        if (byPlayer) {
            if (ex.ridden) stat(St.RIDE)
            h.bond = min(100f, h.bond + 2f)
            rider.hoursRidden += minutes / 60f
            for ((d, w) in ex.gains) rider.xp[d.ordinal] = min(60f, rider.xp[d.ordinal] + w * 0.25f * dur * (1f - rider.xp[d.ordinal] / 70f))
        }
        // Risque de blessure : fatigue, terrain, âge, pieds, robustesse
        val a = h.age(day)
        var risk = ex.injury * dur * (1f + tired * 2.5f) * weather.ground.injury *
            (2f - h.pot(Trait.ROBUSTESSE) / 70f).coerceIn(0.6f, 1.8f) *
            (if (a < 4) 1.6f else if (a > 16) 1.5f else 1f) * (if (h.hooves < 40) 1.6f else 1f) *
            (if (h.fitness < 25 && ex.energy > 25) 1.6f else 1f) * (if (ex.facility != null && level(ex.facility) == 0 && !indoor) 1.3f else 1f) *
            (if (Personality.GENEREUX in h.personality) 1.2f else 1f)
        if (quality < 0.4f) risk *= 1.5f
        if (rng.chance(risk)) {
            val t = when {
                ex.energy > 30 && rng.chance(0.2f) -> AilmentType.TENDINITE
                rng.chance(0.2f) -> AilmentType.PLAIE
                else -> AilmentType.BOITERIE
            }
            addAilment(h, t)
            report.append("Aïe : ${h.name} revient ${if (t == AilmentType.PLAIE) "avec une plaie" else "boiteux"}. ")
        }
        val gains = Discipline.values().filter { h.skills[it.ordinal] - before[it.ordinal] > 0.05f }
            .joinToString { "${it.short} +${fmt1(h.skills[it.ordinal] - before[it.ordinal])}" }
        if (gains.isNotEmpty()) report.append("Progrès : $gains. ")
        if (h.fitness - fit0 > 0.3f) report.append("Condition +${fmt1(h.fitness - fit0)}. ")
        if (h.energy < 25) report.append("${h.name} est fatigué. ")
        return Res.ok(report.toString().trim().ifEmpty { "Bonne séance." })
    }

    fun galopExam(): Res {
        if (rider.galop >= 7) return Res.no("Vous avez déjà le Galop 7.")
        if (!rider.examReady()) return Res.no("Il vous faut ${Rider.GALOP_HOURS[rider.galop].toInt()} h d'équitation (vous en avez ${rider.hoursRidden.toInt()}).")
        if (!payIfCan(45, "Examen du Galop ${rider.galop + 1}", "Divers")) return Res.no("Fonds insuffisants")
        return if (rng.chance(0.8f)) {
            rider.galop++
            log("Félicitations, vous avez obtenu votre Galop ${rider.galop} !", MsgKind.GOOD)
            Res.ok("Galop ${rider.galop} obtenu !")
        } else Res.no("Recalé de peu… Entraînez-vous encore un peu et retentez votre chance.")
    }

    // ===================================================================== Concours

    fun ensureEvents() {
        while (generatedUntil < day + 50) {
            generatedUntil++
            events += CompSim.generateDay(generatedUntil, { nextId++ }, rng)
        }
        events.removeAll { it.done && it.day < day - 2 }
    }

    fun canEnter(e: CompEvent, h: Horse, riderId: Int): String? {
        val st = staff.firstOrNull { it.id == riderId }
        return when {
            e.day < day || (e.day == day && hourOfDay >= 8f) -> "Inscriptions closes"
            e.entries.any { it.horseId == h.id } -> "Déjà engagé"
            events.any { it.day == e.day && it.entries.any { en -> en.horseId == h.id } } -> "Déjà engagé ailleurs ce jour-là"
            !e.ageOk(h, day) -> "Âge non conforme au règlement"
            e.discipline != Discipline.MODELE && !h.backed -> "Cheval non débourré"
            h.injured -> "Cheval blessé"
            h.vaccineDue(e.day) < 0 -> "Vaccins non à jour (obligatoires en concours)"
            riderId == -1 && e.discipline != Discipline.MODELE && rider.galop < Levels.GALOP[e.level] -> "Il vous faut le Galop ${Levels.GALOP[e.level]}"
            riderId != -1 && st == null -> "Cavalier inconnu"
            st != null && st.role != Role.CAVALIER && st.role != Role.LAD -> "Ce salarié ne monte pas en concours"
            e.discipline == Discipline.COURSE && riderId == -1 && rider.galop < 5 -> "Il vous faut une licence de gentleman-rider (Galop 5)"
            h.pregnancy != null && e.discipline != Discipline.MODELE && day - h.pregnancy!!.conceivedDay > 120 -> "Jument pleine depuis plus de 4 mois"
            else -> null
        }
    }

    fun enter(e: CompEvent, h: Horse, riderId: Int, live: Boolean): Res {
        canEnter(e, h, riderId)?.let { return Res.no(it) }
        if (!payIfCan(e.fee, "Engagement ${h.name} — ${e.name}", "Concours")) return Res.no("Fonds insuffisants")
        e.entries += Entry(h.id, riderId, live && riderId == -1)
        stat(St.ENTRY)
        return Res.ok("${h.name} est engagé : ${e.name}, le ${Cal.formatShort(e.day)}.")
    }

    fun withdraw(e: CompEvent, h: Horse) { e.entries.removeAll { it.horseId == h.id } }

    fun ownEntries(): List<Pair<CompEvent, Entry>> = events.filter { !it.done }.flatMap { e -> e.entries.map { e to it } }.sortedBy { it.first.day }

    private fun riderSkillFor(entry: Entry, d: Discipline): Pair<Float, String> {
        if (entry.riderStaffId == -1) return rider.skill(d) to rider.name
        val st = staff.firstOrNull { it.id == entry.riderStaffId } ?: return 45f to "Cavalier"
        val specialty = if (st.discipline == d || (st.role == Role.LAD && d == Discipline.COURSE)) 8f else 0f
        return (35f + st.skill * 11f + specialty) to st.name
    }

    /** Résultat d'un engagement (simulé, ou à partir de la performance jouée en direct). */
    fun runEntry(e: CompEvent, entry: Entry, live: LivePerformance? = null): CompResult? {
        val h = horse(entry.horseId) ?: return null
        val (rs, riderName) = riderSkillFor(entry, e.discipline)
        var perf = CompSim.performance(h, e.discipline, rs, level(BuildingType.SELLERIE), weather.ground, day, rng)
        if (live != null) perf = perf * 0.45f + (Levels.EXPECT[e.level] + (live.quality - 0.6f) * 40f) * 0.55f
        val (text, score) = if (live != null && live.detail.isNotEmpty()) {
            val s = when (e.discipline) {
                Discipline.CSO -> if (live.eliminated) -1000f else -live.faults * 100f - live.timeSec
                Discipline.DRESSAGE -> live.quality * 100f
                Discipline.COURSE -> -live.timeSec
                else -> CompSim.scoreText(e.discipline, perf, e.level, e.distance, rng).second
            }
            live.detail to s
        } else CompSim.scoreText(e.discipline, perf, e.level, e.distance, rng)
        val (rank, of) = if (score <= -999f) e.field to e.field else CompSim.rank(score, e.discipline, e.level, e.field, e.distance, rng)
        val prize = if (score <= -999f) 0 else e.prize(rank)
        val r = CompResult(day, e.name, e.discipline, e.level, rank, of, text, prize, riderName)
        h.results.add(0, r)
        if (h.results.size > 60) h.results.removeAt(h.results.size - 1)
        if (prize > 0) { h.earnings += prize; earn(prize, "Gains de ${h.name} — ${e.name}", "Concours") }
        // Expérience de concours
        if (e.discipline.trainable) {
            val cap = h.skillCap(e.discipline, day)
            h.skills[e.discipline.ordinal] = min(cap, h.skills[e.discipline.ordinal] + 0.6f)
        }
        h.energy = max(5f, h.energy - (if (e.discipline == Discipline.ENDURANCE || e.discipline == Discipline.COMPLET) 45f else 28f))
        h.stress = min(100f, h.stress + 15f * (1.2f - h.pot(Trait.CALME) / 100f))
        h.cleanliness = max(0f, h.cleanliness - 15f)
        h.workToday += 60f
        if (entry.riderStaffId == -1) { rider.hoursRidden += 1f; rider.xp[e.discipline.ordinal] = min(60f, rider.xp[e.discipline.ordinal] + 0.6f) }
        val injuryRisk = 0.006f * weather.ground.injury * (if (e.discipline == Discipline.COMPLET || e.discipline == Discipline.COURSE) 2f else 1f) * (1f + (1f - h.energy / 100f))
        if (rng.chance(injuryRisk)) addAilment(h, if (rng.chance(0.3f)) AilmentType.TENDINITE else AilmentType.BOITERIE)
        val repGain = when { rank == 1 -> 1.5f + e.level * 0.8f; rank <= 3 -> 0.6f + e.level * 0.3f; rank <= of / 4 -> 0.2f; else -> 0f }
        reputation = (reputation + repGain).coerceAtMost(100f)
        if (rank <= 3 && score > -999f) {
            stat(St.PODIUM)
            h.confidence = min(100f, h.confidence + 4f)
        }
        if (rank == 1) {
            wins++
            stat(St.WIN)
            if (e.level >= 1) stat(St.WIN_AMATEUR)
            if (e.level >= 3) stat(St.WIN_PRO)
            if (e.level >= 5) stat(St.WIN_GP)
            if (e.level >= 4) h.titles += "Vainqueur ${e.name} (${Cal.date(day).year})"
        }
        val kind = if (rank <= 3 && score > -999f) MsgKind.GOOD else MsgKind.INFO
        val podium = when (rank) { 1 -> "VICTOIRE"; 2 -> "2e"; 3 -> "3e"; else -> "${rank}e/$of" }
        log("${e.name} : ${h.name} ($riderName) — $podium · $text${if (prize > 0) " · ${fmtMoney(prize)}" else ""}", kind, h.id)
        return r
    }

    /** Appelé par l'application après une épreuve montée en direct. */
    fun playLive(eventId: Int, horseId: Int, perf: LivePerformance): CompResult? {
        val e = events.firstOrNull { it.id == eventId } ?: return null
        val entry = e.entries.firstOrNull { it.horseId == horseId } ?: return null
        pendingLive.removeAll { it.first == eventId && it.second == horseId }
        e.entries.remove(entry)
        val r = runEntry(e, entry, perf)
        horse(horseId)?.place = Place.BOX
        return r
    }

    private fun competitionsMorning() {
        for (e in events.filter { it.day == day && !it.done }) {
            for (en in e.entries) {
                val h = horse(en.horseId) ?: continue
                if (!h.alive || h.injured) { log("${h.name} est forfait (${if (!h.alive) "décédé" else "blessé"}) : ${e.name}.", MsgKind.BAD, h.id); continue }
                h.place = Place.DEPLACEMENT
                val transport = if (level(BuildingType.CAMION) > 0) (e.km * 0.45f).toInt() else (e.km * 1.7f).toInt() + 60
                pay(transport, "Transport de ${h.name} à ${e.venue}", "Concours")
                if (en.live) pendingLive += e.id to h.id
            }
        }
    }

    private fun competitionsEvening() {
        for (e in events.filter { it.day == day && !it.done }) {
            for (en in e.entries.toList()) {
                val h = horse(en.horseId) ?: continue
                if (!h.alive || h.injured) continue
                runEntry(e, en)
                h.place = Place.BOX
            }
            e.done = true
        }
        pendingLive.clear()
        owned().filter { it.place == Place.DEPLACEMENT }.forEach { it.place = Place.BOX }
    }

    // ===================================================================== Personnel et bâtiments

    fun refreshCandidates() {
        candidates.clear()
        for (r in Role.values()) repeat(if (r == Role.PALEFRENIER || r == Role.CAVALIER) 2 else 1) {
            val skill = rng.weighted(doubleArrayOf(0.0, 2.0, 3.0, 2.0, 1.0, 0.4)).coerceAtLeast(1)
            val sal = (r.baseSalary * (0.8f + skill * 0.15f)).roundTo(50)
            val d = if (r == Role.CAVALIER) rng.pick(listOf(Discipline.CSO, Discipline.DRESSAGE, Discipline.COMPLET, Discipline.ENDURANCE, Discipline.ATTELAGE)) else null
            candidates += Staff(nextId++, Names.person(rng), r, skill, sal, d)
        }
    }

    fun hire(s: Staff): Res {
        if (staff.size >= 3 + level(BuildingType.ECURIE) * 2) return Res.no("Pas assez de logements pour le personnel : agrandissez l'écurie.")
        candidates.remove(s); staff += s
        stat(St.HIRE)
        log("${s.name} rejoint l'équipe comme ${s.role.label.lowercase()}.", MsgKind.GOOD)
        return Res.ok("${s.name} est embauché (${fmtMoney(s.salary)}/mois).")
    }

    fun fire(s: Staff): Res {
        staff.remove(s)
        pay(s.salary / 2, "Indemnités de ${s.name}", "Salaires")
        events.forEach { e -> e.entries.filter { it.riderStaffId == s.id }.forEach { it.live = false } }
        return Res.ok("${s.name} a quitté le domaine.")
    }

    fun build(b: BuildingType): Res {
        val lvl = level(b)
        if (lvl >= b.maxLevel) return Res.no("Niveau maximum atteint.")
        val cost = b.cost(lvl + 1)
        if (!payIfCan(cost, "Travaux : ${b.label} niveau ${lvl + 1}", "Travaux")) return Res.no("Fonds insuffisants (${fmtMoney(cost)})")
        levels[b.ordinal]++
        stat(St.BUILD)
        log("Les travaux sont terminés : ${b.label}${if (b.maxLevel > 1) " niveau ${levels[b.ordinal]}" else ""} !", MsgKind.GOOD)
        return Res.ok("${b.label} construit.")
    }

    fun itemPrice(i: Item): Float {
        val m = Cal.month(day)
        return when (i) {
            Item.FOIN -> i.price * (if (m in 5..7) 0.8f else if (m in listOf(0, 1, 2, 11)) 1.25f else 1f) * (if (weather.tempMax > 30) 1.1f else 1f)
            Item.PAILLE -> i.price * (if (m in 6..8) 0.85f else 1.1f)
            else -> i.price
        }
    }

    fun buyStock(i: Item, packs: Int = 1): Res {
        val qty = i.pack * packs
        val cost = (itemPrice(i) * qty).roundToInt()
        val bulky = i == Item.FOIN || i == Item.PAILLE
        if (bulky) {
            val used = count(Item.FOIN) + count(Item.PAILLE) * 15f
            if (used + qty * (if (i == Item.PAILLE) 15f else 1f) > stockCap()) return Res.no("La grange est pleine : agrandissez-la.")
        }
        if (!payIfCan(cost, "Achat : $qty ${i.unit} de ${i.label.lowercase()}", "Alimentation")) return Res.no("Fonds insuffisants")
        stock[i.ordinal] += qty.toFloat()
        return Res.ok("Livraison : $qty ${i.unit} de ${i.label.lowercase()} (${fmtMoney(cost)}).")
    }

    /** Jours d'autonomie restants pour un aliment. */
    fun daysOf(i: Item): Int {
        val perDay = when (i) {
            Item.FOIN -> owned().filter { it.weaned }.sumOf { (if (it.place == Place.PRE && it.turnout == Turnout.PERMANENT && grass > 30) it.ration.hay * 0.2f else it.ration.hay).toDouble() }.toFloat()
            Item.GRANULES -> owned().sumOf { it.ration.feed.toDouble() }.toFloat()
            Item.PAILLE -> owned().count { it.weaned && it.turnout != Turnout.PERMANENT }.toFloat()
            Item.MINERAUX -> owned().count { it.ration.minerals }.toFloat()
            Item.CAROTTES -> 0.5f
        }
        return if (perDay <= 0.01f) 999 else (count(i) / perDay).toInt()
    }

    // ===================================================================== Finances et journal

    fun pay(amount: Int, label: String, cat: String) { if (amount <= 0) return; money -= amount; ledger.add(Txn(day, label, -amount, cat)); trimLedger() }
    fun earn(amount: Int, label: String, cat: String) { if (amount <= 0) return; money += amount; ledger.add(Txn(day, label, amount, cat)); trimLedger() }
    private fun payIfCan(amount: Int, label: String, cat: String): Boolean { if (money < amount) return false; pay(amount, label, cat); return true }
    private fun trimLedger() { if (ledger.size > 600) ledger.subList(0, ledger.size - 600).clear() }

    fun log(text: String, kind: MsgKind = MsgKind.INFO, horseId: Int = -1) {
        messages.add(0, Msg(day, hourOfDay.toInt(), text, kind, horseId))
        if (messages.size > 300) messages.removeAt(messages.size - 1)
        if (kind != MsgKind.INFO) unread++
        listener?.invoke(messages[0])
    }

    /** Notifié à chaque nouveau message (sons, bandeaux). Non sauvegardé. */
    @Transient var listener: ((Msg) -> Unit)? = null

    private fun addAilment(h: Horse, t: AilmentType) {
        if (h.hasAilment(t)) return
        val days = if (t.chronic) 0f else t.days * rng.range(0.7f, 1.4f)
        h.ailments += Ailment(t, days, false, day, 1f)
        val kind = if (t.urgent) MsgKind.URGENT else MsgKind.BAD
        log("${h.name} : ${t.label.lowercase()} ! ${t.advice}", kind, h.id)
        if (!t.work) events.filter { !it.done }.forEach { e -> e.entries.removeAll { it.horseId == h.id } }
        if (t.urgent && staffOf(Role.SOIGNEUR).isNotEmpty()) {
            // Le soigneur appelle le vétérinaire tout de suite.
            if (money >= t.vet) {
                pay(t.vet + 60, "Urgence vétérinaire : ${h.name}", "Vétérinaire")
                h.ailments.last().treated = true
                log("Votre soigneur a appelé le vétérinaire en urgence pour ${h.name}.", MsgKind.INFO, h.id)
            }
        }
    }

    private fun killHorse(h: Horse, cause: String) {
        h.alive = false; h.deathDay = day; h.deathCause = cause
        h.forSale = false; h.clubHorse = false; h.studFee = 0
        offers.removeAll { it.horseId == h.id }
        events.forEach { e -> e.entries.removeAll { it.horseId == h.id } }
    }

    // ===================================================================== Écoulement du temps

    /** Avance de [hours] heures (fractionnaires) ; les règles s'appliquent à chaque heure pleine. */
    fun advance(hours: Double) {
        var remaining = hours
        while (remaining > 0) {
            val toNext = (hour + 1) - time
            if (remaining >= toNext) {
                time = (hour + 1).toDouble()
                remaining -= toNext
                tickHour()
            } else {
                time += remaining
                remaining = 0.0
            }
        }
    }

    /** Avance jusqu'au lendemain matin 7 h. */
    fun sleepUntilMorning() {
        stat(St.DAYS)
        val target = (day + 1) * 24.0 + 7.0
        advance(target - time)
    }

    private fun tickHour() {
        val h = hour % 24
        if (h == 0) newDay()
        val owned = owned()
        val temp = weather.tempAt(h.toFloat())
        val atPre = owned.filter { it.place == Place.PRE }
        val grassF = (grass / 100f).coerceIn(0f, 1f)
        val night = h < 6 || h >= 22
        // Le personnel travaille aux heures habituelles
        val grooms = staffOf(Role.PALEFRENIER)
        val groomCap = grooms.sumOf { 6 + it.skill }
        val careOrder = owned.sortedBy { it.careHourToday }
        val served = careOrder.take(groomCap).toSet()
        if (grooms.isNotEmpty()) {
            when (h) {
                7, 12, 19 -> for (x in served) if (x.weaned && x.place != Place.DEPLACEMENT) {
                    val share = when (h) { 7 -> 0.3f; 12 -> 0.2f; else -> 0.5f }
                    val feedShare = if (x.ration.feed > 3f) 0.34f else if (h == 12) 0f else 0.5f
                    val hayNeed = if (x.place == Place.PRE && grass > 35) x.ration.hay * share * 0.3f else x.ration.hay * share
                    giveMeal(x, hayNeed, x.ration.feed * feedShare)
                    x.bucket = 40f
                    x.careHourToday++
                }
                8 -> for (x in served) if (x.place == Place.BOX && x.turnout != Turnout.BOX && !weather.harsh && !x.injured && x.weaned) setPlace(x, Place.PRE)
                9 -> for (x in served) if (x.litter < 70 && count(Item.PAILLE) >= 1f && x.place != Place.DEPLACEMENT) { stock[Item.PAILLE.ordinal] -= 1f; x.litter = 100f }
                17 -> for (x in served) if (x.place == Place.PRE && (x.turnout == Turnout.JOUR || weather.harsh) && x.weaned) setPlace(x, Place.BOX)
            }
        }
        if (h == 10) staffWork(owned)
        if (h == 8) competitionsMorning()
        if (h == 20) competitionsEvening()
        if (h == 14 && Cal.date(day).dom % 3 == 0) { while (market.size < 14) addListing() }

        val horsesAtPre = atPre.size
        for (x in owned) {
            if (x.place == Place.DEPLACEMENT) { x.satiety = max(40f, x.satiety - 2f); x.hydration = max(50f, x.hydration - 1f); continue }
            val foalOnMilk = !x.weaned
            val heat = ((temp - 22f) / 10f).coerceIn(0f, 1.5f)
            // --- Alimentation
            if (foalOnMilk) {
                x.satiety = min(100f, x.satiety + 6f); x.hydration = min(100f, x.hydration + 6f)
                x.eatGrass += 0.05f
                if (x.place == Place.PRE) x.eatGrass += 0.1f
            } else {
                var ate = 0f
                if (x.place == Place.PRE && grassF > 0.08f) {
                    // Un cheval trop gros porte une muselière de pâturage (mise par le soigneur).
                    val muzzle = x.bcs > 6.8f && staffOf(Role.SOIGNEUR).isNotEmpty()
                    val kg = 0.6f * grassF * (if (night) 0.6f else 1f) * (if (muzzle) 0.35f else 1f)
                    x.eatGrass += kg; ate += kg
                    grass = max(0f, grass - kg * 0.06f / max(1, hectares()))
                    if (x.bcs > 6.5f && Cal.month(day) in 3..5 && rng.chance(0.0006f * (x.bcs - 6f))) addAilment(x, AilmentType.FOURBURE)
                }
                if (x.hayRack > 0f && ate < 0.7f) {
                    val kg = min(x.hayRack, (if (night) 0.42f else 0.75f) - ate)
                    x.hayRack -= kg; x.eatHay += kg; ate += kg
                }
                if (ate > 0.2f) { x.satiety = min(100f, x.satiety + ate * 12f); x.hungryHours = 0 }
                else { x.satiety = max(0f, x.satiety - 7f); x.hungryHours++ }
                // --- Eau
                val needL = 1.4f * (1f + heat) * (x.weight(day) / 500f) + x.workToday / 600f
                val freeWater = x.place == Place.PRE || level(BuildingType.ABREUVOIRS) > 0
                if (freeWater) x.hydration = min(100f, x.hydration + 8f)
                else if (x.bucket >= needL) { x.bucket -= needL; x.hydration = min(100f, x.hydration + 10f) }
                else { x.bucket = 0f; x.hydration = max(0f, x.hydration - 3.5f * (1f + heat)) }
                if (x.hungryHours > 6) x.riskAcc += 0.004f
                if (x.hydration < 25) x.riskAcc += 0.006f
            }
            // --- Propreté et litière
            if (x.place == Place.PRE) {
                x.cleanliness -= when { weather.ground == Ground.LOURD -> 1.5f; weather.rainy -> 1.1f; else -> 0.45f }
            } else {
                x.litter = max(0f, x.litter - (if (x.weaned) 2.1f else 0.6f))
                x.cleanliness -= if (x.litter < 30) 0.8f else 0.2f
            }
            x.cleanliness = x.cleanliness.coerceIn(0f, 100f)
            // --- Énergie
            x.energy = min(100f, x.energy + (if (night) 4.5f else 1.6f) * (if (x.litter < 25 && x.place == Place.BOX) 0.6f else 1f))
            // --- Moral : tend vers le confort du moment
            var comfort = 62f
            comfort += if (x.place == Place.PRE) (if (horsesAtPre > 1) 22f else -6f) else (if (owned.size > 1) 2f else -10f)
            if (Personality.SOCIABLE in x.personality) comfort += if (x.place == Place.PRE && horsesAtPre > 1) 8f else -10f
            if (x.satiety < 40) comfort -= (40 - x.satiety) * 0.7f
            if (x.hydration < 40) comfort -= (40 - x.hydration) * 0.7f
            if (x.place == Place.BOX && x.litter < 30) comfort -= 10f
            comfort -= x.pain * 45f
            comfort += x.bond * 0.1f
            comfort -= x.stress * 0.15f
            if (x.place == Place.PRE) {
                if (weather.harsh && !x.rugged) comfort -= if (Personality.FRILEUX in x.personality) 30f else 18f
                if (temp < 3f && x.clipped && !x.rugged) comfort -= 25f
                if (weather.sky == Sky.SOLEIL && temp in 10f..25f) comfort += 6f
            }
            if (temp > 30f && x.place == Place.PRE && x.hydration < 60) comfort -= 10f
            x.morale += (comfort - x.morale) * 0.06f
            x.morale = x.morale.coerceIn(0f, 100f)
            x.stress = max(5f, x.stress - 0.6f)
            // --- Urgences non traitées
            for (a in x.ailments) if (a.type.urgent && !a.treated) {
                a.severity = if (a.type in LETHAL) a.severity + 0.035f else min(1.6f, a.severity + 0.01f)
                if (a.severity > 2.1f) { killHorse(x, a.type.label + " non soignée"); log("Tristesse au domaine : ${x.name} est mort des suites d'une ${a.type.label.lowercase()} non soignée.", MsgKind.BAD, x.id); break }
            }
        }
        // Colique : déclenchée par le risque accumulé
        for (x in owned) if (x.alive && x.weaned && !x.hasAilment(AilmentType.COLIQUE)) {
            if (rng.chance(min(0.25f, x.riskAcc * 0.02f + 0.000008f))) { addAilment(x, AilmentType.COLIQUE); x.riskAcc = 0f }
        }
        // Coup de chaleur
        if (temp > 31f) for (x in owned) if (x.hydration < 45 && x.workToday > 30 && rng.chance(0.02f)) addAilment(x, AilmentType.COUP_DE_CHALEUR)
    }

    private fun staffWork(owned: List<Horse>) {
        // Soigneurs : pansage, pieds, couvertures
        val carers = staffOf(Role.SOIGNEUR)
        val careCap = carers.sumOf { 8 + it.skill }
        for (x in owned.sortedBy { it.cleanliness }.take(careCap)) {
            x.cleanliness = min(100f, x.cleanliness + 45f)
            x.hooves = min(100f, x.hooves + 0.5f)
            val cold = weather.tempMin < 4f
            x.rugged = (x.clipped && cold) || (cold && weather.rainy && Personality.FRILEUX in x.personality) || (x.age(day) > 22 && cold)
        }
        // Le soigneur ajuste les rations chaque lundi selon l'état corporel
        if (carers.isNotEmpty() && ((day).mod(7)) == 0) for (x in owned) if (x.weaned) {
            if (x.bcs > 6.3f && (x.ration.feed > 0f || x.ration.hay > x.weight(day) * 0.0155f)) {
                // On réduit d'abord les concentrés ; le fourrage ne descend jamais sous 1,5 % du poids.
                val hay = if (x.ration.feed > 0f) x.ration.hay else max(x.weight(day) * 0.015f, x.ration.hay - 0.5f)
                x.ration = Ration(hay, max(0f, x.ration.feed - 0.5f), x.ration.minerals)
            } else if (x.bcs < 4.2f) {
                x.ration = Ration(min(14f, x.ration.hay + 0.5f), min(5f, x.ration.feed + 0.5f), x.ration.minerals)
            }
        }
        // Cavaliers : travail selon le programme de chaque cheval
        val riders = staffOf(Role.CAVALIER) + staffOf(Role.LAD)
        val weekday = ((day).mod(7))
        var slots = riders.sumOf { 4 + it.skill / 2 }
        for (x in owned.filter { it.plan != null }.sortedByDescending { it.skills.maxOrNull() ?: 0f }) {
            if (slots <= 0) break
            if (weekday >= x.planSessions) continue
            val d = x.plan!!
            val r = riders.firstOrNull { it.discipline == d || (it.role == Role.LAD && d == Discipline.COURSE) } ?: riders.firstOrNull() ?: break
            val ex = when {
                !x.backed && x.age(day) >= 2.5f -> Exercise.DEBOURRAGE
                !x.backed -> if (x.handling < 100f) Exercise.MANIPULATION else continue
                weekday % 3 == 2 -> if (d == Discipline.COURSE) Exercise.GALOP else Exercise.PLAT
                weekday == 4 -> Exercise.EXTERIEUR
                else -> Exercise.forDiscipline(d)
            }
            if (canTrain(x, ex) != null) continue
            if (x.energy < 45f) continue
            train(x, ex, 35f + r.skill * 11f, byPlayer = false)
            slots--
        }
        // Moniteur : cours avec les chevaux de club
        val instructors = staffOf(Role.MONITEUR)
        if (instructors.isNotEmpty() && level(BuildingType.CLUB_HOUSE) > 0) {
            val clubs = owned.filter { it.clubHorse && it.backed && !it.injured && it.place != Place.DEPLACEMENT }
            val lessons = if (Cal.isWeekend(day) || weekday == 2) 3 else 1
            var income = 0
            for (c in clubs) {
                val n = if (c.energy > 50) lessons else 1
                income += n * (16 + level(BuildingType.CLUB_HOUSE) * 4) * (3 + instructors.maxOf { it.skill })
                c.energy = max(10f, c.energy - n * 12f)
                c.workToday += n * 60f
                c.fitness = min(80f, c.fitness + 0.4f * n)
                if (n >= 3) c.morale -= 4f
                if (rng.chance(0.002f * n)) addAilment(c, AilmentType.BOITERIE)
            }
            if (income > 0) earn(income, "Cours d'équitation (${clubs.size} chevaux de club)", "Club")
        }
        // Marcheur
        if (level(BuildingType.MARCHEUR) > 0) for (x in owned) if (x.weaned && x.age(day) > 1.5f && x.place == Place.BOX && !x.urgent) {
            x.fitness = min(70f, x.fitness + 0.25f); x.morale = min(100f, x.morale + 3f)
        }
    }

    private fun newDay() {
        lessonsToday = 0
        weather.next(day, rng)
        if (weather.sky != Sky.SOLEIL || weather.tempMax > 0) {
            val m = Cal.month(day)
            val growth = when (m) { 3, 4 -> 3.2f; 2, 5 -> 2.2f; 8, 9 -> 1.6f; 6, 7 -> if (weather.soil > 30) 1.4f else 0.3f; 10 -> 0.6f; else -> 0.1f }
            grass = min(100f, grass + growth * (0.5f + hectares() / 8f))
        }
        val owned = owned()
        val m = Cal.month(day)
        for (x in owned) dailyHorse(x, m)
        // Contagion
        val sick = owned.filter { it.ailments.any { a -> a.type.contagious } }
        if (sick.isNotEmpty()) for (x in owned) if (x !in sick) {
            val vaccinated = x.vaccineDue(day) > 0
            if (rng.chance(if (vaccinated) 0.004f else 0.035f)) addAilment(x, sick.first().ailments.first { it.type.contagious }.type)
        }
        // Nouveau mois
        if (Cal.date(day).dom == 1) newMonth()
        // Marché : expiration, offres d'achat sur nos chevaux en vente
        market.removeAll { it.expires < day }
        for (x in owned.filter { it.forSale }) {
            val v = value(x).toFloat()
            val ratio = x.askingPrice / max(1f, v)
            val p = (0.22f * exp(-(ratio - 0.9f) * 3.2f) * (0.6f + reputation / 100f)).coerceIn(0.005f, 0.6f)
            if (rng.chance(p) && offers.count { it.horseId == x.id } < 3) {
                val amount = (x.askingPrice * rng.range(0.82f, 1.02f)).coerceAtMost(v * 1.35f).roundTo(50)
                offers += Offer(x.id, Names.person(rng), amount, day + 5)
                log("Offre d'achat pour ${x.name} : ${fmtMoney(amount)} (valable 5 jours).", MsgKind.GOOD, x.id)
            }
        }
        offers.removeAll { it.expires < day }
        // Étalons du domaine proposés à la saillie (saison de monte)
        if (m in 1..6) for (x in owned.filter { it.stallion && it.studFee > 0 }) {
            val q = value(x) * 0.06f / max(100, x.studFee)
            val p = (0.06f * q * (0.5f + reputation / 60f)).coerceIn(0f, 0.45f)
            if (rng.chance(p)) { earn(x.studFee, "Saillie de ${x.name} (jument extérieure)", "Élevage"); x.energy -= 5f }
        }
        // Concours, candidats
        ensureEvents()
        if (Cal.date(day).dom % 7 == 1) refreshCandidates()
        if (Cal.date(day).dayOfYear == 0) refreshStuds(full = false)
        // Alerte stocks
        if (daysOf(Item.FOIN) in 0..3 && owned.isNotEmpty() && day % 2 == 0) log("Stock de foin bas : ${daysOf(Item.FOIN)} jour(s) restant(s) !", MsgKind.BAD)
        if (daysOf(Item.PAILLE) in 0..2 && owned.isNotEmpty() && day % 3 == 0) log("Stock de paille bas !", MsgKind.BAD)
        // Bien-être animal : un domaine négligé perd sa réputation
        val neglected = owned.count { it.satiety < 15 || it.hydration < 15 || it.bcs < 3f }
        if (neglected > 0) { reputation = max(0f, reputation - neglected * 0.5f); if (rng.chance(0.05f * neglected)) { pay(1500, "Amende : contrôle des services vétérinaires (bien-être animal)", "Divers"); log("Contrôle des services vétérinaires : chevaux en mauvais état, amende de 1 500 €.", MsgKind.BAD) } }
        if (Cal.date(day).dom % 10 == 0) gc()
    }

    private fun dailyHorse(x: Horse, month: Int) {
        val age = x.age(day)
        // --- Bilan énergétique → état corporel
        val w = x.weight(day)
        val intake = x.eatHay * 1.9f + x.eatFeed * 3.1f + x.eatGrass * (if (month in 3..5) 2.1f else 1.8f)
        var need = w * 0.038f * (1f + (x.workToday / 60f * 0.25f).coerceAtMost(0.9f))
        if (age < 3) need *= 1.3f
        x.pregnancy?.let { if (it.dueDay - day < 100) need *= 1.2f }
        if (owned().any { it.damId == x.id && !it.weaned }) need *= 1.6f
        val coldStress = weather.tempMin < 2f && x.place == Place.PRE && !x.rugged
        if (coldStress || (x.clipped && !x.rugged && weather.tempMin < 5f)) need *= 1.15f
        if (!x.weaned) {
            // le poulain tète : sa croissance dépend de l'état de sa mère
            val dam = horse(x.damId)
            x.bcs += ((dam?.bcs ?: 5f) - 0.2f - x.bcs) * 0.1f
        } else {
            val metabolic = if (Personality.GOURMAND in x.personality || x.breed.pony || x.breed == Breed.HAFLINGER) 0.92f else 1f
            val bal = (intake - need * metabolic) / need
            x.bcs += bal * (if (bal > 0) 0.05f else 0.07f)
        }
        if (x.teeth < 30) x.bcs -= 0.01f
        if (x.hasAilment(AilmentType.PARASITES)) x.bcs -= 0.02f
        x.bcs = x.bcs.coerceIn(1f, 9f)
        // Un poulain sous-alimenté ne grandira pas autant
        if (age < 3 && x.bcs < 3.8f) x.genome.env[Trait.TAILLE.ordinal] -= 0.03f
        if (x.ration.minerals) x.hooves += 0.1f
        x.eatHay = 0f; x.eatFeed = 0f; x.eatGrass = 0f
        // --- Usure et entretien
        x.hooves = max(0f, x.hooves - (if (x.shod) 1.9f else 1.5f) * (if (weather.ground == Ground.LOURD && x.place == Place.PRE) 1.4f else 1f))
        x.teeth = max(0f, x.teeth - 0.22f)
        if (x.workToday < 15f) { x.fitness = max(age.coerceAtMost(5f) * 3f, x.fitness - 0.3f); x.muscle = max(10f, x.muscle - 0.15f) }
        x.riskAcc *= 0.5f
        x.workToday = 0f; x.sessionsToday = 0; x.treatsToday = 0; x.careHourToday = 0
        if (x.pregnancy == null && month in 3..8 && x.clipped) x.clipped = false
        if (month == 4 && x.rugged && weather.tempMin > 8) x.rugged = false
        // Les compétences au-dessus du plafond (vieillissement) déclinent lentement
        for (d in Discipline.values()) if (d.trainable) {
            val cap = x.skillCap(d, day)
            if (x.skills[d.ordinal] > cap) x.skills[d.ordinal] -= 0.05f
            else if (x.lastTrainDay < day - 30 && x.skills[d.ordinal] > 0) x.skills[d.ordinal] -= 0.02f
        }
        // --- Maladies
        val robust = x.pot(Trait.ROBUSTESSE) / 60f
        fun risk(p: Float) = rng.chance(p / robust.coerceIn(0.5f, 1.8f))
        if (x.hooves < 25 || (x.litter < 20 && x.place == Place.BOX)) if (risk(0.004f)) addAilment(x, AilmentType.ABCES)
        if (x.place == Place.PRE && weather.ground == Ground.LOURD && x.cleanliness < 35) if (risk(0.006f)) addAilment(x, AilmentType.GALE_DE_BOUE)
        if (x.place == Place.PRE && x.weaned) {
            val dominant = owned().count { it.place == Place.PRE && Personality.DOMINANT in it.personality && it.id != x.id }
            if (risk(0.0006f + dominant * 0.0015f)) addAilment(x, AilmentType.PLAIE)
        }
        if ((x.hungryHours > 8 || x.stress > 60) && x.ration.feed > 3f) if (risk(0.006f)) addAilment(x, AilmentType.ULCERES)
        if (x.dewormDue(day) < -60 && risk(0.004f)) addAilment(x, AilmentType.PARASITES)
        if (x.vaccineDue(day) < 0 && risk(0.0005f)) addAilment(x, AilmentType.GRIPPE)
        if (risk(0.0001f)) addAilment(x, AilmentType.GOURME)
        if (x.hasAilment(AilmentType.PLAIE) && x.vaccineDue(day) < 0 && rng.chance(0.012f)) addAilment(x, AilmentType.TETANOS)
        if (age > 17 && rng.chance(0.0006f)) addAilment(x, AilmentType.ARTHROSE)
        if (x.genome.has(Locus.GREY) && age > 12 && rng.chance(0.0004f)) addAilment(x, AilmentType.MELANOME)
        // --- Guérison
        val infirmary = if (level(BuildingType.INFIRMERIE) > 0) 1.35f else 1f
        val it = x.ailments.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (a.type.chronic) continue
            a.daysLeft -= if (a.treated) infirmary * (if (level(BuildingType.MARCHEUR) > 0 && a.type in setOf(AilmentType.BOITERIE, AilmentType.TENDINITE)) 1.15f else 1f) else 0.35f
            if (!a.treated && !a.type.urgent && rng.chance(0.02f)) a.severity = min(1.6f, a.severity + 0.1f)
            if (a.daysLeft <= 0) { it.remove(); log("${x.name} est guéri : ${a.type.label.lowercase()}.", MsgKind.GOOD, x.id) }
        }
        if (x.hasAilment(AilmentType.TETANOS) && !x.ailments.first { it.type == AilmentType.TETANOS }.treated && rng.chance(0.08f)) {
            killHorse(x, "Tétanos"); log("${x.name} a succombé au tétanos. La vaccination annuelle l'aurait protégé.", MsgKind.BAD, x.id); return
        }
        // --- Reproduction
        x.pregnancy?.let { pg ->
            if (!pg.confirmed && day - pg.conceivedDay == 16) {
                pg.confirmed = true
                log("Échographie positive : ${x.name} est pleine ! Terme prévu vers le ${Cal.format(pg.dueDay)}.", MsgKind.GOOD, x.id)
            }
            if (rng.chance(0.0002f)) { x.pregnancy = null; log("${x.name} a avorté (perte embryonnaire).", MsgKind.BAD, x.id) }
            else if (day >= pg.dueDay) foaling(x)
        }
        if (x.mare && x.pregnancy == null && x.lastCovered == day - 16) log("Échographie : ${x.name} n'est pas pleine. Prochaines chaleurs dans ${x.nextHeat(day)} jours.", MsgKind.INFO, x.id)
        // Poulain O/O : syndrome du poulain blanc létal
        if (x.genome.lethal && day - x.birthDay >= 2) {
            killHorse(x, "Syndrome du poulain blanc létal (O/O)")
            log("${x.name} n'a pas survécu : syndrome du poulain blanc létal. Ses deux parents étaient porteurs du gène Frame overo (n/O). Faites tester l'ADN de vos reproducteurs !", MsgKind.BAD, x.id)
            return
        }
        // Sevrage automatique par l'équipe
        if (!x.weaned && day - x.birthDay > 210 && staffOf(Role.PALEFRENIER).isNotEmpty() && boxUsers() < boxes()) wean(x)
        // --- Vieillesse
        val life = x.breed.lifespan * (0.85f + x.pot(Trait.ROBUSTESSE) / 400f)
        if (age > life - 6) {
            val p = 0.00015f * exp((age - life) / 2.2f)
            if (rng.chance(p)) {
                killHorse(x, "Vieillesse")
                log("${x.name} s'est éteint paisiblement à ${age.toInt()} ans. Merci pour tout, ${x.name}.", MsgKind.BAD, x.id)
                return
            }
        }
        // Anniversaire administratif
        if (Cal.date(day).dayOfYear == 0) {
            val ac = x.ageClass(day)
            if (ac == 3 && !x.backed) log("${x.name} a 3 ans : c'est l'âge du débourrage.", MsgKind.INFO, x.id)
        }
    }

    private fun newMonth() {
        val sal = staff.sumOf { it.salary }
        pay(sal, "Salaires (${staff.size} employés)", "Salaires")
        val upkeep = BuildingType.values().sumOf { b -> if (level(b) > 0) b.upkeep * level(b) * 30 / 10 else 0 }
        pay(upkeep, "Entretien des installations, eau, électricité", "Charges")
        pay(150 + owned().size * 15, "Assurances et cotisations", "Charges")
        // Pensions : propriétaires extérieurs
        if (level(BuildingType.CLUB_HOUSE) > 0) {
            val free = boxes() - owned().count { it.weaned }
            val target = min(free, (reputation / 9f).toInt() * level(BuildingType.CLUB_HOUSE))
            boarders = max(0, target)
            if (boarders > 0) {
                val price = 420 + level(BuildingType.MANEGE) * 80 + level(BuildingType.CARRIERE) * 30
                earn(boarders * price, "Pensions ($boarders chevaux de propriétaires)", "Pension")
                stock[Item.FOIN.ordinal] = max(0f, count(Item.FOIN) - boarders * 270f)
                stock[Item.PAILLE.ordinal] = max(0f, count(Item.PAILLE) - boarders * 20f)
            }
        } else boarders = 0
        // Sponsors
        if (reputation >= 35) {
            val s = ((reputation - 30f) * 40f).toInt()
            earn(s, "Partenariat sponsor (réputation ${reputation.toInt()})", "Sponsors")
        }
        reputation = max(0f, reputation - 0.5f)
        if (money < 0) log("Votre compte est à découvert : la banque prélève des agios. Vendez un cheval ou réduisez les charges !", MsgKind.URGENT)
        if (money < 0) pay((-money * 0.015f).toInt() + 20, "Agios bancaires", "Divers")
    }

    /** Supprime les chevaux d'archive devenus inutiles (ni possédés, ni annoncés, ni ancêtres utiles). */
    fun gc() {
        val keep = HashSet<Int>()
        fun mark(id: Int, depth: Int) {
            if (id < 0 || depth > 5 || !keep.add(id)) return
            val h = horses[id] ?: return
            mark(h.sireId, depth + 1); mark(h.damId, depth + 1)
        }
        horses.values.filter { it.owned || (it.deathDay >= 0 && it.acquiredDay > 0 && day - it.deathDay < 400) }.forEach { mark(it.id, 0) }
        market.forEach { mark(it.horseId, 0) }
        studs.forEach { mark(it, 0) }
        horses.values.filter { it.owned }.forEach { h -> h.pregnancy?.let { mark(it.sireId, 0) } }
        horses.keys.retainAll(keep)
    }

    // ===================================================================== Sauvegarde

    fun toJson(): String = Json.write(linkedMapOf(
        "v" to 1, "seed" to rng.state.toString(), "name" to stableName, "t" to time, "money" to money, "rep" to reputation,
        "horses" to horses.values.map { it.toJson() }, "market" to market.map { it.toJson() }, "studs" to studs,
        "levels" to levels.toList(), "staff" to staff.map { it.toJson() }, "cand" to candidates.map { it.toJson() },
        "stock" to stock.toList(), "events" to events.map { it.toJson() }, "msgs" to messages.map { it.toJson() },
        "ledger" to ledger.map { it.toJson() }, "offers" to offers.map { it.toJson() }, "w" to weather.toJson(), "grass" to grass,
        "rider" to rider.toJson(), "boarders" to boarders, "nid" to nextId, "gu" to generatedUntil, "wins" to wins, "unread" to unread,
        "stats" to stats, "goals" to goalsDone.toList(), "junk" to junk.map { it.toJson() }, "lt" to lessonsToday,
    ))

    companion object {
        /** Affections mortelles en quelques heures sans vétérinaire. */
        val LETHAL = setOf(AilmentType.COLIQUE, AilmentType.TETANOS, AilmentType.COUP_DE_CHALEUR)

        fun fromJson(s: String): Game {
            @Suppress("UNCHECKED_CAST")
            val o = JObj(Json.parse(s) as Map<String, Any?>)
            val g = Game(0, o.str("name", "Haras de la Baie"))
            g.rng = Rng(o.long("seed"))
            g.time = o.double("t", 7.0); g.money = o.int("money"); g.reputation = o.float("rep")
            o.objs("horses").forEach { val h = Horse.fromJson(it); g.horses[h.id] = h }
            g.market.addAll(o.objs("market").map { Listing.fromJson(it) })
            g.studs.addAll(o.ints("studs").toList())
            o.ints("levels").copyInto(g.levels, 0, 0, min(g.levels.size, o.ints("levels").size))
            g.staff.addAll(o.objs("staff").map { Staff.fromJson(it) })
            g.candidates.addAll(o.objs("cand").map { Staff.fromJson(it) })
            o.floats("stock").let { it.copyInto(g.stock, 0, 0, min(it.size, g.stock.size)) }
            g.events.addAll(o.objs("events").map { CompEvent.fromJson(it) })
            g.messages.addAll(o.objs("msgs").map { Msg.fromJson(it) })
            g.ledger.addAll(o.objs("ledger").map { Txn.fromJson(it) })
            g.offers.addAll(o.objs("offers").map { Offer.fromJson(it) })
            o.obj("w")?.let { g.weather = Weather.fromJson(it) }
            g.grass = o.float("grass", 70f); g.rider = Rider.fromJson(o.obj("rider")); g.boarders = o.int("boarders")
            o.obj("stats")?.m?.forEach { (k, v) -> (v as? Double)?.let { g.stats[k] = it.toInt() } }
            g.goalsDone.addAll(o.list("goals").filterIsInstance<String>())
            g.junk.addAll(o.objs("junk").map { Junk.fromJson(it) })
            g.lessonsToday = o.int("lt")
            g.nextId = o.int("nid", 1); g.generatedUntil = o.int("gu", -1); g.wins = o.int("wins"); g.unread = o.int("unread")
            return g
        }
    }
}

fun fmtMoney(v: Int): String {
    val s = kotlin.math.abs(v).toString().reversed().chunked(3).joinToString(" ").reversed()
    return (if (v < 0) "−" else "") + s + " €"
}

fun fmt1(v: Float): String = "%.1f".format(v).replace('.', ',')
