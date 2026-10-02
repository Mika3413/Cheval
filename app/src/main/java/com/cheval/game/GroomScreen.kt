package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import com.cheval.core.Horse
import com.cheval.core.Personality
import com.cheval.core.Rng
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pansage au doigt : étrille pour décoller la boue, brosse dure pour la poussière, brosse douce pour
 * la tête et la finition, cure-pied pour chaque sabot. L'ordre compte, comme en vrai.
 */
class GroomScreen(app: GameView, private val horse: Horse) : Screen(app) {
    private val game get() = app.game!!
    private enum class Tool(val label: String, val tip: String) {
        ETRILLE("Étrille", "Mouvements circulaires pour décoller la boue et les poils morts"),
        BROSSE_DURE("Brosse dure", "Dans le sens du poil, pour chasser la poussière"),
        BROSSE_DOUCE("Brosse douce", "Tête et finition : le poil brille"),
        CURE_PIED("Cure-pied", "Touchez chaque sabot pour le curer"),
    }

    /** Une zone sale, en coordonnées du modèle (cm). [mud] 2 = boue sèche, 1 = poussière, 0 = propre. */
    private class Spot(val mx: Float, val my: Float, var mud: Int, val head: Boolean, val r: Float, var polish: Float = 0f)
    private val spots = ArrayList<Spot>()
    private val hooves = BooleanArray(4)
    private var tool = Tool.ETRILLE
    private var t = 0f
    private val pose = HorsePose().apply { neck = 40f; head = 40f }
    private var happy = 0f
    private var annoyed = 0f
    private var done = false
    private var wrongOrder = 0
    private var hx = 0f; private var hy = 0f; private var hs = 1f
    private val particles = ArrayList<FloatArray>() // x, y, vx, vy, life
    private var fingerX = -1f; private var fingerY = -1f

    init {
        val a = Appearance.of(horse, game.day)
        val r = Rng(horse.id * 31L + game.day)
        val dirt = 100f - horse.cleanliness
        val n = (10 + dirt * 0.35f).toInt()
        val H = a.H; val L = a.L
        repeat(n) {
            val zone = r.int(10)
            val (x, y) = when {
                zone < 6 -> r.range(-0.45f, 0.42f) * L to r.range(-0.95f, -0.62f) * H   // corps
                zone < 8 -> r.range(0.3f, 0.5f) * L to r.range(-1.15f, -0.85f) * H      // encolure
                else -> (if (r.chance(0.5f)) 0.33f * L else -0.42f * L) + r.range(-0.04f, 0.04f) * H to r.range(-0.42f, -0.1f) * H // membres
            }
            val mud = if (r.float() * 100f < dirt + 10f && zone !in 6..7) 2 else 1
            spots += Spot(x, y, mud, false, H * r.range(0.05f, 0.08f))
        }
        // tête : seulement la brosse douce
        repeat(3) { spots += Spot(L * 0.62f + r.range(0f, 0.1f) * H, -H * r.range(1.05f, 1.2f), 1, true, H * 0.05f) }
        app.gui.onRawTouch = { e -> onTouch(e) }
    }

    override fun dispose() { gui.onRawTouch = null; aisle?.recycle(); aisle = null }

    override fun update(dt: Float) {
        t += dt
        pose.breathe = (pose.breathe + dt * 0.25f) % 1f
        pose.blink = if (t % 4.2f < 0.14f || happy > 0.5f) 0.6f * (if (happy > 0.5f) 1f else 0f) + (if (t % 4.2f < 0.14f) 1f else 0f) else 0f
        pose.ears += ((if (annoyed > 0) -1f else if (happy > 0) 0.2f else 0.5f) - pose.ears) * dt * 4f
        pose.neck += ((if (happy > 0.3f) 30f else 42f) - pose.neck) * dt
        pose.head += ((if (happy > 0.3f) 55f else 40f) - pose.head) * dt
        pose.tailSwing = sin(t * 1.5f) * 0.5f
        happy -= dt * 0.4f; annoyed -= dt
        for (p in particles) { p[0] += p[2] * dt; p[1] += p[3] * dt; p[3] += 120f * dt; p[4] -= dt }
        particles.removeAll { it[4] <= 0f }
    }

    private fun progress(): Float {
        val total = spots.sumOf { (if (it.head) 1 else it.mud.coerceAtLeast(0) + 1).toInt() } + 4
        val left = spots.sumOf { if (it.head) (if (it.polish < 1f) 1 else 0) else it.mud + (if (it.polish < 1f) 1 else 0) } + hooves.count { !it }
        return 1f - left.toFloat() / total.coerceAtLeast(1)
    }

    private fun onTouch(e: MotionEvent): Boolean {
        val u = gui.u
        // la barre d'outils et les boutons restent gérés par l'interface
        if (e.y > gui.h - 70f * u || e.y < 50f * u || e.x > gui.w - 150f * u) return false
        if (done) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val moved = if (fingerX >= 0f) hypot(e.x - fingerX, e.y - fingerY) else 0f
                fingerX = e.x; fingerY = e.y
                if (tool == Tool.CURE_PIED) { if (e.actionMasked == MotionEvent.ACTION_DOWN) pickHoof(e.x, e.y); return true }
                if (moved < 2f && e.actionMasked == MotionEvent.ACTION_MOVE) return true
                var touched = false
                for (s in spots) {
                    val sx = hx + s.mx * hs; val sy = hy + s.my * hs
                    if (hypot(e.x - sx, e.y - sy) > s.r * hs * 1.4f) continue
                    touched = true
                    when (tool) {
                        Tool.ETRILLE -> if (s.head) { annoyed = 1f } else if (s.mud == 2) { s.polish += 0.12f; if (s.polish >= 1f) { s.mud = 1; s.polish = 0f; dust(sx, sy, Color.rgb(120, 96, 66)) } }
                        Tool.BROSSE_DURE -> if (s.head) { annoyed = 1f } else if (s.mud == 2) { wrongOrder++; s.polish += 0.03f; if (s.polish >= 1f) { s.mud = 1; s.polish = 0f } } else if (s.mud == 1) { s.polish += 0.14f; if (s.polish >= 1f) { s.mud = 0; s.polish = 0f; dust(sx, sy, Color.rgb(200, 186, 160)) } }
                        Tool.BROSSE_DOUCE -> if (s.mud == 0 || s.head) { s.polish = (s.polish + 0.12f).coerceAtMost(1f); if (s.head && s.polish >= 1f) s.mud = 0 }
                        else -> {}
                    }
                }
                if (touched) {
                    happy = (happy + 0.02f).coerceAtMost(1f) * (if (Personality.CALIN in horse.personality) 1.2f else 1f)
                    app.sound.play(SoundFx.S.BRUSH, 0.45f, if (tool == Tool.ETRILLE) 0.8f else 1.1f, 120)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { fingerX = -1f; return true }
        }
        return true
    }

    private fun pickHoof(x: Float, y: Float) {
        val a = Appearance.of(horse, game.day)
        val pos = listOf(0.33f * a.L to 0, -0.44f * a.L to 2, 0.33f * a.L - a.H * 0.035f to 1, -0.44f * a.L - a.H * 0.035f to 3)
        for ((mx, i) in pos) {
            val sx = hx + mx * hs; val sy = hy - a.H * 0.03f * hs
            if (hypot(x - sx, y - sy) < a.H * 0.09f * hs && !hooves[i]) {
                hooves[i] = true
                dust(sx, sy, Color.rgb(90, 70, 50))
                app.sound.play(SoundFx.S.HOOF_HARD, 0.6f)
                return
            }
        }
    }

    private fun dust(x: Float, y: Float, col: Int) {
        val r = Rng((x * 7 + y).toLong())
        repeat(8) { particles += floatArrayOf(x, y, r.range(-60f, 60f), r.range(-90f, -20f), 0.8f + r.float() * 0.5f, col.toFloat()) }
    }

    override fun draw(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        drawAisle(c, w, h, u)

        val a = Appearance.of(horse, g.day)
        // la propreté affichée suit le pansage en cours
        val dirtLeft = spots.count { it.mud > 0 }.toFloat() / spots.size.coerceAtLeast(1)
        val shown = Appearance(a.look, a.m, a.H, a.bcs, a.muscle, dirtLeft * (100f - horse.cleanliness), a.age, a.stallion, false, a.winter)
        hs = (h * 0.62f) / (a.H * 1.25f)
        hx = w * 0.42f; hy = h * 0.8f
        HorseArt.draw(c, shown, pose, hx, hy, hs)
        // boue et poussière restantes
        for (s in spots) {
            val sx = hx + s.mx * hs; val sy = hy + s.my * hs; val rr = s.r * hs
            when {
                s.mud == 2 -> { gui.p.color = -1; gui.p.shader = RadialGradient(sx, sy, rr, Color.argb(200, 104, 80, 52), Color.argb(0, 104, 80, 52), Shader.TileMode.CLAMP); c.drawCircle(sx, sy, rr, gui.p) }
                s.mud == 1 -> { gui.p.color = -1; gui.p.shader = RadialGradient(sx, sy, rr, Color.argb(120, 196, 180, 150), Color.argb(0, 196, 180, 150), Shader.TileMode.CLAMP); c.drawCircle(sx, sy, rr, gui.p) }
                s.polish > 0.5f -> { gui.p.color = -1; gui.p.shader = RadialGradient(sx - rr * 0.3f, sy - rr * 0.3f, rr * 0.8f, Color.argb((70 * s.polish).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP); c.drawCircle(sx, sy, rr, gui.p) }
            }
        }
        gui.p.shader = null
        // sabots à curer
        if (tool == Tool.CURE_PIED) {
            val pos = listOf(0.33f * a.L to 0, -0.44f * a.L to 2, 0.33f * a.L - a.H * 0.035f to 1, -0.44f * a.L - a.H * 0.035f to 3)
            for ((mx, i) in pos) {
                gui.sp.color = if (hooves[i]) Pal.OK else Pal.GOLD; gui.sp.strokeWidth = 2.5f * u
                c.drawCircle(hx + mx * hs, hy - a.H * 0.03f * hs, a.H * 0.07f * hs * (1f + 0.08f * sin(t * 5f)), gui.sp)
            }
        }
        for (p in particles) { gui.p.color = HorseArt.alpha(p[5].toInt(), p[4].coerceIn(0f, 1f)); c.drawCircle(p[0], p[1], 2.5f * u, gui.p) }
        // outil sous le doigt
        if (fingerX >= 0f) drawTool(c, fingerX, fingerY)

        // en-tête
        gui.dark(c, RectF(8f * u, 6f * u, w - 8f * u, 44f * u))
        gui.button(c, RectF(14f * u, 10f * u, 52f * u, 40f * u), "‹", Btn.GHOST, size = 18f) { finish() }
        gui.text(c, "Pansage de ${horse.name}", 62f * u, 24f * u, 15f, Pal.CREAM, font = gui.serif)
        gui.text(c, tool.tip, 62f * u, 38f * u, 10f, Pal.GOLD_L)
        val pr = progress()
        gui.gauge(c, w - 220f * u, 22f * u, 200f * u, "Avancement", pr * 100f, Pal.GOLD, "${(pr * 100).toInt()} %", dark = true)
        // outils
        val bar = RectF(8f * u, h - 62f * u, w - 160f * u, h - 8f * u)
        gui.dark(c, bar)
        val bw = (bar.width() - 10f * u) / Tool.values().size
        for ((i, tl) in Tool.values().withIndex()) {
            val r = RectF(bar.left + 5f * u + i * bw, bar.top + 5f * u, bar.left + (i + 1) * bw, bar.bottom - 5f * u)
            gui.button(c, r, tl.label, if (tl == tool) Btn.TAB_ON else Btn.TAB, size = 12f, sub = when (tl) { Tool.CURE_PIED -> "${hooves.count { it }}/4"; else -> null }) { tool = tl; app.sound.play(SoundFx.S.CLICK) }
        }
        gui.button(c, RectF(w - 150f * u, h - 58f * u, w - 10f * u, h - 12f * u), "Terminer", Btn.GOLD, size = 14f) { finish() }
        if (annoyed > 0f) gui.text(c, "${horse.name} n'aime pas ça : sur la tête, seulement la brosse douce !", w * 0.42f, 64f * u, 11f, Pal.CREAM, Paint.Align.CENTER, shadow = true)
        else if (happy > 0.6f) gui.text(c, "${horse.name} ferme à moitié les yeux de plaisir…", w * 0.42f, 64f * u, 11f, Pal.CREAM, Paint.Align.CENTER, shadow = true)
    }

    /** Allée d'écurie : bardage en planches, boxes, fenêtre, sellerie, foin, sol pavé et paille. */
    private var aisle: android.graphics.Bitmap? = null
    private fun drawAisle(c: Canvas, w: Float, h: Float, u: Float) {
        val cached = aisle
        if (cached != null && cached.width == w.toInt() && cached.height == h.toInt()) { c.drawBitmap(cached, 0f, 0f, null); return }
        cached?.recycle()
        val bmp = android.graphics.Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val cc = Canvas(bmp)
        paintAisle(cc, w, h, u)
        aisle = bmp
        c.drawBitmap(bmp, 0f, 0f, null)
    }

    private fun paintAisle(c: Canvas, w: Float, h: Float, u: Float) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = com.cheval.core.Rng(77)
        val floorY = h * 0.74f
        // mur en planches horizontales, chacune avec sa nuance et ses veinures
        val plankH = 18f * u
        var yy = 0f; var row = 0
        while (yy < floorY) {
            val k = 0.88f + r.float() * 0.2f
            p.color = HorseArt.shade(Color.rgb(132, 94, 62), k); c.drawRect(0f, yy, w, yy + plankH, p)
            p.color = Color.argb(28, 255, 230, 190); c.drawRect(0f, yy, w, yy + 2f * u, p)
            p.color = Color.argb(70, 0, 0, 0); c.drawRect(0f, yy + plankH - 1.5f * u, w, yy + plankH, p)
            // joints décalés et veinures
            var jx = (row % 3) * 70f * u
            while (jx < w) { Ink.line(c, jx, yy + 1f, jx, yy + plankH - 1f, 1f * u, Color.argb(80, 40, 26, 16)); jx += 230f * u }
            repeat(6) {
                val gx = r.float() * w; val gy = yy + plankH * (0.3f + r.float() * 0.4f); val gl = r.range(30f, 90f) * u
                Ink.line(c, gx, gy, gx + gl, gy + r.range(-1.5f, 1.5f) * u, 0.7f * u, Color.argb(40, 40, 26, 16))
            }
            if (r.chance(0.4f)) { val nx = r.float() * w; c.drawOval(nx, yy + plankH * 0.35f, nx + 7f * u, yy + plankH * 0.65f, Ink.stroke(Color.argb(70, 40, 26, 16), 0.8f * u)) }
            yy += plankH; row++
        }
        // poutres verticales
        for (bx in listOf(0.06f, 0.3f, 0.62f, 0.93f)) {
            val x = w * bx
            p.color = Color.rgb(98, 66, 42); c.drawRect(x - 9f * u, 0f, x + 9f * u, floorY, p)
            p.color = Color.argb(40, 255, 230, 190); c.drawRect(x - 9f * u, 0f, x - 5f * u, floorY, p)
            c.drawRect(x - 9f * u, 0f, x + 9f * u, floorY, Ink.stroke(Ink.INK, 1.2f * u))
        }
        // fenêtre avec lumière du jour
        val win = RectF(w * 0.38f, h * 0.16f, w * 0.54f, h * 0.36f)
        p.shader = LinearGradient(0f, win.top, 0f, win.bottom, Color.rgb(196, 222, 236), Color.rgb(226, 236, 214), Shader.TileMode.CLAMP); c.drawRect(win, p); p.shader = null
        p.color = Color.rgb(120, 160, 110); c.drawRect(win.left, win.bottom - win.height() * 0.25f, win.right, win.bottom, p)
        c.drawRect(win, Ink.stroke(Color.rgb(236, 226, 206), 5f * u)); c.drawRect(win, Ink.stroke(Ink.INK, 1.2f * u))
        Ink.line(c, win.centerX(), win.top, win.centerX(), win.bottom, 3f * u, Color.rgb(236, 226, 206)); Ink.line(c, win.left, win.centerY(), win.right, win.centerY(), 3f * u, Color.rgb(236, 226, 206))
        // toile d'araignée dans le coin
        for (q in 0..3) { val ang = q * 0.45f; Ink.line(c, win.left + 3f * u, win.top + 3f * u, win.left + 3f * u + kotlin.math.cos(ang) * 22f * u, win.top + 3f * u + kotlin.math.sin(ang) * 22f * u, 0.5f * u, Color.argb(120, 255, 255, 255)) }
        // porte de box à gauche : bas en planches, haut à barreaux
        val bd = RectF(w * 0.08f, h * 0.2f, w * 0.28f, floorY)
        p.color = Color.rgb(30, 22, 16); c.drawRect(bd, p)
        val mid = bd.top + bd.height() * 0.45f
        p.color = Color.rgb(84, 120, 88); c.drawRect(bd.left, mid, bd.right, bd.bottom, p)
        var px = bd.left + 6f * u; while (px < bd.right) { Ink.line(c, px, mid, px, bd.bottom, 1f * u, Color.argb(90, 20, 30, 20)); px += 12f * u }
        Ink.line(c, bd.left, mid, bd.right, bd.bottom, 3f * u, Color.rgb(64, 96, 70)); Ink.line(c, bd.right, mid, bd.left, bd.bottom, 3f * u, Color.rgb(64, 96, 70))
        var bx2 = bd.left + 8f * u; while (bx2 < bd.right) { Ink.line(c, bx2, bd.top, bx2, mid, 2.5f * u, Color.rgb(60, 60, 60)); bx2 += 14f * u }
        c.drawRect(bd, Ink.stroke(Ink.INK, 1.5f * u))
        // plaque du box
        val pl = RectF(bd.centerX() - 28f * u, mid - 22f * u, bd.centerX() + 28f * u, mid - 6f * u)
        Ink.parchment(c, pl, u, border = true)
        // sellerie à droite : selle sur porte-selle, filet sur un crochet, licol
        val sx = w * 0.78f; val sy = h * 0.32f
        Ink.line(c, sx - 26f * u, sy + 10f * u, sx + 26f * u, sy + 10f * u, 5f * u, Color.rgb(90, 60, 40))
        val sp = Path(); sp.moveTo(sx - 34f * u, sy + 6f * u); sp.cubicTo(sx - 30f * u, sy - 18f * u, sx - 10f * u, sy - 6f * u, sx, sy - 6f * u)
        sp.cubicTo(sx + 14f * u, sy - 6f * u, sx + 26f * u, sy - 20f * u, sx + 34f * u, sy + 2f * u); sp.lineTo(sx + 30f * u, sy + 10f * u); sp.lineTo(sx - 30f * u, sy + 12f * u); sp.close()
        Ink.wash(c, sp, Color.rgb(110, 62, 34), 1.5f * u)
        Ink.line(c, sx + 6f * u, sy + 8f * u, sx + 8f * u, sy + 50f * u, 2f * u, Color.rgb(80, 46, 26))
        c.drawRect(sx + 3f * u, sy + 48f * u, sx + 13f * u, sy + 56f * u, Ink.stroke(Color.rgb(170, 170, 170), 2f * u))
        val hk = w * 0.86f; val hky = h * 0.18f
        c.drawCircle(hk, hky, 3f * u, Ink.fill(Color.rgb(160, 160, 160)))
        c.drawOval(hk - 14f * u, hky, hk + 14f * u, hky + 52f * u, Ink.stroke(Color.rgb(70, 40, 24), 2.5f * u))
        Ink.line(c, hk - 10f * u, hky + 30f * u, hk + 10f * u, hky + 30f * u, 2f * u, Color.rgb(70, 40, 24))
        c.drawCircle(hk, hky + 54f * u, 4f * u, Ink.stroke(Color.rgb(180, 180, 180), 1.6f * u))
        // anneaux d'attache avec longes
        for (rx in listOf(w * 0.15f, w * 0.8f)) {
            c.drawCircle(rx, h * 0.5f, 6f * u, Ink.stroke(Color.rgb(170, 170, 170), 2f * u))
            Ink.line(c, rx, h * 0.5f + 6f * u, rx + 4f * u, h * 0.5f + 30f * u, 2.5f * u, Color.rgb(196, 60, 50))
        }
        // sol pavé
        p.color = Color.rgb(150, 142, 130); c.drawRect(0f, floorY, w, h, p)
        var fy = floorY; var frow = 0
        while (fy < h) {
            val ph = 9f * u + (fy - floorY) * 0.12f
            var fx = -(frow % 2) * ph
            while (fx < w) {
                val kk = 0.85f + r.float() * 0.25f
                c.drawRoundRect(fx + 1f * u, fy + 1f * u, fx + ph * 2f - 1f * u, fy + ph - 1f * u, 3f * u, 3f * u, Ink.fill(HorseArt.shade(Color.rgb(160, 150, 136), kk)))
                fx += ph * 2f
            }
            fy += ph; frow++
        }
        p.shader = LinearGradient(0f, floorY, 0f, floorY + 30f * u, Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP); c.drawRect(0f, floorY, w, floorY + 30f * u, p); p.shader = null
        // brins de paille éparpillés
        repeat(160) {
            val x = r.float() * w; val y = floorY + r.float() * (h - floorY)
            val a = r.range(0f, 3.14f); val l = r.range(6f, 16f) * u
            Ink.line(c, x, y, x + kotlin.math.cos(a) * l, y + kotlin.math.sin(a) * l * 0.4f, 1.2f * u, if (r.chance(0.5f)) Color.rgb(222, 196, 120) else Color.rgb(196, 166, 92))
        }
        // botte de foin et seau
        val hb = RectF(w * 0.86f, floorY - 30f * u, w * 0.99f, floorY + 14f * u)
        Ink.wash(c, Path().apply { addRoundRect(hb, 6f * u, 6f * u, Path.Direction.CW) }, Color.rgb(214, 186, 106), 1.4f * u)
        repeat(40) { val x = hb.left + r.float() * hb.width(); val y = hb.top + r.float() * hb.height(); Ink.line(c, x, y, x + r.range(-8f, 8f) * u, y + r.range(-3f, 3f) * u, 0.8f * u, Color.argb(140, 150, 120, 60)) }
        Ink.line(c, hb.left + hb.width() * 0.3f, hb.top, hb.left + hb.width() * 0.3f, hb.bottom, 1.5f * u, Color.rgb(170, 60, 40)); Ink.line(c, hb.left + hb.width() * 0.7f, hb.top, hb.left + hb.width() * 0.7f, hb.bottom, 1.5f * u, Color.rgb(170, 60, 40))
        val bk = Path(); val bxc = w * 0.08f; val byc = floorY + 30f * u
        bk.moveTo(bxc - 18f * u, byc - 26f * u); bk.lineTo(bxc + 18f * u, byc - 26f * u); bk.lineTo(bxc + 14f * u, byc + 4f * u); bk.lineTo(bxc - 14f * u, byc + 4f * u); bk.close()
        Ink.wash(c, bk, Color.rgb(60, 110, 170), 1.4f * u)
        c.drawOval(bxc - 18f * u, byc - 30f * u, bxc + 18f * u, byc - 22f * u, Ink.fill(Color.rgb(120, 170, 200)))
        c.drawArc(bxc - 18f * u, byc - 46f * u, bxc + 18f * u, byc - 14f * u, 180f, 180f, false, Ink.stroke(Color.rgb(170, 170, 170), 1.5f * u))
        // lumière de la fenêtre et grain du papier
        p.shader = RadialGradient(w * 0.46f, h * 0.26f, w * 0.55f, Color.argb(80, 255, 225, 160), Color.argb(0, 255, 225, 160), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        Ink.grain(c, RectF(0f, 0f, w, h), 110)
    }

    private fun drawTool(c: Canvas, x: Float, y: Float) {
        val u = gui.u
        gui.p.shader = null
        when (tool) {
            Tool.ETRILLE -> { gui.p.color = Color.rgb(200, 40, 40); c.drawOval(x - 16f * u, y - 10f * u, x + 16f * u, y + 10f * u, gui.p); gui.sp.color = Color.rgb(140, 20, 20); gui.sp.strokeWidth = 1.5f * u; for (k in 0..2) c.drawOval(x - (12f - k * 4f) * u, y - (7f - k * 2.5f) * u, x + (12f - k * 4f) * u, y + (7f - k * 2.5f) * u, gui.sp) }
            Tool.BROSSE_DURE, Tool.BROSSE_DOUCE -> {
                gui.p.color = Color.rgb(120, 80, 46); c.drawRoundRect(x - 20f * u, y - 9f * u, x + 20f * u, y + 3f * u, 4f * u, 4f * u, gui.p)
                gui.p.color = if (tool == Tool.BROSSE_DURE) Color.rgb(60, 50, 40) else Color.rgb(220, 210, 190)
                c.drawRect(x - 18f * u, y + 3f * u, x + 18f * u, y + 9f * u, gui.p)
            }
            Tool.CURE_PIED -> { gui.sp.color = Color.rgb(180, 180, 180); gui.sp.strokeWidth = 3f * u; c.drawLine(x, y, x + 16f * u, y - 16f * u, gui.sp); c.drawLine(x, y, x - 4f * u, y + 6f * u, gui.sp) }
        }
    }

    private fun finish() {
        if (done) return
        done = true
        val pr = progress()
        if (pr > 0.05f) {
            val q = (pr - wrongOrder * 0.02f).coerceIn(0f, 1f)
            game.spend(0.75f)
            val r = game.groom(horse, q)
            if (hooves.all { it }) horse.hooves = (horse.hooves + 2f).coerceAtMost(100f)
            Looks.invalidate(horse.id)
            gui.toast(r.msg + if (wrongOrder > 5) " (Astuce : l'étrille d'abord, la brosse ensuite.)" else "", Pal.GREEN)
            if (q > 0.85f) app.sound.play(SoundFx.S.SNORT, 0.7f)
        }
        app.pop()
    }

    override fun onBack(): Boolean { finish(); return true }
}
