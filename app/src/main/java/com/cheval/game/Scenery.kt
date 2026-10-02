package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.cheval.core.Coat
import com.cheval.core.Rng
import com.cheval.core.Season
import com.cheval.core.Sky
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Ambiance du moment : heure, saison, météo. */
class Ambience(
    var hour: Float = 12f,
    var season: Season = Season.PRINTEMPS,
    var sky: Sky = Sky.SOLEIL,
    var sunrise: Float = 6.5f,
    var sunset: Float = 20.5f,
    var temp: Float = 15f,
    var wind: Float = 15f,
    var snowGround: Boolean = false,
) {
    /** Luminosité 0 (nuit) … 1 (plein jour), avec aube et crépuscule progressifs. */
    val light: Float
        get() {
            val dawn = ((hour - (sunrise - 0.7f)) / 1.4f).coerceIn(0f, 1f)
            val dusk = (((sunset + 0.7f) - hour) / 1.4f).coerceIn(0f, 1f)
            var l = min(dawn, dusk)
            if (sky == Sky.PLUIE || sky == Sky.NUAGEUX) l *= 0.85f
            if (sky == Sky.ORAGE || sky == Sky.TEMPETE) l *= 0.65f
            if (sky == Sky.BROUILLARD) l *= 0.8f
            return 0.12f + 0.88f * l
        }
    /** 0..1 : intensité de la lumière dorée (aube, coucher). */
    val golden: Float
        get() = max(1f - abs(hour - sunrise) / 1.3f, 1f - abs(hour - sunset) / 1.3f).coerceIn(0f, 1f) * (if (sky == Sky.SOLEIL) 1f else 0.4f)
}

/** Décors peints par le code : ciel, mer de la baie, collines, haies, arbres, clôtures, bâtiments, météo. */
object Scenery {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val r = RectF()

    fun mix(a: Int, b: Int, t: Float) = Coat.mix(a, b, t)
    fun lit(c: Int, amb: Ambience): Int {
        val l = amb.light
        var col = HorseArt.shade(c, 0.25f + 0.75f * l)
        val night = 1f - l
        col = mix(col, Color.rgb(20, 30, 60), night * 0.35f)
        if (amb.golden > 0f) col = mix(col, Color.rgb(255, 170, 90), amb.golden * 0.18f)
        return col or (0xFF shl 24)
    }

    // ------------------------------------------------------------------ ciel
    fun sky(c: Canvas, w: Float, horizon: Float, amb: Ambience, t: Float) {
        val l = amb.light
        val overcast = when (amb.sky) { Sky.SOLEIL -> 0f; Sky.NUAGEUX -> 0.45f; Sky.BROUILLARD -> 0.8f; Sky.NEIGE -> 0.7f; else -> 0.75f }
        var top = mix(Color.rgb(62, 120, 196), Color.rgb(140, 150, 162), overcast)
        var bot = mix(Color.rgb(176, 210, 236), Color.rgb(196, 200, 204), overcast)
        if (amb.golden > 0f) { bot = mix(bot, Color.rgb(255, 176, 110), amb.golden * 0.75f); top = mix(top, Color.rgb(120, 110, 170), amb.golden * 0.45f) }
        top = mix(Color.rgb(8, 12, 30), top, l); bot = mix(Color.rgb(24, 32, 58), bot, l)
        p.color = -1; p.shader = LinearGradient(0f, 0f, 0f, horizon, top, bot, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, horizon + 2f, p)
        p.shader = null
        // étoiles
        if (l < 0.45f && overcast < 0.5f) {
            val rr = Rng(7)
            p.color = Color.argb(((0.45f - l) / 0.45f * 220).toInt(), 255, 255, 240)
            repeat(90) {
                val x = rr.float() * w; val y = rr.float() * horizon * 0.85f
                val tw = 0.6f + 0.4f * sin(t * 2f + it)
                c.drawCircle(x, y, (0.6f + rr.float() * 1.3f) * tw, p)
            }
        }
        // soleil / lune
        val dayLen = amb.sunset - amb.sunrise
        val k = ((amb.hour - amb.sunrise) / dayLen)
        if (k in -0.05f..1.05f && overcast < 0.7f) {
            val sx = w * (0.1f + 0.8f * k); val sy = horizon - sin(PI.toFloat() * k.coerceIn(0f, 1f)) * horizon * 0.8f
            p.color = -1; p.shader = RadialGradient(sx, sy, horizon * 0.3f, Color.argb((120 * (1 - overcast)).toInt(), 255, 236, 180), Color.argb(0, 255, 236, 180), Shader.TileMode.CLAMP)
            c.drawCircle(sx, sy, horizon * 0.3f, p)
            p.shader = null
            p.color = mix(Color.rgb(255, 248, 225), Color.rgb(255, 170, 90), amb.golden)
            c.drawCircle(sx, sy, horizon * 0.045f, p)
        } else if (l < 0.5f) {
            val mk = ((amb.hour + 24f - amb.sunset) % 24f) / (24f - dayLen)
            val mx = w * (0.15f + 0.7f * mk); val my = horizon * (0.5f - 0.35f * sin(PI.toFloat() * mk))
            p.color = Color.argb(230, 236, 236, 222); c.drawCircle(mx, my, horizon * 0.035f, p)
            p.color = mix(Color.rgb(8, 12, 30), Color.rgb(24, 32, 58), 0.3f); c.drawCircle(mx + horizon * 0.015f, my - horizon * 0.008f, horizon * 0.032f, p)
        }
        // nuages qui dérivent avec le vent
        val nClouds = when (amb.sky) { Sky.SOLEIL -> 4; Sky.NUAGEUX -> 9; Sky.BROUILLARD -> 3; else -> 12 }
        val cloudCol = mix(mix(Color.WHITE, Color.rgb(120, 126, 136), overcast * 0.9f), Color.rgb(30, 36, 56), 1f - l)
        val rr = Rng(11)
        for (i in 0 until nClouds) {
            val speed = 4f + amb.wind * 0.4f
            val baseX = rr.float() * (w + 600f)
            val x = ((baseX + t * speed * (0.6f + rr.float())) % (w + 600f)) - 300f
            val y = horizon * (0.12f + rr.float() * 0.5f)
            val s = horizon * (0.08f + rr.float() * 0.1f)
            cloud(c, x, y, s, HorseArt.alpha(cloudCol, 0.85f))
        }
    }

    fun cloud(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        p.shader = null; p.color = col
        c.drawOval(x - s * 2.2f, y - s * 0.5f, x + s * 2.2f, y + s * 0.6f, p)
        c.drawCircle(x - s * 0.9f, y - s * 0.35f, s * 0.8f, p)
        c.drawCircle(x + s * 0.3f, y - s * 0.6f, s * 1.0f, p)
        c.drawCircle(x + s * 1.3f, y - s * 0.2f, s * 0.7f, p)
        p.color = HorseArt.alpha(Color.BLACK, 0.06f)
        c.drawOval(x - s * 2f, y + s * 0.1f, x + s * 2f, y + s * 0.6f, p)
    }

    // ------------------------------------------------------------------ paysage
    fun grassColor(amb: Ambience): Int = when {
        amb.snowGround -> Color.rgb(236, 240, 244)
        amb.season == Season.PRINTEMPS -> Color.rgb(98, 156, 62)
        amb.season == Season.ETE -> Color.rgb(132, 152, 64)
        amb.season == Season.AUTOMNE -> Color.rgb(112, 130, 62)
        else -> Color.rgb(98, 120, 76)
    }

    fun foliage(amb: Ambience, i: Int): Int = when (amb.season) {
        Season.PRINTEMPS -> if (i % 3 == 0) Color.rgb(120, 170, 80) else Color.rgb(74, 128, 60)
        Season.ETE -> Color.rgb(56, 98, 48)
        Season.AUTOMNE -> when (i % 3) { 0 -> Color.rgb(196, 120, 40); 1 -> Color.rgb(170, 70, 34); else -> Color.rgb(140, 120, 50) }
        Season.HIVER -> Color.rgb(96, 88, 80)
    }

    /** Collines lointaines (parallaxe). */
    fun hills(c: Canvas, w: Float, baseY: Float, height: Float, scroll: Float, seed: Int, col: Int) {
        val rr = Rng(seed.toLong())
        val a1 = rr.range(0.002f, 0.004f); val a2 = rr.range(0.006f, 0.01f); val ph = rr.range(0f, 6f)
        path.reset(); path.moveTo(0f, baseY + 2f)
        var x = 0f
        while (x <= w + 8f) {
            val wx = (x + scroll) / max(1f, height / 60f)
            val y = baseY - height * (0.55f + 0.3f * sin(wx * a1 + ph) + 0.15f * sin(wx * a2 + ph * 2))
            path.lineTo(x, y); x += 8f
        }
        path.lineTo(w, baseY + 2f); path.close()
        p.shader = null; p.color = col
        c.drawPath(path, p)
        c.save(); c.clipPath(path); Ink.grain(c, RectF(0f, baseY - height * 2f, w, baseY + 4f), 110); c.restore()
        c.drawPath(path, Ink.stroke(HorseArt.alpha(Ink.INK, 0.45f), 1.4f))
    }

    /** La mer de la baie, avec reflets et vagues. */
    fun sea(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float, amb: Ambience, t: Float) {
        val base = lit(if (amb.sky == Sky.SOLEIL) Color.rgb(70, 132, 168) else Color.rgb(96, 118, 128), amb)
        p.color = -1; p.shader = LinearGradient(0f, top, 0f, bottom, HorseArt.lighten(base, 0.2f), HorseArt.shade(base, 0.8f), Shader.TileMode.CLAMP)
        c.drawRect(x0, top, x1, bottom, p)
        p.shader = null
        sp.strokeWidth = 1.5f
        val rr = Rng(3)
        for (i in 0 until 40) {
            val y = top + (bottom - top) * rr.float()
            val x = x0 + (x1 - x0) * ((rr.float() + t * 0.01f * (1 + i % 3)) % 1f)
            val len = 10f + 30f * ((y - top) / (bottom - top))
            sp.color = Color.argb((60 + 80 * amb.light).toInt(), 255, 255, 255)
            c.drawLine(x, y, x + len, y, sp)
        }
        // crêtes de vagues : petites courbes encrées avec leur écume
        val rc = Rng(11)
        for (i in 0 until 26) {
            val f = rc.float()
            val y = top + (bottom - top) * (0.15f + f * 0.8f)
            val sc = (0.5f + f * 1.2f) * (x1 - x0) / 800f
            val x = x0 + (x1 - x0) * ((rc.float() + t * 0.004f * (1 + i % 2)) % 1f)
            path.reset(); path.moveTo(x - 14f * sc, y); path.quadTo(x - 4f * sc, y - 5f * sc, x + 4f * sc, y - 2f * sc); path.quadTo(x + 8f * sc, y - 1f * sc, x + 10f * sc, y + 1f * sc)
            c.drawPath(path, Ink.stroke(HorseArt.alpha(HorseArt.shade(base, 0.6f), 0.6f), 1.1f * sc))
            c.drawPath(path, Ink.stroke(Color.argb((90 * amb.light + 30).toInt(), 255, 255, 255), 0.6f * sc))
        }
        // scintillements du soleil
        if (amb.sky == Sky.SOLEIL && amb.light > 0.5f) {
            val rs = Rng(21)
            p.color = Color.argb(200, 255, 252, 230)
            for (i in 0 until 30) {
                val x = x0 + (x1 - x0) * rs.float(); val y = top + (bottom - top) * rs.float() * 0.6f
                val tw = (sin(t * 3f + i * 1.7f) + 1f) * 0.5f
                if (tw > 0.6f) c.drawCircle(x, y, (1.2f + tw) * (x1 - x0) / 800f, p)
            }
        }
        // sable mouillé, puis écume festonnée qui va et vient
        val wave = sin(t * 0.8f) * 6f
        p.color = Color.argb(70, 60, 50, 40); c.drawRect(x0, bottom - 4f, x1, bottom + 10f + wave, p)
        path.reset()
        val fy = bottom - 3f + wave * 0.6f
        path.moveTo(x0, fy + 6f)
        var fx = x0
        while (fx < x1) { path.quadTo(fx + 9f, fy - 4f + sin(fx * 0.05f + t) * 2f, fx + 18f, fy + 1f); fx += 18f }
        path.lineTo(x1, fy + 6f); path.close()
        p.color = Color.argb((160 * amb.light + 50).toInt(), 250, 250, 245); c.drawPath(path, p)
        sp.color = Color.argb((140 * amb.light + 40).toInt(), 250, 250, 245); sp.strokeWidth = 3f
        c.drawLine(x0, bottom - 2f + wave * 0.3f, x1, bottom - 2f + wave * 0.3f, sp)
        // bulles d'écume
        val rb = Rng(7)
        p.color = Color.argb((150 * amb.light + 30).toInt(), 255, 255, 255)
        val us = (x1 - x0) / 800f
        for (i in 0 until 60) { c.drawCircle(x0 + (x1 - x0) * rb.float(), fy + (4f + rb.float() * 6f) * us, (1f + rb.float() * 1.5f) * us, p) }
    }

    fun tree(c: Canvas, x: Float, ground: Float, size: Float, amb: Ambience, seed: Int, poplar: Boolean = false) {
        val k = size / 80f
        val trunk = lit(Color.rgb(110, 84, 60), amb)
        path.reset(); path.moveTo(x - size * 0.06f, ground); path.lineTo(x - size * 0.035f, ground - size * 0.65f); path.lineTo(x + size * 0.04f, ground - size * 0.65f); path.lineTo(x + size * 0.07f, ground); path.close()
        Ink.wash(c, path, trunk, 1.2f * k, 0.3f, 60)
        val rr = Rng(seed.toLong())
        if (amb.season == Season.HIVER && !poplar) {
            repeat(7) { val a = -PI.toFloat() / 2 + rr.range(-0.9f, 0.9f); val l = size * rr.range(0.3f, 0.55f); Ink.line(c, x, ground - size * 0.5f, x + cos(a) * l, ground - size * 0.5f + sin(a) * l, 1.4f * k, trunk) }
            return
        }
        val col = lit(foliage(amb, seed), amb)
        if (poplar) { Ink.wash(c, Ink.blobPath(x, ground - size * 0.85f, size * 0.17f, size * 0.55f, seed.toLong(), 8, 0.12f), col, 1.2f * k, 0.3f, 120); return }
        // branches maîtresses, masse d'ombre, puis touffes éclairées par le haut
        Ink.line(c, x, ground - size * 0.55f, x - size * 0.22f, ground - size * 0.8f, 2.2f * k, trunk)
        Ink.line(c, x + size * 0.01f, ground - size * 0.58f, x + size * 0.24f, ground - size * 0.86f, 2f * k, trunk)
        Ink.line(c, x - size * 0.015f, ground - size * 0.1f, x - size * 0.01f, ground - size * 0.5f, 0.8f * k, HorseArt.alpha(Ink.INK, 0.4f))
        Ink.wash(c, Ink.blobPath(x, ground - size * 0.88f, size * 0.55f, size * 0.34f, seed * 5L, 10, 0.2f), HorseArt.shade(col, 0.72f), 1.2f * k, 0.3f, 110)
        for (b2 in 0 until 6) {
            val ang = b2 / 6f * 6.28f + rr.range(-0.3f, 0.3f)
            val bx = x + cos(ang) * size * 0.3f; val by = ground - size * 0.97f + sin(ang) * size * 0.18f
            val up = ((ground - size * 0.97f) - by) / (size * 0.18f)
            Ink.wash(c, Ink.blobPath(bx, by, size * rr.range(0.24f, 0.3f), size * rr.range(0.19f, 0.24f), rr.nextLong(), 8, 0.28f), HorseArt.shade(col, 0.9f + up * 0.08f + rr.range(-0.03f, 0.05f)), 1.1f * k, 0.3f, 120)
        }
        Ink.wash(c, Ink.blobPath(x - size * 0.05f, ground - size * 1.08f, size * 0.28f, size * 0.22f, seed * 7L, 8, 0.25f), HorseArt.lighten(col, 0.05f), 1.1f * k, 0.3f, 120)
        c.drawPath(Ink.blobPath(x - size * 0.15f, ground - size * 1.15f, size * 0.16f, size * 0.1f, seed * 3L, 6, 0.3f), Ink.fill(HorseArt.alpha(Color.rgb(230, 240, 180), 0.3f)))
        for (q in 0 until 14) {
            val a = rr.range(0f, 6.28f); val d = rr.range(0.05f, 0.45f) * size
            val lx = x + cos(a) * d; val ly = ground - size * 0.97f + sin(a) * d * 0.6f
            path.reset(); path.moveTo(lx, ly); path.quadTo(lx + 2.5f * k, ly - 2.5f * k, lx + 5f * k, ly + 0.5f * k)
            c.drawPath(path, Ink.stroke(if (ly < ground - size * 0.97f) HorseArt.alpha(Color.rgb(240, 250, 200), 0.45f) else HorseArt.alpha(Ink.INK, 0.4f), 0.9f * k))
        }
    }

    fun hedge(c: Canvas, x0: Float, x1: Float, y: Float, hgt: Float, amb: Ambience) {
        val col = lit(HorseArt.shade(foliage(amb, 1), 0.85f), amb)
        p.shader = null
        var x = x0
        val rr = Rng(x0.toLong())
        while (x < x1) { p.color = HorseArt.shade(col, rr.range(0.85f, 1.05f)); c.drawCircle(x, y - hgt * 0.5f, hgt * rr.range(0.45f, 0.65f), p); x += hgt * 0.6f }
        p.color = col; c.drawRect(x0, y - hgt * 0.5f, x1, y, p)
    }

    /** Lice blanche à trois lisses. */
    fun fence(c: Canvas, x0: Float, x1: Float, ground: Float, hgt: Float, amb: Ambience, spacing: Float = hgt * 1.6f, color: Int = Color.rgb(240, 238, 230)) {
        val col = lit(color, amb)
        p.shader = null; p.color = col
        var x = x0
        while (x <= x1 + 1f) { c.drawRect(x - hgt * 0.05f, ground - hgt, x + hgt * 0.05f, ground, p); x += spacing }
        for (k in 0..2) { val y = ground - hgt * (0.3f + k * 0.3f); c.drawRect(x0, y - hgt * 0.04f, x1, y + hgt * 0.04f, p) }
        p.color = HorseArt.alpha(Color.BLACK, 0.12f)
        for (k in 0..2) { val y = ground - hgt * (0.3f + k * 0.3f); c.drawRect(x0, y + hgt * 0.02f, x1, y + hgt * 0.04f, p) }
    }

    /** Sol herbeux avec touffes. */
    fun grass(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float, amb: Ambience, scroll: Float, mud: Float = 0f) {
        val g = lit(mix(grassColor(amb), Color.rgb(110, 90, 60), mud * 0.5f), amb)
        p.color = -1; p.shader = LinearGradient(0f, top, 0f, bottom, HorseArt.lighten(g, 0.06f), HorseArt.shade(g, 0.82f), Shader.TileMode.CLAMP)
        c.drawRect(x0, top, x1, bottom, p)
        p.shader = null
        if (amb.snowGround) return
        sp.strokeWidth = 1.6f
        val rr = Rng(5)
        val spacing = 13f
        val start = floor((x0 + scroll) / spacing) * spacing
        var x = start
        val flowers = amb.season == Season.PRINTEMPS || amb.season == Season.ETE
        while (x < x1 + scroll) {
            val seed = (x / spacing).toInt()
            val r2 = Rng(seed.toLong() * 31)
            val y = top + (bottom - top) * r2.float()
            val sx = x - scroll + r2.range(-6f, 6f)
            val depth = (y - top) / (bottom - top)
            sp.color = HorseArt.shade(g, r2.range(0.6f, 1.15f))
            sp.strokeWidth = 1.1f + depth * 1.1f
            val hh = 4f + 12f * depth
            c.drawLine(sx, y, sx - 2.5f, y - hh, sp); c.drawLine(sx, y, sx + 2f, y - hh * 0.9f, sp)
            if (r2.chance(0.5f)) { c.drawLine(sx + 1f, y, sx + 0.5f, y - hh * 1.15f, sp); c.drawLine(sx - 1f, y, sx - 4.5f, y - hh * 0.6f, sp) }
            if (flowers && r2.chance(0.07f)) {
                val fc = when (r2.range(0, 3)) { 0 -> Color.rgb(250, 240, 120); 1 -> Color.WHITE; else -> Color.rgb(230, 150, 180) }
                p.color = lit(fc, amb); val fr = 1.5f + depth * 2f
                c.drawCircle(sx, y - hh, fr, p); p.color = lit(Color.rgb(240, 190, 60), amb); c.drawCircle(sx, y - hh, fr * 0.4f, p)
            } else if (r2.chance(0.04f)) {
                val pr = 1.5f + depth * 3f
                p.color = lit(Color.rgb(170, 164, 150), amb); c.drawOval(sx - pr * 1.4f, y - pr, sx + pr * 1.4f, y + pr * 0.4f, p)
                p.color = Color.argb(110, 255, 255, 255); c.drawOval(sx - pr, y - pr * 0.9f, sx, y - pr * 0.4f, p)
            }
            x += spacing
        }
        if (rr.float() < 0f) return
    }

    fun sand(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float, amb: Ambience, wet: Boolean = false, scroll: Float = 0f) {
        val span = x1 - x0
        fun sx(f: Float, depth: Float): Float = x0 + (((span * f - scroll * (0.6f + depth * 0.6f)) % span) + span) % span
        val s = lit(if (wet) Color.rgb(176, 160, 128) else Color.rgb(222, 204, 160), amb)
        p.color = -1; p.shader = LinearGradient(0f, top, 0f, bottom, HorseArt.lighten(s, 0.08f), HorseArt.shade(s, 0.85f), Shader.TileMode.CLAMP)
        c.drawRect(x0, top, x1, bottom, p)
        p.shader = null
        // rides du sable, grain, coquillages
        val us = span / 800f
        val rr = Rng(19)
        for (i in 0 until 30) {
            val y = top + (bottom - top) * rr.float(); val d = (y - top) / (bottom - top); val x = sx(rr.float(), d); val l = (20f + 40f * d) * us
            path.reset(); path.moveTo(x, y); path.quadTo(x + l * 0.5f, y - 3f * us, x + l, y)
            c.drawPath(path, Ink.stroke(HorseArt.alpha(HorseArt.shade(s, 0.72f), 0.55f), (0.8f + d) * us))
            path.offset(0f, 1.6f * us)
            c.drawPath(path, Ink.stroke(HorseArt.alpha(Color.WHITE, 0.3f), 0.8f * us))
        }
        p.color = HorseArt.alpha(HorseArt.shade(s, 0.65f), 0.5f)
        for (i in 0 until 200) { val fx = rr.float(); val fy = rr.float(); c.drawCircle(sx(fx, fy), top + (bottom - top) * fy, (0.5f + rr.float() * 0.8f) * us, p) }
        for (i in 0 until 6) {
            val fx = rr.float(); val y = top + (bottom - top) * (0.3f + rr.float() * 0.7f); val x = sx(fx, (y - top) / (bottom - top))
            val r0 = 3.5f * us
            p.color = lit(Color.rgb(246, 236, 222), amb); c.drawArc(x - r0, y - r0, x + r0, y + r0 * 0.7f, 180f, 180f, true, p)
            c.drawArc(x - r0, y - r0, x + r0, y + r0 * 0.7f, 180f, 180f, true, Ink.stroke(HorseArt.alpha(Ink.INK, 0.5f), 0.6f * us))
        }
    }

    // ------------------------------------------------------------------ météo
    fun weather(c: Canvas, w: Float, h: Float, amb: Ambience, t: Float) {
        when (amb.sky) {
            Sky.PLUIE, Sky.ORAGE, Sky.TEMPETE -> {
                sp.color = Color.argb(120, 200, 210, 230); sp.strokeWidth = 1.4f
                val n = if (amb.sky == Sky.PLUIE) 120 else 220
                val slant = 0.15f + amb.wind / 200f
                val rr = Rng(9)
                for (i in 0 until n) {
                    val x = (rr.float() * (w + 200f) + t * 60f * slant * 10f) % (w + 200f) - 100f
                    val y = (rr.float() * h + t * 900f * (0.8f + rr.float() * 0.4f)) % h
                    c.drawLine(x, y, x - 14f * slant * 3f, y + 18f, sp)
                }
                if (amb.sky == Sky.ORAGE && (t * 0.37f % 1f) < 0.02f) { p.shader = null; p.color = Color.argb(90, 255, 255, 255); c.drawRect(0f, 0f, w, h, p) }
            }
            Sky.NEIGE -> {
                p.shader = null; p.color = Color.argb(220, 255, 255, 255)
                val rr = Rng(13)
                for (i in 0 until 160) {
                    val x = (rr.float() * w + sin(t + i) * 20f + t * amb.wind * 0.5f) % w
                    val y = (rr.float() * h + t * 50f * (0.5f + rr.float())) % h
                    c.drawCircle(x, y, 1.5f + rr.float() * 2f, p)
                }
            }
            Sky.BROUILLARD -> {
                p.color = -1; p.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(120, 220, 224, 228), Color.argb(60, 220, 224, 228), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null
            }
            else -> {}
        }
        // voile nocturne
        val night = 1f - amb.light
        if (night > 0.05f) { p.shader = null; p.color = Color.argb((night * 70).toInt(), 10, 16, 40); c.drawRect(0f, 0f, w, h, p) }
    }

    // ------------------------------------------------------------------ bâtiments
    /** Écurie en pierre et colombages : [boxes] portes, [inside] dessine l'intérieur d'un box (cheval). */
    fun stable(c: Canvas, x: Float, ground: Float, boxW: Float, boxes: Int, amb: Ambience, inside: (Int, RectF) -> Unit) {
        val wdt = boxW * boxes + boxW * 0.6f
        val wallH = boxW * 1.25f
        val roofH = boxW * 0.75f
        val stone = lit(Color.rgb(196, 182, 160), amb)
        val timber = lit(Color.rgb(92, 64, 44), amb)
        val roof = lit(Color.rgb(92, 72, 70), amb)
        // toit d'ardoise
        path.reset()
        path.moveTo(x - boxW * 0.2f, ground - wallH); path.lineTo(x + wdt * 0.12f, ground - wallH - roofH); path.lineTo(x + wdt * 0.88f, ground - wallH - roofH); path.lineTo(x + wdt + boxW * 0.2f, ground - wallH); path.close()
        p.color = -1; p.shader = LinearGradient(0f, ground - wallH - roofH, 0f, ground - wallH, HorseArt.lighten(roof, 0.12f), HorseArt.shade(roof, 0.8f), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        sp.color = HorseArt.alpha(Color.BLACK, 0.15f); sp.strokeWidth = 1.2f
        var yy = ground - wallH - roofH + 8f
        while (yy < ground - wallH) { c.drawLine(x + wdt * 0.12f - (yy - (ground - wallH - roofH)) * 0.3f, yy, x + wdt * 0.88f + (yy - (ground - wallH - roofH)) * 0.3f, yy, sp); yy += 9f }
        // lanterneau et girouette cheval
        p.color = lit(Color.rgb(230, 224, 210), amb)
        c.drawRect(x + wdt * 0.47f, ground - wallH - roofH - boxW * 0.35f, x + wdt * 0.53f, ground - wallH - roofH, p)
        p.color = roof
        path.reset(); path.moveTo(x + wdt * 0.45f, ground - wallH - roofH - boxW * 0.35f); path.lineTo(x + wdt * 0.5f, ground - wallH - roofH - boxW * 0.55f); path.lineTo(x + wdt * 0.55f, ground - wallH - roofH - boxW * 0.35f); path.close(); c.drawPath(path, p)
        // murs
        p.color = stone
        c.drawRect(x, ground - wallH, x + wdt, ground, p)
        sp.color = HorseArt.alpha(Color.BLACK, 0.08f); sp.strokeWidth = 1f
        val rr = Rng(17)
        for (i in 0 until boxes * 6) { val sx = x + rr.float() * wdt; val sy = ground - rr.float() * wallH; c.drawRect(sx, sy, sx + 12f + rr.float() * 10f, sy + 7f, sp) }
        p.color = timber
        c.drawRect(x, ground - wallH, x + wdt, ground - wallH + boxW * 0.08f, p)
        // boxes
        for (i in 0 until boxes) {
            val bx = x + boxW * 0.3f + i * boxW
            r.set(bx + boxW * 0.12f, ground - wallH * 0.82f, bx + boxW * 0.88f, ground)
            p.color = timber
            c.drawRect(r.left - boxW * 0.05f, r.top - boxW * 0.05f, r.right + boxW * 0.05f, r.bottom, p)
            // intérieur sombre (moitié haute ouverte)
            val open = RectF(r.left, r.top, r.right, r.top + r.height() * 0.48f)
            p.color = lit(Color.rgb(38, 30, 24), amb)
            c.drawRect(open, p)
            if (amb.light < 0.4f) { p.color = Color.argb(90, 255, 200, 110); c.drawRect(open, p) }
            inside(i, open)
            // demi-porte en bois avec croix de Saint-André
            val door = RectF(r.left, open.bottom, r.right, r.bottom)
            p.color = lit(Color.rgb(56, 86, 64), amb)
            c.drawRect(door, p)
            sp.color = lit(Color.rgb(36, 60, 44), amb); sp.strokeWidth = boxW * 0.04f
            c.drawLine(door.left, door.top, door.right, door.bottom, sp); c.drawLine(door.right, door.top, door.left, door.bottom, sp)
            c.drawRect(door, sp)
            p.color = lit(Color.rgb(180, 180, 176), amb)
            c.drawCircle(door.right - boxW * 0.1f, door.top + door.height() * 0.3f, boxW * 0.025f, p)
        }
        // lanternes allumées la nuit
        if (amb.light < 0.5f) for (i in 0..boxes) {
            val lx = x + boxW * 0.3f + i * boxW; val ly = ground - wallH * 0.9f
            p.color = -1; p.shader = RadialGradient(lx, ly, boxW * 0.6f, Color.argb(120, 255, 210, 120), Color.argb(0, 255, 210, 120), Shader.TileMode.CLAMP)
            c.drawCircle(lx, ly, boxW * 0.6f, p); p.shader = null
            p.color = Color.rgb(255, 230, 160); c.drawCircle(lx, ly, boxW * 0.03f, p)
        }
    }

    fun barn(c: Canvas, x: Float, ground: Float, wdt: Float, hgt: Float, amb: Ambience, label: String? = null) {
        val wall = lit(Color.rgb(150, 70, 52), amb)
        path.reset()
        path.moveTo(x, ground); path.lineTo(x, ground - hgt * 0.6f); path.lineTo(x + wdt * 0.5f, ground - hgt); path.lineTo(x + wdt, ground - hgt * 0.6f); path.lineTo(x + wdt, ground); path.close()
        p.shader = null; p.color = wall; c.drawPath(path, p)
        sp.color = HorseArt.alpha(Color.BLACK, 0.12f); sp.strokeWidth = 1.2f
        var xx = x + 6f; while (xx < x + wdt) { c.drawLine(xx, ground, xx, ground - hgt * 0.6f, sp); xx += 9f }
        p.color = lit(Color.rgb(70, 50, 40), amb)
        c.drawRect(x + wdt * 0.3f, ground - hgt * 0.5f, x + wdt * 0.7f, ground, p)
        p.color = lit(Color.rgb(222, 196, 120), amb)
        c.drawRect(x + wdt * 0.33f, ground - hgt * 0.47f, x + wdt * 0.67f, ground - hgt * 0.2f, p) // bottes de foin
        sp.color = lit(Color.rgb(240, 236, 226), amb); sp.strokeWidth = 3f
        c.drawLine(x + wdt * 0.3f, ground - hgt * 0.5f, x + wdt * 0.7f, ground, sp); c.drawLine(x + wdt * 0.7f, ground - hgt * 0.5f, x + wdt * 0.3f, ground, sp)
        p.color = lit(Color.rgb(70, 60, 58), amb)
        path.reset(); path.moveTo(x - 6f, ground - hgt * 0.58f); path.lineTo(x + wdt * 0.5f, ground - hgt - 6f); path.lineTo(x + wdt + 6f, ground - hgt * 0.58f)
        sp.color = p.color; sp.strokeWidth = 7f; c.drawPath(path, sp.apply { style = Paint.Style.STROKE })
    }

    fun indoorArena(c: Canvas, x: Float, ground: Float, wdt: Float, hgt: Float, amb: Ambience) {
        val wall = lit(Color.rgb(170, 150, 120), amb)
        val roof = lit(Color.rgb(110, 116, 120), amb)
        p.shader = null; p.color = wall
        c.drawRect(x, ground - hgt * 0.65f, x + wdt, ground, p)
        path.reset(); path.moveTo(x - 8f, ground - hgt * 0.65f); path.quadTo(x + wdt / 2, ground - hgt * 1.25f, x + wdt + 8f, ground - hgt * 0.65f); path.close()
        p.color = roof; c.drawPath(path, p)
        p.color = lit(Color.rgb(90, 72, 56), amb)
        var xx = x + wdt * 0.08f
        while (xx < x + wdt * 0.92f) { c.drawRect(xx, ground - hgt * 0.6f, xx + wdt * 0.04f, ground, p); xx += wdt * 0.14f }
        p.color = lit(Color.rgb(230, 220, 190), amb)
        c.drawRect(x + wdt * 0.1f, ground - hgt * 0.58f, x + wdt * 0.9f, ground - hgt * 0.5f, p)
        if (amb.light < 0.5f) { p.color = Color.argb(110, 255, 220, 140); c.drawRect(x + wdt * 0.1f, ground - hgt * 0.58f, x + wdt * 0.9f, ground - hgt * 0.5f, p) }
    }

    fun clubHouse(c: Canvas, x: Float, ground: Float, wdt: Float, hgt: Float, amb: Ambience) {
        p.shader = null
        p.color = lit(Color.rgb(236, 230, 214), amb); c.drawRect(x, ground - hgt * 0.7f, x + wdt, ground, p)
        p.color = lit(Color.rgb(60, 80, 70), amb)
        path.reset(); path.moveTo(x - 10f, ground - hgt * 0.7f); path.lineTo(x + wdt / 2, ground - hgt); path.lineTo(x + wdt + 10f, ground - hgt * 0.7f); path.close(); c.drawPath(path, p)
        for (i in 0..2) {
            val wx = x + wdt * (0.12f + i * 0.3f)
            p.color = if (amb.light < 0.5f) Color.rgb(255, 214, 140) else lit(Color.rgb(140, 170, 190), amb)
            c.drawRect(wx, ground - hgt * 0.55f, wx + wdt * 0.16f, ground - hgt * 0.3f, p)
        }
        p.color = lit(Color.rgb(140, 40, 40), amb)
        c.drawRect(x + wdt * 0.05f, ground - hgt * 0.72f, x + wdt * 0.95f, ground - hgt * 0.66f, p)
    }

    /**
     * Obstacle de CSO vu de côté, en perspective : chandelier proche (plus bas), chandelier lointain,
     * barres rayées qui traversent la piste. [fallen] : une barre au sol.
     */
    fun jump(c: Canvas, x: Float, ground: Float, heightPx: Float, wdt: Float, amb: Ambience, oxer: Boolean = false, colorA: Int = Color.rgb(200, 40, 40), fallen: Boolean = false, water: Boolean = false) {
        val white = lit(Color.WHITE, amb)
        val col = lit(colorA, amb)
        val dx = wdt * 0.45f; val dy = -wdt * 0.35f   // décalage vers le chandelier lointain
        p.shader = null
        if (water) { p.color = lit(Color.rgb(70, 140, 190), amb); c.drawRect(x - wdt * 1.2f, ground - 4f, x + wdt * 0.6f, ground + 3f, p) }
        val depths = if (oxer) listOf(wdt * 0.55f, 0f) else listOf(0f)
        for ((di, off) in depths.withIndex()) {
            val bx = x + off
            fun standard(sx: Float, sy: Float, k: Float) {
                p.color = HorseArt.shade(white, k); c.drawRect(sx - wdt * 0.04f, sy - heightPx * 1.3f, sx + wdt * 0.04f, sy, p)
                p.color = HorseArt.shade(col, k); c.drawRect(sx - wdt * 0.045f, sy - heightPx * 1.3f, sx + wdt * 0.045f, sy - heightPx * 1.22f, p)
                // aile décorative
                p.color = HorseArt.shade(col, k * 0.9f)
                c.drawRect(sx - wdt * 0.04f, sy - heightPx * 0.9f, sx + wdt * 0.04f + (if (sy > ground) -wdt * 0.25f else wdt * 0.25f), sy - heightPx * 0.8f, p)
            }
            standard(bx + dx, ground + dy, 0.85f)
            val bars = 3
            for (k in 0 until bars) {
                val hh = heightPx * (if (oxer && di == 0) 1.03f else 1f) * (0.4f + k * 0.3f)
                if (fallen && di == depths.size - 1 && k == bars - 1) {
                    sp.color = col; sp.strokeWidth = wdt * 0.07f
                    c.drawLine(bx - wdt * 0.6f, ground + 4f, bx + dx - wdt * 0.2f, ground + dy + 4f, sp)
                    continue
                }
                val x0 = bx; val y0 = ground + 6f - hh; val x1 = bx + dx; val y1 = ground + dy - hh
                val n = 4
                for (sgm in 0 until n) {
                    val t0 = sgm / n.toFloat(); val t1 = (sgm + 1) / n.toFloat()
                    sp.color = if (sgm % 2 == 0) col else white; sp.strokeWidth = wdt * 0.075f; sp.strokeCap = Paint.Cap.BUTT
                    c.drawLine(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0, x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1, sp)
                }
                sp.strokeCap = Paint.Cap.ROUND
            }
            standard(bx, ground + 6f, 1f)
        }
        // fleurs au pied
        p.color = lit(Color.rgb(60, 110, 50), amb); c.drawOval(x - wdt * 0.35f, ground - 2f, x + wdt * 0.3f, ground + 12f, p)
        p.color = lit(Color.rgb(230, 80, 120), amb)
        for (k in 0..3) c.drawCircle(x - wdt * 0.25f + k * wdt * 0.15f, ground + 3f, 3f, p)
    }
}
