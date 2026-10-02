package com.cheval.game

import android.graphics.Canvas
import android.graphics.RectF

/** Guide du cavalier : l'essentiel des soins et de la vie du domaine. */
class HelpScreen(app: GameView) : Screen(app) {
    private val sections = listOf(
        "Votre journée" to "Vous avez racheté un domaine à l'abandon. Chaque journée de travail va de 7 h à 21 h : chaque action prend du temps (nourrir, panser, monter, donner un cours, nettoyer…). Quand il est tard, touchez « Nouvelle journée » pour dormir. Les Objectifs vous guident et rapportent de l'argent.",
        "Apprentissage" to "Depuis le menu principal, « Apprentissage » propose un parcours complet (toutes les bases pas à pas) ou rapide (l'essentiel en quelques étapes). Il se joue sur un domaine d'entraînement séparé : votre partie n'est jamais touchée.",
        "Le domaine" to "Faites glisser la carte (ou utilisez les flèches) pour la parcourir. Touchez un bâtiment pour le restaurer ou l'utiliser, un tas de déchets pour le débarrasser, un cheval pour ouvrir sa fiche. Le tableau d'affichage à l'entrée liste les concours, la boîte aux lettres les annonces de chevaux.",
        "Gagner sa vie" to "Donnez des cours d'équitation en touchant la carrière : choisissez des chevaux calmes et en confiance. Engagez vos chevaux en concours, élevez des poulains et vendez-les, proposez vos étalons à la saillie. Plus tard, le club-house attire pensionnaires et sponsors.",
        "Force et confiance" to "La Force vient du travail régulier (condition physique et musculature). La Confiance se gagne au pansage, au travail à pied, par les réussites… et se perd avec les refus et les frayeurs. Un cheval confiant saute mieux et rassure les élèves.",
        "Les disciplines" to "CSO, hunter (noté sur le style), dressage (transitions aux lettres, arrêt immobile, reculer, trot allongé et cadence au rythme des foulées), cross, course de galop (stalles, aspiration derrière un concurrent, cravache limitée), trot attelé au sulky (ne jamais galoper !), endurance (le cœur doit redescendre sous 64 au contrôle vétérinaire) et barrel race western.",
        "Nourrir un cheval" to "Un cheval doit manger du fourrage presque en continu : 1,5 à 2 % de son poids par jour en foin ou en herbe. Les granulés apportent de l'énergie aux chevaux au travail, mais de gros repas de céréales et les longs jeûnes favorisent coliques et ulcères. Surveillez la note d'état corporel (idéal : 5 sur 9) et ajustez la ration.",
        "L'eau et la litière" to "Sans abreuvoir automatique, il faut remplir le seau. Une litière sale abîme les pieds (abcès) et salit le cheval. Le palefrenier s'occupe des repas, de l'eau, des boxes et des sorties au pré.",
        "Le pré" to "Les chevaux sont des animaux sociaux : la vie au pré avec des congénères les rend heureux. Attention à l'herbe riche du printemps chez les chevaux gros (fourbure), à la boue (gale de boue) et aux coups de pied d'un cheval dominant.",
        "La santé" to "Vaccins (grippe, tétanos) chaque année, vermifuge raisonné, maréchal toutes les 6 à 8 semaines, dentiste une fois par an. Une colique est une urgence : appelez le vétérinaire immédiatement. Un soigneur appelle le vétérinaire pour vous en cas d'urgence.",
        "Le pansage" to "Étrille en caoutchouc d'abord, en petits cercles, pour décoller la boue et les poils morts : jamais sur la tête ni sur les canons, et tape-la quand elle est pleine. Puis brosse dure par coups secs dans le sens du poil (vers l'arrière et le bas), aussi sur les membres ; brosse douce pour la finition et la tête ; peigne pour les crins, en commençant par le bas. On cure les pieds chaque jour, du talon vers la pince : cailloux, pourriture de fourchette, fer qui bouge. Le pansage sert aussi à inspecter le cheval : plaies, gale de boue, tiques, membre chaud. Grattez-lui le garrot, il adore !",
        "Le travail" to "Deux séances par jour au plus. Variez : plat, obstacles, extérieur, et un jour de repos. Un cheval fatigué, jeune ou sur sol dur se blesse plus facilement. Les jeunes se débourrent vers 3 ans. Chaque cheval a un plafond qui dépend de sa génétique et de son âge.",
        "Monter" to "Choisissez l'allure avec les boutons du bas. Pour sauter, présentez-vous au galop et appuyez sur SAUTER dans la zone verte. En dressage, changez d'allure pile à la lettre demandée. En course, économisez le souffle et poussez dans la dernière ligne droite.",
        "Les concours" to "Engagez vos chevaux dans le calendrier (vaccins à jour). Montez vous-même en direct, ou confiez-les à un cavalier salarié. Il faut le Galop 4 pour l'Amateur et le Galop 7 pour le Pro : passez vos examens dans Domaine › Cavalier.",
        "L'élevage" to "Les juments ont des chaleurs tous les 21 jours de février à août. La gestation dure environ 11 mois et la plupart des poulains naissent la nuit : un box de poulinage sous caméra réduit les risques. Le poulain hérite d'un allèle de chaque parent pour chaque gène de robe, et de la moyenne de leurs qualités, plus le hasard. Évitez la consanguinité, et ne croisez jamais deux porteurs Frame overo : 25 % des poulains ne seraient pas viables. Le test ADN révèle les gènes cachés.",
        "Les robes" to "Alezan, bai et noir sont les couleurs de base (gènes Extension et Agouti). Le gène crème donne palomino, isabelle et crème ; le dun ajoute la raie de mulet ; le gris fait blanchir le cheval avec l'âge. Pie tobiano, overo, sabino, rouan, appaloosa, silver, champagne : tout est simulé selon la génétique réelle.",
        "L'argent" to "Salaires et entretien tombent chaque mois. Gagnez de l'argent en concours, en vendant vos élèves et vos poulains, avec les saillies de vos étalons, les cours d'équitation (club-house, moniteur et chevaux de club), les pensions et les sponsors attirés par votre réputation. Un domaine où les chevaux sont négligés perd sa réputation… et peut être contrôlé.",
    )

    override fun update(dt: Float) {}

    override fun draw(c: Canvas) {
        val u = gui.u; val w = gui.w; val h = gui.h
        paperBackground(c, gui)
        titleBar(c, gui, "Guide du cavalier", "Tout ce qu'il faut savoir pour prendre soin de vos chevaux") { app.pop() }
        val area = RectF(20f * u, 54f * u, w - 20f * u, h - 10f * u)
        val colW = (area.width() - 20f * u) / 2f
        // hauteur de chaque section
        val heights = sections.map { (_, txt) -> 22f * u + gui.wrap(null, txt, 0f, 0f, colW, 11f) + 10f * u }
        var leftH = 0f; var rightH = 0f
        val placement = sections.indices.map { i -> if (leftH <= rightH) { val y = leftH; leftH += heights[i]; 0 to y } else { val y = rightH; rightH += heights[i]; 1 to y } }
        gui.beginScroll(c, "help", area, maxOf(leftH, rightH) + 20f * u)
        for ((i, s) in sections.withIndex()) {
            val (col, y) = placement[i]
            val x = area.left + col * (colW + 20f * u)
            gui.text(c, s.first, x, area.top + y + 16f * u, 14f, Pal.GREEN, font = gui.serif)
            gui.wrap(c, s.second, x, area.top + y + 34f * u, colW, 11f)
        }
        gui.endScroll(c, "help")
    }
}
