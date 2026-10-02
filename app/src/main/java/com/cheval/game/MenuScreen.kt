package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.cheval.core.Breed
import com.cheval.core.Game
import com.cheval.core.Season
import com.cheval.core.Sex
import com.cheval.core.Sky

/** Écran titre : un cheval galope sur la grève de la baie au soleil couchant. */
class MenuScreen(app: GameView) : Screen(app) {
    private var t = 0f
    private val demo = Game(2027)
    private val horses = listOf(
        demo.generateHorse(Breed.SELLE_FRANCAIS, Sex.JUMENT, 8, withAncestors = false),
        demo.generateHorse(Breed.CONNEMARA, Sex.HONGRE, 9, withAncestors = false),
    )
    private val poses = List(2) { HorsePose().apply { gait = Gait.GRAND_GALOP; speedBlend = 0.8f; neck = 30f; head = 52f } }
    private val amb = Ambience(hour = 20.6f, season = Season.ETE, sky = Sky.SOLEIL, sunrise = 6f, sunset = 21.4f)
    private var confirmNew = false
    private var chooseTuto = false

    init { horses[1].genome.alleles[com.cheval.core.Locus.GREY.ordinal * 2] = 1 }

    override fun update(dt: Float) {
        t += dt
        poses.forEachIndexed { i, p -> p.phase = (p.phase + dt * Gait.GRAND_GALOP.freq * (0.97f + i * 0.04f)) % 1f; p.tailSwing = kotlin.math.sin(t * 3f + i) }
        if ((t * 2f).toInt() != ((t - dt) * 2f).toInt()) {
            val ph = poses[0].phase
            if (ph < 0.1f) app.sound.play(SoundFx.S.HOOF_SOFT, 0.25f, 1f)
        }
    }

    override fun draw(c: Canvas) {
        val w = gui.w; val h = gui.h; val u = gui.u
        val horizon = h * 0.5f
        Scenery.sky(c, w, horizon, amb, t)
        Scenery.hills(c, w, horizon, h * 0.08f, 120f, 4, Scenery.lit(Color.rgb(96, 112, 130), amb))
        Scenery.sea(c, 0f, w, horizon, h * 0.66f, amb, t)
        Scenery.sand(c, 0f, w, h * 0.66f, h, amb, wet = false)
        // sable mouillé et reflets
        gui.p.color = -1; gui.p.shader = LinearGradient(0f, h * 0.66f, 0f, h * 0.76f, Color.argb(160, 150, 130, 110), Color.argb(0, 150, 130, 110), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.66f, w, h * 0.76f, gui.p); gui.p.shader = null
        val scale = h / 520f
        val travel = (w * 0.55f + t * 70f) % (w + 900f) - 200f
        for (i in horses.indices.reversed()) {
            val x = travel - i * 210f * scale
            val y = h * (0.86f - i * 0.05f)
            HorseArt.draw(c, Looks.of(horses[i], demo.day), poses[i], x, y, scale * (1f - i * 0.12f), light = amb.light)
        }
        // titre
        gui.text(c, "Haras de la Baie", 28f * u, 64f * u, 34f, Pal.CREAM, font = gui.serif, shadow = true)
        gui.text(c, "Élevage · Soins · Équitation · Concours", 30f * u, 86f * u, 13f, Pal.GOLD_L, shadow = true)
        // boutons
        val bw = 190f * u; val bx = w - bw - 26f * u
        var y = h * 0.16f
        if (app.hasSave()) {
            gui.button(c, RectF(bx, y, bx + bw, y + 40f * u), "Continuer", Btn.GOLD, size = 15f) { app.sound.play(SoundFx.S.CLICK); app.loadGame() }
            y += 50f * u
        }
        gui.button(c, RectF(bx, y, bx + bw, y + 40f * u), "Nouvelle partie", Btn.PRIMARY, size = 15f) {
            app.sound.play(SoundFx.S.CLICK)
            if (app.hasSave()) confirmNew = true else app.push(NewGameScreen(app))
        }
        y += 50f * u
        gui.button(c, RectF(bx, y, bx + bw, y + 40f * u), "Apprentissage", Btn.GOLD, size = 14f) { app.sound.play(SoundFx.S.CLICK); chooseTuto = true }
        y += 50f * u
        gui.button(c, RectF(bx, y, bx + bw, y + 40f * u), "Guide du cavalier", size = 14f) { app.sound.play(SoundFx.S.CLICK); app.push(HelpScreen(app)) }
        y += 50f * u
        gui.button(c, RectF(bx, y, bx + bw, y + 34f * u), if (app.sound.enabled) "Son : activé" else "Son : coupé", Btn.GHOST, size = 12f) { app.toggleSound() }
        gui.text(c, "Version 1.0 — tout est dessiné et synthétisé par le jeu", w - 14f * u, h - 10f * u, 9f, HorseArt.alpha(Pal.CREAM, 0.7f), Paint.Align.RIGHT)
        if (chooseTuto) {
            gui.modal(c)
            val r = RectF(w / 2 - 230f * u, h / 2 - 120f * u, w / 2 + 230f * u, h / 2 + 120f * u)
            gui.paper(c, r)
            gui.text(c, "Apprentissage", r.left + 20f * u, r.top + 32f * u, 19f, Ink.INK, font = Ink.hand)
            gui.wrap(c, "Un domaine d'entraînement, à part de ta sauvegarde, pour apprendre en jouant.", r.left + 20f * u, r.top + 56f * u, r.width() - 40f * u, 11.5f, Ink.INK)
            gui.button(c, RectF(r.left + 20f * u, r.top + 82f * u, r.right - 20f * u, r.top + 124f * u), "Apprentissage complet", Btn.PRIMARY, size = 14f, sub = "tout le jeu pas à pas · environ 10 min") {
                chooseTuto = false; app.sound.play(SoundFx.S.NEIGH, 0.5f); app.startTutorial(full = true)
            }
            gui.button(c, RectF(r.left + 20f * u, r.top + 132f * u, r.right - 20f * u, r.top + 174f * u), "Apprentissage rapide", Btn.GOLD, size = 14f, sub = "l'essentiel · environ 2 min") {
                chooseTuto = false; app.sound.play(SoundFx.S.NEIGH, 0.5f); app.startTutorial(full = false)
            }
            gui.button(c, RectF(r.right - 120f * u, r.bottom - 42f * u, r.right - 20f * u, r.bottom - 12f * u), "Annuler", size = 12f) { chooseTuto = false }
        }
        if (confirmNew) {
            gui.modal(c)
            val r = RectF(w / 2 - 170f * u, h / 2 - 70f * u, w / 2 + 170f * u, h / 2 + 70f * u)
            gui.paper(c, r)
            gui.text(c, "Recommencer ?", r.left + 18f * u, r.top + 30f * u, 17f, font = gui.serif)
            gui.wrap(c, "Votre domaine actuel sera remplacé par une nouvelle partie.", r.left + 18f * u, r.top + 54f * u, r.width() - 36f * u, 12f)
            gui.button(c, RectF(r.left + 18f * u, r.bottom - 46f * u, r.centerX() - 6f * u, r.bottom - 14f * u), "Annuler") { confirmNew = false }
            gui.button(c, RectF(r.centerX() + 6f * u, r.bottom - 46f * u, r.right - 18f * u, r.bottom - 14f * u), "Nouvelle partie", Btn.DANGER) {
                confirmNew = false; app.push(NewGameScreen(app))
            }
        }
    }

    override fun onBack(): Boolean { if (confirmNew || chooseTuto) { confirmNew = false; chooseTuto = false; return true }; return false }
}
