package com.cheval.core

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Générateur pseudo-aléatoire déterministe (SplitMix64) dont l'état tient dans un Long :
 * il est sauvegardé avec la partie, ce qui rend toute la simulation reproductible.
 */
class Rng(var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniforme dans [0, 1). */
    fun float(): Float = ((nextLong() ushr 40).toFloat() / (1 shl 24).toFloat())
    fun double(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()
    fun int(bound: Int): Int = if (bound <= 1) 0 else ((nextLong() ushr 1) % bound).toInt()
    fun range(from: Int, toInclusive: Int): Int = from + int(toInclusive - from + 1)
    fun range(from: Float, to: Float): Float = from + float() * (to - from)
    fun chance(p: Float): Boolean = float() < p
    fun chance(p: Double): Boolean = double() < p
    fun <T> pick(list: List<T>): T = list[int(list.size)]
    fun <T> pick(arr: Array<T>): T = arr[int(arr.size)]

    /** Loi normale centrée réduite (Box-Muller). */
    fun gauss(): Double {
        var u = double()
        if (u < 1e-12) u = 1e-12
        val v = double()
        return sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
    }

    fun gauss(mean: Double, sd: Double): Double = mean + sd * gauss()

    /** Tirage pondéré : retourne l'indice choisi. */
    fun weighted(weights: DoubleArray): Int {
        val total = weights.sum()
        var r = double() * total
        for (i in weights.indices) {
            r -= weights[i]
            if (r < 0) return i
        }
        return weights.size - 1
    }

    fun fork(salt: Long): Rng = Rng(nextLong() xor salt)
}
