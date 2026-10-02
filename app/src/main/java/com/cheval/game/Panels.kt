package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.cheval.core.BuildingType
import com.cheval.core.Cal
import com.cheval.core.Coat
import com.cheval.core.CompEvent
import com.cheval.core.Discipline
import com.cheval.core.Genome
import com.cheval.core.Horse
import com.cheval.core.Item
import com.cheval.core.Levels
import com.cheval.core.Locus
import com.cheval.core.MsgKind
import com.cheval.core.Res
import com.cheval.core.Rider
import com.cheval.core.Rng
import com.cheval.core.Role
import com.cheval.core.Sex
import com.cheval.core.fmt1
import com.cheval.core.fmtMoney

/** Base commune des écrans de gestion : fond papier, titre, liste défilante. */
abstract class PanelScreen(app: GameView, private val title: String) : Screen(app) {
    val game get() = app.game!!
    protected var t = 0f
    protected val idlePose = HorsePose()
    open val subtitle: String? get() = null
    override fun update(dt: Float) {
        t += dt
        app.tickGame(dt)
        idlePose.breathe = (idlePose.breathe + dt * 0.25f) % 1f
        idlePose.blink = if (t % 4.4f < 0.14f) 1f else 0f
    }
    fun res(r: Res) {
        if (r.ok) { gui.toast(r.msg, Pal.GREEN); app.sound.play(SoundFx.S.CLICK) } else { gui.toast(r.msg, Pal.LEATHER); app.sound.play(SoundFx.S.BAD, 0.5f) }
    }
    override fun draw(c: Canvas) {
        paperBackground(c, gui)
        titleBar(c, gui, title, subtitle) { app.pop() }
        content(c, RectF(12f * gui.u, 54f * gui.u, gui.w - 12f * gui.u, gui.h - 10f * gui.u))
    }
    abstract fun content(c: Canvas, r: RectF)

    /** Ligne de cheval avec portrait miniature ; retourne la hauteur. */
    fun horseRow(c: Canvas, h: Horse, x: Float, y: Float, wd: Float, info: String, info2: String, onClick: () -> Unit): Float {
        val u = gui.u
        val row = RectF(x, y, x + wd, y + 62f * u)
        gui.paper(c, row, 8f, Pal.PAPER)
        drawHorseFit(c, h, game.day, RectF(row.left + 4f * u, row.top + 2f * u, row.left + 92f * u, row.bottom - 2f * u), idlePose)
        gui.text(c, "${h.name} ${sexIcon(h)}", row.left + 98f * u, row.top + 20f * u, 13f, Pal.INK, font = gui.serif, maxW = wd - 110f * u)
        gui.text(c, info, row.left + 98f * u, row.top + 36f * u, 10f, Pal.INK_L, maxW = wd - 110f * u)
        gui.text(c, info2, row.left + 98f * u, row.top + 51f * u, 10f, if (h.urgent || h.injured) Pal.RED else Pal.INK, maxW = wd - 110f * u)
        gui.hit(row, onClick)
        return 68f * u
    }
}

// ============================================================================ chevaux
class HorsesScreen(app: GameView) : PanelScreen(app, "Mes chevaux") {
    private var sort = 0
    override val subtitle get() = "${game.owned().size} chevaux · ${game.boxUsers()}/${game.boxes()} boxes occupés${if (game.boarders > 0) " (dont ${game.boarders} en pension)" else ""}"
    override fun content(c: Canvas, r: RectF) {
        val u = gui.u
        val labels = listOf("Nom", "Âge", "Niveau", "État")
        for ((i, l) in labels.withIndex()) gui.button(c, RectF(r.left + i * 84f * u, r.top, r.left + i * 84f * u + 78f * u, r.top + 24f * u), "Tri : $l", if (sort == i) Btn.PRIMARY else Btn.NORMAL, size = 10f) { sort = i }
        gui.button(c, RectF(r.right - 200f * u, r.top, r.right, r.top + 24f * u), "Tout le monde au pré", Btn.GHOST, size = 10.5f) {
            game.owned().forEach { game.setPlace(it, com.cheval.core.Place.PRE) }; gui.toast("Les chevaux galopent vers la prairie.")
        }
        val list = game.owned().let { l ->
            when (sort) { 0 -> l.sortedBy { it.name }; 1 -> l.sortedBy { it.birthDay }; 2 -> l.sortedByDescending { it.skills.maxOrNull() ?: 0f }; else -> l.sortedBy { it.health + it.morale } }
        }
        val area = RectF(r.left, r.top + 30f * u, r.right, r.bottom)
        val cols = 2
        val cw = (area.width() - 10f * u) / cols
        gui.beginScroll(c, "horses", area, ((list.size + 1) / cols) * 68f * u + 20f * u)
        for ((i, h) in list.withIndex()) {
            val best = h.bestDiscipline()
            horseRow(c, h, area.left + (i % cols) * (cw + 10f * u), area.top + (i / cols) * 68f * u, cw,
                "${h.breed.label} · ${Cal.ageText(h.birthDay, game.day)} · ${h.coatName(game.day)}",
                "${h.summaryStatus(game.day)} · ${best.short} ${h.skill(best).toInt()}") { app.push(HorseScreen(app, h)) }
        }
        if (list.isEmpty()) gui.text(c, "Votre écurie est vide : rendez-vous au marché !", area.left, area.top + 20f * u, 13f)
        gui.endScroll(c, "horses")
    }
}

// ============================================================================ concours
class CompetitionScreen(app: GameView) : PanelScreen(app, "Concours") {
    private var tab = 0
    private var selected: CompEvent? = null
    private var filter: Discipline? = null
    override val subtitle get() = "Votre niveau : Galop ${game.rider.galop} · ${game.wins} victoire(s) au total"
    override fun content(c: Canvas, r: RectF) {
        val u = gui.u
        val tabs = listOf("Calendrier", "Mes engagements", "Résultats")
        for ((i, l) in tabs.withIndex()) gui.button(c, RectF(r.left + i * 130f * u, r.top, r.left + i * 130f * u + 124f * u, r.top + 26f * u), l, if (tab == i) Btn.PRIMARY else Btn.NORMAL, size = 11f) { tab = i; selected = null }
        val area = RectF(r.left, r.top + 34f * u, r.right, r.bottom)
        when (tab) {
            0 -> calendar(c, area)
            1 -> entries(c, area)
            else -> results(c, area)
        }
        selected?.let { entryDialog(c, it) }
    }

    private fun calendar(c: Canvas, area: RectF) {
        val u = gui.u
        val discs = listOf<Discipline?>(null) + Discipline.values()
        val fw = area.width() / discs.size
        for ((i, d) in discs.withIndex()) gui.button(c, RectF(area.left + i * fw, area.top, area.left + (i + 1) * fw - 4f * u, area.top + 22f * u), d?.short ?: "Tous", if (filter == d) Btn.GOLD else Btn.GHOST, size = 9.5f) { filter = d }
        val evs = game.events.filter { !it.done && it.day >= game.day && (filter == null || it.discipline == filter) }.sortedBy { it.day }
        val list = RectF(area.left, area.top + 28f * u, area.right, area.bottom)
        gui.beginScroll(c, "cal", list, evs.size * 44f * u + 10f * u)
        var y = list.top
        for (e in evs) {
            val row = RectF(list.left, y, list.right - 8f * u, y + 40f * u)
            gui.paper(c, row, 6f, if (e.entries.isNotEmpty()) Color.rgb(236, 244, 230) else Pal.PAPER, border = false)
            gui.text(c, "${Cal.weekday(e.day).take(3)}. ${Cal.formatShort(e.day)}", row.left + 8f * u, row.top + 17f * u, 11f, Pal.INK, font = gui.sansB)
            gui.text(c, e.discipline.short, row.left + 8f * u, row.top + 32f * u, 9.5f, Pal.INK_L)
            gui.text(c, e.name, row.left + 92f * u, row.top + 17f * u, 12f, Pal.INK, font = gui.serif, maxW = row.width() * 0.5f)
            gui.text(c, "${e.field} partants · engagement ${fmtMoney(e.fee)} · 1er prix ${fmtMoney(e.firstPrize)} · ${e.km} km${if (e.distance > 0) " · ${e.distance} m" else ""}", row.left + 92f * u, row.top + 32f * u, 9.5f, Pal.INK_L, maxW = row.width() * 0.6f)
            if (e.entries.isNotEmpty()) gui.text(c, e.entries.joinToString { game.horse(it.horseId)?.name ?: "?" }, row.right - 110f * u, row.top + 32f * u, 9.5f, Pal.OK, Paint.Align.RIGHT, maxW = 160f * u)
            gui.button(c, RectF(row.right - 100f * u, row.top + 7f * u, row.right - 8f * u, row.bottom - 7f * u), "Engager", Btn.PRIMARY, size = 11f) { selected = e }
            y += 44f * u
        }
        gui.endScroll(c, "cal")
    }

    private fun entries(c: Canvas, area: RectF) {
        val u = gui.u
        val es = game.ownEntries()
        var y = area.top
        if (es.isEmpty()) gui.text(c, "Aucun engagement. Choisissez une épreuve dans le calendrier.", area.left, y + 16f * u, 12f, Pal.INK_L)
        for ((e, en) in es) {
            val h = game.horse(en.horseId) ?: continue
            val rider = if (en.riderStaffId == -1) "vous${if (en.live) " (en direct)" else ""}" else game.staff.firstOrNull { it.id == en.riderStaffId }?.name ?: "?"
            gui.paper(c, RectF(area.left, y, area.right, y + 40f * u), 6f, border = false)
            gui.text(c, "${Cal.formatShort(e.day)} · ${e.name}", area.left + 10f * u, y + 17f * u, 12f, font = gui.serif, maxW = area.width() * 0.6f)
            gui.text(c, "${h.name} monté par $rider", area.left + 10f * u, y + 32f * u, 10f, Pal.INK_L)
            gui.button(c, RectF(area.right - 100f * u, y + 7f * u, area.right - 8f * u, y + 33f * u), "Retirer", Btn.DANGER, size = 10.5f) { game.withdraw(e, h); gui.toast("Engagement retiré (frais non remboursés).") }
            y += 46f * u
        }
    }

    private fun results(c: Canvas, area: RectF) {
        val u = gui.u
        val all = game.owned().flatMap { h -> h.results.map { h to it } }.sortedByDescending { it.second.day }
        gui.beginScroll(c, "res", area, all.size * 18f * u + 20f * u)
        var y = area.top + 14f * u
        for ((h, r) in all) {
            val col = if (r.rank == 1) Pal.GOLD else if (r.rank <= 3) Pal.OK else Pal.INK
            gui.text(c, "${Cal.formatShort(r.day)} · ${h.name} · ${r.eventName}", area.left, y, 10.5f, Pal.INK, maxW = area.width() * 0.6f)
            gui.text(c, "${if (r.rank == 1) "1er" else "${r.rank}e"}/${r.of} · ${r.score}${if (r.prize > 0) " · ${fmtMoney(r.prize)}" else ""}", area.right - 10f * u, y, 10.5f, col, Paint.Align.RIGHT, maxW = area.width() * 0.4f)
            y += 18f * u
        }
        if (all.isEmpty()) gui.text(c, "Pas encore de résultat.", area.left, y, 12f, Pal.INK_L)
        gui.endScroll(c, "res")
    }

    private var riderChoice = -1
    private var liveChoice = true

    private fun entryDialog(c: Canvas, e: CompEvent) {
        val u = gui.u; val w = gui.w; val h = gui.h
        gui.modal(c)
        val r = RectF(w / 2 - 300f * u, 40f * u, w / 2 + 300f * u, h - 20f * u)
        gui.paper(c, r)
        gui.text(c, e.name, r.left + 16f * u, r.top + 26f * u, 16f, font = gui.serif, maxW = r.width() - 40f * u)
        gui.text(c, "${Cal.format(e.day)} · ${e.discipline.label} · engagement ${fmtMoney(e.fee)} + transport", r.left + 16f * u, r.top + 44f * u, 10.5f, Pal.INK_L)
        // choix du cavalier
        val riders = listOf(-1) + game.staff.filter { it.role == Role.CAVALIER || it.role == Role.LAD }.map { it.id }
        var x = r.left + 16f * u
        for (id in riders) {
            val name = if (id == -1) "Moi (Galop ${game.rider.galop})" else game.staff.first { it.id == id }.let { "${it.name} (${it.role.label.lowercase()} ${"★".repeat(it.skill)})" }
            val bw = gui.textW(name, 10.5f) + 20f * u
            gui.button(c, RectF(x, r.top + 54f * u, x + bw, r.top + 78f * u), name, if (riderChoice == id) Btn.PRIMARY else Btn.NORMAL, size = 10.5f) { riderChoice = id }
            x += bw + 6f * u
        }
        val canLive = riderChoice == -1 && e.discipline in RideMode.LIVE
        if (canLive) gui.button(c, RectF(r.right - 200f * u, r.top + 54f * u, r.right - 16f * u, r.top + 78f * u), if (liveChoice) "Je monte en direct ✓" else "Résultat simulé", Btn.GHOST, size = 10.5f) { liveChoice = !liveChoice }
        // chevaux
        val list = RectF(r.left + 12f * u, r.top + 86f * u, r.right - 12f * u, r.bottom - 50f * u)
        val horses = game.owned().filter { it.backed || e.discipline == Discipline.MODELE }
        gui.beginScroll(c, "entry", list, horses.size * 40f * u)
        var y = list.top
        for (hz in horses) {
            val why = game.canEnter(e, hz, riderChoice)
            gui.text(c, hz.name, list.left + 6f * u, y + 16f * u, 12f, if (why == null) Pal.INK else Pal.INK_L, font = gui.sansB, maxW = 160f * u)
            gui.text(c, why ?: "${e.discipline.short} ${hz.skill(e.discipline).toInt()} (attendu ≈ ${Levels.EXPECT[e.level].toInt()}) · forme ${(hz.dayForm() * 100).toInt()} %", list.left + 6f * u, y + 31f * u, 9.5f, if (why == null) Pal.INK_L else Pal.RED, maxW = list.width() - 120f * u)
            gui.button(c, RectF(list.right - 100f * u, y + 6f * u, list.right - 6f * u, y + 32f * u), "Engager", Btn.PRIMARY, enabled = why == null, size = 10.5f) {
                res(game.enter(e, hz, riderChoice, canLive && liveChoice)); selected = null
            }
            y += 40f * u
        }
        gui.endScroll(c, "entry")
        gui.button(c, RectF(r.right - 140f * u, r.bottom - 42f * u, r.right - 16f * u, r.bottom - 12f * u), "Fermer") { selected = null }
        gui.wrap(c, "Galop requis : ${Levels.GALOP[e.level]}. Vaccins à jour obligatoires.", r.left + 16f * u, r.bottom - 26f * u, r.width() - 180f * u, 10f, Pal.INK_L)
    }

    override fun onBack(): Boolean { if (selected != null) { selected = null; return true }; return false }
}

// ============================================================================ élevage
class BreedingScreen(app: GameView) : PanelScreen(app, "Élevage") {
    private var mare: Horse? = null
    private var stallion: Horse? = null
    private var followUp = true
    private var prediction: List<Pair<String, Float>> = emptyList()
    private var predKey = ""
    override val subtitle get() = "Saison de monte : février à août · gestation ≈ 11 mois · affixe « ${game.affix} »"

    override fun content(c: Canvas, r: RectF) {
        val u = gui.u
        val colW = r.width() * 0.32f
        // juments
        gui.text(c, "Vos juments", r.left, r.top + 12f * u, 13f, font = gui.serif)
        val mares = game.owned().filter { it.mare && it.age(game.day) >= 2.5f }
        val ml = RectF(r.left, r.top + 20f * u, r.left + colW, r.bottom)
        gui.beginScroll(c, "mares", ml, mares.size * 50f * u)
        var y = ml.top
        for (m in mares) {
            val sel = mare == m
            val row = RectF(ml.left, y, ml.right - 6f * u, y + 46f * u)
            gui.paper(c, row, 6f, if (sel) Color.rgb(236, 244, 230) else Pal.PAPER, border = sel)
            gui.text(c, m.name, row.left + 8f * u, row.top + 17f * u, 12f, font = gui.sansB, maxW = row.width() - 16f * u)
            val st = when {
                m.pregnancy?.confirmed == true -> "Pleine · terme ${Cal.formatShort(m.pregnancy!!.dueDay)}"
                m.pregnancy != null || m.lastCovered > game.day - 16 -> "Saillie récente, écho à venir"
                m.inHeat(game.day) -> "EN CHALEUR"
                else -> m.nextHeat(game.day).let { if (it < 0) "Hors saison" else "Chaleurs dans $it j" }
            }
            gui.text(c, "${Cal.ageText(m.birthDay, game.day)} · $st", row.left + 8f * u, row.top + 33f * u, 9.5f, if (m.inHeat(game.day)) Pal.OK else Pal.INK_L, maxW = row.width() - 16f * u)
            gui.hit(row) { mare = m }
            y += 50f * u
        }
        if (mares.isEmpty()) gui.wrap(c, "Pas de jument en âge de reproduire. Achetez-en une au marché !", ml.left, ml.top + 16f * u, ml.width(), 11f, Pal.INK_L)
        gui.endScroll(c, "mares")
        // étalons
        val sx = r.left + colW + 10f * u
        gui.text(c, "Étalons disponibles", sx, r.top + 12f * u, 13f, font = gui.serif)
        val studs = game.availableStallions()
        val sl = RectF(sx, r.top + 20f * u, sx + colW, r.bottom)
        gui.beginScroll(c, "studs", sl, studs.size * 50f * u)
        y = sl.top
        for (s in studs) {
            val sel = stallion == s
            val row = RectF(sl.left, y, sl.right - 6f * u, y + 46f * u)
            gui.paper(c, row, 6f, if (sel) Color.rgb(236, 244, 230) else Pal.PAPER, border = sel)
            gui.text(c, s.name, row.left + 8f * u, row.top + 17f * u, 12f, font = gui.sansB, maxW = row.width() * 0.62f)
            gui.text(c, if (s.owned) "à vous" else fmtMoney(s.studFee), row.right - 8f * u, row.top + 17f * u, 11f, Pal.LEATHER, Paint.Align.RIGHT)
            gui.text(c, "${s.breed.label} · ${s.coatName(game.day)} · ${s.bestDiscipline().short} ${s.skill(s.bestDiscipline()).toInt()}", row.left + 8f * u, row.top + 33f * u, 9.5f, Pal.INK_L, maxW = row.width() - 16f * u)
            gui.hit(row) { stallion = s }
            y += 50f * u
        }
        gui.endScroll(c, "studs")
        // projet de croisement
        val px = sx + colW + 10f * u
        val pr = RectF(px, r.top, r.right, r.bottom)
        gui.paper(c, pr)
        val m = mare; val s = stallion
        var yy = pr.top + 22f * u
        gui.text(c, "Projet de poulain", pr.left + 12f * u, yy, 14f, font = gui.serif); yy += 18f * u
        if (m == null || s == null) { gui.wrap(c, "Choisissez une jument et un étalon pour voir le pronostic : robe, consanguinité, potentiel et risques génétiques.", pr.left + 12f * u, yy, pr.width() - 24f * u, 11f, Pal.INK_L); return }
        val coi = game.inbreedingOf(s.id, m.id)
        gui.text(c, "${m.name} × ${s.name}", pr.left + 12f * u, yy, 11.5f, Pal.INK, font = gui.sansB, maxW = pr.width() - 24f * u); yy += 15f * u
        gui.text(c, "Race du produit : ${com.cheval.core.Breed.offspring(s.breed, m.breed).label}", pr.left + 12f * u, yy, 10.5f, maxW = pr.width() - 24f * u); yy += 14f * u
        gui.text(c, "Consanguinité : ${fmt1(coi * 100)} %${if (coi > 0.0625f) " (déconseillé)" else ""}", pr.left + 12f * u, yy, 10.5f, if (coi > 0.0625f) Pal.RED else Pal.INK); yy += 14f * u
        val pot = (m.bestDiscipline().potential(m) + s.bestDiscipline().potential(s)) / 2f
        gui.text(c, "Potentiel attendu", pr.left + 12f * u, yy, 10.5f); gui.stars(c, pr.left + 120f * u, yy - 4f * u, ((pot - 30f) / 10f).coerceIn(0f, 5f), 8f); yy += 16f * u
        val known = m.dnaTested && (s.dnaTested || !s.owned)
        if (known && m.genome.has(Locus.FRAME) && s.genome.has(Locus.FRAME)) { yy += gui.wrap(c, "⚠ Deux porteurs Frame overo : 25 % de risque de poulain blanc létal !", pr.left + 12f * u, yy, pr.width() - 24f * u, 10.5f, Pal.RED) }
        else if (!m.dnaTested && (m.breed.freq(Locus.FRAME) > 0f || s.breed.freq(Locus.FRAME) > 0f)) { yy += gui.wrap(c, "Race à risque Frame overo : faites tester l'ADN de la jument.", pr.left + 12f * u, yy, pr.width() - 24f * u, 10.5f, Pal.ORANGE) }
        // pronostic de robe (Monte-Carlo sur les génotypes)
        val key = "${m.id}-${s.id}"
        if (key != predKey) {
            predKey = key
            val rr = Rng(key.hashCode().toLong())
            val counts = HashMap<String, Int>()
            repeat(500) { val gg = Genome.cross(s.genome, m.genome, 0f, 0f, rr); val n = if (gg.lethal) "Non viable (O/O)" else Coat.name(gg, 6f); counts[n] = (counts[n] ?: 0) + 1 }
            prediction = counts.entries.sortedByDescending { it.value }.filter { it.value >= 5 }.take(6).map { it.key to it.value / 5f }
        }
        gui.text(c, if (known) "Robes probables (génotypes connus)" else "Robes probables (estimation)", pr.left + 12f * u, yy + 4f * u, 10.5f, Pal.INK_L); yy += 16f * u
        for ((n, pct) in prediction) { gui.text(c, "${pct.toInt()} %  $n", pr.left + 18f * u, yy, 10f, Pal.INK, maxW = pr.width() - 30f * u); yy += 13f * u }
        yy += 4f * u
        val cost = game.coverCost(s) + if (followUp) 180 else 0
        gui.button(c, RectF(pr.left + 12f * u, yy, pr.right - 12f * u, yy + 24f * u), if (followUp) "Suivi gynécologique : oui (+180 €)" else "Suivi gynécologique : non", Btn.GHOST, size = 10f) { followUp = !followUp }
        yy += 30f * u
        gui.button(c, RectF(pr.left + 12f * u, yy, pr.right - 12f * u, yy + 34f * u), "Saillir (${fmtMoney(cost)})", Btn.GOLD, size = 13f) { res(game.cover(m, s, followUp)) }
    }
}

// ============================================================================ marché
class MarketScreen(app: GameView) : PanelScreen(app, "Marché aux chevaux") {
    private var detail: Horse? = null
    override val subtitle get() = "${game.market.size} chevaux à vendre · ${fmtMoney(game.money)} disponibles"
    override fun content(c: Canvas, r: RectF) {
        val u = gui.u
        // nos offres reçues
        var top = r.top
        if (game.offers.isNotEmpty()) {
            for (o in game.offers.toList()) {
                val hz = game.horse(o.horseId) ?: continue
                gui.paper(c, RectF(r.left, top, r.right, top + 32f * u), 6f, Color.rgb(236, 244, 230))
                gui.text(c, "${o.buyer} propose ${fmtMoney(o.amount)} pour ${hz.name} (demandé ${fmtMoney(hz.askingPrice)})", r.left + 10f * u, top + 21f * u, 11f, maxW = r.width() - 220f * u)
                gui.button(c, RectF(r.right - 200f * u, top + 4f * u, r.right - 104f * u, top + 28f * u), "Accepter", Btn.PRIMARY, size = 10.5f) { res(game.acceptOffer(o)) }
                gui.button(c, RectF(r.right - 98f * u, top + 4f * u, r.right - 6f * u, top + 28f * u), "Refuser", size = 10.5f) { game.declineOffer(o) }
                top += 38f * u
            }
        }
        val list = game.market.toList()
        val area = RectF(r.left, top, r.right, r.bottom)
        val cols = 2
        val cw = (area.width() - 10f * u) / cols
        gui.beginScroll(c, "market", area, ((list.size + 1) / cols) * 68f * u + 10f * u)
        for ((i, l) in list.withIndex()) {
            val hz = game.horse(l.horseId) ?: continue
            val best = hz.bestDiscipline()
            horseRow(c, hz, area.left + (i % cols) * (cw + 10f * u), area.top + (i / cols) * 68f * u, cw,
                "${hz.breed.label} · ${sexIcon(hz)} · ${Cal.ageText(hz.birthDay, game.day)} · ${hz.coatName(game.day)} · ${hz.heightCm} cm",
                "${fmtMoney(l.price)} · ${best.short} ${hz.skill(best).toInt()}${if (hz.pregnancy != null) " · pleine" else ""}") { detail = hz }
        }
        gui.endScroll(c, "market")
        detail?.let { drawDetail(c, it) }
    }

    private fun drawDetail(c: Canvas, hz: Horse) {
        val u = gui.u; val w = gui.w; val h = gui.h
        val l = game.market.firstOrNull { it.horseId == hz.id } ?: run { detail = null; return }
        gui.modal(c)
        val r = RectF(w / 2 - 320f * u, 30f * u, w / 2 + 320f * u, h - 20f * u)
        gui.paper(c, r)
        drawHorseFit(c, hz, game.day, RectF(r.left + 8f * u, r.top + 8f * u, r.left + r.width() * 0.48f, r.bottom - 60f * u), idlePose)
        val x = r.left + r.width() * 0.5f
        var y = r.top + 28f * u
        gui.text(c, hz.name, x, y, 17f, font = gui.serif, maxW = r.width() * 0.48f); y += 18f * u
        val lines = listOf(
            "${hz.breed.label} · ${sexIcon(hz)} ${hz.sex.label} · ${Cal.ageText(hz.birthDay, game.day)}",
            "${hz.coatName(game.day)} · ${hz.heightCm} cm",
            "Vendu par ${l.seller}",
            "Caractère : ${hz.personality.joinToString { it.label.lowercase() }}",
            if (hz.backed) "Débourré, ${if (hz.shod) "ferré" else "pieds nus"}" else "Non débourré",
            "Père : ${game.horse(hz.sireId)?.name ?: "?"} · Mère : ${game.horse(hz.damId)?.name ?: "?"}",
        )
        for (s in lines) { gui.text(c, s, x, y, 10.5f, Pal.INK_L, maxW = r.width() * 0.47f); y += 14f * u }
        y += 4f * u
        gui.text(c, "Potentiel", x, y, 11f); gui.stars(c, x + 70f * u, y - 4f * u, potentialStars(hz)); y += 16f * u
        for (d in Discipline.values().filter { it.trainable }) {
            gui.gauge(c, x, y + 8f * u, r.width() * 0.44f, d.label, hz.skill(d), Pal.BLUE); y += 21f * u
        }
        gui.text(c, "Visite d'achat vétérinaire : ${if (hz.ailments.isEmpty()) "favorable" else hz.ailments.joinToString { it.type.label }}", x, y + 6f * u, 10f, if (hz.ailments.isEmpty()) Pal.OK else Pal.RED, maxW = r.width() * 0.47f)
        gui.text(c, fmtMoney(l.price), r.left + 20f * u, r.bottom - 22f * u, 20f, Pal.LEATHER, font = gui.serif)
        gui.button(c, RectF(r.right - 290f * u, r.bottom - 50f * u, r.right - 160f * u, r.bottom - 14f * u), "Retour") { detail = null }
        gui.button(c, RectF(r.right - 150f * u, r.bottom - 50f * u, r.right - 16f * u, r.bottom - 14f * u), "Acheter", Btn.GOLD, enabled = game.money >= l.price, size = 14f) {
            val rr = game.buy(l); res(rr); if (rr.ok) { detail = null; app.sound.play(SoundFx.S.NEIGH, 0.6f) }
        }
    }

    override fun onBack(): Boolean { if (detail != null) { detail = null; return true }; return false }
}

// ============================================================================ domaine : bâtiments, équipe, stocks, finances
class ManageScreen(app: GameView, private var tab: Int = 0) : PanelScreen(app, "Le domaine") {
    override val subtitle get() = "${game.stableName} · réputation ${game.reputation.toInt()}/100 · ${fmtMoney(game.money)}"
    override fun content(c: Canvas, r: RectF) {
        val u = gui.u
        val tabs = listOf("Bâtiments", "Équipe", "Stocks", "Finances", "Cavalier")
        for ((i, l) in tabs.withIndex()) gui.button(c, RectF(r.left + i * 112f * u, r.top, r.left + i * 112f * u + 106f * u, r.top + 26f * u), l, if (tab == i) Btn.PRIMARY else Btn.NORMAL, size = 11f) { tab = i }
        val area = RectF(r.left, r.top + 34f * u, r.right, r.bottom)
        when (tab) { 0 -> buildings(c, area); 1 -> staff(c, area); 2 -> stocks(c, area); 3 -> finances(c, area); else -> riderTab(c, area) }
    }

    private fun buildings(c: Canvas, area: RectF) {
        val u = gui.u
        val list = BuildingType.values().toList()
        val cols = 2; val cw = (area.width() - 10f * u) / cols
        gui.beginScroll(c, "bld", area, ((list.size + 1) / 2) * 76f * u)
        for ((i, b) in list.withIndex()) {
            val x = area.left + (i % cols) * (cw + 10f * u); val y = area.top + (i / cols) * 76f * u
            val lvl = game.level(b)
            val row = RectF(x, y, x + cw, y + 70f * u)
            gui.paper(c, row, 8f, if (lvl > 0) Color.rgb(238, 244, 232) else Pal.PAPER)
            gui.text(c, b.label + if (b.maxLevel > 1) " — niveau $lvl/${b.maxLevel}" else if (lvl > 0) " ✓" else "", row.left + 10f * u, row.top + 18f * u, 12.5f, font = gui.serif, maxW = cw - 130f * u)
            gui.wrap(c, b.desc, row.left + 10f * u, row.top + 34f * u, cw - 140f * u, 9.5f, Pal.INK_L)
            if (lvl < b.maxLevel) gui.button(c, RectF(row.right - 122f * u, row.top + 16f * u, row.right - 8f * u, row.bottom - 16f * u), if (lvl == 0) "Construire" else "Agrandir", Btn.PRIMARY, enabled = game.money >= b.cost(lvl + 1), size = 11f, sub = fmtMoney(b.cost(lvl + 1))) { res(game.build(b)) }
        }
        gui.endScroll(c, "bld")
    }

    private fun staff(c: Canvas, area: RectF) {
        val u = gui.u
        val half = area.width() / 2f - 6f * u
        gui.text(c, "Votre équipe (${game.staff.size}/${3 + game.level(BuildingType.ECURIE) * 2})", area.left, area.top + 12f * u, 13f, font = gui.serif)
        var y = area.top + 20f * u
        for (s in game.staff.toList()) {
            gui.paper(c, RectF(area.left, y, area.left + half, y + 44f * u), 6f, border = false)
            gui.text(c, "${s.name} — ${s.role.label} ${"★".repeat(s.skill)}${s.discipline?.let { " (${it.short})" } ?: ""}", area.left + 8f * u, y + 17f * u, 11f, font = gui.sansB, maxW = half - 100f * u)
            gui.text(c, "${fmtMoney(s.salary)}/mois · ${s.role.desc}", area.left + 8f * u, y + 33f * u, 9f, Pal.INK_L, maxW = half - 100f * u)
            gui.button(c, RectF(area.left + half - 86f * u, y + 9f * u, area.left + half - 6f * u, y + 35f * u), "Licencier", Btn.DANGER, size = 10f) { res(game.fire(s)) }
            y += 50f * u
        }
        val x2 = area.left + half + 12f * u
        gui.text(c, "Candidats (renouvelés chaque semaine)", x2, area.top + 12f * u, 13f, font = gui.serif)
        y = area.top + 20f * u
        for (s in game.candidates.toList()) {
            gui.paper(c, RectF(x2, y, area.right, y + 44f * u), 6f, border = false)
            gui.text(c, "${s.name} — ${s.role.label} ${"★".repeat(s.skill)}${s.discipline?.let { " (${it.short})" } ?: ""}", x2 + 8f * u, y + 17f * u, 11f, font = gui.sansB, maxW = half - 100f * u)
            gui.text(c, "${fmtMoney(s.salary)}/mois · ${s.role.desc}", x2 + 8f * u, y + 33f * u, 9f, Pal.INK_L, maxW = half - 100f * u)
            gui.button(c, RectF(area.right - 86f * u, y + 9f * u, area.right - 6f * u, y + 35f * u), "Embaucher", Btn.PRIMARY, size = 10f) { res(game.hire(s)) }
            y += 50f * u
        }
    }

    private fun stocks(c: Canvas, area: RectF) {
        val u = gui.u
        var y = area.top
        val used = game.count(Item.FOIN) + game.count(Item.PAILLE) * 15f
        gui.text(c, "Grange : ${used.toInt()} / ${game.stockCap().toInt()} (équivalent kg)", area.left, y + 14f * u, 12f, Pal.INK_L); y += 26f * u
        for (it in Item.values()) {
            val row = RectF(area.left, y, area.right, y + 52f * u)
            gui.paper(c, row, 6f, border = false)
            val days = game.daysOf(it)
            gui.text(c, "${it.label} : ${game.count(it).toInt()} ${it.unit}", row.left + 10f * u, row.top + 19f * u, 13f, font = gui.sansB)
            gui.text(c, "${it.desc}${if (days < 999) " · autonomie ≈ $days j" else ""}", row.left + 10f * u, row.top + 36f * u, 9.5f, if (days < 5) Pal.RED else Pal.INK_L, maxW = row.width() - 260f * u)
            val price = game.itemPrice(it)
            gui.button(c, RectF(row.right - 250f * u, row.top + 9f * u, row.right - 130f * u, row.bottom - 9f * u), "+${it.pack} ${it.unit}", size = 10.5f, sub = fmtMoney((price * it.pack).toInt())) { res(game.buyStock(it, 1)) }
            gui.button(c, RectF(row.right - 124f * u, row.top + 9f * u, row.right - 8f * u, row.bottom - 9f * u), "+${it.pack * 5} ${it.unit}", Btn.PRIMARY, size = 10.5f, sub = fmtMoney((price * it.pack * 5).toInt())) { res(game.buyStock(it, 5)) }
            y += 58f * u
        }
        gui.wrap(c, "Le foin est moins cher après les fenaisons (juin-août) et plus cher en hiver. Pensez à remplir la grange à l'avance !", area.left, y + 14f * u, area.width(), 10.5f, Pal.INK_L)
    }

    private fun finances(c: Canvas, area: RectF) {
        val u = gui.u
        val month = Cal.date(game.day).month
        val thisMonth = game.ledger.filter { Cal.date(it.day).month == month && game.day - it.day < 40 }
        val byCat = thisMonth.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amount } }
        gui.text(c, "Ce mois-ci", area.left, area.top + 14f * u, 13f, font = gui.serif)
        var y = area.top + 32f * u
        for ((cat, sum) in byCat.entries.sortedBy { it.value }) { gui.text(c, cat, area.left, y, 11f); gui.text(c, fmtMoney(sum), area.left + 260f * u, y, 11f, moneyColor(sum), Paint.Align.RIGHT, gui.sansB); y += 16f * u }
        val total = byCat.values.sum()
        drawDivider(c, gui, area.left, area.left + 260f * u, y - 8f * u)
        gui.text(c, "Solde du mois", area.left, y + 6f * u, 11.5f, font = gui.sansB); gui.text(c, fmtMoney(total), area.left + 260f * u, y + 6f * u, 11.5f, moneyColor(total), Paint.Align.RIGHT, gui.sansB)
        y += 26f * u
        val fixed = game.staff.sumOf { it.salary } + BuildingType.values().sumOf { b -> if (game.level(b) > 0) b.upkeep * game.level(b) * 3 else 0 }
        gui.wrap(c, "Charges fixes mensuelles ≈ ${fmtMoney(fixed)} (salaires et entretien). Revenus possibles : concours, ventes, saillies, cours (club-house + moniteur + chevaux de club), pensions (club-house), sponsors (réputation ≥ 35).", area.left, y, 270f * u, 10f, Pal.INK_L)
        // grand livre
        val lx = area.left + 300f * u
        gui.text(c, "Dernières opérations", lx, area.top + 14f * u, 13f, font = gui.serif)
        val list = RectF(lx, area.top + 22f * u, area.right, area.bottom)
        val tx = game.ledger.asReversed()
        gui.beginScroll(c, "ledger", list, tx.size * 16f * u + 10f * u)
        var yy = list.top + 12f * u
        for (x in tx) {
            gui.text(c, "${Cal.formatShort(x.day)} · ${x.label}", lx, yy, 9.5f, Pal.INK, maxW = list.width() - 90f * u)
            gui.text(c, fmtMoney(x.amount), list.right - 8f * u, yy, 9.5f, moneyColor(x.amount), Paint.Align.RIGHT)
            yy += 16f * u
        }
        gui.endScroll(c, "ledger")
    }

    private fun riderTab(c: Canvas, area: RectF) {
        val u = gui.u
        val rd = game.rider
        var y = area.top + 16f * u
        gui.text(c, "${rd.name} — Galop ${rd.galop}", area.left, y, 16f, font = gui.serif); y += 20f * u
        gui.text(c, "${fmt1(rd.hoursRidden)} h d'équitation", area.left, y, 11f, Pal.INK_L); y += 18f * u
        for (d in Discipline.values().filter { it.trainable }) { gui.gauge(c, area.left, y + 8f * u, 300f * u, d.label, rd.skill(d), Pal.BLUE); y += 22f * u }
        y += 10f * u
        if (rd.galop < 7) {
            gui.wrap(c, "Les Galops fédéraux attestent de votre niveau : il faut le Galop 4 pour l'Amateur, le Galop 7 pour le Pro, et une licence (Galop 5) pour monter en course. Prochain examen : ${Rider.GALOP_HOURS[rd.galop].toInt()} h d'équitation requises.", area.left, y, 420f * u, 10.5f, Pal.INK_L); y += 44f * u
            gui.button(c, RectF(area.left, y, area.left + 240f * u, y + 32f * u), "Passer le Galop ${rd.galop + 1} (45 €)", Btn.GOLD, enabled = rd.examReady(), size = 12f) { res(game.galopExam()) }
        } else gui.text(c, "Vous avez le niveau maximal : tous les concours vous sont ouverts.", area.left, y + 10f * u, 11f, Pal.OK)
        val x2 = area.left + 460f * u
        gui.text(c, "Partie", x2, area.top + 16f * u, 14f, font = gui.serif)
        gui.button(c, RectF(x2, area.top + 30f * u, x2 + 200f * u, area.top + 60f * u), "Sauvegarder et quitter", size = 11f) { app.save(); app.replaceAll(MenuScreen(app)) }
        gui.button(c, RectF(x2, area.top + 68f * u, x2 + 200f * u, area.top + 98f * u), if (app.sound.enabled) "Son : activé" else "Son : coupé", Btn.GHOST, size = 11f) { app.toggleSound() }
        gui.button(c, RectF(x2, area.top + 106f * u, x2 + 200f * u, area.top + 136f * u), "Guide du cavalier", Btn.GHOST, size = 11f) { app.push(HelpScreen(app)) }
        gui.button(c, RectF(x2, area.top + 144f * u, x2 + 200f * u, area.top + 174f * u), "Renommer le domaine", Btn.GHOST, size = 11f) { app.askText("Nom du domaine", game.stableName) { game.stableName = it } }
    }
}

// ============================================================================ journal
class JournalScreen(app: GameView) : PanelScreen(app, "Journal du domaine") {
    override fun onShow() { app.game?.unread = 0 }
    override fun content(c: Canvas, r: RectF) {
        val u = gui.u
        val msgs = game.messages
        gui.beginScroll(c, "journal", r, msgs.sumOf { (gui.wrap(null, it.text, 0f, 0f, r.width() - 130f * u, 11f) + 10f * u).toDouble() }.toFloat() + 20f * u)
        var y = r.top + 14f * u
        for (m in msgs) {
            val col = when (m.kind) { MsgKind.URGENT -> Pal.RED; MsgKind.BAD -> Pal.LEATHER; MsgKind.GOOD -> Pal.OK; else -> Pal.INK }
            gui.text(c, "${Cal.formatShort(m.day)} ${m.hour}h", r.left, y, 10f, Pal.INK_L)
            val hh = gui.wrap(c, m.text, r.left + 110f * u, y, r.width() - 130f * u, 11f, col)
            if (m.horseId >= 0) game.horse(m.horseId)?.let { hz -> gui.hit(RectF(r.left, y - 12f * u, r.right, y + hh - 10f * u)) { app.push(HorseScreen(app, hz)) } }
            y += hh + 10f * u
        }
        if (msgs.isEmpty()) gui.text(c, "Rien à signaler.", r.left, y, 12f, Pal.INK_L)
        gui.endScroll(c, "journal")
    }
}
