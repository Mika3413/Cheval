package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Palette « haras » : vert anglais, crème, or et cuir. */
object Pal {
    val GREEN = Color.rgb(31, 58, 46)
    val GREEN_D = Color.rgb(20, 38, 30)
    val GREEN_L = Color.rgb(64, 104, 78)
    val CREAM = Color.rgb(246, 240, 226)
    val CREAM_D = Color.rgb(230, 220, 198)
    val PAPER = Color.rgb(251, 247, 238)
    val GOLD = Color.rgb(201, 164, 76)
    val GOLD_L = Color.rgb(232, 206, 130)
    val INK = Color.rgb(48, 36, 26)
    val INK_L = Color.rgb(120, 104, 86)
    val LEATHER = Color.rgb(110, 66, 38)
    val RED = Color.rgb(176, 52, 44)
    val ORANGE = Color.rgb(214, 128, 40)
    val OK = Color.rgb(62, 140, 74)
    val BLUE = Color.rgb(44, 92, 150)
    val WHITE = Color.WHITE
    val SHADOW = Color.argb(90, 0, 0, 0)

    /** Couleur d'une jauge 0..100 : rouge → orange → vert. */
    fun gauge(v: Float): Int = when { v < 25 -> RED; v < 50 -> ORANGE; v < 75 -> Color.rgb(170, 160, 60); else -> OK }
}

enum class Btn { NORMAL, PRIMARY, GOLD, DANGER, GHOST, TAB, TAB_ON }

/**
 * Interface en mode immédiat : chaque image, les écrans dessinent leurs widgets et enregistrent
 * des zones cliquables ; le toucher est résolu sur les zones de la dernière image.
 * Unité [u] : le petit côté de l'écran vaut 360u.
 */
class Gui {
    var u = 1f
    var w = 1f
    var h = 1f
    val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val serifN: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    val sans: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val sansB: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r = RectF()
    val path = Path()

    class Hit(val rect: RectF, val clip: RectF?, val layer: Int, val action: () -> Unit)
    class Scroll(var offset: Float = 0f, var max: Float = 0f, val rect: RectF = RectF(), var vel: Float = 0f)

    private val hits = ArrayList<Hit>()
    private val prevHits = ArrayList<Hit>()
    val scrolls = HashMap<String, Scroll>()
    private val activeScrolls = ArrayList<String>()
    var layer = 0
    private var clip: RectF? = null

    // toasts
    private class Toast(val text: String, val color: Int, var t: Float)
    private val toasts = ArrayList<Toast>()

    fun resize(width: Int, height: Int) { w = width.toFloat(); h = height.toFloat(); u = min(w, h) / 360f }

    fun begin() { hits.clear(); activeScrolls.clear(); layer = 0; clip = null }
    fun end() { prevHits.clear(); prevHits.addAll(hits) }

    /** Décalage vertical du contenu défilant en cours (les zones sont enregistrées en coordonnées écran). */
    private var hitDy = 0f
    fun hit(rect: RectF, action: () -> Unit) { hits += Hit(RectF(rect).also { it.offset(0f, -hitDy) }, clip?.let { RectF(it) }, layer, action) }
    fun hit(l: Float, t: Float, rr: Float, b: Float, action: () -> Unit) = hit(RectF(l, t, rr, b), action)

    /** Ouvre une couche modale : seules ses zones restent cliquables. */
    fun modal(c: Canvas, dim: Boolean = true) {
        layer++
        if (dim) { p.shader = null; p.color = Color.argb(150, 10, 14, 12); c.drawRect(0f, 0f, w, h, p) }
        hit(0f, 0f, w, h) {} // absorbe les touchers hors de la boîte
    }

    // ------------------------------------------------------------------ texte
    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Pal.INK, align: Paint.Align = Paint.Align.LEFT,
             font: Typeface = sans, shadow: Boolean = false, maxW: Float = 0f) {
        tp.typeface = font; tp.textSize = size * u; tp.textAlign = align
        var str = s
        if (maxW > 0f && tp.measureText(str) > maxW) {
            while (str.length > 2 && tp.measureText("$str…") > maxW) str = str.dropLast(1)
            str = "$str…"
        }
        if (shadow) { tp.color = Color.argb(Color.alpha(color) * 2 / 3, 0, 0, 0); c.drawText(str, x + 1.1f * u, y + 1.3f * u, tp) }
        tp.color = color
        c.drawText(str, x, y, tp)
    }

    fun textW(s: String, size: Float, font: Typeface = sans): Float { tp.typeface = font; tp.textSize = size * u; return tp.measureText(s) }

    /** Texte sur plusieurs lignes ; retourne la hauteur occupée. [c] nul = simple mesure. */
    fun wrap(c: Canvas?, s: String, x: Float, y: Float, maxW: Float, size: Float, color: Int = Pal.INK, font: Typeface = sans, gap: Float = 1.32f): Float {
        val lh = size * u * gap
        var yy = y
        for (para in s.split("\n")) {
            var line = ""
            for (word in para.split(" ")) {
                val test = if (line.isEmpty()) word else "$line $word"
                if (textW(test, size, font) > maxW && line.isNotEmpty()) {
                    c?.let { text(it, line, x, yy, size, color, font = font) }
                    yy += lh; line = word
                } else line = test
            }
            c?.let { text(it, line, x, yy, size, color, font = font) }
            yy += lh
        }
        return yy - y
    }

    // ------------------------------------------------------------------ surfaces
    fun paper(c: Canvas, rect: RectF, radius: Float = 10f, color: Int = Pal.PAPER, border: Boolean = true) {
        r.set(rect); r.offset(0f, 2.5f * u)
        p.shader = null; p.color = Pal.SHADOW
        c.drawRoundRect(r, radius * u, radius * u, p)
        p.color = color
        c.drawRoundRect(rect, radius * u, radius * u, p)
        if (border) { sp.shader = null; sp.color = Color.argb(160, 201, 164, 76); sp.strokeWidth = 1.2f * u; c.drawRoundRect(rect, radius * u, radius * u, sp) }
    }

    fun dark(c: Canvas, rect: RectF, radius: Float = 10f, alpha: Int = 215) {
        p.shader = null; p.color = Color.argb(alpha, 18, 34, 27)
        c.drawRoundRect(rect, radius * u, radius * u, p)
        sp.shader = null; sp.color = Color.argb(140, 201, 164, 76); sp.strokeWidth = 1f * u
        c.drawRoundRect(rect, radius * u, radius * u, sp)
    }

    fun button(c: Canvas, rect: RectF, label: String, style: Btn = Btn.NORMAL, enabled: Boolean = true, size: Float = 13f, sub: String? = null, onClick: () -> Unit) {
        val (bg, fg, border) = when {
            !enabled -> Triple(Color.argb(200, 200, 194, 182), Color.rgb(140, 132, 120), Color.argb(80, 0, 0, 0))
            style == Btn.PRIMARY -> Triple(Pal.GREEN, Pal.CREAM, Pal.GOLD)
            style == Btn.GOLD -> Triple(Pal.GOLD, Pal.INK, Pal.GOLD_L)
            style == Btn.DANGER -> Triple(Pal.RED, Pal.CREAM, Color.rgb(230, 140, 120))
            style == Btn.GHOST -> Triple(Color.argb(120, 255, 255, 255), Pal.INK, Color.argb(120, 120, 100, 70))
            style == Btn.TAB -> Triple(Color.argb(0, 0, 0, 0), Pal.CREAM, Color.argb(0, 0, 0, 0))
            style == Btn.TAB_ON -> Triple(Color.argb(60, 232, 206, 130), Pal.GOLD_L, Pal.GOLD)
            else -> Triple(Pal.CREAM, Pal.INK, Color.argb(200, 170, 140, 80))
        }
        val rad = 8f * u
        if (style != Btn.TAB && style != Btn.TAB_ON && enabled) {
            r.set(rect); r.offset(0f, 2f * u); p.shader = null; p.color = Color.argb(70, 0, 0, 0); c.drawRoundRect(r, rad, rad, p)
        }
        p.shader = if (enabled && (style == Btn.PRIMARY || style == Btn.GOLD)) LinearGradient(0f, rect.top, 0f, rect.bottom, HorseArt.lighten(bg, 0.12f), HorseArt.shade(bg, 0.88f), Shader.TileMode.CLAMP) else null
        p.color = bg
        c.drawRoundRect(rect, rad, rad, p)
        p.shader = null
        if (Color.alpha(border) > 0) { sp.shader = null; sp.color = border; sp.strokeWidth = 1.2f * u; c.drawRoundRect(rect, rad, rad, sp) }
        if (sub == null) text(c, label, rect.centerX(), rect.centerY() + size * u * 0.36f, size, fg, Paint.Align.CENTER, sansB, maxW = rect.width() - 6f * u)
        else {
            text(c, label, rect.centerX(), rect.centerY() - 1f * u, size, fg, Paint.Align.CENTER, sansB, maxW = rect.width() - 6f * u)
            text(c, sub, rect.centerX(), rect.centerY() + size * u * 0.95f, size * 0.72f, HorseArt.alpha(fg, 0.75f), Paint.Align.CENTER, sans, maxW = rect.width() - 6f * u)
        }
        if (enabled) hit(rect, onClick)
    }

    /** Jauge horizontale avec libellé. */
    fun gauge(c: Canvas, x: Float, y: Float, wd: Float, label: String, v: Float, color: Int = Pal.gauge(v), valueText: String? = null, dark: Boolean = false) {
        text(c, label, x, y, 10.5f, if (dark) Pal.GOLD_L else Pal.INK_L)
        val bt = y + 4f * u; val bh = 6f * u
        r.set(x, bt, x + wd, bt + bh); p.shader = null; p.color = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0); c.drawRoundRect(r, bh / 2, bh / 2, p)
        r.set(x, bt, x + wd * (v / 100f).coerceIn(0f, 1f), bt + bh); p.color = color; c.drawRoundRect(r, bh / 2, bh / 2, p)
        text(c, valueText ?: "${v.toInt()}", x + wd, y, 10.5f, if (dark) Pal.CREAM else Pal.INK, Paint.Align.RIGHT, sansB)
    }

    /** Étoiles de potentiel (0..5, demi-étoiles). */
    fun stars(c: Canvas, x: Float, y: Float, value: Float, size: Float = 9f) {
        val n = (value * 2f).toInt().coerceIn(0, 10)
        for (i in 0 until 5) {
            val cx = x + i * size * 1.25f * u + size * u / 2; val cy = y
            starPath(cx, cy, size * u / 2)
            p.shader = null; p.color = Color.argb(60, 0, 0, 0); c.drawPath(path, p)
            val full = n >= (i + 1) * 2; val half = n == i * 2 + 1
            if (full || half) {
                c.save()
                if (half) c.clipRect(cx - size * u, cy - size * u, cx, cy + size * u)
                p.color = Pal.GOLD; c.drawPath(path, p)
                c.restore()
            }
        }
    }

    private fun starPath(cx: Float, cy: Float, rad: Float) {
        path.reset()
        for (k in 0 until 10) {
            val a = -Math.PI / 2 + k * Math.PI / 5
            val rr = if (k % 2 == 0) rad else rad * 0.45f
            val px = cx + (kotlin.math.cos(a) * rr).toFloat(); val py = cy + (kotlin.math.sin(a) * rr).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    // ------------------------------------------------------------------ défilement
    /** Zone défilante : retourne le décalage courant et clippe le contenu (appeler [endScroll] ensuite). */
    fun beginScroll(c: Canvas, id: String, rect: RectF, contentH: Float): Float {
        val s = scrolls.getOrPut(id) { Scroll() }
        s.rect.set(rect)
        s.max = max(0f, contentH - rect.height())
        s.offset = s.offset.coerceIn(0f, s.max)
        activeScrolls += id
        c.save(); c.clipRect(rect)
        clip = RectF(rect)
        hitDy = s.offset
        c.translate(0f, -s.offset)
        return s.offset
    }

    fun endScroll(c: Canvas, id: String) {
        c.restore(); clip = null; hitDy = 0f
        val s = scrolls[id] ?: return
        if (s.max > 0f) {
            val rect = s.rect
            val th = rect.height() * rect.height() / (rect.height() + s.max)
            val ty = rect.top + (rect.height() - th) * (s.offset / s.max)
            r.set(rect.right - 3.5f * u, ty, rect.right - 1f * u, ty + th)
            p.shader = null; p.color = Color.argb(110, 120, 100, 70); c.drawRoundRect(r, 2f * u, 2f * u, p)
        }
    }

    fun resetScroll(id: String) { scrolls[id]?.offset = 0f }

    fun update(dt: Float) {
        for (id in scrolls.keys) { val s = scrolls[id]!!; if (!dragging && abs(s.vel) > 1f) { s.offset = (s.offset - s.vel * dt).coerceIn(0f, s.max); s.vel *= 0.9f } }
        toasts.forEach { it.t -= dt }; toasts.removeAll { it.t <= 0f }
    }

    // ------------------------------------------------------------------ bandeaux
    fun toast(text: String, color: Int = Pal.GREEN) {
        if (toasts.any { it.text == text }) return
        toasts += Toast(text, color, 3.2f)
        if (toasts.size > 3) toasts.removeAt(0)
    }

    fun drawToasts(c: Canvas) {
        var y = h - 70f * u
        for (t in toasts.reversed()) {
            val tw = min(w - 40f * u, textW(t.text, 12f) + 30f * u)
            val lines = wrap(null, t.text, 0f, 0f, tw - 24f * u, 12f)
            val th = lines + 12f * u
            r.set(w / 2 - tw / 2, y - th, w / 2 + tw / 2, y)
            val a = (t.t / 0.4f).coerceIn(0f, 1f)
            p.shader = null; p.color = HorseArt.alpha(t.color, 0.93f * a)
            c.drawRoundRect(r, 10f * u, 10f * u, p)
            sp.color = HorseArt.alpha(Pal.GOLD, a); sp.strokeWidth = 1f * u; c.drawRoundRect(r, 10f * u, 10f * u, sp)
            wrap(c, t.text, r.left + 12f * u, r.top + 15f * u, tw - 24f * u, 12f, HorseArt.alpha(Pal.CREAM, a))
            y -= th + 6f * u
        }
    }

    // ------------------------------------------------------------------ toucher
    private var downX = 0f; private var downY = 0f; private var lastY = 0f; private var lastX = 0f; private var lastT = 0L
    var dragging = false; private set
    private var dragScroll: Scroll? = null
    /** Glisser libre (ex. caméra du domaine) quand aucune zone défilante n'est sous le doigt. */
    var onFreeDrag: ((Float, Float) -> Unit)? = null
    /** Toucher brut (ex. pansage) : retourne vrai pour consommer l'événement. */
    var onRawTouch: ((MotionEvent) -> Boolean)? = null

    fun onTouch(e: MotionEvent) {
        if (onRawTouch?.invoke(e) == true) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; lastT = System.nanoTime(); dragging = false
                dragScroll = activeScrolls.asReversed().mapNotNull { scrolls[it] }.firstOrNull { it.rect.contains(e.x, e.y) && it.max > 0f }
                dragScroll?.vel = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.x - downX) > 9f * u || abs(e.y - downY) > 9f * u)) dragging = true
                if (dragging) {
                    val dy = e.y - lastY; val dx = e.x - lastX
                    val now = System.nanoTime(); val dt = ((now - lastT) / 1e9f).coerceAtLeast(0.001f)
                    dragScroll?.let { it.offset = (it.offset - dy).coerceIn(0f, it.max); it.vel = dy / dt * 0.9f } ?: onFreeDrag?.invoke(dx, dy)
                    lastT = now
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val top = prevHits.maxOfOrNull { it.layer } ?: 0
                    val target = prevHits.lastOrNull { it.layer == top && it.rect.contains(e.x, e.y) && (it.clip == null || it.clip.contains(e.x, e.y)) }
                    target?.action?.invoke()
                } else dragScroll?.let { if (abs(it.vel) < 60f) it.vel = 0f }
                dragging = false
                dragScroll = null
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; dragScroll = null }
        }
    }
}
