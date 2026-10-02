package com.cheval.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeneticsTest {
    private fun genome(vararg loci: Pair<Locus, Int>): Genome {
        val g = Genome.random(Breed.SELLE_FRANCAIS, Rng(1))
        for (l in Locus.values()) { g.alleles[l.ordinal * 2] = 0; g.alleles[l.ordinal * 2 + 1] = 0 }
        g.alleles[Locus.EXTENSION.ordinal * 2] = 1; g.alleles[Locus.EXTENSION.ordinal * 2 + 1] = 1
        g.alleles[Locus.AGOUTI.ordinal * 2] = 1; g.alleles[Locus.AGOUTI.ordinal * 2 + 1] = 1
        g.breeding[Trait.NUANCE.ordinal] = 50f; g.env[Trait.NUANCE.ordinal] = 0f
        g.breeding[Trait.CRINS_LAVES.ordinal] = 30f; g.env[Trait.CRINS_LAVES.ordinal] = 0f
        for ((l, c) in loci) {
            g.alleles[l.ordinal * 2] = if (c >= 1) 1 else 0
            g.alleles[l.ordinal * 2 + 1] = if (c >= 2) 1 else 0
        }
        return g
    }

    @Test
    fun mendelianRatios() {
        // Ee × Ee : 25 % d'alezans (ee)
        val a = genome(Locus.EXTENSION to 1); val b = genome(Locus.EXTENSION to 1)
        val rng = Rng(42)
        val n = 20000
        val ee = (1..n).count { Genome.cross(a, b, 0f, 0f, rng).count(Locus.EXTENSION) == 0 }
        assertEquals(0.25, ee / n.toDouble(), 0.015)
        // n/Cr × n/Cr : 25 % double dilués
        val c1 = genome(Locus.CREAM to 1)
        val dd = (1..n).count { Genome.cross(c1, c1, 0f, 0f, rng).count(Locus.CREAM) == 2 }
        assertEquals(0.25, dd / n.toDouble(), 0.015)
    }

    @Test
    fun lethalWhiteOnlyFromTwoCarriers() {
        val carrier = genome(Locus.FRAME to 1)
        val clear = genome()
        val rng = Rng(7)
        assertTrue((1..2000).none { Genome.cross(carrier, clear, 0f, 0f, rng).lethal })
        val lethal = (1..20000).count { Genome.cross(carrier, carrier, 0f, 0f, rng).lethal }
        assertEquals(0.25, lethal / 20000.0, 0.015)
    }

    @Test
    fun coatNames() {
        assertEquals("Alezan", Coat.name(genome(Locus.EXTENSION to 0), 5f))
        assertEquals("Bai", Coat.name(genome(), 5f))
        val black = genome(); black.alleles[Locus.AGOUTI.ordinal * 2] = 0; black.alleles[Locus.AGOUTI.ordinal * 2 + 1] = 0
        assertEquals("Noir", Coat.name(black, 5f))
        assertEquals("Palomino", Coat.name(genome(Locus.EXTENSION to 0, Locus.CREAM to 1), 5f))
        assertEquals("Isabelle", Coat.name(genome(Locus.CREAM to 1), 5f))
        assertEquals("Crème (perlino)", Coat.name(genome(Locus.CREAM to 2), 5f))
        assertEquals("Bai dun", Coat.name(genome(Locus.DUN to 1), 5f))
        assertEquals("Bai, pie tobiano", Coat.name(genome(Locus.TOBIANO to 1), 5f))
        assertTrue(Coat.name(genome(Locus.GREY to 1), 0.5f).startsWith("Gris (né"))
        assertEquals("Gris pommelé", Coat.name(genome(Locus.GREY to 1), 4f))
        assertTrue(Coat.name(genome(Locus.LEOPARD to 1, Locus.PATN1 to 1), 5f).contains("léopard"))
    }

    @Test
    fun greyHorsesWhitenWithAge() {
        val g = genome(Locus.GREY to 1)
        assertTrue(Coat.greyLevel(g, 0.2f) < 0.05f)
        assertTrue(Coat.greyLevel(g, 10f) > 0.95f)
        assertTrue(Coat.look(g, 12f).greyLevel > Coat.look(g, 3f).greyLevel)
    }

    @Test
    fun quantitativeHeritability() {
        // La moyenne des produits suit la moyenne des parents (h² > 0)
        val rng = Rng(3)
        val good = Genome.random(Breed.SELLE_FRANCAIS, rng); val good2 = Genome.random(Breed.SELLE_FRANCAIS, rng)
        good.breeding[Trait.SAUT.ordinal] = 85f; good2.breeding[Trait.SAUT.ordinal] = 85f
        val bad = Genome.random(Breed.SELLE_FRANCAIS, rng); val bad2 = Genome.random(Breed.SELLE_FRANCAIS, rng)
        bad.breeding[Trait.SAUT.ordinal] = 50f; bad2.breeding[Trait.SAUT.ordinal] = 50f
        val g = (1..2000).map { Genome.cross(good, good2, 0f, 0f, rng).value(Trait.SAUT) }.average()
        val b = (1..2000).map { Genome.cross(bad, bad2, 0f, 0f, rng).value(Trait.SAUT) }.average()
        assertEquals(85.0, g, 1.0)
        assertEquals(50.0, b, 1.0)
    }

    @Test
    fun inbreedingCoefficient() {
        val game = Game(5)
        val sire = game.generateHorse(Breed.SELLE_FRANCAIS, Sex.ETALON, 10, withAncestors = false)
        val dam = game.generateHorse(Breed.SELLE_FRANCAIS, Sex.JUMENT, 10, withAncestors = false)
        val r = Rng(1)
        val son = Horse(game.nextId++, "Fils", Sex.ETALON, game.day - 1000, Breed.SELLE_FRANCAIS, Genome.cross(sire.genome, dam.genome, 0f, 0f, r), sire.id, dam.id)
        val daughter = Horse(game.nextId++, "Fille", Sex.JUMENT, game.day - 900, Breed.SELLE_FRANCAIS, Genome.cross(sire.genome, dam.genome, 0f, 0f, r), sire.id, dam.id)
        game.horses[son.id] = son; game.horses[daughter.id] = daughter
        // Plein frère × pleine sœur : F = 0,25 ; père × fille : F = 0,25 ; non apparentés : 0
        assertEquals(0.25f, game.inbreedingOf(son.id, daughter.id), 1e-4f)
        assertEquals(0.25f, game.inbreedingOf(sire.id, daughter.id), 1e-4f)
        assertEquals(0f, game.inbreedingOf(sire.id, dam.id), 1e-4f)
        val half = Horse(game.nextId++, "Demi", Sex.JUMENT, game.day - 800, Breed.SELLE_FRANCAIS, Genome.cross(sire.genome, dam.genome, 0f, 0f, r), sire.id, -1)
        game.horses[half.id] = half
        assertEquals(0.125f, game.inbreedingOf(son.id, half.id), 1e-4f)
    }

    @Test
    fun breedTraitsAreCoherent() {
        val rng = Rng(11)
        fun avg(b: Breed, t: Trait) = (1..400).map { Genome.random(b, rng).value(t) }.average()
        assertTrue(avg(Breed.PUR_SANG, Trait.VITESSE) > avg(Breed.PERCHERON, Trait.VITESSE) + 30)
        assertTrue(avg(Breed.PERCHERON, Trait.FORCE) > avg(Breed.ARABE, Trait.FORCE) + 30)
        assertTrue(avg(Breed.ISLANDAIS, Trait.TAILLE) < 140)
        // Le Haflinger est toujours alezan, le Frison (presque) toujours noir
        assertTrue((1..300).all { Genome.random(Breed.HAFLINGER, rng).count(Locus.EXTENSION) == 0 })
        assertTrue((1..300).count { Coat.name(Genome.random(Breed.FRISON, rng), 6f) == "Noir" } > 270)
    }

    @Test
    fun calendar() {
        assertEquals("1 mars 2027", Cal.format(0))
        assertEquals("1 janvier 2028", Cal.format(306))
        assertEquals(306, Cal.dayOf(2028, 0, 1))
        assertEquals('T', Names.yearLetter(2027))
        assertTrue(abs(Cal.sunset(120) - Cal.sunrise(120)) > 14f) // fin juin : longues journées
    }
}
