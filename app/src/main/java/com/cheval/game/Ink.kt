package com.cheval.game

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.cheval.core.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Style « illustré à la main » : traits d'encre légèrement tremblés, aplats d'aquarelle,
 * grain du papier, planches de bois et parchemins pour l'interface.
 */
object Ink {
    val INK = Color.rgb(56, 40, 30)
    val INK_SOFT = Color.argb(150, 56, 40, 30)
    val WOOD = Color.rgb(196, 148, 96)
    val WOOD_D = Color.rgb(132, 88, 52)
    val WOOD_L = Color.rgb(226, 188, 136)
    val PARCH = Color.rgb(244, 230, 200)
    val PARCH_D = Color.rgb(222, 198, 156)

    val hand: Typeface = Typeface.create("casual", Typeface.BOLD)
    val handN: Typeface = Typeface.create("casual", Typeface.NORMAL)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    // ------------------------------------------------------------------ grain du papier
    private var grainBmp: Bitmap? = null
    private var grainShader: BitmapShader? = null
    private val gp = Paint()

    /** Grain : petites taches claires et sombres, répétées en mosaïque. */
    fun grainPaint(alpha: Int): Paint {
        if (grainShader == null) {
            val n = 160
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val r = Rng(1234)
            val px = IntArray(n * n)
            for (i in px.indices) {
                val v = r.float()
                px[i] = when {
                    v < 0.08f -> Color.argb(70, 40, 26, 10)
                    v < 0.16f -> Color.argb(60, 255, 250, 235)
                    v < 0.2f -> Color.argb(40, 90, 60, 30)
                    else -> 0
                }
            }
            bmp.setPixels(px, 0, n, 0, 0, n, n)
            grainBmp = bmp
            grainShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        gp.shader = grainShader
        gp.alpha = alpha
        return gp
    }

    fun grain(c: Canvas, r: RectF, alpha: Int = 160) { c.drawRect(r, grainPaint(alpha)) }
    fun grain(c: Canvas, pth: Path, alpha: Int = 160) { c.drawPath(pth, grainPaint(alpha)) }

    // ------------------------------------------------------------------ traits
    fun stroke(color: Int = INK, w: Float): Paint { sp.shader = null; sp.color = color; sp.strokeWidth = w; return sp }
    fun fill(color: Int): Paint { p.shader = null; p.color = color; return p }

    /** Trait d'encre légèrement courbé (déterministe selon les coordonnées). */
    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, color: Int = INK) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val bend = (((x1 * 13.7f + y1 * 7.3f).toInt() % 7) - 3) / 3f * min(len * 0.04f, 4f)
        path.reset(); path.moveTo(x1, y1)
        path.quadTo((x1 + x2) / 2 - dy / len * bend, (y1 + y2) / 2 + dx / len * bend, x2, y2)
        c.drawPath(path, stroke(color, w))
    }

    /** Contour d'encre d'un chemin, doublé d'un léger décalage pour l'effet « dessiné à la main ». */
    fun outline(c: Canvas, pth: Path, w: Float, color: Int = INK) {
        c.drawPath(pth, stroke(color, w))
        c.save(); c.translate(w * 0.35f, -w * 0.25f)
        c.drawPath(pth, stroke(HorseArt.alpha(color, 0.35f), w * 0.6f))
        c.restore()
    }

    /** Forme organique (buisson, tas, nuage) autour d'un centre. */
    fun blobPath(cx: Float, cy: Float, rx: Float, ry: Float, seed: Long, bumps: Int = 9, rough: Float = 0.22f): Path {
        val r = Rng(seed)
        val out = Path()
        val n = bumps * 2
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = (i / n.toFloat()) * 2 * PI.toFloat()
            val k = 1f + (if (i % 2 == 0) r.range(-rough, rough * 0.4f) else r.range(0f, rough))
            pts[i * 2] = cx + cos(a) * rx * k; pts[i * 2 + 1] = cy + sin(a) * ry * k
        }
        out.moveTo((pts[0] + pts[2]) / 2, (pts[1] + pts[3]) / 2)
        for (i in 1..n) {
            val j = i % n; val k2 = (i + 1) % n
            out.quadTo(pts[j * 2], pts[j * 2 + 1], (pts[j * 2] + pts[k2 * 2]) / 2, (pts[j * 2 + 1] + pts[k2 * 2 + 1]) / 2)
        }
        out.close()
        return out
    }

    /** Aplat d'aquarelle : couleur, bords plus foncés (pigment), grain, contour d'encre. */
    fun wash(c: Canvas, pth: Path, color: Int, inkW: Float, edge: Float = 0.18f, grainA: Int = 120) {
        c.drawPath(pth, fill(color))
        c.save(); c.clipPath(pth)
        c.drawPath(pth, stroke(HorseArt.shade(color, 0.78f), inkW * 3.5f).also { it.alpha = (255 * edge).toInt() })
        grain(c, pth, grainA)
        c.restore()
        if (inkW > 0f) outline(c, pth, inkW)
    }

    // ------------------------------------------------------------------ bois et parchemin
    /** Planche de bois : veinage, bord sombre, clous, contour d'encre. */
    fun plank(c: Canvas, r: RectF, radius: Float, u: Float, base: Int = WOOD, nails: Boolean = true) {
        p.color = Color.argb(70, 0, 0, 0); c.drawRoundRect(r.left + 1.5f * u, r.top + 2.5f * u, r.right + 1.5f * u, r.bottom + 2.5f * u, radius, radius, p)
        p.color = -1
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, HorseArt.lighten(base, 0.12f), HorseArt.shade(base, 0.82f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        c.save(); c.clipRect(r)
        val rr = Rng((r.left * 3 + r.top * 7).toLong())
        sp.shader = null
        var y = r.top + 4f * u
        while (y < r.bottom) {
            sp.color = HorseArt.alpha(HorseArt.shade(base, 0.6f), 0.35f); sp.strokeWidth = 0.8f * u
            path.reset(); path.moveTo(r.left, y)
            var x = r.left
            while (x < r.right) { val nx = x + 18f * u; path.quadTo(x + 9f * u, y + rr.range(-1.6f, 1.6f) * u, nx, y + rr.range(-0.6f, 0.6f) * u); x = nx }
            c.drawPath(path, sp)
            y += rr.range(4f, 8f) * u
        }
        grain(c, r, 140)
        c.restore()
        sp.color = INK; sp.strokeWidth = 1.6f * u
        c.drawRoundRect(r, radius, radius, sp)
        if (nails && r.width() > 40f * u && r.height() > 18f * u) {
            p.color = Color.rgb(80, 64, 52)
            for ((nx, ny) in listOf(r.left + 6f * u to r.top + 6f * u, r.right - 6f * u to r.top + 6f * u, r.left + 6f * u to r.bottom - 6f * u, r.right - 6f * u to r.bottom - 6f * u))
                c.drawCircle(nx, ny, 1.6f * u, p)
        }
    }

    /** Feuille de parchemin aux bords irréguliers. */
    fun parchment(c: Canvas, r: RectF, u: Float, base: Int = PARCH, border: Boolean = true) {
        path.reset()
        val rr = Rng((r.left * 5 + r.right * 3 + r.top).toLong())
        val step = 14f * u
        path.moveTo(r.left, r.top)
        var x = r.left; while (x < r.right) { x = min(r.right, x + step); path.lineTo(x, r.top + rr.range(-1.2f, 1.2f) * u) }
        var y = r.top; while (y < r.bottom) { y = min(r.bottom, y + step); path.lineTo(r.right + rr.range(-1.2f, 1.2f) * u, y) }
        x = r.right; while (x > r.left) { x = max(r.left, x - step); path.lineTo(x, r.bottom + rr.range(-1.2f, 1.2f) * u) }
        y = r.bottom; while (y > r.top) { y = max(r.top, y - step); path.lineTo(r.left + rr.range(-1.2f, 1.2f) * u, y) }
        path.close()
        c.save(); c.translate(1.5f * u, 3f * u); p.color = Color.argb(60, 0, 0, 0); c.drawPath(path, p); c.restore()
        p.color = -1
        p.shader = RadialGradient(r.centerX(), r.centerY(), max(r.width(), r.height()) * 0.75f, HorseArt.lighten(base, 0.12f), HorseArt.shade(base, 0.9f), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        c.save(); c.clipPath(path)
        c.drawPath(path, stroke(PARCH_D, 10f * u).also { it.alpha = 90 })
        grain(c, r, 150)
        c.restore()
        if (border) c.drawPath(path, stroke(INK, 1.4f * u))
    }

    /** Petite tête de cheval en bois sculpté (décor des enseignes). */
    fun carvedHead(c: Canvas, x: Float, y: Float, s: Float, facingRight: Boolean, u: Float) {
        c.save(); c.translate(x, y); if (!facingRight) c.scale(-1f, 1f)
        path.reset()
        path.moveTo(-s * 0.5f, s * 0.6f)
        path.quadTo(-s * 0.55f, -s * 0.2f, -s * 0.1f, -s * 0.55f)
        path.lineTo(-s * 0.02f, -s * 0.85f); path.lineTo(s * 0.08f, -s * 0.5f)
        path.quadTo(s * 0.5f, -s * 0.35f, s * 0.75f, s * 0.05f)
        path.quadTo(s * 0.8f, s * 0.22f, s * 0.6f, s * 0.25f)
        path.quadTo(s * 0.3f, s * 0.15f, s * 0.15f, s * 0.3f)
        path.quadTo(s * 0.05f, s * 0.5f, s * 0.1f, s * 0.6f)
        path.close()
        p.color = WOOD_L; c.drawPath(path, p)
        c.drawPath(path, stroke(INK, 1.4f * u))
        p.color = INK; c.drawCircle(s * 0.15f, -s * 0.2f, s * 0.06f, p)
        c.restore()
    }

    /** Enseigne en bois suspendue : titre du domaine, avec deux têtes de chevaux. */
    fun banner(c: Canvas, r: RectF, title: String, u: Float, gui: Gui) {
        sp.color = Color.rgb(150, 120, 80); sp.strokeWidth = 2.2f * u
        c.drawLine(r.left + r.width() * 0.22f, r.top - 30f * u, r.left + r.width() * 0.28f, r.top + 6f * u, sp)
        c.drawLine(r.right - r.width() * 0.22f, r.top - 30f * u, r.right - r.width() * 0.28f, r.top + 6f * u, sp)
        plank(c, RectF(r.left - 6f * u, r.top - 4f * u, r.right + 6f * u, r.bottom + 4f * u), 6f * u, u, WOOD_D)
        parchment(c, RectF(r.left + 8f * u, r.top + 4f * u, r.right - 8f * u, r.bottom - 4f * u), u)
        carvedHead(c, r.left - 10f * u, r.centerY() - 2f * u, r.height() * 0.62f, false, u)
        carvedHead(c, r.right + 10f * u, r.centerY() - 2f * u, r.height() * 0.62f, true, u)
        gui.text(c, title, r.centerX(), r.centerY() + 6f * u, 16f, INK, Paint.Align.CENTER, hand, maxW = r.width() - 24f * u)
    }

    /** Pièce de monnaie (fer à cheval doré). */
    fun coin(c: Canvas, x: Float, y: Float, rad: Float, u: Float) {
        p.color = -1
        p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.3f, Color.rgb(255, 228, 130), Color.rgb(196, 140, 40), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rad, p); p.shader = null
        c.drawCircle(x, y, rad, stroke(INK, 1.2f * u))
        sp.strokeWidth = rad * 0.25f; sp.color = Color.rgb(150, 100, 30)
        c.drawArc(x - rad * 0.5f, y - rad * 0.55f, x + rad * 0.5f, y + rad * 0.45f, 200f, 300f, false, sp)
    }
}
