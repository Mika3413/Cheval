package com.cheval.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {
    private fun newGame(seed: Long = 1): Game {
        val g = Game(seed)
        g.newGameSetup(withStaff = true)
        g.takeStarter(g.starterChoices()[0])
        return g
    }

    @Test
    fun setupIsSane() {
        val g = newGame()
        assertEquals(1, g.owned().size)
        assertTrue(g.market.size >= 10)
        assertTrue(g.studs.size >= 10)
        assertTrue(g.events.isNotEmpty())
        val h = g.owned()[0]
        assertTrue(h.backed)
        assertTrue(h.sireId >= 0 && g.horse(h.sireId) != null)
    }

    @Test
    fun neglectedHorseGetsHungryAndThirsty() {
        val g = Game(3); g.newGameSetup(withStaff = true)
        g.staff.clear()
        val h = g.starterChoices()[0]; g.takeStarter(h)
        h.place = Place.BOX; h.hayRack = 0f; h.bucket = 0f
        g.advance(30.0)
        assertTrue("satiété ${h.satiety}", h.satiety < 20)
        assertTrue("hydratation ${h.hydration}", h.hydration < 30)
        assertTrue(h.morale < 60)
        // On s'en occupe : il récupère
        g.feed(h); g.water(h); g.muck(h)
        g.advance(5.0)
        assertTrue(h.satiety > 30)
        assertTrue(h.hydration > 40)
    }

    @Test
    fun starvationLowersBodyCondition() {
        val g = Game(4); g.newGameSetup(withStaff = true); g.staff.clear()
        val h = g.starterChoices()[0]; g.takeStarter(h)
        val bcs0 = h.bcs
        h.turnout = Turnout.BOX
        repeat(10) { h.hayRack = 0f; h.bucket = 40f; g.advance(24.0); if (h.ailments.isNotEmpty()) g.callVet(h) }
        assertTrue("BCS ${h.bcs} vs $bcs0", h.bcs < bcs0 - 0.5f)
    }

    @Test
    fun trainingImprovesSkillAndTires() {
        val g = newGame(5)
        g.build(BuildingType.CARRIERE)
        val h = g.owned()[0]
        val s0 = h.skill(Discipline.CSO)
        val e0 = h.energy
        val r = g.train(h, Exercise.PARCOURS, 60f, true)
        assertTrue(r.msg, r.ok)
        assertTrue(h.skill(Discipline.CSO) > s0)
        assertTrue(h.energy < e0)
        g.train(h, Exercise.PLAT, 60f, true)
        assertFalse(g.train(h, Exercise.PLAT, 60f, true).ok) // max 2 séances par jour
    }

    @Test
    fun breedingProducesFoal() {
        val g = newGame(6)
        g.money = 200000
        val mare = g.generateHorse(Breed.SELLE_FRANCAIS, Sex.JUMENT, 8)
        g.horses[mare.id] = mare
        mare.owned = true; mare.ration = g.defaultRation(mare)
        // jusqu'au printemps et aux chaleurs
        var covered = false
        fun supplies() {
            if (g.daysOf(Item.FOIN) < 20) g.buyStock(Item.FOIN, 4)
            if (g.daysOf(Item.PAILLE) < 20) g.buyStock(Item.PAILLE, 2)
            if (g.daysOf(Item.GRANULES) < 20) g.buyStock(Item.GRANULES, 2)
            for (x in g.owned()) if (x.ailments.any { !it.treated }) g.callVet(x)
        }
        repeat(200) {
            supplies()
            if (!covered && mare.inHeat(g.day) && Cal.month(g.day) in 2..7) {
                val r = g.cover(mare, g.availableStallions().first { !it.owned }, followUp = true)
                assertTrue(r.msg, r.ok)
                if (mare.pregnancy != null) covered = true
            }
            g.advance(24.0)
        }
        assertTrue("jument pleine", covered)
        val pg = mare.pregnancy!!
        assertTrue(pg.confirmed)
        val len = pg.dueDay - pg.conceivedDay
        assertTrue(len in 318..368)
        while (g.day <= pg.dueDay + 1) { supplies(); g.advance(24.0) }
        val foals = g.horses.values.filter { it.damId == mare.id && it.birthDay >= pg.conceivedDay }
        assertEquals(1, foals.size)
        val foal = foals[0]
        assertEquals(Names.yearLetter(Cal.date(foal.birthDay).year), foal.name.first())
    }

    @Test
    fun competitionGivesResults() {
        val g = newGame(8)
        val h = g.owned()[0]
        h.lastVaccine = g.day
        g.rider.galop = 7
        val e = g.events.first { it.day > g.day && it.discipline == Discipline.CSO && it.level == 0 }
            .let { it } ?: g.events.first { it.day > g.day }
        val r = g.enter(e, h, -1, live = false)
        assertTrue(r.msg, r.ok)
        while (g.day <= e.day) g.advance(24.0)
        assertTrue(h.results.isNotEmpty())
        assertTrue(e.done)
    }

    @Test
    fun betterHorsesWinMore() {
        val rng = Rng(9)
        val g = Game(9)
        val star = g.generateHorse(Breed.SELLE_FRANCAIS, Sex.HONGRE, 9, withAncestors = false)
        val nag = g.generateHorse(Breed.CROISE, Sex.HONGRE, 9, withAncestors = false)
        star.skills[Discipline.CSO.ordinal] = 80f; nag.skills[Discipline.CSO.ordinal] = 30f
        fun avgRank(h: Horse) = (1..300).map {
            val p = CompSim.performance(h, Discipline.CSO, 60f, 0, Ground.BON, g.day, rng)
            val (_, s) = CompSim.scoreText(Discipline.CSO, p, 2, 0, rng)
            CompSim.rank(s, Discipline.CSO, 2, 30, 0, rng).first
        }.average()
        assertTrue(avgRank(star) < avgRank(nag) - 5)
    }

    @Test
    fun saveRoundTrip() {
        val g = newGame(10)
        g.advance(24.0 * 40)
        val s = g.toJson()
        val g2 = Game.fromJson(s)
        assertEquals(s, g2.toJson())
        assertEquals(g.money, g2.money)
        assertEquals(g.owned().size, g2.owned().size)
        // La suite de la simulation est identique (RNG sauvegardé)
        g.advance(24.0 * 10); g2.advance(24.0 * 10)
        assertEquals(g.toJson(), g2.toJson())
    }

    @Test
    fun yearsOfStaffManagedStableStayAlive() {
        val g = newGame(12)
        g.money = 400000
        g.build(BuildingType.CARRIERE); g.build(BuildingType.ABREUVOIRS); g.build(BuildingType.ECURIE)
        g.candidates.firstOrNull { it.role == Role.SOIGNEUR }?.let { g.hire(it) }
        g.candidates.firstOrNull { it.role == Role.CAVALIER }?.let { g.hire(it) }
        repeat(4) { g.buy(g.market.first { l -> g.horse(l.horseId)!!.age(g.day) >= 4 }) }
        for (h in g.owned()) { g.vaccinate(h); g.deworm(h); h.plan = h.bestDiscipline(); h.turnout = Turnout.JOUR }
        repeat(365 * 2) {
            if (g.daysOf(Item.FOIN) < 20) g.buyStock(Item.FOIN, 10)
            if (g.daysOf(Item.PAILLE) < 20) g.buyStock(Item.PAILLE, 4)
            if (g.daysOf(Item.GRANULES) < 20) g.buyStock(Item.GRANULES, 4)
            if (g.daysOf(Item.MINERAUX) < 10) g.buyStock(Item.MINERAUX, 1)
            for (h in g.owned()) {
                if (h.ailments.any { !it.treated }) g.callVet(h)
                if (h.vaccineDue(g.day) < 0) g.vaccinate(h)
                if (h.dewormDue(g.day) < 0) g.deworm(h)
                if (h.farrierDue(g.day) < 0) g.farrier(h, h.shod)
                if (h.dentistDue(g.day) < 0) g.dentist(h)
            }
            g.advance(24.0)
        }
        val alive = g.owned()
        assertTrue("chevaux vivants : ${alive.size}", alive.size >= 3)
        for (h in alive) {
            assertTrue("${h.name} BCS ${h.bcs}", h.bcs in 3.5f..7.5f)
            if (h.ailments.isEmpty()) assertTrue("${h.name} moral ${h.morale}", h.morale > 30)
        }
        val trained = alive.filter { it.age(g.day) > 5 }.maxOf { it.skills.maxOrNull() ?: 0f }
        assertTrue("progression $trained", trained > 30)
        assertNotNull(g.toJson())
    }
}

class DayLoopTest {
    @Test
    fun dayBudgetJunkLessonsAndGoals() {
        val g = Game(77); g.newGameSetup()
        val h = g.starterChoices()[0]; g.takeStarter(h)
        org.junit.Assert.assertEquals(2, g.boxes())
        org.junit.Assert.assertEquals(8, g.junk.size)
        // le temps de la journée est limité
        val left = g.hoursLeft()
        org.junit.Assert.assertTrue(left in 13.5f..14.1f)
        org.junit.Assert.assertTrue(g.cleanJunk(g.junk.first().id).ok)
        org.junit.Assert.assertTrue(g.hoursLeft() < left)
        // nourrir, panser : premiers objectifs
        g.feed(h); g.groom(h, 1f)
        val first = Goals.active(g).first()
        org.junit.Assert.assertTrue(first.done(g))
        val m0 = g.money
        org.junit.Assert.assertTrue(g.claimGoal(first).ok)
        org.junit.Assert.assertTrue(g.money > m0)
        // un cours d'équitation rapporte de l'argent
        val m1 = g.money
        val r = g.giveLesson(listOf(h))
        org.junit.Assert.assertTrue(r.msg, r.ok)
        org.junit.Assert.assertTrue(g.money > m1)
        // la journée finit par manquer de temps
        while (g.spend(1f)) {}
        org.junit.Assert.assertFalse(g.giveLesson(listOf(h)).ok)
        g.sleepUntilMorning()
        org.junit.Assert.assertEquals(7, g.hourOfDay.toInt())
        org.junit.Assert.assertEquals(0, g.lessonsToday)
        // sauvegarde des nouveaux champs
        val g2 = Game.fromJson(g.toJson())
        org.junit.Assert.assertEquals(g.junk.size, g2.junk.size)
        org.junit.Assert.assertEquals(g.goalsDone, g2.goalsDone)
        org.junit.Assert.assertEquals(g.stats, g2.stats)
    }

    @Test
    fun newDisciplinesHaveBreedAptitudes() {
        val g = Game(5)
        val trot = g.generateHorse(Breed.TROTTEUR, Sex.HONGRE, 5, withAncestors = false)
        val perch = g.generateHorse(Breed.PERCHERON, Sex.HONGRE, 5, withAncestors = false)
        org.junit.Assert.assertTrue(Discipline.TROT_ATTELE.potential(trot) > Discipline.TROT_ATTELE.potential(perch) + 15)
        for (d in Discipline.values()) for (l in 0..5) org.junit.Assert.assertTrue(Levels.name(d, l).isNotEmpty())
    }

    @Test
    fun hauteEcoleEtCoursAvance() {
        val g = Game(91); g.newGameSetup(withStaff = true)
        val h = g.starterChoices().first { it.backed }; g.takeStarter(h)
        h.energy = 90f
        h.skills[Discipline.DRESSAGE.ordinal] = 20f
        // le piaffer et le passage demandent un cheval confirmé
        org.junit.Assert.assertNotNull(g.canTrain(h, Exercise.HAUTE_ECOLE))
        g.rider.galop = 2
        org.junit.Assert.assertFalse(g.giveLesson(listOf(h), advanced = true).ok)
        h.skills[Discipline.DRESSAGE.ordinal] = 60f
        g.rider.galop = 5
        g.weather = Weather()
        org.junit.Assert.assertNull(g.canTrain(h, Exercise.HAUTE_ECOLE))
        val m0 = g.money
        val r = g.giveLesson(listOf(h), advanced = true, demo = 1f)
        org.junit.Assert.assertTrue(r.msg, r.ok)
        org.junit.Assert.assertTrue(g.money > m0)
    }
}
