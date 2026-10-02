package com.cheval.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sons synthétisés au premier lancement (aucun fichier audio) : bruits de sabots sur différents sols,
 * hennissement, ébrouement, brosse, mastication, applaudissements, cloche du jury, barre qui tombe…
 */
class SoundFx(context: Context) {
    enum class S { CLICK, HOOF_SOFT, HOOF_HARD, NEIGH, SNORT, BRUSH, MUNCH, COIN, APPLAUSE, BELL, RAIL, JUMP, GOOD, BAD, WATER, PAGE, WHINNY_SHORT }

    private val rate = 22050
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = IntArray(S.values().size)
    private val last = LongArray(S.values().size)
    var enabled = true

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        for (s in S.values()) {
            val f = File(dir, "${s.name.lowercase()}_v1.wav")
            if (!f.exists()) writeWav(f, synth(s))
            ids[s.ordinal] = pool.load(f.absolutePath, 1)
        }
    }

    fun play(s: S, volume: Float = 1f, pitch: Float = 1f, minGapMs: Long = 40) {
        if (!enabled || volume <= 0.02f) return
        val now = System.currentTimeMillis()
        if (now - last[s.ordinal] < minGapMs) return
        last[s.ordinal] = now
        val v = volume.coerceIn(0f, 1f)
        pool.play(ids[s.ordinal], v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    private fun synth(s: S): ShortArray {
        val rnd = Random(s.ordinal * 7919 + 3)
        fun noise() = rnd.nextFloat() * 2 - 1
        return when (s) {
            S.CLICK -> tone(0.05f) { t -> sin2(t, 1400f) * env(t, 0.05f, 70f) * 0.3f + noise() * env(t, 0.01f, 300f) * 0.15f }
            S.PAGE -> { var lp = 0f; tone(0.18f) { t -> lp += (noise() - lp) * 0.5f; lp * sin(PI.toFloat() * t / 0.18f) * 0.25f } }
            // Sabot sur l'herbe ou le sable : choc sourd et étouffé
            S.HOOF_SOFT -> { var lp = 0f; tone(0.12f) { t -> lp += (noise() - lp) * 0.15f; lp * env(t, 0.12f, 40f) * 1.6f + sin2(t, 85f - t * 200f) * env(t, 0.12f, 35f) * 0.5f } }
            // Sabot ferré sur sol dur : claquement
            S.HOOF_HARD -> { var lp = 0f; tone(0.1f) { t -> lp += (noise() - lp) * 0.6f; lp * env(t, 0.1f, 60f) * 0.7f + sin2(t, 1900f) * env(t, 0.03f, 140f) * 0.25f + sin2(t, 160f) * env(t, 0.1f, 45f) * 0.4f } }
            // Hennissement : fondamentale qui monte puis descend avec un vibrato rapide et des harmoniques nasales
            S.NEIGH -> tone(1.5f) { t ->
                val f0 = when { t < 0.15f -> 600f + t * 2800f; t < 0.9f -> 1020f - (t - 0.15f) * 520f; else -> 630f - (t - 0.9f) * 380f }
                val vib = 1f + 0.06f * sin(2f * PI.toFloat() * 26f * t) * (if (t > 0.25f) 1f else 0.3f)
                val ph = 2f * PI.toFloat() * f0 * vib * t
                val v = sin(ph) * 0.45f + sin(ph * 2f) * 0.25f + sin(ph * 3f) * 0.12f + noise() * 0.06f
                v * (t / 0.05f).coerceAtMost(1f) * (1f - (t / 1.5f)).coerceAtLeast(0f) * 0.7f
            }
            S.WHINNY_SHORT -> tone(0.6f) { t ->
                val f0 = 420f + 160f * sin(PI.toFloat() * t / 0.6f)
                val ph = 2f * PI.toFloat() * f0 * (1f + 0.05f * sin(2f * PI.toFloat() * 18f * t)) * t
                (sin(ph) * 0.4f + sin(ph * 2) * 0.2f) * sin(PI.toFloat() * t / 0.6f) * 0.6f
            }
            // Ébrouement : souffle bruité par les naseaux
            S.SNORT -> { var lp = 0f; tone(0.55f) { t -> lp += (noise() - lp) * 0.35f; lp * (sin(PI.toFloat() * t / 0.55f)) * (0.6f + 0.4f * sin(2f * PI.toFloat() * 35f * t)) * 0.9f } }
            S.BRUSH -> { var hp = 0f; var prev = 0f; tone(0.22f) { t -> val n = noise(); hp = n - prev; prev = n; hp * sin(PI.toFloat() * t / 0.22f) * 0.22f } }
            S.MUNCH -> { var lp = 0f; tone(0.35f) { t -> lp += (noise() - lp) * 0.3f; lp * (if ((t * 9).toInt() % 2 == 0) env((t * 9) % 1f / 9f, 0.1f, 40f) else 0f) * 1.2f } }
            S.COIN -> tone(0.35f) { t -> val f = if (t < 0.08f) 1318f else 1760f; (sin2(t, f) * 0.3f + sin2(t, f * 2.01f) * 0.1f) * env(t, 0.35f, 9f) }
            S.APPLAUSE -> {
                val claps = FloatArray(260) { rnd.nextFloat() * 2.4f }
                tone(2.6f) { t ->
                    var v = 0f
                    for (c0 in claps) { val d = t - c0; if (d in 0f..0.03f) v += noise() * exp(-d * 160f) }
                    v * 0.35f * (1f - (t / 2.6f)).coerceAtLeast(0f)
                }
            }
            S.BELL -> tone(1.2f) { t -> (sin2(t, 880f) * 0.3f + sin2(t, 2214f) * 0.12f + sin2(t, 1320f) * 0.1f) * env(t, 1.2f, 3.5f) }
            S.RAIL -> { var lp = 0f; tone(0.5f) { t ->
                val k = if (t < 0.05f) 1f else if (t in 0.18f..0.24f) 0.7f else if (t in 0.33f..0.37f) 0.4f else 0f
                lp += (noise() - lp) * 0.4f; (lp * 1.3f + sin2(t, 300f) * 0.4f) * k } }
            S.JUMP -> { var lp = 0f; tone(0.45f) { t -> lp += (noise() - lp) * 0.08f; lp * sin(PI.toFloat() * t / 0.45f) * 1.5f } }
            S.GOOD -> tone(0.9f) { t ->
                val notes = floatArrayOf(523f, 659f, 784f, 1046f)
                val i = (t / 0.16f).toInt().coerceAtMost(3)
                (sin2(t, notes[i]) * 0.28f + sin2(t, notes[i] * 2) * 0.06f) * env(t - i * 0.16f, 0.5f, 4f)
            }
            S.BAD -> tone(0.7f) { t -> val f = if (t < 0.3f) 392f else 311f; (sin2(t, f) * 0.25f + sin2(t, f / 2) * 0.12f) * env(t - (if (t < 0.3f) 0f else 0.3f), 0.4f, 5f) }
            S.WATER -> { var lp = 0f; tone(0.6f) { t -> lp += (noise() - lp) * 0.25f; (lp * 0.5f + sin2(t, 700f + 300f * sin(t * 60f)) * 0.08f) * sin(PI.toFloat() * t / 0.6f) } }
        }
    }

    private fun sin2(t: Float, f: Float) = sin(2f * PI.toFloat() * f * t)
    private fun env(t: Float, len: Float, k: Float): Float {
        if (t < 0f) return 0f
        return (t / 0.004f).coerceAtMost(1f) * exp(-k * t) * (1f - (t / len).coerceIn(0f, 1f) * 0.2f)
    }

    private inline fun tone(seconds: Float, f: (Float) -> Float): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n) { i -> (f(i.toFloat() / rate).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val out = ByteArray(44 + dataLen)
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0); i32(4, 36 + dataLen)
        "WAVE".toByteArray().copyInto(out, 8); "fmt ".toByteArray().copyInto(out, 12)
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16)
        "data".toByteArray().copyInto(out, 36); i32(40, dataLen)
        for (i in pcm.indices) i16(44 + i * 2, pcm[i].toInt())
        FileOutputStream(f).use { it.write(out) }
    }
}
