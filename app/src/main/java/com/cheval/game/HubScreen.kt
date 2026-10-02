package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.cheval.core.BuildingType
import com.cheval.core.Cal
import com.cheval.core.Goals
import com.cheval.core.Horse
import com.cheval.core.Junk
import com.cheval.core.MsgKind
import com.cheval.core.Personality
import com.cheval.core.Place
import com.cheval.core.Sky
import com.cheval.core.fmt1
import com.cheval.core.fmtMoney
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le domaine vu de trois quarts, façon Hoofprint Bay : on clique sur les bâtiments, les paddocks,
 * les déchets et les chevaux. La journée avance au fil des actions ; « Nouvelle journée » pour dormir.
 */
class HubScreen(app: GameView) : Screen(app) {
    private val game get() = app.game!!
    private val art = FarmMapArt()
    private var t = 0f
    private var camX = 0f
    private var panTarget = -1f

    /** Un cheval au paddock : promenade libre, demi-tours, troupeau, sieste. */
    private class Actor(val id: Int) {
        var x = 0f; var y = 0f; var dir = 1f
        var tx = 0f; var ty = 0f
        var state = 0 // 0 brouter, 1 marcher, 2 jouer, 3 attentif, 4 couché
        var timer = 0f
        var turn = 0f // 0 = pas de demi-tour, >0 = en cours (0..1)
        var speed = 0f
        var paddock = 0
        val pose = HorsePose()
    }
    private val actors = HashMap<Int, Actor>()
    private val hitHorses = ArrayList<Pair<Int, RectF>>()
    private var modal: (Canvas.() -> Unit)? = null
    private var report: List<String>? = null
    private var lessonPick = HashSet<Int>()
    private var lastMsgCount = 0

    override fun resize(w: Int, h: Int) { art.resize(w.toFloat(), h.toFloat()); camX = (art.mapW * 0.3f - w * 0.45f).coerceIn(0f, max(0f, art.mapW - w)) }

    override fun onShow() { if (art.mapW <= 1f && gui.w > 1f) resize(gui.w.toInt(), gui.h.toInt()) }

    override fun update(dt: Float) {
        t += dt
        if (art.mapW <= 1f) return
        if (panTarget >= 0f) { camX += (panTarget - camX) * min(1f, dt * 5f); if (abs(panTarget - camX) < 2f) panTarget = -1f }
        val g = game
        val paddocks = art.paddocks(g.level(BuildingType.PRE))
        val atPre = g.owned().filter { it.place == Place.PRE }
        actors.keys.retainAll(atPre.map { it.id }.toSet())
        for ((i, h) in atPre.withIndex()) {
            val a = actors.getOrPut(h.id) {
                Actor(h.id).also { ac ->
                    ac.paddock = (if (!h.weaned) atPre.indexOfFirst { it.id == h.damId }.coerceAtLeast(0) else i) % paddocks.size
                    val pd = paddocks[ac.paddock]
                    val (x, y) = pd.randomPoint(((h.id * 37) % 100) / 100f, ((h.id * 53) % 100) / 100f)
                    ac.x = x; ac.y = y; ac.tx = x; ac.ty = y
                }
            }
            if (!h.weaned) g.horse(h.damId)?.let { d -> actors[d.id]?.let { m -> a.paddock = m.paddock } }
            updateActor(a, h, dt, paddocks[a.paddock.coerceIn(0, paddocks.size - 1)])
        }
    }

    private fun night() = game.hourOfDay >= 20.5f || game.hourOfDay < 6.5f

    private fun updateActor(a: Actor, h: Horse, dt: Float, pd: Paddock) {
        val g = game
        val foal = !h.weaned
        a.timer -= dt
        if (a.timer <= 0f) {
            val r = (sin(t * 7.3f + h.id * 1.37f) * 0.5f + 0.5f)
            val playful = (Personality.JOUEUR in h.personality || h.age(g.day) < 3) && h.energy > 50 && h.morale > 55
            a.state = when {
                h.injured -> 3
                night() && r < 0.7f -> 4
                foal -> if (r < 0.15f) 4 else 1
                h.energy < 30 && r < 0.4f -> 4
                playful && r < 0.2f -> 2
                r < 0.5f -> 0
                r < 0.82f -> 1
                else -> 3
            }
            a.timer = when (a.state) { 0 -> 5f + r * 7f; 1 -> 4f + r * 4f; 2 -> 3f; 4 -> 12f + r * 10f; else -> 2f + r * 3f }
            // le troupeau reste groupé : on rejoint souvent un congénère
            val mates = actors.values.filter { it !== a && it.paddock == a.paddock }
            val (nx, ny) = if (foal) {
                g.horse(h.damId)?.let { d -> actors[d.id] }?.let { m -> (m.x - 34f * art.k * m.dir) to (m.y + 6f * art.k) } ?: pd.randomPoint(r, 0.5f)
            } else if (mates.isNotEmpty() && r < 0.45f) {
                val m = mates[(h.id + (t * 0.1f).toInt()) % mates.size]
                (m.x + (r - 0.5f) * 90f * art.k) to (m.y + (r - 0.5f) * 30f * art.k)
            } else pd.randomPoint(((t * 0.37f + h.id * 0.71f) % 1f), r)
            if (pd.contains(nx, ny)) { a.tx = nx; a.ty = ny } else { val (rx, ry) = pd.randomPoint(r, 0.6f); a.tx = rx; a.ty = ry }
        }
        val p = a.pose
        val moving = a.state == 1 || a.state == 2 || (foal && hypot(a.tx - a.x, a.ty - a.y) > 30f * art.k)
        val dist = hypot(a.tx - a.x, a.ty - a.y)
        val wantDir = if (a.tx > a.x) 1f else -1f
        // demi-tour : le cheval pivote au lieu de changer brusquement de sens
        if (moving && dist > 8f * art.k && wantDir != a.dir && a.turn <= 0f && abs(a.tx - a.x) > 12f * art.k) a.turn = 0.001f
        if (a.turn > 0f) {
            a.turn += dt / 0.7f
            if (a.turn >= 0.5f && a.dir != wantDir) a.dir = wantDir
            if (a.turn >= 1f) a.turn = 0f
        }
        val targetSpeed = when {
            !moving || dist < 6f * art.k -> 0f
            a.state == 2 -> if (a.timer > 1.5f) 3.2f else 1.8f
            foal && dist > 60f * art.k -> 2.2f
            else -> 0.75f
        } * (if (a.turn > 0f) 0.3f else 1f)
        a.speed += (targetSpeed - a.speed) * min(1f, dt * 2.5f)
        val gait = when { a.speed < 0.12f -> Gait.ARRET; a.speed < 1.2f -> Gait.PAS; a.speed < 2.4f -> Gait.TROT; else -> Gait.GALOP }
        p.gait = gait
        // cadence liée à la vitesse réelle : pas de glissade des sabots
        val look = Looks.of(h, g.day)
        val sc = art.depthScale(a.y) * horseScale()
        val pxPerSec = a.speed * look.H * sc
        if (gait != Gait.ARRET) {
            val cycle = gait.stride / gait.duty * look.H * sc
            p.phase = (p.phase + pxPerSec / cycle * dt) % 1f
            if (dist > 1f) {
                val ang = atan2(a.ty - a.y, a.tx - a.x)
                a.x += cos(ang) * pxPerSec * dt * (if (a.turn > 0f) 0.3f else 1f)
                a.y += sin(ang) * pxPerSec * dt * 0.6f
            }
        }
        if (dist < 6f * art.k && a.state == 1) { a.state = 0; a.timer = 3f + (h.id % 5) }
        p.breathe = (p.breathe + dt * 0.22f) % 1f
        p.speedBlend += ((if (gait.ordinal >= Gait.GALOP.ordinal) 0.6f else 0f) - p.speedBlend) * min(1f, dt * 2f)
        val lieTarget = if (a.state == 4) 1f else 0f
        p.lie += (lieTarget - p.lie) * min(1f, dt * 0.9f)
        val tNeck = when { p.lie > 0.5f -> 18f; a.state == 0 -> -40f; a.state == 3 -> 60f; a.state == 2 -> 38f; else -> 30f }
        val tHead = when { p.lie > 0.5f -> 40f; a.state == 0 -> 10f; a.state == 3 -> 28f; else -> 45f }
        p.neck += (tNeck - p.neck) * min(1f, dt * 1.8f)
        p.head += (tHead - p.head) * min(1f, dt * 1.8f)
        // en broutant, un pas de temps en temps et des mouvements de tête
        if (a.state == 0 && (t + h.id * 0.37f) % 3.5f < dt) { a.x += a.dir * 5f * art.k; p.phase = (p.phase + 0.25f) % 1f }
        p.ears = if (a.state == 3) 0.9f else sin(t * 0.9f + h.id) * 0.4f
        val hot = g.weather.tempMax > 20
        p.tailSwing = sin(t * (if (hot) 3.2f else 1.3f) + h.id) * (if (hot) 1f else 0.4f)
        p.blink = if ((t + h.id) % 5.1f < 0.14f || p.lie > 0.8f && night()) 1f else 0f
    }

    private fun horseScale() = art.mapH / 1500f

    // ===================================================================== dessin
    override fun draw(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        if (art.mapW <= 1f) resize(w.toInt(), h.toInt())
        val amb = ambienceOf(g)
        gui.onFreeDrag = { dx, _ -> panTarget = -1f; camX = (camX - dx).coerceIn(0f, max(0f, art.mapW - w)) }

        c.save(); c.translate(-camX, 0f)
        art.drawStatic(c, g, amb)
        // têtes aux portes des boxes
        val inBox = g.owned().filter { it.place == Place.BOX && it.weaned }
        hitHorses.clear()
        for ((i, door) in art.boxDoors.withIndex()) {
            val hz = inBox.getOrNull(i) ?: continue
            c.save(); c.clipRect(door)
            val a = Looks.of(hz, g.day)
            val sc = door.height() * 2.2f / a.H
            val pose = HorsePose().apply { neck = 42f + sin(t * 0.5f + hz.id) * 6f; head = 30f; ears = sin(t * 0.8f + hz.id) * 0.6f; blink = if ((t + hz.id) % 4.7f < 0.14f || night()) 1f else 0f }
            HorseArt.draw(c, a, pose, door.left - a.L * 0.46f * sc, door.bottom + a.H * 0.72f * sc, sc, true, amb.light, shadow = false)
            c.restore()
            hitHorses += hz.id to RectF(door.left - camX, door.top, door.right - camX, door.bottom + door.height())
            if (hz.urgent || hz.injured || hz.satiety < 25 || hz.hydration < 30) { gui.p.shader = null; gui.p.color = if (hz.urgent || hz.injured) Pal.RED else Pal.ORANGE; c.drawCircle(door.right - 2f * u, door.top - 3f * u, 3.5f * u, gui.p) }
        }
        // chevaux au paddock, triés par profondeur, puis clôtures avant
        val paddocks = art.paddocks(g.level(BuildingType.PRE))
        for (a in actors.values.sortedBy { it.y }) {
            val hz = g.horse(a.id) ?: continue
            val look = Looks.of(hz, g.day)
            val sc = art.depthScale(a.y) * horseScale()
            c.save()
            if (a.turn > 0f) { val k = abs(cos(PI.toFloat() * a.turn)).coerceAtLeast(0.15f); c.scale(k, 1f, a.x, a.y) }
            HorseArt.draw(c, look, a.pose, a.x, a.y, sc, a.dir > 0, amb.light)
            c.restore()
            val hw = look.L * sc * 0.7f
            hitHorses += hz.id to RectF(a.x - hw - camX, a.y - look.H * sc * 1.25f, a.x + hw - camX, a.y + 4f * u)
            if (hz.urgent || hz.injured) { gui.p.color = Pal.RED; c.drawCircle(a.x, a.y - look.H * sc * 1.35f, 3.5f * u, gui.p) }
        }
        for (pd in paddocks) art.fence(c, pd, back = false)
        // fenêtres éclairées et fumée de la caravane le soir
        if (amb.light < 0.55f) {
            gui.p.color = Color.argb(150, 255, 210, 120)
            for (d in art.boxDoors) c.drawRect(d.left + 2f, d.top + 2f, d.right - 2f, d.top + d.height() * 0.4f, gui.p)
        }
        val chX = art.px(0.13f) + 20f * art.k * art.depthScale(art.py(0.43f)); val chY = art.py(0.43f) - 56f * art.k
        for (i in 0..4) { val ph = (t * 0.25f + i * 0.2f) % 1f; gui.p.color = Color.argb((90 * (1 - ph)).toInt(), 220, 220, 220); c.drawCircle(chX + ph * 18f * u + sin(t + i) * 3f * u, chY - ph * 40f * u, (3f + ph * 8f) * u, gui.p) }
        c.restore()

        // lumière du moment (aube, crépuscule, nuit) et météo
        val night = 1f - amb.light
        if (night > 0.02f) { gui.p.shader = null; gui.p.color = Color.argb((night * 150).toInt(), 18, 26, 60); c.drawRect(0f, 0f, w, h, gui.p) }
        if (amb.golden > 0.05f) { gui.p.color = Color.argb((amb.golden * 50).toInt(), 255, 150, 70); c.drawRect(0f, 0f, w, h, gui.p) }
        Scenery.weather(c, w, h, amb.also { it.hour = 12f }, t)

        // zones cliquables du domaine
        registerMapHits()
        for ((id, r) in hitHorses) gui.hit(r) { app.sound.play(SoundFx.S.WHINNY_SHORT, 0.4f, 1.1f); g.horse(id)?.let { app.push(HorseScreen(app, it)) } }

        drawHud(c, amb)
        if (g.pendingLive.isNotEmpty() && modal == null && report == null) livePrompt(c)
        report?.let { drawReport(c, it) }
        modal?.let { it(c) }
    }

    private fun registerMapHits() {
        val g = game
        val u = gui.u
        for (j in g.junk) {
            val x = art.px(j.x) - camX; val y = art.py(j.y); val s = 26f * art.k * art.depthScale(art.py(j.y))
            gui.hit(RectF(x - s, y - s, x + s, y + 6f * u)) { junkDialog(j) }
        }
        for (pl in art.plots) {
            val x = art.px(pl.fx) - camX; val y = art.py(pl.fy)
            val r = RectF(x - pl.w * art.mapW * 0.55f, y - pl.h * art.mapH * 0.9f, x + pl.w * art.mapW * 0.65f, y + 6f * u)
            gui.hit(r) { plotAction(pl.type) }
        }
        val cx = art.px(0.13f) - camX; val cy = art.py(0.43f)
        gui.hit(RectF(cx - 36f * u, cy - 50f * u, cx + 36f * u, cy + 4f * u)) { app.push(JournalScreen(app)) }
        val bx = art.px(0.1f) - camX; val by = art.py(0.56f)
        gui.hit(RectF(bx - 18f * u, by - 36f * u, bx + 18f * u, by + 2f * u)) { app.push(CompetitionScreen(app)) }
        val mx = art.px(0.15f) - camX; val my = art.py(0.6f)
        gui.hit(RectF(mx - 12f * u, my - 30f * u, mx + 12f * u, my + 2f * u)) { app.push(MarketScreen(app)) }
    }

    // ===================================================================== actions sur la carte
    private fun plotAction(b: BuildingType) {
        val g = game
        app.sound.play(SoundFx.S.CLICK)
        val lvl = g.level(b)
        when {
            b == BuildingType.ECURIE -> modal = { stableDialog(this) }
            b == BuildingType.GRENIER && lvl > 0 -> app.push(ManageScreen(app, 2))
            b == BuildingType.CARRIERE && lvl > 0 -> modal = { arenaDialog(this) }
            b == BuildingType.CLUB_HOUSE && lvl > 0 -> modal = { arenaDialog(this) }
            else -> modal = { buildDialog(this, b) }
        }
    }

    private fun dialogFrame(c: Canvas, wd: Float, ht: Float, title: String): RectF {
        val u = gui.u; val w = gui.w; val h = gui.h
        gui.modal(c)
        val r = RectF(w / 2 - wd * u / 2, h / 2 - ht * u / 2, w / 2 + wd * u / 2, h / 2 + ht * u / 2)
        Ink.parchment(c, r, u)
        gui.text(c, title, r.left + 18f * u, r.top + 30f * u, 18f, Ink.INK, font = Ink.hand)
        gui.button(c, RectF(r.right - 40f * u, r.top + 8f * u, r.right - 10f * u, r.top + 34f * u), "×", size = 16f) { modal = null }
        return r
    }

    private fun buildDialog(c: Canvas, b: BuildingType) {
        val g = game
        val u = gui.u
        val lvl = g.level(b)
        val r = dialogFrame(c, 400f, 210f, if (lvl == 0) b.label else "${b.label} — niveau $lvl")
        gui.wrap(c, b.desc, r.left + 18f * u, r.top + 56f * u, r.width() - 36f * u, 12.5f, Ink.INK)
        if (lvl < b.maxLevel) {
            val cost = b.cost(lvl + 1)
            gui.text(c, "Coût : ${fmtMoney(cost)} · entretien ${b.upkeep * 3} €/mois", r.left + 18f * u, r.bottom - 64f * u, 12f, Pal.LEATHER, font = Ink.hand)
            gui.button(c, RectF(r.right - 200f * u, r.bottom - 50f * u, r.right - 18f * u, r.bottom - 14f * u), if (lvl == 0) "Construire" else "Agrandir", Btn.PRIMARY, enabled = g.money >= cost, size = 14f) {
                val res = g.build(b); toast(res.msg, res.ok); if (res.ok) { modal = null; app.sound.play(SoundFx.S.GOOD) }
            }
        } else gui.text(c, "Niveau maximal atteint.", r.left + 18f * u, r.bottom - 30f * u, 12f, Pal.OK, font = Ink.hand)
    }

    private fun stableDialog(c: Canvas) {
        val g = game
        val u = gui.u
        val lvl = g.level(BuildingType.ECURIE)
        val r = dialogFrame(c, 460f, 240f, if (lvl == 0) "Le vieil abri" else "L'écurie")
        val txt = if (lvl == 0) "Deux boxes branlants, un toit qui fuit… C'est un début. Restaurez-le pour obtenir une vraie écurie de 6 boxes."
                  else "${g.boxes()} boxes · ${g.boxUsers()} occupés${if (g.boarders > 0) " (dont ${g.boarders} pensionnaires)" else ""}."
        gui.wrap(c, txt, r.left + 18f * u, r.top + 56f * u, r.width() - 36f * u, 12.5f, Ink.INK)
        val bw = (r.width() - 48f * u) / 3f
        val y = r.bottom - 54f * u
        gui.button(c, RectF(r.left + 18f * u, y, r.left + 18f * u + bw, y + 38f * u), "Mes chevaux", size = 12.5f) { modal = null; app.push(HorsesScreen(app)) }
        gui.button(c, RectF(r.left + 24f * u + bw, y, r.left + 24f * u + bw * 2, y + 38f * u), "Tournée des soins", Btn.GOLD, size = 12f, sub = "repas, eau, litière") { modal = null; careRound() }
        val nb = BuildingType.ECURIE
        gui.button(c, RectF(r.left + 30f * u + bw * 2, y, r.right - 18f * u, y + 38f * u), if (lvl == 0) "Restaurer" else "Agrandir", Btn.PRIMARY, enabled = lvl < nb.maxLevel && g.money >= nb.cost(lvl + 1), size = 12.5f, sub = if (lvl < nb.maxLevel) fmtMoney(nb.cost(lvl + 1)) else "max") {
            val res = g.build(nb); toast(res.msg, res.ok); if (res.ok) { modal = null; app.sound.play(SoundFx.S.GOOD) }
        }
    }

    /** Tournée du matin et du soir : nourrir, abreuver, curer tous les boxes (le temps dépend du nombre de chevaux). */
    private fun careRound() {
        val g = game
        val hs = g.owned().filter { it.weaned && it.place != Place.DEPLACEMENT }
        if (hs.isEmpty()) { toast("Aucun cheval à soigner.", false); return }
        val hours = 0.25f + hs.size * 0.2f
        if (!timeGate(app, hours)) return
        var fed = 0
        for (h in hs) {
            if (g.feed(h).ok) fed++
            g.water(h)
            if (h.place == Place.BOX && h.litter < 70) g.muck(h)
            Looks.invalidate(h.id)
        }
        app.sound.play(SoundFx.S.MUNCH)
        toast(if (fed == hs.size) "Tournée faite : ${hs.size} chevaux nourris, abreuvés et litières refaites (${fmt1(hours)} h)." else "Tournée faite, mais il manque du foin pour ${hs.size - fed} cheval(aux) : passez à la grange !", fed == hs.size)
    }

    private fun arenaDialog(c: Canvas) {
        val g = game
        val u = gui.u
        val r = dialogFrame(c, 520f, 280f, "Donner un cours d'équitation")
        val horses = g.owned().filter { it.backed && !it.injured }
        gui.wrap(c, "1 h 30 · ${g.lessonStudents(max(1, lessonPick.size))} élève(s) attendu(s) · ${g.lessonPrice()} € par élève. Choisissez des chevaux calmes et en confiance : les élèves seront ravis.", r.left + 18f * u, r.top + 54f * u, r.width() - 36f * u, 11.5f, Ink.INK)
        val list = RectF(r.left + 14f * u, r.top + 88f * u, r.right - 14f * u, r.bottom - 54f * u)
        gui.beginScroll(c, "lesson", list, horses.size * 30f * u)
        var y = list.top
        for (hz in horses) {
            val on = hz.id in lessonPick
            gui.button(c, RectF(list.left, y, list.right - 6f * u, y + 26f * u), "${if (on) "☑" else "☐"}  ${hz.name} — confiance ${hz.confidence.toInt()} · énergie ${hz.energy.toInt()}", if (on) Btn.TAB_ON else Btn.GHOST, size = 11f) {
                if (on) lessonPick.remove(hz.id) else lessonPick.add(hz.id)
            }
            y += 30f * u
        }
        if (horses.isEmpty()) gui.text(c, "Aucun cheval débourré disponible.", list.left, list.top + 18f * u, 12f, Pal.RED)
        gui.endScroll(c, "lesson")
        gui.button(c, RectF(r.left + 18f * u, r.bottom - 46f * u, r.left + 200f * u, r.bottom - 12f * u), "Entraîner un cheval", size = 12f) { modal = null; app.push(HorsesScreen(app)) }
        gui.button(c, RectF(r.right - 200f * u, r.bottom - 46f * u, r.right - 18f * u, r.bottom - 12f * u), "Donner le cours", Btn.PRIMARY, enabled = lessonPick.isNotEmpty(), size = 13f) {
            val res = g.giveLesson(g.owned().filter { it.id in lessonPick })
            toast(res.msg, res.ok)
            if (res.ok) { modal = null; app.sound.play(SoundFx.S.COIN) }
        }
    }

    private fun junkDialog(j: Junk) {
        app.sound.play(SoundFx.S.CLICK)
        modal = {
            val u = gui.u
            val r = dialogFrame(this, 380f, 170f, j.label)
            gui.wrap(this, "Débarrasser ce coin du domaine prendra environ ${fmt1(j.hours)} h. Qui sait ce qu'on trouvera dessous ?", r.left + 18f * u, r.top + 56f * u, r.width() - 36f * u, 12.5f, Ink.INK)
            gui.button(this, RectF(r.right - 200f * u, r.bottom - 48f * u, r.right - 18f * u, r.bottom - 14f * u), "Nettoyer (${fmt1(j.hours)} h)", Btn.PRIMARY, size = 13f) {
                val res = game.cleanJunk(j.id)
                toast(res.msg, res.ok)
                if (res.ok) { modal = null; app.sound.play(SoundFx.S.GOOD, 0.6f) }
            }
        }
    }

    private fun toast(msg: String, ok: Boolean) { gui.toast(msg, if (ok) Pal.GREEN else Pal.LEATHER); if (!ok) app.sound.play(SoundFx.S.BAD, 0.4f) }

    // ===================================================================== interface en bois
    private fun drawHud(c: Canvas, amb: Ambience) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        // Menu
        gui.button(c, RectF(10f * u, 8f * u, 92f * u, 36f * u), "Menu", Btn.GOLD, size = 13f) { modal = { menuDialog(this) } }
        // météo
        val icon = when (g.weather.sky) { Sky.SOLEIL -> if (amb.light > 0.4f) "☀" else "☾"; Sky.NUAGEUX -> "☁"; Sky.PLUIE -> "☂"; Sky.ORAGE -> "⚡"; Sky.NEIGE -> "❄"; Sky.BROUILLARD -> "≋"; Sky.TEMPETE -> "≈" }
        val wr = RectF(10f * u, 40f * u, 150f * u, 60f * u)
        Ink.parchment(c, wr, u)
        gui.text(c, "$icon ${g.weather.tempAt(g.hourOfDay).toInt()}°C · ${g.weather.sky.label}", wr.left + 8f * u, wr.bottom - 6f * u, 10f, Ink.INK, maxW = wr.width() - 12f * u)
        // enseigne
        Ink.banner(c, RectF(172f * u, 8f * u, w - 352f * u, 40f * u), g.stableName, u, gui)
        // objectifs, nouvelle journée, calendrier, pièces
        val claimable = Goals.active(g).any { it.done(g) }
        val gx = w - 330f * u
        gui.button(c, RectF(gx, 8f * u, gx + 96f * u, 36f * u), "Objectifs", if (claimable) Btn.PRIMARY else Btn.NORMAL, size = 12.5f) { app.sound.play(SoundFx.S.PAGE); modal = { goalsDialog(this) } }
        if (claimable) { gui.p.shader = null; gui.p.color = Pal.RED; c.drawCircle(gx + 92f * u, 10f * u, 5f * u, gui.p) }
        gui.button(c, RectF(gx + 102f * u, 8f * u, gx + 222f * u, 36f * u), "Nouvelle journée", Btn.NORMAL, size = 12f) { newDay() }
        // calendrier à spirale
        val cal = RectF(w - 104f * u, 4f * u, w - 58f * u, 50f * u)
        Ink.parchment(c, cal, u)
        gui.p.color = Pal.RED; c.drawRect(cal.left, cal.top, cal.right, cal.top + 9f * u, gui.p)
        for (i in 0..4) { gui.sp.color = Ink.INK; gui.sp.strokeWidth = 1.2f * u; c.drawCircle(cal.left + 6f * u + i * 8.5f * u, cal.top, 2.4f * u, gui.sp) }
        gui.text(c, "Jour", cal.centerX(), cal.top + 20f * u, 8.5f, Ink.INK, Paint.Align.CENTER, Ink.handN)
        gui.text(c, "${(g.stats["days"] ?: 0) + 1}", cal.centerX(), cal.top + 38f * u, 15f, Ink.INK, Paint.Align.CENTER, Ink.hand)
        gui.hit(cal) { modal = { calendarDialog(this) } }
        // argent
        val mr = RectF(w - 54f * u, 6f * u, w - 6f * u, 48f * u)
        Ink.parchment(c, mr, u)
        Ink.coin(c, mr.centerX(), mr.top + 13f * u, 7f * u, u)
        gui.text(c, compactMoney(g.money), mr.centerX(), mr.bottom - 8f * u, 10f, if (g.money < 0) Pal.RED else Ink.INK, Paint.Align.CENTER, Ink.hand, maxW = mr.width() - 4f * u)
        // horloge de la journée
        val hh = g.hourOfDay.toInt(); val mm = ((g.hourOfDay - hh) * 60).toInt() / 15 * 15
        val cr = RectF(w - 230f * u, 40f * u, w - 58f * u, 60f * u)
        Ink.parchment(c, cr, u)
        val left = g.hoursLeft()
        gui.text(c, "%02dh%02d · ${Cal.formatShort(g.day)} · ${if (left > 0.1f) "reste ${fmt1(left)} h" else "il est tard !"}".format(hh, mm), cr.centerX(), cr.bottom - 6f * u, 10f, if (left < 1.5f) Pal.RED else Ink.INK, Paint.Align.CENTER, maxW = cr.width() - 8f * u)
        // flèches de défilement
        gui.button(c, RectF(6f * u, h / 2 - 22f * u, 34f * u, h / 2 + 22f * u), "‹", Btn.GHOST, size = 20f) { panTarget = (camX - w * 0.7f).coerceIn(0f, max(0f, art.mapW - w)) }
        gui.button(c, RectF(w - 34f * u, h / 2 - 22f * u, w - 6f * u, h / 2 + 22f * u), "›", Btn.GHOST, size = 20f) { panTarget = (camX + w * 0.7f).coerceIn(0f, max(0f, art.mapW - w)) }
        // étagère du bas
        val shelf = RectF(10f * u, h - 46f * u, w - 10f * u, h - 6f * u)
        Ink.plank(c, shelf, 6f * u, u, Ink.WOOD_D)
        val items = listOf(
            "Chevaux" to { app.push(HorsesScreen(app)) }, "Concours" to { app.push(CompetitionScreen(app)) },
            "Élevage" to { app.push(BreedingScreen(app)) }, "Marché" to { app.push(MarketScreen(app)) },
            "Domaine" to { app.push(ManageScreen(app)) }, "Journal" to { app.push(JournalScreen(app)) },
        )
        val bw = (shelf.width() - 12f * u) / items.size
        for ((i, it) in items.withIndex()) {
            val br = RectF(shelf.left + 6f * u + i * bw, shelf.top + 5f * u, shelf.left + (i + 1) * bw, shelf.bottom - 5f * u)
            gui.button(c, br, it.first, Btn.NORMAL, size = 12.5f) { app.sound.play(SoundFx.S.PAGE, 0.6f); it.second() }
            if ((i == 5 && g.unread > 0) || (i == 3 && g.offers.isNotEmpty())) { gui.p.color = Pal.RED; c.drawCircle(br.right - 6f * u, br.top + 4f * u, 4f * u, gui.p) }
        }
        // alertes
        var ay = 66f * u
        for (hz in g.owned()) {
            val msg = when {
                hz.urgent -> "⚠ ${hz.name} : ${hz.ailments.first { it.type.urgent && !it.treated }.type.label} !"
                hz.weaned && hz.satiety < 22 -> "${hz.name} a faim"
                hz.weaned && hz.hydration < 28 -> "${hz.name} a soif"
                else -> null
            } ?: continue
            val tw = gui.textW(msg, 10.5f) + 18f * u
            val ar = RectF(10f * u, ay, 10f * u + tw, ay + 19f * u)
            Ink.plank(c, ar, 4f * u, u, Color.rgb(166, 66, 50), nails = false)
            gui.text(c, msg, ar.left + 9f * u, ar.bottom - 6f * u, 10.5f, Pal.CREAM)
            gui.hit(ar) { app.push(HorseScreen(app, hz)) }
            ay += 23f * u
            if (ay > h * 0.5f) break
        }
    }

    private fun compactMoney(v: Int): String = when {
        kotlin.math.abs(v) >= 1_000_000 -> "${"%.1f".format(v / 1e6)} M"
        kotlin.math.abs(v) >= 10_000 -> "${v / 1000} k"
        else -> v.toString()
    }

    private fun newDay() {
        val g = game
        app.sound.play(SoundFx.S.CLICK)
        val before = g.messages.size
        val firstMsg = g.messages.firstOrNull()
        g.sleepUntilMorning()
        app.save()
        val fresh = g.messages.takeWhile { it !== firstMsg }.filter { it.kind != MsgKind.INFO || it.horseId >= 0 }.map { it.text }
        val lines = ArrayList<String>()
        lines += "${Cal.weekday(g.day).replaceFirstChar { it.uppercase() }} ${Cal.format(g.day)} — ${g.weather.sky.label.lowercase()}, ${g.weather.tempMin.toInt()} à ${g.weather.tempMax.toInt()} °C."
        val hungry = g.owned().count { it.weaned && it.satiety < 30 }
        if (hungry > 0) lines += "$hungry cheval(aux) ont faim : commencez par la tournée des soins."
        lines.addAll(fresh.take(8))
        if (before == g.messages.size && fresh.isEmpty()) lines += "Une nuit calme au domaine."
        report = lines
        app.sound.play(SoundFx.S.NEIGH, 0.4f)
    }

    private fun drawReport(c: Canvas, lines: List<String>) {
        val u = gui.u
        val r = RectF(gui.w / 2 - 250f * u, 50f * u, gui.w / 2 + 250f * u, gui.h - 50f * u)
        gui.modal(c)
        Ink.parchment(c, r, u)
        gui.text(c, "Bonjour ! Il est 7 h.", r.left + 20f * u, r.top + 32f * u, 18f, Ink.INK, font = Ink.hand)
        var y = r.top + 58f * u
        for (l in lines) { y += gui.wrap(c, "• $l", r.left + 20f * u, y, r.width() - 40f * u, 11.5f, Ink.INK) + 2f * u; if (y > r.bottom - 60f * u) break }
        gui.button(c, RectF(r.right - 190f * u, r.bottom - 48f * u, r.right - 18f * u, r.bottom - 14f * u), "Au travail !", Btn.PRIMARY, size = 14f) { report = null }
    }

    private fun goalsDialog(c: Canvas) {
        val g = game
        val u = gui.u
        val r = dialogFrame(c, 520f, 290f, "Objectifs")
        var y = r.top + 52f * u
        for (goal in Goals.active(g)) {
            val done = goal.done(g)
            val row = RectF(r.left + 16f * u, y, r.right - 16f * u, y + 62f * u)
            Ink.parchment(c, row, u, if (done) Color.rgb(222, 236, 200) else Ink.PARCH_D)
            gui.text(c, (if (done) "✔ " else "○ ") + goal.title, row.left + 10f * u, row.top + 20f * u, 14f, Ink.INK, font = Ink.hand)
            gui.text(c, goal.desc, row.left + 10f * u, row.top + 38f * u, 11f, Ink.INK, maxW = row.width() - 170f * u)
            gui.text(c, "Récompense : ${fmtMoney(goal.reward)}", row.left + 10f * u, row.top + 54f * u, 10f, Pal.LEATHER)
            gui.button(c, RectF(row.right - 140f * u, row.top + 14f * u, row.right - 10f * u, row.bottom - 14f * u), if (done) "Réclamer" else "En cours…", Btn.PRIMARY, enabled = done, size = 12.5f) {
                val res = g.claimGoal(goal); toast(res.msg, res.ok); if (res.ok) { app.sound.play(SoundFx.S.COIN); app.sound.play(SoundFx.S.GOOD) }
            }
            y += 70f * u
        }
        gui.text(c, "${g.goalsDone.size}/${Goals.ALL.size} objectifs accomplis", r.left + 18f * u, r.bottom - 14f * u, 10.5f, Ink.INK)
    }

    private fun calendarDialog(c: Canvas) {
        val g = game
        val u = gui.u
        val r = dialogFrame(c, 460f, 250f, Cal.format(g.day))
        val entries = g.ownEntries().take(5)
        var y = r.top + 58f * u
        gui.text(c, "Saison : ${Cal.season(g.day).label} · lever ${fmt1(Cal.sunrise(g.day))} h · coucher ${fmt1(Cal.sunset(g.day))} h", r.left + 18f * u, y, 11.5f, Ink.INK); y += 22f * u
        if (entries.isEmpty()) gui.text(c, "Aucun concours prévu. Le tableau d'affichage à l'entrée liste les épreuves.", r.left + 18f * u, y, 11.5f, Ink.INK, maxW = r.width() - 36f * u)
        for ((e, en) in entries) { gui.text(c, "${Cal.formatShort(e.day)} · ${g.horse(en.horseId)?.name} · ${e.name}", r.left + 18f * u, y, 11.5f, Ink.INK, maxW = r.width() - 36f * u); y += 18f * u }
        g.owned().filter { it.pregnancy?.confirmed == true }.forEach { m -> gui.text(c, "${m.name} doit pouliner vers le ${Cal.formatShort(m.pregnancy!!.dueDay)}", r.left + 18f * u, y, 11.5f, Pal.OK); y += 18f * u }
    }

    private fun menuDialog(c: Canvas) {
        val u = gui.u
        val r = dialogFrame(c, 300f, 250f, "Menu")
        val bw = r.width() - 36f * u
        var y = r.top + 50f * u
        gui.button(c, RectF(r.left + 18f * u, y, r.left + 18f * u + bw, y + 34f * u), "Guide du cavalier", size = 13f) { modal = null; app.push(HelpScreen(app)) }; y += 40f * u
        gui.button(c, RectF(r.left + 18f * u, y, r.left + 18f * u + bw, y + 34f * u), if (app.sound.enabled) "Son : activé" else "Son : coupé", size = 13f) { app.toggleSound() }; y += 40f * u
        gui.button(c, RectF(r.left + 18f * u, y, r.left + 18f * u + bw, y + 34f * u), "Gestion du domaine", size = 13f) { modal = null; app.push(ManageScreen(app)) }; y += 40f * u
        gui.button(c, RectF(r.left + 18f * u, y, r.left + 18f * u + bw, y + 34f * u), "Sauvegarder et quitter", Btn.DANGER, size = 13f) { modal = null; app.save(); app.replaceAll(MenuScreen(app)) }
    }

    private fun livePrompt(c: Canvas) {
        val g = game
        val u = gui.u
        val (eid, hid) = g.pendingLive.first()
        val e = g.events.firstOrNull { it.id == eid } ?: return
        val hz = g.horse(hid) ?: return
        gui.modal(c)
        val r = RectF(gui.w / 2 - 210f * u, gui.h / 2 - 95f * u, gui.w / 2 + 210f * u, gui.h / 2 + 95f * u)
        Ink.parchment(c, r, u)
        gui.text(c, "Jour de concours !", r.left + 18f * u, r.top + 32f * u, 18f, Ink.INK, font = Ink.hand)
        gui.wrap(c, "${hz.name} est arrivé à ${e.venue} pour « ${e.name} ». La cloche va sonner : à vous de monter !", r.left + 18f * u, r.top + 58f * u, r.width() - 36f * u, 12f, Ink.INK)
        gui.button(c, RectF(r.left + 18f * u, r.bottom - 50f * u, r.centerX() - 6f * u, r.bottom - 14f * u), "Laisser le jury juger", size = 12f) {
            g.pendingLive.removeAll { it.first == eid && it.second == hid }
        }
        gui.button(c, RectF(r.centerX() + 6f * u, r.bottom - 50f * u, r.right - 18f * u, r.bottom - 14f * u), "Entrer en piste", Btn.PRIMARY, size = 13f) {
            app.sound.play(SoundFx.S.BELL)
            app.push(RideScreen(app, hz, RideMode.forEvent(e), e.id))
        }
    }

    override fun onBack(): Boolean {
        if (modal != null) { modal = null; return true }
        if (report != null) { report = null; return true }
        return false
    }
}
