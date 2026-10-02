package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.cheval.core.AilmentType
import com.cheval.core.Cal
import com.cheval.core.Coat
import com.cheval.core.Discipline
import com.cheval.core.Exercise
import com.cheval.core.Horse
import com.cheval.core.Locus
import com.cheval.core.Place
import com.cheval.core.Ration
import com.cheval.core.Res
import com.cheval.core.Sex
import com.cheval.core.Trait
import com.cheval.core.Turnout
import com.cheval.core.fmt1
import com.cheval.core.fmtMoney
import kotlin.math.sin

/** Fiche d'un cheval : soins, travail, aptitudes, génétique, origines, carrière et vente. */
class HorseScreen(app: GameView, private val horse: Horse) : Screen(app) {
    private val game get() = app.game!!
    private var tab = 0
    private var t = 0f
    private val pose = HorsePose()
    private var snort = 0f
    private val tabs = listOf("Soins", "Travail", "Aptitudes", "Génétique", "Origines", "Carrière")
    private var confirm: Pair<String, () -> Unit>? = null

    override fun update(dt: Float) {
        t += dt
        app.tickGame(dt)
        pose.breathe = (pose.breathe + dt * 0.25f) % 1f
        pose.blink = if (t % 4.6f < 0.14f) 1f else 0f
        pose.tailSwing = sin(t * 1.6f) * 0.7f
        val mood = horse.morale
        pose.ears = if (snort > 0f) 1f else if (mood < 30) -0.8f else sin(t * 0.7f) * 0.5f + 0.2f
        pose.neck += ((if (horse.pain > 0.5f) 25f else if (mood > 60) 50f else 38f) - pose.neck) * dt * 2f
        pose.head += ((if (snort > 0f) 55f else 38f) - pose.head) * dt * 3f
        snort -= dt
        if (!horse.alive || !horse.owned) { if (!horse.owned && !horse.alive) {} }
    }

    private fun res(r: Res) {
        Looks.invalidate(horse.id)
        if (r.ok) { gui.toast(r.msg, Pal.GREEN); app.sound.play(SoundFx.S.CLICK) } else { gui.toast(r.msg, Pal.LEATHER); app.sound.play(SoundFx.S.BAD, 0.5f) }
    }

    override fun draw(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        paperBackground(c, gui)
        val sub = "${horse.breed.label} · ${sexIcon(horse)} ${horse.sex.label} · ${Cal.ageText(horse.birthDay, g.day)} · ${horse.coatName(g.day)} · ${(horse.heightAt(g.day)).toInt()} cm"
        titleBar(c, gui, horse.name, sub) { app.pop() }
        if (horse.owned && horse.alive) gui.button(c, RectF(w - 120f * u, 9f * u, w - 12f * u, 35f * u), "Renommer", Btn.GHOST, size = 11f) {
            app.askText("Nouveau nom", horse.name) { horse.name = it }
        }

        // ------------- portrait vivant
        val pic = RectF(10f * u, 54f * u, w * 0.42f, h - 12f * u)
        drawStage(c, pic)
        val picH = RectF(pic.left + 6f * u, pic.top + 30f * u, pic.right - 6f * u, pic.bottom - 64f * u)
        drawHorseFit(c, horse, g.day, picH, pose, light = 1f)
        gui.hit(picH) {
            snort = 1.2f
            res(g.caress(horse))
            app.sound.play(SoundFx.S.SNORT, 0.7f)
        }
        // résumé sous le portrait
        val st = horse.summaryStatus(g.day)
        gui.paper(c, RectF(pic.left + 8f * u, pic.bottom - 58f * u, pic.right - 8f * u, pic.bottom - 8f * u), 8f)
        gui.text(c, st, pic.left + 18f * u, pic.bottom - 38f * u, 13f, if (horse.urgent || horse.injured) Pal.RED else Pal.INK, font = gui.serif, maxW = pic.width() - 36f * u)
        gui.text(c, "${horse.place.label} · ${horse.personality.joinToString(" · ") { it.label }} · valeur ≈ ${fmtMoney(g.value(horse))}", pic.left + 18f * u, pic.bottom - 20f * u, 10f, Pal.INK_L, maxW = pic.width() - 36f * u)
        gui.text(c, "Touchez-le pour le caresser", pic.centerX(), pic.top + 18f * u, 9.5f, Pal.CREAM, Paint.Align.CENTER, shadow = true)

        // ------------- onglets
        val right = RectF(w * 0.42f + 10f * u, 54f * u, w - 10f * u, h - 10f * u)
        val tw = right.width() / tabs.size
        for ((i, name) in tabs.withIndex()) {
            val r = RectF(right.left + i * tw, right.top, right.left + (i + 1) * tw - 3f * u, right.top + 28f * u)
            gui.button(c, r, name, if (i == tab) Btn.PRIMARY else Btn.NORMAL, size = 11.5f) { tab = i; gui.resetScroll("horse") ; app.sound.play(SoundFx.S.PAGE, 0.5f) }
        }
        val body = RectF(right.left, right.top + 34f * u, right.right, right.bottom)
        gui.paper(c, body)
        val inner = RectF(body.left + 12f * u, body.top + 8f * u, body.right - 10f * u, body.bottom - 6f * u)
        val contentH = when (tab) { 0 -> 700f; 1 -> 640f; 2 -> 560f; 3 -> 560f; 4 -> 420f; else -> 520f + horse.results.size * 15f } * u
        val off = gui.beginScroll(c, "horse", inner, contentH)
        val y0 = inner.top + 14f * u
        when (tab) {
            0 -> careTab(c, inner, y0, off)
            1 -> workTab(c, inner, y0, off)
            2 -> skillsTab(c, inner, y0)
            3 -> geneticsTab(c, inner, y0, off)
            4 -> pedigreeTab(c, inner, y0, off)
            else -> careerTab(c, inner, y0, off)
        }
        gui.endScroll(c, "horse")

        confirm?.let { (msg, act) ->
            gui.modal(c)
            val r = RectF(w / 2 - 180f * u, h / 2 - 70f * u, w / 2 + 180f * u, h / 2 + 70f * u)
            gui.paper(c, r)
            gui.wrap(c, msg, r.left + 18f * u, r.top + 30f * u, r.width() - 36f * u, 13f)
            gui.button(c, RectF(r.left + 18f * u, r.bottom - 46f * u, r.centerX() - 6f * u, r.bottom - 14f * u), "Annuler") { confirm = null }
            gui.button(c, RectF(r.centerX() + 6f * u, r.bottom - 46f * u, r.right - 18f * u, r.bottom - 14f * u), "Confirmer", Btn.DANGER) { confirm = null; act() }
        }
    }

    /** Décor du portrait : allée d'écurie ou prairie. */
    private fun drawStage(c: Canvas, r: RectF) {
        val g = game
        val amb = ambienceOf(g)
        c.save()
        val u = gui.u
        gui.p.shader = null
        c.clipRect(r)
        if (horse.place == Place.PRE) {
            Scenery.sky(c, r.width() + r.left, r.top + r.height() * 0.55f, amb, t)
            Scenery.hills(c, r.right, r.top + r.height() * 0.55f, 30f * u, 0f, 5, Scenery.lit(Color.rgb(90, 120, 90), amb))
            Scenery.grass(c, r.left, r.right, r.top + r.height() * 0.55f, r.bottom, amb, 0f)
            Scenery.fence(c, r.left, r.right, r.top + r.height() * 0.6f, 24f * u, amb, color = Color.rgb(120, 88, 60))
        } else {
            // allée d'écurie : bardage de bois, porte de box, sol pavé
            gui.p.color = -1; gui.p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(122, 86, 58), Color.rgb(74, 52, 36), Shader.TileMode.CLAMP)
            c.drawRect(r, gui.p); gui.p.shader = null
            gui.p.color = Color.argb(40, 0, 0, 0)
            var yy = r.top; while (yy < r.bottom) { c.drawRect(r.left, yy, r.right, yy + 1.5f * u, gui.p); yy += 16f * u }
            gui.p.color = Color.rgb(56, 86, 64); c.drawRect(r.right - r.width() * 0.32f, r.top + r.height() * 0.15f, r.right - r.width() * 0.04f, r.top + r.height() * 0.72f, gui.p)
            gui.p.color = Color.rgb(160, 150, 136); c.drawRect(r.left, r.top + r.height() * 0.72f, r.right, r.bottom, gui.p)
            gui.p.color = Color.argb(50, 0, 0, 0)
            var xx = r.left; while (xx < r.right) { c.drawRect(xx, r.top + r.height() * 0.72f, xx + 1.5f * u, r.bottom, gui.p); xx += 22f * u }
            // lampe
            gui.p.color = -1; gui.p.shader = android.graphics.RadialGradient(r.centerX(), r.top + 10f * u, r.width() * 0.6f, Color.argb(70, 255, 220, 150), Color.argb(0, 255, 220, 150), Shader.TileMode.CLAMP)
            c.drawRect(r, gui.p); gui.p.shader = null
        }
        c.restore()
    }

    // ===================================================================== onglets
    private fun careTab(c: Canvas, r: RectF, y0: Float, off: Float) {
        val g = game
        val u = gui.u
        var y = y0
        if (!horse.alive) { gui.wrap(c, "${horse.name} nous a quittés le ${Cal.format(horse.deathDay)} (${horse.deathCause.lowercase()}).", r.left, y, r.width(), 13f); return }
        if (!horse.owned) { gui.text(c, "Ce cheval ne vous appartient pas.", r.left, y, 13f); return }
        val colW = (r.width() - 14f * u) / 2f
        val x1 = r.left; val x2 = r.left + colW + 14f * u
        gui.text(c, "Besoins", x1, y, 13f, font = gui.serif); gui.text(c, "Santé et forme", x2, y, 13f, font = gui.serif); y += 14f * u
        val left = listOf(
            "Satiété" to horse.satiety, "Hydratation" to horse.hydration, "Propreté" to horse.cleanliness,
            "Litière du box" to horse.litter, "Moral" to horse.morale, "Énergie" to horse.energy, "Complicité" to horse.bond,
        )
        val right = listOf(
            "Santé" to horse.health, "Sabots" to horse.hooves, "Dents" to horse.teeth,
            "Condition physique" to horse.fitness, "Musculature" to horse.muscle,
        )
        var ya = y
        for ((l, v) in left) { gui.gauge(c, x1, ya + 10f * u, colW, l, v); ya += 25f * u }
        var yb = y
        for ((l, v) in right) { gui.gauge(c, x2, yb + 10f * u, colW, l, v); yb += 25f * u }
        val bcs = horse.bcs
        val bcsLabel = when { bcs < 3 -> "maigre"; bcs < 4.3f -> "un peu maigre"; bcs < 6.2f -> "idéal"; bcs < 7.3f -> "en surpoids"; else -> "obèse" }
        gui.gauge(c, x2, yb + 10f * u, colW, "État corporel (Henneke)", ((bcs - 1f) / 8f) * 100f, if (bcs in 4.3f..6.2f) Pal.OK else Pal.ORANGE, "${fmt1(bcs)}/9 $bcsLabel")
        yb += 25f * u
        gui.text(c, "Poids estimé : ${horse.weight(g.day).toInt()} kg", x2, yb + 10f * u, 10.5f, Pal.INK_L); yb += 18f * u
        y = maxOf(ya, yb) + 6f * u
        // affections
        if (horse.ailments.isNotEmpty()) {
            for (a in horse.ailments) {
                val box = RectF(r.left, y, r.right - 6f * u, y + 0f)
                val txt = "${a.type.label}${if (a.treated) " (soigné, ${if (a.type.chronic) "chronique" else "${a.daysLeft.toInt()} j de convalescence"})" else " — NON SOIGNÉ"} : ${a.type.advice}"
                val hh = gui.wrap(null, txt, 0f, 0f, box.width() - 20f * u, 10.5f) + 10f * u
                box.bottom = y + hh
                gui.p.shader = null; gui.p.color = if (a.treated) Color.argb(40, 62, 140, 74) else Color.argb(50, 176, 52, 44); c.drawRoundRect(box, 6f * u, 6f * u, gui.p)
                gui.wrap(c, txt, box.left + 10f * u, box.top + 14f * u, box.width() - 20f * u, 10.5f, if (a.treated) Pal.INK else Pal.RED)
                y += hh + 6f * u
            }
        }
        // soins préventifs
        gui.text(c, "Suivi", r.left, y + 12f * u, 13f, font = gui.serif); y += 20f * u
        fun due(label: String, d: Int) = "$label : ${if (d < 0) "en retard de ${-d} j" else "dans $d j"}"
        val prev = listOf(due("Maréchal", horse.farrierDue(g.day)) + if (horse.shod) " (ferré)" else " (pieds nus)", due("Dentiste", horse.dentistDue(g.day)), due("Vaccins", horse.vaccineDue(g.day)), due("Vermifuge", horse.dewormDue(g.day)))
        for ((i, s) in prev.withIndex()) gui.text(c, s, r.left + (i % 2) * (colW + 14f * u), y + 12f * u + (i / 2) * 15f * u, 10.5f, if (s.contains("retard")) Pal.RED else Pal.INK_L)
        y += 38f * u
        // ration
        gui.text(c, "Ration quotidienne", r.left, y + 12f * u, 13f, font = gui.serif)
        y += 18f * u
        val ra = horse.ration
        fun rationRow(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
            gui.text(c, label, r.left, y + 15f * u, 11f)
            gui.button(c, RectF(r.left + 150f * u, y, r.left + 178f * u, y + 22f * u), "−", size = 13f) { minus() }
            gui.text(c, value, r.left + 214f * u, y + 15f * u, 11.5f, Pal.INK, Paint.Align.CENTER, gui.sansB)
            gui.button(c, RectF(r.left + 250f * u, y, r.left + 278f * u, y + 22f * u), "+", size = 13f) { plus() }
            y += 26f * u
        }
        rationRow("Foin", "${fmt1(ra.hay)} kg", { horse.ration = Ration((ra.hay - 0.5f).coerceAtLeast(0f), ra.feed, ra.minerals) }) { horse.ration = Ration((ra.hay + 0.5f).coerceAtMost(16f), ra.feed, ra.minerals) }
        rationRow("Granulés", "${fmt1(ra.feed)} kg", { horse.ration = Ration(ra.hay, (ra.feed - 0.5f).coerceAtLeast(0f), ra.minerals) }) { horse.ration = Ration(ra.hay, (ra.feed + 0.5f).coerceAtMost(8f), ra.minerals) }
        gui.button(c, RectF(r.left + 290f * u, y - 52f * u, r.right - 6f * u, y - 30f * u), if (ra.minerals) "Minéraux : oui" else "Minéraux : non", Btn.GHOST, size = 10.5f) { horse.ration = Ration(ra.hay, ra.feed, !ra.minerals) }
        gui.button(c, RectF(r.left + 290f * u, y - 26f * u, r.right - 6f * u, y - 4f * u), "Ration conseillée", Btn.GHOST, size = 10.5f) { horse.ration = g.defaultRation(horse); gui.toast("Ration ajustée à son poids et son activité.") }
        val w = horse.weight(g.day)
        gui.wrap(c, "Repère : ${fmt1(w * 0.015f)} à ${fmt1(w * 0.02f)} kg de fourrage par jour pour ${w.toInt()} kg. Plus de 2,5 kg de granulés par repas augmente le risque de colique.", r.left, y + 10f * u, r.width() - 10f * u, 10f, Pal.INK_L)
        y += 36f * u
        // actions
        gui.text(c, "Actions", r.left, y + 12f * u, 13f, font = gui.serif); y += 20f * u
        val acts = ArrayList<Triple<String, String?, () -> Unit>>()
        if (horse.weaned) {
            acts += Triple("Nourrir", "${fmt1(ra.hay / 2)} kg foin", { res(g.feed(horse)); app.sound.play(SoundFx.S.MUNCH, 0.8f) })
            if (horse.place == Place.BOX) {
                acts += Triple("Abreuver", "seau ${horse.bucket.toInt()} L", { res(g.water(horse)); app.sound.play(SoundFx.S.WATER, 0.7f) })
                acts += Triple("Curer le box", "1 botte de paille", { res(g.muck(horse)) })
            }
        }
        acts += Triple("Panser", "brosses et cure-pied", { app.push(GroomScreen(app, horse)) })
        acts += Triple("Carotte", "complicité +", { res(g.treat(horse)); app.sound.play(SoundFx.S.MUNCH) })
        acts += Triple(if (horse.place == Place.PRE) "Rentrer au box" else "Sortir au pré", null, { res(g.setPlace(horse, if (horse.place == Place.PRE) Place.BOX else Place.PRE)); app.sound.play(SoundFx.S.HOOF_SOFT) })
        acts += Triple("Sortie : ${horse.turnout.label}", "routine du palefrenier", { horse.turnout = Turnout.values()[(horse.turnout.ordinal + 1) % Turnout.values().size] })
        acts += Triple("Vétérinaire", if (horse.ailments.any { !it.treated }) "${horse.ailments.filter { !it.treated }.sumOf { it.type.vet } + 60} €" else "bilan 90 €", { res(g.callVet(horse)) })
        acts += Triple("Maréchal : parer", "60 €", { res(g.farrier(horse, false)) })
        if (horse.backed) acts += Triple("Maréchal : ferrer", "140 €", { res(g.farrier(horse, true)) })
        acts += Triple("Dentiste", "95 €", { res(g.dentist(horse)) })
        acts += Triple("Vacciner", "80 €", { res(g.vaccinate(horse)) })
        acts += Triple("Vermifuger", "28 €", { res(g.deworm(horse)) })
        acts += Triple(if (horse.rugged) "Ôter la couverture" else "Mettre la couverture", null, { res(g.toggleRug(horse)) })
        if (!horse.clipped && horse.backed) acts += Triple("Tondre", "70 €", { res(g.clip(horse)) })
        if (!horse.weaned) acts += Triple("Sevrer", "vers 6 mois", { res(g.wean(horse)) })
        if (horse.handling < 100f) acts += Triple("Éducation", "${horse.handling.toInt()} %", { res(g.train(horse, Exercise.MANIPULATION, g.rider.overall, true)) })
        val bw = (r.width() - 16f * u) / 3f
        for ((i, a) in acts.withIndex()) {
            val bx = r.left + (i % 3) * (bw + 6f * u); val by = y + (i / 3) * 40f * u
            gui.button(c, RectF(bx, by, bx + bw, by + 34f * u), a.first, size = 11f, sub = a.second) { a.third() }
        }
    }

    private fun workTab(c: Canvas, r: RectF, y0: Float, off: Float) {
        val g = game
        val u = gui.u
        var y = y0
        if (!horse.owned || !horse.alive) return
        if (!horse.backed) {
            val txt = if (horse.age(g.day) < 2.5f) "${horse.name} est trop jeune pour être monté. Éduquez-le (licol, pieds, marcher en main) et laissez-le grandir au pré avec d'autres jeunes : c'est ainsi que se construisent les chevaux solides."
            else "${horse.name} n'est pas encore débourré : il faut l'habituer progressivement à la selle, au filet puis au cavalier (${horse.backingProgress.toInt()} %)."
            y += gui.wrap(c, txt, r.left, y, r.width() - 10f * u, 11.5f) + 6f * u
        }
        gui.text(c, "Séances (2 par jour au plus)", r.left, y, 13f, font = gui.serif); y += 10f * u
        for (ex in Exercise.values()) {
            val why = g.canTrain(horse, ex)
            val row = RectF(r.left, y, r.right - 8f * u, y + 46f * u)
            gui.p.shader = null; gui.p.color = Color.argb(if (why == null) 30 else 14, 120, 100, 60); c.drawRoundRect(row, 6f * u, 6f * u, gui.p)
            gui.text(c, ex.label, row.left + 8f * u, row.top + 16f * u, 12f, if (why == null) Pal.INK else Pal.INK_L, font = gui.sansB)
            gui.text(c, why ?: ex.desc, row.left + 8f * u, row.top + 31f * u, 9.5f, if (why == null) Pal.INK_L else Pal.RED, maxW = row.width() - 200f * u)
            val gains = ex.gains.keys.joinToString(" ") { it.short }
            gui.text(c, "${ex.minutes} min · $gains", row.left + 8f * u, row.top + 43f * u, 8.5f, Pal.INK_L, maxW = row.width() - 200f * u)
            val ridable = ex.ridden || ex == Exercise.DEBOURRAGE || ex == Exercise.LONGE
            gui.button(c, RectF(row.right - 186f * u, row.top + 8f * u, row.right - 96f * u, row.bottom - 8f * u), if (ex.ridden) "Monter" else "Travailler", Btn.PRIMARY, enabled = why == null, size = 11f) {
                if (ex.ridden) app.push(RideScreen(app, horse, RideMode.forExercise(ex), -1, ex))
                else { res(g.train(horse, ex, g.rider.overall, true)); app.sound.play(SoundFx.S.HOOF_SOFT) }
            }
            gui.button(c, RectF(row.right - 90f * u, row.top + 8f * u, row.right - 6f * u, row.bottom - 8f * u), "Rapide", enabled = why == null && ridable, size = 10.5f, sub = "sans jouer") {
                res(g.train(horse, ex, g.rider.overall, true, quality = 0.8f))
            }
            y += 52f * u
        }
        // programme du cavalier salarié
        y += 6f * u
        gui.text(c, "Programme confié à l'équipe", r.left, y + 10f * u, 13f, font = gui.serif); y += 18f * u
        val riders = g.staff.count { it.role == com.cheval.core.Role.CAVALIER || it.role == com.cheval.core.Role.LAD }
        if (riders == 0) { gui.text(c, "Embauchez un cavalier (Domaine › Équipe) pour faire travailler vos chevaux chaque jour.", r.left, y + 10f * u, 10.5f, Pal.INK_L, maxW = r.width()); y += 20f * u }
        val opts = listOf<Discipline?>(null) + Discipline.values().filter { it.trainable }
        val bw = (r.width() - 8f * u - 6f * u * 3) / 4f
        for ((i, d) in opts.withIndex()) {
            val bx = r.left + (i % 4) * (bw + 6f * u); val by = y + (i / 4) * 32f * u
            gui.button(c, RectF(bx, by, bx + bw, by + 26f * u), d?.label ?: "Repos", if (horse.plan == d) Btn.PRIMARY else Btn.NORMAL, size = 10f) { horse.plan = d }
        }
        y += 70f * u
        gui.text(c, "Séances par semaine : ${horse.planSessions}", r.left, y + 10f * u, 11f)
        gui.button(c, RectF(r.left + 170f * u, y - 4f * u, r.left + 198f * u, y + 18f * u), "−") { horse.planSessions = (horse.planSessions - 1).coerceAtLeast(1) }
        gui.button(c, RectF(r.left + 204f * u, y - 4f * u, r.left + 232f * u, y + 18f * u), "+") { horse.planSessions = (horse.planSessions + 1).coerceAtMost(6) }
        y += 28f * u
        gui.wrap(c, "Conseil : alternez travail spécifique, plat et extérieur, et gardez un jour de repos. Un cheval fatigué progresse mal et se blesse.", r.left, y, r.width() - 10f * u, 10f, Pal.INK_L)
    }

    private fun skillsTab(c: Canvas, r: RectF, y0: Float) {
        val g = game
        val u = gui.u
        var y = y0
        gui.text(c, "Niveau dans chaque discipline", r.left, y, 13f, font = gui.serif); y += 8f * u
        for (d in Discipline.values()) {
            val s = horse.skill(d); val cap = horse.skillCap(d, g.day)
            gui.gauge(c, r.left, y + 12f * u, r.width() * 0.62f, d.label, s.coerceAtMost(100f), Pal.BLUE, "${s.toInt()}")
            // plafond actuel
            val cx = r.left + r.width() * 0.62f * (cap / 100f).coerceIn(0f, 1f)
            gui.p.color = Pal.GOLD; c.drawRect(cx - 1f * u, y + 14f * u, cx + 1f * u, y + 24f * u, gui.p)
            gui.stars(c, r.left + r.width() * 0.66f, y + 14f * u, ((d.potential(horse) - 30f) / 10f).coerceIn(0f, 5f), 8f)
            y += 28f * u
        }
        gui.text(c, "Trait doré : plafond actuel (selon l'âge). Étoiles : potentiel génétique.", r.left, y + 6f * u, 9.5f, Pal.INK_L)
        y += 22f * u
        gui.text(c, "Qualités naturelles", r.left, y + 6f * u, 13f, font = gui.serif); y += 14f * u
        val colW = r.width() / 2f
        for ((i, tr) in Trait.SPORT.withIndex()) {
            val x = r.left + (i % 2) * colW; val yy = y + (i / 2) * 20f * u
            gui.text(c, tr.label, x, yy + 10f * u, 10.5f, Pal.INK, maxW = colW * 0.55f)
            gui.stars(c, x + colW * 0.56f, yy + 6f * u, traitStars(horse, tr), 8f)
        }
        y += (Trait.SPORT.size / 2 + 1) * 20f * u + 6f * u
        if (g.rider.galop >= 0) gui.text(c, "Votre niveau : Galop ${g.rider.galop} · ${g.rider.hoursRidden.toInt()} h d'équitation", r.left, y + 6f * u, 10.5f, Pal.INK_L)
    }

    private fun geneticsTab(c: Canvas, r: RectF, y0: Float, off: Float) {
        val g = game
        val u = gui.u
        var y = y0
        gui.text(c, "Robe : ${horse.coatName(g.day)}", r.left, y, 14f, font = gui.serif, maxW = r.width()); y += 18f * u
        if (horse.genome.has(Locus.GREY)) { gui.text(c, "Gène gris : ${horse.name} blanchira avec l'âge (${(Coat.greyLevel(horse.genome, horse.age(g.day)) * 100).toInt()} % aujourd'hui).", r.left, y, 10.5f, Pal.INK_L, maxW = r.width()); y += 15f * u }
        if (horse.dnaTested || !horse.owned) {
            gui.text(c, "Génotype (test ADN) :", r.left, y + 4f * u, 11f, Pal.INK_L); y += 18f * u
            gui.text(c, horse.genome.genotypeString(), r.left, y, 14f, Pal.BLUE, font = gui.sansB, maxW = r.width()); y += 18f * u
            val mstn = horse.genome.notation(Locus.MSTN)
            val mtxt = when (mstn) { "C/C" -> "sprinteur (distances courtes)"; "C/T" -> "polyvalent (distances moyennes)"; else -> "stayer (longues distances)" }
            gui.text(c, "Myostatine $mstn : $mtxt", r.left, y, 10.5f, Pal.INK, maxW = r.width()); y += 16f * u
        } else if (horse.owned) {
            gui.wrap(c, "Le génotype exact n'est pas connu : la robe visible peut cacher des gènes récessifs (alezan, crème…) ou un gène Frame overo dangereux en élevage.", r.left, y, r.width() - 10f * u, 10.5f, Pal.INK_L); y += 30f * u
            gui.button(c, RectF(r.left, y, r.left + 200f * u, y + 28f * u), "Test ADN des couleurs (65 €)", Btn.PRIMARY, size = 11f) { res(g.dnaTest(horse)) }
            y += 36f * u
        }
        for (n in Coat.geneticNotes(horse.genome).filter { horse.dnaTested || !it.startsWith("Porteur") }) { y += gui.wrap(c, "• $n", r.left, y, r.width() - 10f * u, 10.5f, Pal.LEATHER) }
        y += 6f * u
        gui.text(c, "Consanguinité : ${fmt1(horse.inbreeding * 100f)} %", r.left, y + 4f * u, 11f, if (horse.inbreeding > 0.0625f) Pal.RED else Pal.INK); y += 18f * u
        gui.text(c, "Taille adulte attendue : ${horse.heightCm} cm (actuelle ${horse.heightAt(g.day).toInt()} cm)", r.left, y + 4f * u, 11f); y += 20f * u
        gui.text(c, "Caractère", r.left, y + 6f * u, 13f, font = gui.serif); y += 16f * u
        for (p in horse.personality) { y += gui.wrap(c, "${p.label} — ${p.desc}", r.left, y + 6f * u, r.width() - 10f * u, 10.5f) }
        y += 10f * u
        gui.text(c, "Race", r.left, y + 6f * u, 13f, font = gui.serif); y += 16f * u
        gui.wrap(c, "${horse.breed.label} (${horse.breed.origin}). ${horse.breed.description}", r.left, y + 6f * u, r.width() - 10f * u, 10.5f, Pal.INK_L)
    }

    private fun pedigreeTab(c: Canvas, r: RectF, y0: Float, off: Float) {
        val g = game
        val u = gui.u
        val colW = r.width() / 3f
        val top = y0
        val hgt = 360f * u
        fun node(id: Int, gen: Int, idx: Int) {
            val slots = 1 shl gen
            val slotH = hgt / slots
            val x = r.left + (gen - 1) * colW
            val y = top + idx * slotH + slotH / 2f
            val hz = g.horse(id)
            val box = RectF(x + 2f * u, y - 18f * u, x + colW - 6f * u, y + 18f * u)
            gui.p.shader = null; gui.p.color = if (hz?.sex == Sex.JUMENT) Color.argb(40, 190, 90, 120) else Color.argb(40, 60, 100, 160)
            c.drawRoundRect(box, 6f * u, 6f * u, gui.p)
            if (hz == null) { gui.text(c, "inconnu", box.left + 8f * u, box.centerY() + 4f * u, 10f, Pal.INK_L); return }
            gui.text(c, hz.name, box.left + 8f * u, box.centerY() - 2f * u, 11f, Pal.INK, font = gui.sansB, maxW = box.width() - 12f * u)
            gui.text(c, "${hz.breed.label} · ${hz.coatName(g.day)}", box.left + 8f * u, box.centerY() + 11f * u, 8.5f, Pal.INK_L, maxW = box.width() - 12f * u)
            if (gen < 3) { node(hz.sireId, gen + 1, idx * 2); node(hz.damId, gen + 1, idx * 2 + 1) }
        }
        node(horse.sireId, 1, 0); node(horse.damId, 1, 1)
        gui.text(c, "Père en haut, mère en bas. Trois générations.", r.left, top + hgt + 16f * u, 9.5f, Pal.INK_L)
        val kids = g.horses.values.filter { it.sireId == horse.id || it.damId == horse.id }
        if (kids.isNotEmpty()) {
            gui.text(c, "Produits : ${kids.joinToString { it.name }}", r.left, top + hgt + 32f * u, 10.5f, Pal.INK, maxW = r.width())
        }
    }

    private fun careerTab(c: Canvas, r: RectF, y0: Float, off: Float) {
        val g = game
        val u = gui.u
        var y = y0
        val wins = horse.results.count { it.rank == 1 }
        gui.text(c, "Gains : ${fmtMoney(horse.earnings)} · ${horse.results.size} départs · $wins victoire(s)", r.left, y, 12f, font = gui.sansB); y += 16f * u
        if (horse.purchasePrice > 0) { gui.text(c, "Acheté ${fmtMoney(horse.purchasePrice)} le ${Cal.format(horse.acquiredDay)}", r.left, y, 10.5f, Pal.INK_L); y += 14f * u }
        for (ti in horse.titles) { gui.text(c, "🏆 $ti", r.left, y, 10.5f, Pal.GOLD, maxW = r.width()); y += 14f * u }
        if (horse.owned && horse.alive) {
            y += 6f * u
            gui.text(c, "Valeur estimée : ${fmtMoney(g.value(horse))}", r.left, y + 6f * u, 13f, font = gui.serif); y += 16f * u
            val bw = (r.width() - 20f * u) / 3f
            val acts = ArrayList<Triple<String, String?, () -> Unit>>()
            if (horse.forSale) acts += Triple("Retirer de la vente", "${fmtMoney(horse.askingPrice)}", { g.unlist(horse) })
            else acts += Triple("Mettre en vente", "prix libre", {
                app.askText("Prix demandé (€)", g.value(horse).toString()) { s -> s.filter { it.isDigit() }.toIntOrNull()?.let { res(g.listForSale(horse, it)) } }
            })
            acts += Triple("Vendre au marchand", "${fmtMoney((g.value(horse) * 0.65f).toInt())}", { confirm = "Vendre ${horse.name} tout de suite à un marchand ?" to { res(g.sellToDealer(horse)); app.pop() } })
            if (horse.stallion) acts += Triple("Castrer", "350 €", { confirm = "Castrer ${horse.name} ? Il deviendra hongre, plus calme, mais ne pourra plus reproduire." to { res(g.geld(horse)) } })
            if (horse.stallion && horse.age(g.day) >= 3) acts += Triple(if (horse.studFee > 0) "Saillies : ${fmtMoney(horse.studFee)}" else "Proposer à la saillie", "juments extérieures", {
                app.askText("Prix de la saillie (€, 0 = non)", (if (horse.studFee > 0) horse.studFee else (g.value(horse) * 0.05f).toInt()).toString()) { s -> horse.studFee = s.filter { it.isDigit() }.toIntOrNull() ?: 0 }
            })
            if (horse.backed) acts += Triple(if (horse.clubHorse) "Cheval de club : oui" else "Cheval de club : non", "cours d'équitation", { horse.clubHorse = !horse.clubHorse })
            for ((i, a) in acts.withIndex()) {
                val bx = r.left + (i % 3) * (bw + 6f * u); val by = y + (i / 3) * 40f * u
                gui.button(c, RectF(bx, by, bx + bw, by + 34f * u), a.first, size = 10.5f, sub = a.second) { a.third() }
            }
            y += ((acts.size + 2) / 3) * 40f * u + 6f * u
            g.offers.filter { it.horseId == horse.id }.forEach { o ->
                gui.text(c, "Offre de ${o.buyer} : ${fmtMoney(o.amount)}", r.left, y + 16f * u, 11.5f, Pal.OK, font = gui.sansB)
                gui.button(c, RectF(r.right - 190f * u, y, r.right - 100f * u, y + 26f * u), "Accepter", Btn.PRIMARY, size = 10.5f) { res(g.acceptOffer(o)); app.pop() }
                gui.button(c, RectF(r.right - 94f * u, y, r.right - 8f * u, y + 26f * u), "Refuser", size = 10.5f) { g.declineOffer(o) }
                y += 32f * u
            }
        }
        y += 8f * u
        gui.text(c, "Palmarès", r.left, y + 6f * u, 13f, font = gui.serif); y += 16f * u
        if (horse.results.isEmpty()) gui.text(c, "Aucun départ en concours pour l'instant.", r.left, y + 6f * u, 10.5f, Pal.INK_L)
        for (res in horse.results) {
            val col = if (res.rank == 1) Pal.GOLD else if (res.rank <= 3) Pal.OK else Pal.INK
            gui.text(c, "${Cal.formatShort(res.day)} · ${res.eventName}", r.left, y + 6f * u, 10f, Pal.INK, maxW = r.width() * 0.55f)
            gui.text(c, "${if (res.rank == 1) "1er" else "${res.rank}e"}/${res.of} · ${res.score}${if (res.prize > 0) " · ${fmtMoney(res.prize)}" else ""}", r.right - 8f * u, y + 6f * u, 10f, col, Paint.Align.RIGHT, maxW = r.width() * 0.43f)
            y += 15f * u
        }
    }

    override fun onBack(): Boolean { if (confirm != null) { confirm = null; return true }; return false }
}
