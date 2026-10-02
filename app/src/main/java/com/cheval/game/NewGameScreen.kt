package com.cheval.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.cheval.core.Cal
import com.cheval.core.Game
import com.cheval.core.fmtMoney

/** Nouvelle partie : nom du domaine, du cavalier, et choix du premier cheval parmi trois. */
class NewGameScreen(app: GameView) : Screen(app) {
    private val game = Game(System.nanoTime())
    private var choices = game.starterChoices()
    private var chosen = 0
    private var t = 0f
    private val poses = List(3) { HorsePose().apply { breathe = it * 0.3f } }

    init { game.newGameSetup() }

    override fun update(dt: Float) {
        t += dt
        poses.forEachIndexed { i, p ->
            p.breathe = (p.breathe + dt * 0.25f) % 1f
            p.blink = if ((t + i * 1.7f) % 4.3f < 0.15f) 1f else 0f
            p.tailSwing = kotlin.math.sin(t * 1.3f + i * 2f) * 0.6f
            p.ears = if (i == chosen) 0.8f else kotlin.math.sin(t * 0.7f + i) * 0.5f
            p.neck = if (i == chosen) 55f else 45f
        }
    }

    override fun draw(c: Canvas) {
        paperBackground(c, gui)
        val u = gui.u; val w = gui.w; val h = gui.h
        titleBar(c, gui, "Nouveau domaine", "Choisissez votre premier cheval — il vous est offert") { app.pop() }
        // nom du domaine
        val top = 54f * u
        gui.text(c, "Domaine :", 16f * u, top + 18f * u, 12f, Pal.INK_L)
        gui.button(c, RectF(80f * u, top + 2f * u, 290f * u, top + 28f * u), game.stableName, size = 12.5f) {
            app.askText("Nom de votre domaine", game.stableName) { game.stableName = it }
        }
        gui.text(c, "Cavalier :", 304f * u, top + 18f * u, 12f, Pal.INK_L)
        gui.button(c, RectF(372f * u, top + 2f * u, 520f * u, top + 28f * u), game.rider.name, size = 12.5f) {
            app.askText("Votre prénom", game.rider.name) { game.rider.name = it }
        }
        gui.text(c, "Départ le ${Cal.format(game.day)} · ${fmtMoney(game.money)} · une caravane, un vieil abri de 2 boxes et un domaine à remettre en état", w - 16f * u, top + 18f * u, 10.5f, Pal.INK_L, Paint.Align.RIGHT, maxW = w - 540f * u)
        // trois cartes
        val cardTop = top + 38f * u
        val cardH = h - cardTop - 60f * u
        val cw = (w - 16f * u * 4) / 3f
        for ((i, hz) in choices.withIndex()) {
            val r = RectF(16f * u + i * (cw + 16f * u), cardTop, 16f * u + i * (cw + 16f * u) + cw, cardTop + cardH)
            gui.paper(c, r, color = if (i == chosen) Pal.PAPER else Pal.CREAM)
            if (i == chosen) { gui.sp.color = Pal.GOLD; gui.sp.strokeWidth = 3f * u; c.drawRoundRect(r, 10f * u, 10f * u, gui.sp) }
            val pic = RectF(r.left + 6f * u, r.top + 4f * u, r.right - 6f * u, r.top + cardH * 0.52f)
            drawHorseFit(c, hz, game.day, pic, poses[i])
            var y = pic.bottom + 16f * u
            gui.text(c, hz.name, r.left + 12f * u, y, 15f, font = gui.serif, maxW = r.width() - 24f * u); y += 16f * u
            gui.text(c, "${hz.breed.label} · ${sexIcon(hz)} ${hz.sex.label} · ${Cal.ageText(hz.birthDay, game.day)}", r.left + 12f * u, y, 10.5f, Pal.INK_L, maxW = r.width() - 24f * u); y += 14f * u
            gui.text(c, "${hz.coatName(game.day)} · ${hz.heightCm} cm", r.left + 12f * u, y, 10.5f, Pal.INK_L, maxW = r.width() - 24f * u); y += 16f * u
            gui.text(c, "Potentiel", r.left + 12f * u, y, 10.5f, Pal.INK); gui.stars(c, r.left + 70f * u, y - 4f * u, potentialStars(hz)); y += 15f * u
            gui.text(c, "Point fort : ${hz.bestDiscipline().label}", r.left + 12f * u, y, 10.5f, Pal.INK, maxW = r.width() - 24f * u); y += 14f * u
            gui.text(c, "Caractère : ${hz.personality.joinToString { it.label.lowercase() }}", r.left + 12f * u, y, 10.5f, Pal.INK_L, maxW = r.width() - 24f * u)
            gui.hit(r) { chosen = i; app.sound.play(SoundFx.S.WHINNY_SHORT, 0.5f, 0.9f + i * 0.1f) }
        }
        gui.button(c, RectF(w - 236f * u, h - 50f * u, w - 16f * u, h - 12f * u), "Ouvrir le domaine", Btn.GOLD, size = 15f) {
            app.sound.play(SoundFx.S.NEIGH, 0.7f)
            game.takeStarter(choices[chosen])
            // Les deux autres chevaux restent proposés sur le marché local.
            choices.filterIndexed { i, _ -> i != chosen }.forEach { o -> game.market += com.cheval.core.Listing(o.id, game.value(o), "Élevage voisin", game.day + 20) }
            app.startGame(game)
        }
        gui.wrap(c, "Astuce : un cheval calme et déjà dressé pardonne les erreurs du début. Les jeunes cracks, eux, demandent du temps.", 16f * u, h - 34f * u, w - 280f * u, 10.5f, Pal.INK_L)
    }
}
