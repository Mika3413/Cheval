package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
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

    override fun dispose() { gui.onRawTouch = null }

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
        // allée d'écurie
        gui.p.color = -1; gui.p.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(130, 92, 62), Color.rgb(70, 48, 32), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, gui.p); gui.p.shader = null
        gui.p.color = Color.argb(40, 0, 0, 0)
        var yy = 0f; while (yy < h * 0.74f) { c.drawRect(0f, yy, w, yy + 1.5f * u, gui.p); yy += 18f * u }
        gui.p.color = Color.rgb(150, 142, 130); c.drawRect(0f, h * 0.74f, w, h, gui.p)
        gui.p.color = -1; gui.p.shader = RadialGradient(w * 0.45f, h * 0.1f, w * 0.6f, Color.argb(80, 255, 225, 160), Color.argb(0, 255, 225, 160), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, gui.p); gui.p.shader = null
        // anneaux d'attache
        gui.sp.color = Color.rgb(170, 170, 170); gui.sp.strokeWidth = 2f * u
        c.drawCircle(w * 0.15f, h * 0.3f, 6f * u, gui.sp); c.drawCircle(w * 0.8f, h * 0.3f, 6f * u, gui.sp)

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
