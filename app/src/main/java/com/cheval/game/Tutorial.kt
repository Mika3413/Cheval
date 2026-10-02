package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.cheval.core.BuildingType
import com.cheval.core.Game
import com.cheval.core.Place
import com.cheval.core.St
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Une étape d'apprentissage : un texte, l'écran où elle se déroule, l'élément à mettre en valeur,
 * et la condition qui la valide (ou un bouton « Suivant »).
 */
class TutoStep(
    val title: String,
    val text: String,
    val screen: Class<out Screen>? = HubScreen::class.java,
    val target: ((GameView) -> RectF?)? = null,
    val onEnter: ((GameView) -> Unit)? = null,
    val next: Boolean = false,
    val done: (GameView) -> Boolean = { false },
)

/**
 * Apprentissage guidé, complet ou rapide, joué sur une partie d'entraînement à part
 * (la vraie sauvegarde n'est jamais touchée).
 */
class Tutorial(val full: Boolean) {
    private var index = 0
    private var entered = -1
    private var t = 0f
    var finished = false
        private set
    private val steps: List<TutoStep> = if (full) fullSteps() else quickSteps()
    val step get() = steps[index.coerceAtMost(steps.size - 1)]
    val progress get() = "${index + 1}/${steps.size}"

    companion object {
        /** Prépare la partie d'entraînement : le domaine à l'abandon et un cheval calme. */
        fun newGame(): Game {
            val g = Game(20270301)
            g.stableName = "Domaine d'apprentissage"
            g.newGameSetup()
            val starter = g.starterChoices().maxBy { it.pot(com.cheval.core.Trait.CALME) }
            g.takeStarter(starter)
            starter.place = Place.BOX
            starter.satiety = 35f; starter.hayRack = 0f; starter.bucket = 5f; starter.litter = 40f; starter.cleanliness = 30f
            return g
        }

        private fun hub(app: GameView) = app.screen as? HubScreen

        private fun fullSteps(): List<TutoStep> = listOf(
            TutoStep("Bienvenue !", "Tu viens de racheter un vieux domaine au bord de la baie. Il y a tout à reconstruire… et un cheval qui compte sur toi. Suis le guide : je te montre tout, pas à pas.", next = true),
            TutoStep("Ton domaine", "Voici la carte. Fais-la glisser du doigt (ou utilise les flèches ‹ ›) pour te promener. La caravane orange, c'est chez toi. Le vieil abri en bois, c'est l'écurie de ton cheval.", next = true,
                onEnter = { hub(it)?.focusPlot(BuildingType.ECURIE) }),
            TutoStep("L'écurie", "Ton cheval a faim et soif. Touche le vieil abri pour t'en occuper.",
                target = { hub(it)?.plotRect(BuildingType.ECURIE) }, onEnter = { hub(it)?.focusPlot(BuildingType.ECURIE) },
                done = { hub(it)?.modalOpen == true || (it.game?.stats?.get(St.FEED) ?: 0) > 0 }),
            TutoStep("La tournée des soins", "Touche « Tournée des soins » : repas de foin, eau fraîche et litière propre pour tous les chevaux. Chaque action prend du temps : regarde l'horloge en haut à droite.",
                target = { app -> hub(app)?.takeIf { it.modalOpen }?.careRoundRect() },
                done = { (it.game?.stats?.get(St.FEED) ?: 0) > 0 }),
            TutoStep("Tes chevaux", "Ouvre maintenant la liste de tes chevaux : touche « Chevaux » sur l'étagère du bas.",
                target = { hub(it)?.hudShelf(0) },
                done = { it.screen is HorsesScreen || it.screen is HorseScreen }),
            TutoStep("La fiche", "Touche ton cheval pour ouvrir sa fiche.", screen = HorsesScreen::class.java,
                target = { app -> val u = app.gui.u; RectF(12f * u, 84f * u, 12f * u + (app.gui.w - 34f * u) / 2f, 146f * u) },
                done = { it.screen is HorseScreen }),
            TutoStep("Sa fiche de santé", "À droite, ses besoins : satiété, eau, propreté, moral, santé, sabots… Une jauge rouge, c'est urgent. Fais défiler vers le bas : tu trouveras la ration et tous les soins. Touche « Panser » pour le brosser.", screen = HorseScreen::class.java,
                target = { app -> val u = app.gui.u; RectF(app.gui.w * 0.42f + 10f * u, 88f * u, app.gui.w - 10f * u, app.gui.h - 10f * u) },
                done = { it.screen is GroomScreen || (it.game?.stats?.get(St.GROOM) ?: 0) > 0 }),
            TutoStep("Le pansage", "L'étrille en petits cercles décolle la boue (jamais sur la tête). La brosse dure chasse la poussière dans le sens du poil, la brosse douce fait briller et soigne la tête. Démêle les crins au peigne, puis cure les pieds. Touche « Terminer » quand il brille.", screen = GroomScreen::class.java,
                target = { app -> val u = app.gui.u; RectF(8f * u, app.gui.h - 62f * u, app.gui.w - 10f * u, app.gui.h - 8f * u) },
                done = { (it.game?.stats?.get(St.GROOM) ?: 0) > 0 }),
            TutoStep("En selle !", "Touche l'onglet « Travail », puis « Monter » sur la ligne « Balade en extérieur ».", screen = HorseScreen::class.java,
                target = { app -> val u = app.gui.u; val right = RectF(app.gui.w * 0.42f + 10f * u, 54f * u, app.gui.w - 10f * u, app.gui.h); val tw = right.width() / 6; RectF(right.left + tw, right.top, right.left + 2 * tw, right.top + 28f * u) },
                done = { it.screen is RideScreen || (it.game?.stats?.get(St.RIDE) ?: 0) > 0 }),
            TutoStep("Les allures", "Choisis l'allure avec les boutons du bas : pas, trot, galop, grand galop. Surveille le souffle en haut. Quand tu veux, touche « Rentrer ».", screen = RideScreen::class.java,
                target = { app -> val u = app.gui.u; RectF(10f * u, app.gui.h - 58f * u, 10f * u + 5 * 80f * u, app.gui.h - 8f * u) },
                done = { (it.game?.stats?.get(St.RIDE) ?: 0) > 0 }),
            TutoStep("Retour au domaine", "Bravo, première balade ! Touche « Retour », puis la flèche ‹ en haut à gauche jusqu'à revenir sur la carte.", screen = null,
                done = { it.screen is HubScreen }),
            TutoStep("Faire le ménage", "Des déchets traînent partout. Touche un tas et nettoie-le : ça embellit le domaine, et on y trouve parfois de quoi revendre !",
                target = { hub(it)?.junkRect() }, onEnter = { hub(it)?.focusJunk() },
                done = { (it.game?.stats?.get(St.JUNK) ?: 0) > 0 }),
            TutoStep("Les objectifs", "Les objectifs te guident et rapportent de l'argent. Ouvre-les et réclame tes récompenses !",
                target = { hub(it)?.hudObjectifs() },
                done = { (it.game?.goalsDone?.size ?: 0) > 0 }),
            TutoStep("Bâtir son domaine", "Touche un bâtiment ou une parcelle avec un panneau pour le construire ou le restaurer. La carrière, par exemple, permet de donner des cours d'équitation : la première source d'argent du domaine.",
                target = { hub(it)?.plotRect(BuildingType.CARRIERE) }, onEnter = { hub(it)?.focusPlot(BuildingType.CARRIERE) }, next = true),
            TutoStep("Les concours", "Le tableau d'affichage, à l'entrée du domaine, liste les concours (aussi via « Concours » en bas). Tu peux monter toi-même en direct : CSO, dressage, cross, course, trot attelé, hunter, endurance, western…",
                target = { hub(it)?.boardRect() }, onEnter = { hub(it)?.focus(0.1f) }, next = true),
            TutoStep("Élevage, marché, domaine", "« Élevage » pour faire saillir tes juments, « Marché » pour acheter et vendre (offres d'achat comprises), « Domaine » pour les bâtiments, l'équipe, les stocks de foin et de paille, et tes finances.",
                target = { app -> hub(app)?.let { h -> RectF(h.hudShelf(2).left, h.hudShelf(2).top, h.hudShelf(4).right, h.hudShelf(4).bottom) } }, next = true),
            TutoStep("La fin de journée", "La journée de travail va de 7 h à 21 h. Quand tu as fini, touche « Nouvelle journée » : tu dors, et le matin tu lis le rapport de la nuit.",
                target = { app -> hub(app)?.let { h -> RectF(h.hudNewDay().left, h.hudNewDay().top, h.hudNewDay().right, h.hudClock().bottom) } },
                done = { (it.game?.stats?.get(St.DAYS) ?: 0) > 0 }),
            TutoStep("Tu es prêt !", "Pense chaque jour à la tournée des soins, au pansage et au travail, et fais vacciner, vermifuger et ferrer tes chevaux à temps (fiche › Soins). Bonne chance pour bâtir le plus beau haras de la baie !", next = true),
        )

        private fun quickSteps(): List<TutoStep> = listOf(
            TutoStep("L'essentiel", "Voici ton domaine à reconstruire. Fais glisser la carte pour te promener. Ton cheval vit dans le vieil abri.", next = true,
                onEnter = { hub(it)?.focusPlot(BuildingType.ECURIE) }),
            TutoStep("Les soins", "Touche l'abri puis « Tournée des soins » : repas, eau et litière pour tout le monde.",
                target = { app -> hub(app)?.let { h -> if (h.modalOpen) h.careRoundRect() else h.plotRect(BuildingType.ECURIE) } },
                onEnter = { hub(it)?.focusPlot(BuildingType.ECURIE) },
                done = { (it.game?.stats?.get(St.FEED) ?: 0) > 0 }),
            TutoStep("Tes chevaux", "Le bouton « Chevaux » ouvre leurs fiches : besoins, santé, pansage, travail et monte, génétique, concours. Une jauge rouge, c'est urgent !",
                target = { hub(it)?.hudShelf(0) }, next = true),
            TutoStep("Le temps", "Chaque action prend du temps : l'horloge indique ce qu'il reste de la journée (7 h à 21 h). « Nouvelle journée » pour dormir.",
                target = { app -> hub(app)?.let { h -> RectF(h.hudNewDay().left, h.hudNewDay().top, h.hudNewDay().right, h.hudClock().bottom) } }, next = true),
            TutoStep("Les objectifs", "Les objectifs te disent quoi faire ensuite et rapportent de l'argent. Pense à les réclamer !",
                target = { hub(it)?.hudObjectifs() }, next = true),
            TutoStep("Gagner sa vie", "Nettoie les déchets, restaure les bâtiments, donne des cours d'équitation (carrière), engage tes chevaux en concours (tableau d'affichage) et vends tes poulains. Le Guide du cavalier (Menu) explique tout en détail.", next = true),
        )
    }

    fun update(app: GameView, dt: Float) {
        t += dt
        if (finished) return
        val s = step
        if (entered != index) { entered = index; s.onEnter?.invoke(app) }
        if (!s.next && s.done(app)) advance(app)
    }

    private fun advance(app: GameView) {
        app.sound.play(SoundFx.S.GOOD, 0.5f)
        if (index >= steps.size - 1) { finished = true; return }
        index++
    }

    fun skip() { finished = true }

    fun draw(c: Canvas, app: GameView) {
        val gui = app.gui
        val u = gui.u; val w = gui.w; val h = gui.h
        if (finished) { drawEnd(c, app); return }
        val s = step
        val onScreen = s.screen == null || s.screen.isInstance(app.screen)
        val target = if (onScreen) s.target?.invoke(app) else null
        // voile autour de l'élément à toucher
        if (target != null) {
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(0f, 0f, w, h, Path.Direction.CW); addRoundRect(target.left - 6f * u, target.top - 6f * u, target.right + 6f * u, target.bottom + 6f * u, 10f * u, 10f * u, Path.Direction.CW) }
            gui.p.shader = null; gui.p.color = Color.argb(110, 10, 14, 10)
            c.drawPath(path, gui.p)
            val pulse = 1f + 0.5f * (0.5f + 0.5f * sin(t * 5f))
            gui.sp.shader = null; gui.sp.color = Pal.GOLD_L; gui.sp.strokeWidth = 2.5f * u * pulse
            c.drawRoundRect(target.left - 6f * u, target.top - 6f * u, target.right + 6f * u, target.bottom + 6f * u, 10f * u, 10f * u, gui.sp)
        }
        // bulle
        val bw = min(330f * u, w - 30f * u)
        val text = if (onScreen) s.text else "Reviens à l'écran indiqué pour continuer (bouton ‹ pour revenir en arrière)."
        val th = gui.wrap(null, text, 0f, 0f, bw - 28f * u, 11.5f) + 70f * u + (if (s.next) 30f * u else 0f)
        var bx = w / 2 - bw / 2; var by = h * 0.3f
        if (target != null) {
            // dessous, dessus, à droite ou à gauche de l'élément : la première place libre
            val top = 50f * u; val bottom = h - 50f * u; val m = 14f * u
            val cx = (target.centerX() - bw / 2).coerceIn(12f * u, w - bw - 12f * u)
            val cy = (target.centerY() - th / 2).coerceIn(top, bottom - th)
            when {
                target.bottom + m + th < bottom -> { bx = cx; by = target.bottom + m }
                target.top - m - th > top -> { bx = cx; by = target.top - m - th }
                target.right + m + bw < w - 8f * u -> { bx = target.right + m; by = cy }
                target.left - m - bw > 8f * u -> { bx = target.left - m - bw; by = cy }
                else -> { bx = cx; by = bottom - th }
            }
        }
        val r = RectF(bx, by, bx + bw, by + th)
        Ink.parchment(c, r, u)
        // petit cheval guide
        gui.text(c, "🐴", r.left + 12f * u, r.top + 26f * u, 16f, Ink.INK)
        gui.text(c, s.title, r.left + 38f * u, r.top + 24f * u, 15f, Ink.INK, font = Ink.hand, maxW = bw - 110f * u)
        gui.text(c, progress, r.right - 14f * u, r.top + 22f * u, 10f, Ink.INK, Paint.Align.RIGHT)
        gui.wrap(c, text, r.left + 14f * u, r.top + 46f * u, bw - 28f * u, 11.5f, Ink.INK)
        if (s.next) gui.button(c, RectF(r.right - 120f * u, r.bottom - 36f * u, r.right - 12f * u, r.bottom - 10f * u), "Suivant ›", Btn.PRIMARY, size = 12f) { advance(app) }
        gui.button(c, RectF(r.left + 12f * u, r.bottom - 30f * u, r.left + 140f * u, r.bottom - 10f * u), "Passer l'apprentissage", Btn.GHOST, size = 9.5f) { finished = true }
    }

    private fun drawEnd(c: Canvas, app: GameView) {
        val gui = app.gui
        val u = gui.u
        gui.modal(c)
        val r = RectF(gui.w / 2 - 220f * u, gui.h / 2 - 110f * u, gui.w / 2 + 220f * u, gui.h / 2 + 110f * u)
        Ink.parchment(c, r, u)
        gui.text(c, "Apprentissage terminé !", r.centerX(), r.top + 36f * u, 19f, Ink.INK, Paint.Align.CENTER, Ink.hand)
        gui.wrap(c, "Tu peux garder ce domaine d'entraînement comme vraie partie, ou repartir de zéro avec ton propre domaine.", r.left + 22f * u, r.top + 64f * u, r.width() - 44f * u, 12f, Ink.INK)
        gui.button(c, RectF(r.left + 18f * u, r.bottom - 50f * u, r.centerX() - 6f * u, r.bottom - 14f * u), "Garder ce domaine", size = 12.5f) { app.endTutorial(keep = true) }
        gui.button(c, RectF(r.centerX() + 6f * u, r.bottom - 50f * u, r.right - 18f * u, r.bottom - 14f * u), "Nouvelle partie", Btn.PRIMARY, size = 12.5f) { app.endTutorial(keep = false) }
    }
}
