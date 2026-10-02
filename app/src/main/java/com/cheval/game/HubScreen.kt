package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.cheval.core.BuildingType
import com.cheval.core.Cal
import com.cheval.core.Horse
import com.cheval.core.MsgKind
import com.cheval.core.Personality
import com.cheval.core.Place
import com.cheval.core.Sky
import com.cheval.core.fmtMoney
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le domaine, vivant : ciel selon l'heure et la météo, écurie avec les chevaux aux portes de leur box,
 * prairie où les chevaux broutent, se promènent et jouent, carrière, grange, manège et la baie au loin.
 */
class HubScreen(app: GameView) : Screen(app) {
    private val game get() = app.game!!
    private var t = 0f
    private var camX = 0f
    private var worldW = 2000f

    /** Acteurs au pré : comportement autonome. */
    private class Actor(val id: Int) {
        var x = 0f; var depth = 0.5f; var dir = 1f; var targetX = 0f
        var state = 0 // 0 brouter, 1 marcher, 2 jouer (trot/galop), 3 attentif, 4 se rouler (non dessiné)
        var timer = 0f
        val pose = HorsePose()
    }
    private val actors = HashMap<Int, Actor>()
    private var preX0 = 0f; private var preX1 = 0f
    private val boxRects = ArrayList<Pair<Int, RectF>>()
    private var livePrompt = true
    private var lastHourSound = -1

    override fun onShow() { livePrompt = true }

    override fun update(dt: Float) {
        t += dt
        app.tickGame(dt)
        val g = game
        val atPre = g.owned().filter { it.place == Place.PRE }
        if (preX1 - preX0 < 200f) return // pas encore dessiné : la prairie n'est pas placée
        actors.keys.retainAll(atPre.map { it.id }.toSet())
        for (h in atPre) {
            val a = actors.getOrPut(h.id) { Actor(h.id).also { it.x = preX0 + (preX1 - preX0) * (0.2f + 0.6f * ((h.id * 37) % 100) / 100f); it.depth = ((h.id * 53) % 100) / 100f; it.targetX = it.x } }
            updateActor(a, h, dt)
        }
        // sons d'ambiance : hennissement de temps en temps
        val hr = g.hour
        if (hr != lastHourSound) {
            lastHourSound = hr
            if (atPre.size > 1 && hr % 3 == 0 && app.timeSpeed in 1..2) app.sound.play(SoundFx.S.WHINNY_SHORT, 0.3f, 0.9f + (hr % 5) * 0.05f)
            if (hr == 7 && app.timeSpeed in 1..2) app.sound.play(SoundFx.S.NEIGH, 0.35f)
        }
    }

    private fun updateActor(a: Actor, h: Horse, dt: Float) {
        val g = game
        val foal = !h.weaned
        a.timer -= dt
        if (a.timer <= 0f) {
            val playful = (Personality.JOUEUR in h.personality || h.age(g.day) < 3) && h.energy > 50 && h.morale > 60
            val r = (sin(t * 7.3f + h.id) * 0.5f + 0.5f)
            a.state = when {
                h.injured -> 3
                foal -> 1
                playful && r < 0.18f -> 2
                r < 0.55f -> 0
                r < 0.85f -> 1
                else -> 3
            }
            a.timer = when (a.state) { 0 -> 5f + r * 8f; 1 -> 3f + r * 4f; 2 -> 2.5f; else -> 2f + r * 3f }
            a.targetX = (preX0 + 60f + (preX1 - preX0 - 120f) * ((sin(t * 3.1f + h.id * 1.7f) + 1f) / 2f))
            if (foal) game.horse(h.damId)?.let { d -> actors[d.id]?.let { m -> a.targetX = m.x - 60f * m.dir; a.depth = m.depth + 0.05f } }
        }
        val p = a.pose
        val gait = when (a.state) { 1 -> if (foal && abs(a.targetX - a.x) > 120f) Gait.TROT else Gait.PAS; 2 -> if (a.timer > 1.2f) Gait.GALOP else Gait.TROT; else -> Gait.ARRET }
        p.gait = gait
        p.phase = (p.phase + dt * gait.freq) % 1f
        p.breathe = (p.breathe + dt * 0.22f) % 1f
        p.speedBlend += ((if (gait.ordinal >= Gait.GALOP.ordinal) 0.6f else 0f) - p.speedBlend) * dt * 2f
        val targetNeck = when (a.state) { 0 -> -38f; 3 -> 58f; 2 -> 40f; else -> 32f }
        val targetHead = when (a.state) { 0 -> 12f; 3 -> 30f; else -> 45f }
        p.neck += (targetNeck - p.neck) * dt * 2f
        p.head += (targetHead - p.head) * dt * 2f
        p.ears = if (a.state == 3) 0.9f else sin(t * 0.9f + h.id) * 0.4f
        p.tailSwing = sin(t * (if (g.weather.tempMax > 20) 3.5f else 1.4f) + h.id) * (if (g.weather.tempMax > 20) 1f else 0.4f)
        p.blink = if ((t + h.id) % 5.1f < 0.14f) 1f else 0f
        if (gait != Gait.ARRET) {
            val dx = a.targetX - a.x
            if (abs(dx) < 10f) { a.state = 0; a.timer = 4f } else {
                a.dir = if (dx > 0) 1f else -1f
                val scale = scaleFor(a.depth)
                a.x += a.dir * gait.speed * Looks.of(h, g.day).H * scale * dt * 0.8f
            }
        } else if (a.state == 0) {
            // en broutant, le cheval avance d'un pas de temps en temps
            if ((t + h.id * 0.37f) % 3f < dt) a.x += a.dir * 6f
        }
        a.x = a.x.coerceIn(preX0 + 40f, preX1 - 40f)
    }

    private fun scaleFor(depth: Float): Float = gui.h / 760f * (0.75f + depth * 0.45f)

    override fun draw(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        val amb = ambienceOf(g)
        val ground = h * 0.6f
        gui.onFreeDrag = { dx, _ -> camX = (camX - dx).coerceIn(0f, max(0f, worldW - w)) }

        // ---------------- fond
        Scenery.sky(c, w, ground, amb, t)
        Scenery.hills(c, w, ground - 30f * u, 70f * u, camX * 0.15f, 21, Scenery.lit(Color.rgb(112, 130, 128), amb))
        // la baie sur la droite du domaine
        val seaStart = (worldW - w * 0.9f) - camX * 0.5f
        if (seaStart < w) Scenery.sea(c, max(0f, seaStart), w, ground - 34f * u, ground - 6f * u, amb, t)
        Scenery.hills(c, w, ground, 34f * u, camX * 0.4f, 8, Scenery.lit(Scenery.mix(Scenery.grassColor(amb), Color.rgb(60, 90, 70), 0.5f), amb))
        for (i in 0 until 14) { val tx = i * 260f * u - camX * 0.6f + 80f; if (tx > -100 && tx < w + 100) Scenery.tree(c, tx, ground - 4f * u, (60f + (i * 37 % 30)) * u, amb, i, poplar = i % 4 == 1) }
        Scenery.grass(c, 0f, w, ground, h, amb, camX, mud = if (g.weather.ground == com.cheval.core.Ground.LOURD) 1f else 0f)

        // ---------------- domaine (coordonnées monde)
        c.save(); c.translate(-camX, 0f)
        var x = 30f * u
        boxRects.clear()
        if (g.level(BuildingType.MANEGE) > 0) { Scenery.indoorArena(c, x, ground, 280f * u, 170f * u, amb); x += 310f * u }
        // écurie(s) : 12 boxes par bâtiment au plus
        val owned = g.owned()
        val inBox = owned.filter { it.place == Place.BOX && it.weaned }
        val foals = owned.filter { !it.weaned && it.place == Place.BOX }
        var boxIndex = 0
        val boxW = 46f * u
        val nBoxes = g.boxes()
        var remaining = nBoxes
        while (remaining > 0) {
            val n = min(12, remaining)
            val startIndex = boxIndex
            Scenery.stable(c, x, ground, boxW, n, amb) { i, open ->
                val idx = startIndex + i
                val hz = inBox.getOrNull(idx)
                if (hz != null) {
                    c.save(); c.clipRect(open)
                    val a = Looks.of(hz, g.day)
                    val sc = open.height() * 2.3f / a.H
                    val pose = HorsePose().apply { neck = 42f + sin(t * 0.5f + hz.id) * 6f; head = 30f; ears = sin(t * 0.8f + hz.id) * 0.6f; blink = if ((t + hz.id) % 4.7f < 0.14f) 1f else 0f }
                    HorseArt.draw(c, a, pose, open.left - a.L * 0.48f * sc, open.bottom + a.H * 0.72f * sc, sc, true, amb.light, shadow = false)
                    c.restore()
                    boxRects += hz.id to RectF(open.left - camX, open.top, open.right - camX, ground)
                    // poulain dans le même box
                    foals.firstOrNull { it.damId == hz.id }?.let { f -> boxRects += f.id to RectF(open.left - camX, open.top + open.height() * 0.5f, open.right - camX, ground) }
                }
                // plaque du nom
                val plate = RectF(open.left, open.bottom + 3f * u, open.right, open.bottom + 13f * u)
                gui.p.shader = null; gui.p.color = Scenery.lit(Color.rgb(226, 214, 180), amb); c.drawRect(plate, gui.p)
                if (hz != null) {
                    gui.text(c, hz.name.substringBefore(' '), plate.centerX(), plate.bottom - 2.5f * u, 7f, Pal.INK, Paint.Align.CENTER, maxW = plate.width() - 2f * u)
                    if (hz.urgent || hz.injured) { gui.p.color = Pal.RED; c.drawCircle(open.right - 4f * u, open.top + 5f * u, 4f * u, gui.p) }
                    else if (hz.satiety < 30 || hz.hydration < 35 || hz.litter < 30) { gui.p.color = Pal.ORANGE; c.drawCircle(open.right - 4f * u, open.top + 5f * u, 4f * u, gui.p) }
                }
            }
            boxIndex += n; remaining -= n
            x += boxW * n + boxW * 0.6f + 30f * u
        }
        // grange et club-house
        Scenery.barn(c, x, ground, 130f * u, 120f * u, amb); x += 160f * u
        if (g.level(BuildingType.CLUB_HOUSE) > 0) { Scenery.clubHouse(c, x, ground, 150f * u, 110f * u, amb); x += 180f * u }
        // carrière au premier plan
        val arenaX = x
        if (g.level(BuildingType.CARRIERE) > 0) {
            val top = ground + 18f * u; val bot = h - 72f * u
            Scenery.sand(c, arenaX, arenaX + 360f * u, top, bot, amb)
            Scenery.jump(c, arenaX + 110f * u, (top + bot) / 2f, 26f * u, 50f * u, amb, colorA = Color.rgb(30, 90, 170))
            Scenery.jump(c, arenaX + 250f * u, (top + bot) / 2f + 20f * u, 30f * u, 50f * u, amb, oxer = true)
            Scenery.fence(c, arenaX, arenaX + 360f * u, top + 4f * u, 18f * u, amb)
            Scenery.fence(c, arenaX, arenaX + 360f * u, bot, 18f * u, amb)
        } else sign(c, arenaX + 120f * u, ground + 40f * u, "Carrière à construire", amb)
        x += 400f * u
        // prairie
        preX0 = x; preX1 = x + (380f + g.hectares() * 50f) * u
        Scenery.fence(c, preX0, preX1, ground + 6f * u, 22f * u, amb, color = Color.rgb(120, 88, 60))
        // abri au pré
        gui.p.color = Scenery.lit(Color.rgb(110, 80, 56), amb); c.drawRect(preX1 - 120f * u, ground - 50f * u, preX1 - 30f * u, ground - 4f * u, gui.p)
        gui.p.color = Scenery.lit(Color.rgb(70, 60, 56), amb); c.drawRect(preX1 - 128f * u, ground - 58f * u, preX1 - 22f * u, ground - 48f * u, gui.p)
        // abreuvoir
        gui.p.color = Scenery.lit(Color.rgb(140, 150, 160), amb); c.drawRect(preX0 + 40f * u, ground + 30f * u, preX0 + 90f * u, ground + 42f * u, gui.p)
        val sorted = actors.values.sortedBy { it.depth }
        for (a in sorted) {
            val hz = g.horse(a.id) ?: continue
            val sc = scaleFor(a.depth)
            val gy = ground + 20f * u + a.depth * (h - ground - 110f * u)
            HorseArt.draw(c, Looks.of(hz, g.day), a.pose, a.x, gy, sc, a.dir > 0, amb.light)
            val hw = Looks.of(hz, g.day).L * sc * 0.7f
            boxRects += hz.id to RectF(a.x - hw - camX, gy - Looks.of(hz, g.day).H * sc * 1.2f, a.x + hw - camX, gy)
        }
        Scenery.fence(c, preX0, preX1, h - 66f * u, 26f * u, amb, color = Color.rgb(120, 88, 60))
        x = preX1 + 60f * u
        if (g.level(BuildingType.PISTE) > 0) { Scenery.fence(c, x, x + 260f * u, ground + 30f * u, 14f * u, amb); gui.text(c, "Piste de galop", x + 130f * u, ground + 12f * u, 9f, Pal.CREAM, Paint.Align.CENTER, shadow = true); x += 290f * u }
        if (g.level(BuildingType.CROSS) > 0) {
            gui.p.color = Scenery.lit(Color.rgb(110, 80, 50), amb)
            c.drawRoundRect(x, ground + 50f * u, x + 70f * u, ground + 64f * u, 7f * u, 7f * u, gui.p)
            gui.p.color = Scenery.lit(Color.rgb(70, 130, 170), amb); c.drawOval(x + 110f * u, ground + 60f * u, x + 210f * u, ground + 80f * u, gui.p)
            gui.text(c, "Cross", x + 100f * u, ground + 40f * u, 9f, Pal.CREAM, Paint.Align.CENTER, shadow = true); x += 250f * u
        }
        // chemin vers la plage
        Scenery.sand(c, x, x + w * 0.9f, ground + 6f * u, h, amb)
        x += w * 0.9f
        worldW = x
        c.restore()

        Scenery.weather(c, w, h, amb, t)

        // tap sur un cheval
        for ((id, r) in boxRects) gui.hit(r) { app.sound.play(SoundFx.S.CLICK); g.horse(id)?.let { app.push(HorseScreen(app, it)) } }

        drawHud(c, amb)
        drawNav(c)
        drawAlerts(c)
        if (g.pendingLive.isNotEmpty() && livePrompt) drawLivePrompt(c)
    }

    private fun sign(c: Canvas, x: Float, y: Float, label: String, amb: Ambience) {
        val u = gui.u
        gui.p.shader = null; gui.p.color = Scenery.lit(Color.rgb(110, 80, 50), amb)
        c.drawRect(x - 2f * u, y - 30f * u, x + 2f * u, y, gui.p)
        val r = RectF(x - 60f * u, y - 44f * u, x + 60f * u, y - 26f * u)
        gui.p.color = Scenery.lit(Color.rgb(236, 226, 200), amb); c.drawRoundRect(r, 3f * u, 3f * u, gui.p)
        gui.text(c, label, x, y - 32f * u, 9f, Pal.INK, Paint.Align.CENTER)
    }

    private fun drawHud(c: Canvas, amb: Ambience) {
        val g = game
        val u = gui.u; val w = gui.w
        val r = RectF(8f * u, 6f * u, w - 8f * u, 40f * u)
        gui.dark(c, r, 9f, 200)
        val d = Cal.date(g.day)
        val hh = g.hourOfDay.toInt(); val mm = ((g.hourOfDay - hh) * 60).toInt() / 15 * 15
        gui.text(c, "${Cal.weekday(g.day).take(3).replaceFirstChar { it.uppercase() }}. ${d.dom} ${Cal.MONTHS[d.month]} ${d.year}", 18f * u, 20f * u, 12f, Pal.CREAM, font = gui.sansB)
        gui.text(c, "%02d:%02d".format(hh, mm), 18f * u, 34f * u, 11f, Pal.GOLD_L)
        val icon = when (g.weather.sky) { Sky.SOLEIL -> if (amb.light > 0.4f) "☀" else "☾"; Sky.NUAGEUX -> "☁"; Sky.PLUIE -> "☂"; Sky.ORAGE -> "⚡"; Sky.NEIGE -> "❄"; Sky.BROUILLARD -> "≋"; Sky.TEMPETE -> "🌀" }
        gui.text(c, "$icon ${g.weather.sky.label} ${g.weather.tempAt(g.hourOfDay).toInt()}°C · sol ${g.weather.ground.label.lowercase()}", 150f * u, 20f * u, 11f, Pal.CREAM)
        gui.text(c, "Herbe ${g.grass.toInt()} % · ${Cal.season(g.day).label}", 150f * u, 34f * u, 10f, Pal.GOLD_L)
        gui.text(c, fmtMoney(g.money), w * 0.52f, 25f * u, 15f, if (g.money < 0) Color.rgb(255, 140, 120) else Pal.GOLD_L, Paint.Align.CENTER, gui.sansB)
        gui.text(c, "Réputation ${g.reputation.toInt()}", w * 0.52f, 36f * u, 9f, Pal.CREAM, Paint.Align.CENTER)
        // vitesse du temps
        val labels = listOf("❚❚", "▶", "▶▶", "▶▶▶")
        val bw = 34f * u
        val x0 = w - 16f * u - bw * 5 - 4f * u * 4
        for (i in 0..3) {
            gui.button(c, RectF(x0 + i * (bw + 4f * u), 10f * u, x0 + i * (bw + 4f * u) + bw, 36f * u), labels[i], if (app.timeSpeed == i) Btn.TAB_ON else Btn.TAB, size = 11f) { app.timeSpeed = i; app.sound.play(SoundFx.S.CLICK) }
        }
        gui.button(c, RectF(x0 + 4 * (bw + 4f * u), 10f * u, x0 + 4 * (bw + 4f * u) + bw, 36f * u), "☾", Btn.TAB, size = 13f) {
            app.sound.play(SoundFx.S.CLICK)
            g.sleepUntilMorning()
            gui.toast("Une nuit passe… Bonjour, il est 7 h !", Pal.GREEN)
        }
    }

    private fun drawNav(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        val r = RectF(8f * u, h - 52f * u, w - 8f * u, h - 6f * u)
        gui.dark(c, r, 10f, 215)
        val items = listOf(
            "Chevaux" to { app.push(HorsesScreen(app)) },
            "Concours" to { app.push(CompetitionScreen(app)) },
            "Élevage" to { app.push(BreedingScreen(app)) },
            "Marché" to { app.push(MarketScreen(app)) },
            "Domaine" to { app.push(ManageScreen(app)) },
            "Journal" to { app.push(JournalScreen(app)) },
        )
        val bw = (r.width() - 8f * u) / items.size
        for ((i, it) in items.withIndex()) {
            val br = RectF(r.left + 4f * u + i * bw, r.top + 4f * u, r.left + 4f * u + (i + 1) * bw - 4f * u, r.bottom - 4f * u)
            val sub = when (i) {
                0 -> "${g.owned().size} chevaux"
                1 -> g.ownEntries().size.let { if (it > 0) "$it engagé(s)" else "Calendrier" }
                2 -> g.owned().count { it.pregnancy?.confirmed == true }.let { if (it > 0) "$it jument(s) pleine(s)" else "Saillies" }
                3 -> if (g.offers.isNotEmpty()) "${g.offers.size} offre(s) !" else "${g.market.size} annonces"
                4 -> "Bâtiments, équipe"
                else -> if (g.unread > 0) "${g.unread} nouveau(x)" else "Messages"
            }
            gui.button(c, br, it.first, Btn.TAB, size = 12.5f, sub = sub) { app.sound.play(SoundFx.S.PAGE, 0.6f); it.second() }
            if ((i == 5 && g.unread > 0) || (i == 3 && g.offers.isNotEmpty())) { gui.p.color = Pal.RED; c.drawCircle(br.right - 10f * u, br.top + 8f * u, 4f * u, gui.p) }
        }
    }

    private fun drawAlerts(c: Canvas) {
        val g = game
        val u = gui.u
        val alerts = ArrayList<Pair<String, () -> Unit>>()
        for (hz in g.owned()) {
            when {
                hz.urgent -> alerts += "⚠ ${hz.name} : ${hz.ailments.first { it.type.urgent && !it.treated }.type.label} — appelez le vétérinaire !" to { app.push(HorseScreen(app, hz)) }
                hz.satiety < 20 && hz.weaned -> alerts += "${hz.name} a faim" to { app.push(HorseScreen(app, hz)) }
                hz.hydration < 25 && hz.weaned -> alerts += "${hz.name} a soif" to { app.push(HorseScreen(app, hz)) }
                hz.ailments.any { !it.treated && !it.type.chronic } -> alerts += "${hz.name} : ${hz.ailments.first { !it.treated }.type.label.lowercase()}" to { app.push(HorseScreen(app, hz)) }
            }
        }
        if (g.daysOf(com.cheval.core.Item.FOIN) < 4 && g.owned().isNotEmpty()) alerts += "Stock de foin : ${g.daysOf(com.cheval.core.Item.FOIN)} j" to { app.push(ManageScreen(app, 2)) }
        if (g.daysOf(com.cheval.core.Item.PAILLE) < 3 && g.owned().isNotEmpty()) alerts += "Stock de paille : ${g.daysOf(com.cheval.core.Item.PAILLE)} j" to { app.push(ManageScreen(app, 2)) }
        var y = 48f * u
        for ((txt, act) in alerts.take(4)) {
            val tw = gui.textW(txt, 10.5f) + 20f * u
            val r = RectF(12f * u, y, 12f * u + tw, y + 20f * u)
            gui.p.shader = null; gui.p.color = Color.argb(225, 150, 42, 34)
            c.drawRoundRect(r, 6f * u, 6f * u, gui.p)
            gui.text(c, txt, r.left + 10f * u, r.bottom - 6f * u, 10.5f, Pal.CREAM)
            gui.hit(r) { act() }
            y += 24f * u
        }
    }

    private fun drawLivePrompt(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        val (eid, hid) = g.pendingLive.first()
        val e = g.events.firstOrNull { it.id == eid } ?: return
        val hz = g.horse(hid) ?: return
        gui.modal(c)
        val r = RectF(w / 2 - 200f * u, h / 2 - 100f * u, w / 2 + 200f * u, h / 2 + 100f * u)
        gui.paper(c, r)
        gui.text(c, "Jour de concours !", r.left + 18f * u, r.top + 30f * u, 18f, font = gui.serif)
        gui.wrap(c, "${hz.name} est arrivé à ${e.venue} pour l'épreuve « ${e.name} ». La cloche va sonner : à vous de monter !", r.left + 18f * u, r.top + 56f * u, r.width() - 36f * u, 12f)
        gui.button(c, RectF(r.left + 18f * u, r.bottom - 50f * u, r.centerX() - 6f * u, r.bottom - 14f * u), "Plus tard") { livePrompt = false }
        gui.button(c, RectF(r.centerX() + 6f * u, r.bottom - 50f * u, r.right - 18f * u, r.bottom - 14f * u), "Entrer en piste", Btn.GOLD) {
            app.sound.play(SoundFx.S.BELL)
            app.push(RideScreen(app, hz, RideMode.forEvent(e), e.id))
        }
    }
}
