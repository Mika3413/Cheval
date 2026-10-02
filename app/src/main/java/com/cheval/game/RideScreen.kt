package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.cheval.core.Breed
import com.cheval.core.CompEvent
import com.cheval.core.CompResult
import com.cheval.core.Discipline
import com.cheval.core.Exercise
import com.cheval.core.Game
import com.cheval.core.Horse
import com.cheval.core.Levels
import com.cheval.core.LivePerformance
import com.cheval.core.Personality
import com.cheval.core.Rng
import com.cheval.core.Sex
import com.cheval.core.Trait
import com.cheval.core.fmtMoney
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class RideKind { BALADE, PLAT, DRESSAGE, OBSTACLES, CROSS, PISTE }

class RideMode(val kind: RideKind, val competition: Boolean, val level: Int = 0, val distance: Int = 0) {
    companion object {
        fun forExercise(ex: Exercise) = when (ex) {
            Exercise.EXTERIEUR, Exercise.FOND -> RideMode(RideKind.BALADE, false)
            Exercise.DRESSAGE -> RideMode(RideKind.DRESSAGE, false, 1)
            Exercise.GYMNASTIQUE -> RideMode(RideKind.OBSTACLES, false, 0)
            Exercise.PARCOURS -> RideMode(RideKind.OBSTACLES, false, 1)
            Exercise.CROSS -> RideMode(RideKind.CROSS, false, 1)
            Exercise.GALOP -> RideMode(RideKind.PISTE, false, 0, 1000)
            else -> RideMode(RideKind.PLAT, false)
        }
        fun forEvent(e: CompEvent) = when (e.discipline) {
            Discipline.CSO -> RideMode(RideKind.OBSTACLES, true, e.level)
            Discipline.DRESSAGE -> RideMode(RideKind.DRESSAGE, true, e.level)
            Discipline.COMPLET -> RideMode(RideKind.CROSS, true, e.level)
            Discipline.COURSE -> RideMode(RideKind.PISTE, true, e.level, e.distance)
            else -> RideMode(RideKind.PLAT, true, e.level)
        }
        val LIVE = setOf(Discipline.CSO, Discipline.DRESSAGE, Discipline.COMPLET, Discipline.COURSE)
    }
}

/**
 * Monter à cheval : balade libre (bocage, forêt, plage), carrière, reprise de dressage, parcours d'obstacles,
 * cross et course. Le cheval répond selon son niveau, sa forme, son caractère et la précision du cavalier.
 */
class RideScreen(app: GameView, private val horse: Horse, private val mode: RideMode, private val eventId: Int = -1, private val exercise: Exercise? = null) : Screen(app) {
    private val game get() = app.game!!
    private val rng = Rng(System.nanoTime())
    private val app2 = app
    private var t = 0f
    private val pose = HorsePose()
    private val tack = Tack(saddle = true, bridle = true, rider = true, number = if (mode.competition) 1 + rng.int(98) else 0)
    private val a get() = Looks.of(horse, game.day)

    // ---------------- état du cheval monté
    private var dist = 0f            // mètres parcourus
    private var v = 0f               // vitesse (m/s)
    private var gaitIdx = 1          // 0 arrêt … 4 grand galop
    private var stamina = 100f
    private var rideTime = 0f
    private var gaitChanges = 0
    private var finished = false
    private var resultText = ""
    private var resultSub = ""
    private var quality = 0f
    private var spook = 0f
    private var push = 0f
    private val skillD = when (mode.kind) { RideKind.DRESSAGE, RideKind.PLAT -> Discipline.DRESSAGE; RideKind.OBSTACLES -> Discipline.CSO; RideKind.CROSS -> Discipline.COMPLET; RideKind.PISTE -> Discipline.COURSE; RideKind.BALADE -> Discipline.ENDURANCE }
    private val hSkill = horse.skill(skillD)
    private val rSkill = game.rider.skill(skillD)
    private val maxSpeed = 13f + horse.pot(Trait.VITESSE) / 100f * 5f * horse.ageFactor(game.day)

    // ---------------- obstacles
    private class Fence(val x: Float, val height: Float, val oxer: Boolean, val natural: Int, val color: Int) {
        var state = 0 // 0 à venir, 1 sauté net, 2 barre tombée, 3 refus
        var refusals = 0
    }
    private val fences = ArrayList<Fence>()
    private var faults = 0
    private var refusals = 0
    private var eliminated = false
    private var jumpStart = -1f
    private var jumpLen = 4f
    private var jumpFence: Fence? = null
    private var jumpQuality = 0f
    private var courseLen = 0f
    private var timeAllowed = 0f

    // ---------------- dressage
    private class Movement(val at: Float, val gait: Int, val text: String) { var score = -1f }
    private val movements = ArrayList<Movement>()
    private var lastGaitChangeAt = 0f

    // ---------------- course
    private class Rival(val name: String, val pace: Float, val kick: Float, val stamina: Float, val look: Appearance, val lane: Int) {
        var d = 0f; var v = 0f; var st = 100f; val pose = HorsePose(); var finish = -1f
    }
    private val rivals = ArrayList<Rival>()
    private var finishTime = -1f

    private var msg = ""
    private var msgT = 0f

    init {
        pose.gait = Gait.PAS
        stamina = horse.energy
        when (mode.kind) {
            RideKind.OBSTACLES -> setupJumps(false)
            RideKind.CROSS -> setupJumps(true)
            RideKind.DRESSAGE, RideKind.PLAT -> setupDressage()
            RideKind.PISTE -> setupRace()
            RideKind.BALADE -> {}
        }
        if (mode.competition) app.sound.play(SoundFx.S.BELL)
        say(intro())
    }

    private fun intro() = when (mode.kind) {
        RideKind.BALADE -> "Balade libre : accélérez, ralentissez, profitez du paysage. Rentrez quand vous voulez."
        RideKind.PLAT -> "Travail sur le plat : enchaînez les transitions demandées au bon endroit."
        RideKind.DRESSAGE -> "Reprise : changez d'allure exactement à la lettre indiquée."
        RideKind.OBSTACLES -> "Abordez chaque obstacle au galop et appuyez sur SAUTER au point de battue (zone verte)."
        RideKind.CROSS -> "Cross : gardez un bon galop, sautez au bon moment, attention au gué !"
        RideKind.PISTE -> if (mode.competition) "Départ ! Gérez l'effort : poussez dans la ligne droite finale." else "Galop d'entraînement : tenez un rythme régulier."
    }

    private fun say(s: String) { msg = s; msgT = 4f }

    // ===================================================================== mise en place
    private fun setupJumps(natural: Boolean) {
        val n = if (mode.competition) (if (natural) 10 else 8 + mode.level / 2) else if (mode.level == 0) 5 else 8
        val baseH = if (mode.competition) (if (natural) Levels.CSO_HEIGHT[mode.level] - 0.1f else Levels.CSO_HEIGHT[mode.level]) else if (mode.level == 0) 0.7f else min(1.2f, 0.8f + hSkill / 100f * 0.6f)
        var x = 40f
        val cols = intArrayOf(Color.rgb(200, 40, 40), Color.rgb(30, 90, 170), Color.rgb(230, 160, 30), Color.rgb(40, 140, 70), Color.rgb(120, 60, 150))
        for (i in 0 until n) {
            val hgt = baseH * (if (i == n - 1) 1.03f else rng.range(0.9f, 1f))
            val natType = if (natural) rng.int(4) + 1 else 0
            fences += Fence(x, hgt, !natural && rng.chance(0.4f), natType, cols[i % cols.size])
            x += if (natural) rng.range(55f, 90f) else rng.range(26f, 40f)
        }
        courseLen = x + 20f
        val speed = if (natural) 9.5f else 5.8f // 570 m/min au cross, 350 m/min en CSO
        timeAllowed = courseLen / speed
    }

    private fun setupDressage() {
        val names = listOf("A", "K", "E", "H", "C", "M", "B", "F")
        val n = if (mode.kind == RideKind.DRESSAGE) 10 + mode.level else 7
        var x = 30f
        val gaitNames = listOf("à l'arrêt", "au pas", "au trot", "au galop")
        var prev = 1
        for (i in 0 until n) {
            var g = rng.range(0, 3)
            if (g == prev) g = (g + 1 + rng.int(2)) % 4
            if (g == 0 && mode.level < 1 && rng.chance(0.5f)) g = 2
            movements += Movement(x, g, "En ${names[i % names.size]} : ${gaitNames[g]}")
            prev = g
            x += rng.range(22f, 34f)
        }
        courseLen = x + 15f
    }

    private fun setupRace() {
        courseLen = (if (mode.competition) mode.distance.toFloat() else 1000f).coerceAtLeast(800f)
        if (!mode.competition) return
        val n = 6
        val g = Game(rng.nextLong())
        for (i in 0 until n) {
            val h = g.generateHorse(Breed.PUR_SANG, if (rng.chance(0.5f)) Sex.ETALON else Sex.JUMENT, 4, withAncestors = false)
            val q = (Levels.EXPECT[mode.level] + rng.gauss(0.0, 8.0).toFloat()) / 100f
            rivals += Rival(h.name, 14.2f + q * 3.2f, 0.6f + rng.float() * 0.8f, 70f + q * 40f, Appearance.of(h, g.day), i)
        }
    }

    // ===================================================================== mise à jour
    private val gaitSpeeds get() = floatArrayOf(0f, 1.7f, 3.6f, if (mode.kind == RideKind.CROSS) 9f else 6.2f, maxSpeed)

    override fun update(dt: Float) {
        t += dt
        msgT -= dt
        if (finished) { idleAnim(dt); return }
        rideTime += dt
        // vitesse cible selon l'allure demandée, la forme et le caractère
        var target = gaitSpeeds[gaitIdx]
        if (stamina < 15f && gaitIdx >= 3) { target = gaitSpeeds[2] + 1f; if (rng.chance(dt * 0.5f)) say("${horse.name} est essoufflé…") }
        if (mode.kind == RideKind.PISTE && gaitIdx == 4) target *= (0.86f + horse.skill(Discipline.COURSE) / 100f * 0.14f) * (1f + push * 0.06f)
        if (spook > 0f) { target = 0f; spook -= dt }
        val acc = if (target > v) 2.2f + gaitIdx * 0.5f else 4.5f
        v += (target - v).coerceIn(-acc * dt, acc * dt)
        dist += v * dt
        // effort
        val cost = when { v < 2f -> -1.5f; v < 4.5f -> 0.25f; v < 8f -> 0.55f; else -> 1.6f + (v - 12f).coerceAtLeast(0f) * 0.4f } * (1.4f - horse.fitness / 160f) * (1.25f - horse.pot(Trait.ENDURANCE) / 250f)
        stamina = (stamina - cost * dt * (1f + push * 1.5f)).coerceIn(0f, 100f)
        push = max(0f, push - dt)
        // allure affichée et cadence des membres
        val gait = when { v < 0.3f -> Gait.ARRET; v < 2.6f -> Gait.PAS; v < 4.8f -> Gait.TROT; v < 10f -> Gait.GALOP; else -> Gait.GRAND_GALOP }
        if (jumpStart < 0f) pose.gait = gait
        val H = a.H / 100f
        val cycleDist = if (gait == Gait.ARRET) 1f else gait.stride / gait.duty * H
        val oldPhase = pose.phase
        pose.phase = (pose.phase + (if (gait == Gait.ARRET) dt * 0.2f else v / cycleDist * dt)) % 1f
        if (gait != Gait.ARRET && jumpStart < 0f) hoofSounds(oldPhase, pose.phase, gait)
        pose.speedBlend += (((v - 4f) / 10f).coerceIn(0f, 1f) - pose.speedBlend) * dt * 2f
        pose.neck += ((if (gait == Gait.GRAND_GALOP) 28f else if (gait == Gait.ARRET) 48f else 40f) - pose.neck) * dt * 2f
        pose.head += ((if (gait == Gait.GRAND_GALOP) 55f else if (mode.kind == RideKind.DRESSAGE) 22f else 35f) - pose.head) * dt * 2f
        pose.ears = if (jumpFence != null || nextFence()?.let { it.x - dist < 15f } == true) 1f else sin(t * 0.8f) * 0.4f
        pose.tailSwing = sin(t * 2f) * 0.5f
        tack.riderForward += ((if (jumpStart >= 0f || gait == Gait.GRAND_GALOP) 1f else if (gait == Gait.GALOP && mode.kind != RideKind.DRESSAGE) 0.45f else 0f) - tack.riderForward) * dt * 4f
        tack.riderPost = if (gait == Gait.TROT && mode.kind != RideKind.DRESSAGE) (sin(pose.phase * 6.283f * 2f) * 0.5f + 0.5f) else 0f
        // peur soudaine (cheval peureux en extérieur)
        if (mode.kind == RideKind.BALADE && Personality.PEUREUX in horse.personality && spook <= 0f && rng.chance(dt * 0.02f)) {
            spook = 1.4f; say("Un faisan s'envole ! ${horse.name} fait un écart."); app.sound.play(SoundFx.S.SNORT)
        }
        when (mode.kind) {
            RideKind.OBSTACLES, RideKind.CROSS -> updateJumps(dt)
            RideKind.DRESSAGE, RideKind.PLAT -> updateDressage()
            RideKind.PISTE -> updateRace(dt)
            RideKind.BALADE -> {}
        }
    }

    private fun idleAnim(dt: Float) {
        v = max(0f, v - dt * 4f)
        if (v < 0.3f) pose.gait = Gait.ARRET
        pose.phase = (pose.phase + dt * 0.5f) % 1f
        pose.breathe = (pose.breathe + dt * 0.3f) % 1f
        pose.neck += (35f - pose.neck) * dt
    }

    private fun hoofSounds(a0: Float, a1: Float, g: Gait) {
        for (o in g.offsets) {
            val crossed = if (a1 >= a0) (o in a0..a1) else (o >= a0 || o <= a1)
            if (crossed) {
                val hard = mode.kind == RideKind.PISTE
                app2.sound.play(if (hard) SoundFx.S.HOOF_HARD else SoundFx.S.HOOF_SOFT, 0.25f + 0.1f * g.ordinal, 0.9f + rng.float() * 0.2f, 30)
            }
        }
    }

    private fun nextFence(): Fence? = fences.firstOrNull { it.state == 0 && it.x > dist - 1f }

    private fun idealTakeoff(f: Fence) = 1.2f + f.height * 1.1f + (if (f.oxer) 0.3f else 0f)
    private fun window() = 0.6f + hSkill / 100f * 1.1f + rSkill / 100f * 0.5f

    private fun updateJumps(dt: Float) {
        if (jumpStart >= 0f) {
            val k = (dist - jumpStart) / jumpLen
            pose.jump = k.coerceIn(0f, 1f)
            pose.jumpHeight = jumpFence?.height ?: 1f
            if (k >= 1f) landJump()
            return
        }
        pose.jump = -1f
        val f = nextFence() ?: run { if (dist >= courseLen) finishCourse(); return }
        val d = f.x - dist
        // refus : l'obstacle arrive sans ordre de sauter, ou trop lentement
        val tooSlow = (if (f.height > 0.9f) 4.4f else 3f) > v
        if (d < 0.4f) {
            refuse(f, if (tooSlow) "trop lent" else "pas d'appel")
        }
    }

    /** Le joueur demande le saut. */
    private fun requestJump() {
        if (jumpStart >= 0f || finished) return
        val f = nextFence() ?: return
        val d = f.x - dist
        val ideal = idealTakeoff(f)
        if (d > ideal + 6f) { say("Trop tôt ! Attendez la zone verte."); return }
        val err = abs(d - ideal)
        val w = window()
        jumpQuality = (1f - err / (w * 2.2f)).coerceIn(0f, 1f)
        // refus selon le courage, l'allure et la précision
        val courage = horse.pot(Trait.COURAGE) / 100f + (if (Personality.COURAGEUX in horse.personality) 0.2f else 0f) - (if (Personality.PEUREUX in horse.personality) 0.15f else 0f)
        val tooSlow = (if (f.height > 0.9f) 4.4f else 3f) > v
        val pRefuse = ((if (tooSlow) 0.55f else 0.04f) + (1f - jumpQuality) * 0.35f + max(0f, f.height - hSkill / 100f * 1.7f) * 0.5f) * (1.3f - courage) * (if (stamina < 15f) 1.6f else 1f)
        if (rng.chance(pRefuse.coerceIn(0f, 0.9f))) { refuse(f, if (tooSlow) "trop lent" else "mal présenté"); return }
        jumpStart = dist
        jumpLen = 2.4f + f.height * 2.6f + d * 0.4f
        jumpFence = f
        tack.riderForward = 1f
        app.sound.play(SoundFx.S.JUMP, 0.6f)
    }

    private fun refuse(f: Fence, why: String) {
        f.refusals++; refusals++
        app.sound.play(SoundFx.S.SNORT, 0.8f)
        v = 0f; dist = f.x - 14f
        spook = 0.8f
        if (mode.kind == RideKind.CROSS) faults += 20 else faults += 4
        val elimAt = if (mode.kind == RideKind.CROSS) 3 else 2
        if (refusals >= elimAt && mode.competition) { eliminated = true; f.state = 3; say("Deuxième refus : élimination."); finishCourse(); return }
        say("Refus ($why) ! Reprenez de l'élan et représentez-vous.")
    }

    private fun landJump() {
        val f = jumpFence ?: return
        jumpStart = -1f; jumpFence = null; pose.jump = -1f
        // probabilité de faire tomber une barre
        val ability = (hSkill * 0.6f + horse.pot(Trait.TECHNIQUE) * 0.4f) / 100f
        val heightStress = (f.height / (0.7f + ability * 1.1f)).coerceIn(0.4f, 1.6f)
        val tired = if (stamina < 25f) 0.15f else 0f
        val pRail = (0.04f + (1f - jumpQuality) * 0.45f * heightStress + tired + max(0f, heightStress - 1f) * 0.4f) * (if (f.natural > 0) 0.4f else 1f)
        if (rng.chance(pRail.coerceIn(0.01f, 0.9f))) {
            f.state = 2
            if (f.natural == 0) { faults += 4; app.sound.play(SoundFx.S.RAIL); say("Barre !") } else { faults += 0; say("Un peu juste, mais ça passe.") }
        } else {
            f.state = 1
            app.sound.play(SoundFx.S.HOOF_SOFT, 0.8f)
            if (jumpQuality > 0.8f) say("Superbe saut !")
        }
        stamina -= 3f + f.height * 2f
        if (f.natural == 4) app.sound.play(SoundFx.S.WATER, 0.8f)
        if (fences.all { it.state != 0 }) say("Dernier obstacle franchi : passez la ligne d'arrivée !")
    }

    private fun finishCourse() {
        if (finished) return
        finished = true
        val timeFaults = if (rideTime > timeAllowed) (if (mode.kind == RideKind.CROSS) ((rideTime - timeAllowed) * 0.4f).toInt() else ((rideTime - timeAllowed) / 4f).toInt() + 1) else 0
        val total = faults + timeFaults
        val clean = fences.count { it.state == 1 }.toFloat() / fences.size.coerceAtLeast(1)
        quality = (clean * 0.8f + (if (rideTime <= timeAllowed) 0.2f else 0.05f) - refusals * 0.1f).coerceIn(0f, 1.1f)
        val time = rideTime
        if (mode.kind == RideKind.CROSS) {
            resultText = if (eliminated) "Éliminé" else "${total} pts de pénalité"
            resultSub = "Temps ${"%.1f".format(time)} s (temps idéal ${"%.0f".format(timeAllowed)} s) · ${refusals} refus"
        } else {
            resultText = if (eliminated) "Éliminé" else if (total == 0) "Sans faute !" else "$total points"
            resultSub = "Temps ${"%.2f".format(time)} s (accordé ${"%.0f".format(timeAllowed)} s) · ${fences.count { it.state == 2 }} barre(s), $refusals refus"
        }
        val detail = when (mode.kind) {
            RideKind.CROSS -> if (eliminated) "Éliminé (refus)" else "$total pts — ${"%.1f".format(time)} s"
            else -> if (eliminated) "Éliminé (2 refus)" else "$total pts — ${"%.2f".format(time)} s"
        }
        conclude(LivePerformance(quality, total, time, eliminated, detail))
    }

    private fun updateDressage() {
        for (m in movements) {
            if (m.score >= 0f) continue
            if (dist > m.at + 3f) {
                // jugé à la lettre : bonne allure, transition nette (récente), régularité
                val ok = gaitIdx.coerceAtMost(3) == m.gait
                val timing = abs(lastGaitChangeAt - m.at)
                var s = if (!ok) 2f else (10f - (timing - 1.5f).coerceAtLeast(0f) * 1.2f).coerceIn(4f, 10f)
                s = s * (0.55f + hSkill / 100f * 0.45f) + (rSkill - 50f) / 50f
                m.score = s.coerceIn(0f, 10f)
                say(if (m.score >= 7f) "Bien ! (${"%.1f".format(m.score)})" else if (ok) "Transition tardive (${"%.1f".format(m.score)})" else "Mauvaise allure (${"%.1f".format(m.score)})")
            }
        }
        if (dist >= courseLen && !finished) {
            finished = true
            val avg = movements.map { it.score.coerceAtLeast(0f) }.average().toFloat()
            val pct = (avg * 7.2f + 6f + (hSkill - 50f) * 0.08f).coerceIn(40f, 88f)
            quality = (avg / 10f).coerceIn(0f, 1f)
            resultText = "${"%.2f".format(pct)} %"
            resultSub = "Moyenne des figures : ${"%.1f".format(avg)}/10 · ${movements.count { it.score >= 7f }}/${movements.size} réussies"
            conclude(LivePerformance(pct / 100f, 0, rideTime, false, "${"%.3f".format(pct)} %"))
        }
    }

    private fun updateRace(dt: Float) {
        for (r in rivals) {
            if (r.finish >= 0f) { r.v = max(0f, r.v - dt * 3f); r.d += r.v * dt; continue }
            val left = courseLen - r.d
            var target = r.pace
            if (left < 400f) target += r.kick * (r.st / 100f) * 1.6f
            if (r.st < 10f) target -= 2f
            r.v += (target - r.v).coerceIn(-3f * dt, 2.5f * dt)
            r.d += r.v * dt
            r.st = (r.st - dt * (0.6f + max(0f, r.v - 14f) * 0.5f) * (100f / r.stamina)).coerceAtLeast(0f)
            if (r.d >= courseLen) r.finish = rideTime
            val gt = if (r.v > 10f) Gait.GRAND_GALOP else Gait.GALOP
            r.pose.gait = gt
            r.pose.phase = (r.pose.phase + r.v / (gt.stride / gt.duty * r.look.H / 100f) * dt) % 1f
            r.pose.speedBlend = 0.8f; r.pose.neck = 28f; r.pose.head = 55f
        }
        if (dist >= courseLen && finishTime < 0f) {
            finishTime = rideTime
            finished = true
            val rank = 1 + rivals.count { it.finish in 0f..finishTime } + if (mode.competition) 0 else 0
            val field = rivals.size + 1
            quality = if (mode.competition) (1f - (rank - 1f) / field) else (stamina / 100f * 0.4f + 0.6f)
            val tt = finishTime
            resultText = if (mode.competition) (if (rank == 1) "Victoire !" else "${rank}e sur $field") else "Galop terminé"
            resultSub = "${courseLen.toInt()} m en ${(tt / 60).toInt()}'${"%05.2f".format(tt % 60)}"
            conclude(LivePerformance(quality, 0, tt, false, "${(tt / 60).toInt()}'${"%05.2f".format(tt % 60)}"))
        }
    }

    private var compResult: CompResult? = null
    private var trainMsg = ""

    private fun conclude(perf: LivePerformance) {
        val g = game
        if (mode.competition && eventId >= 0) {
            compResult = g.playLive(eventId, horse.id, perf)
            compResult?.let {
                if (it.rank <= 3) { app.sound.play(SoundFx.S.APPLAUSE); app.sound.play(SoundFx.S.GOOD) } else app.sound.play(SoundFx.S.APPLAUSE, 0.4f)
            }
        } else {
            val ex = exercise ?: Exercise.PLAT
            val minutes = if (mode.kind == RideKind.BALADE) (rideTime / 2f).toInt().coerceIn(15, 150) else ex.minutes
            val r = g.train(horse, ex, g.rider.skill(skillD), true, quality.coerceIn(0.2f, 1.2f), minutes)
            trainMsg = r.msg
            app.sound.play(if (quality > 0.6f) SoundFx.S.GOOD else SoundFx.S.CLICK, 0.6f)
        }
        horse.energy = min(horse.energy, stamina.coerceAtLeast(5f))
        Looks.invalidate(horse.id)
    }

    private fun stopBalade() {
        if (finished) return
        finished = true
        val varied = (gaitChanges / 6f).coerceAtMost(1f)
        quality = (0.5f + varied * 0.3f + (if (stamina > 20) 0.2f else 0f)).coerceAtMost(1.1f)
        resultText = "Retour à l'écurie"
        resultSub = "${(dist / 1000f).let { "%.1f".format(it) }} km parcourus en ${(rideTime / 60).toInt()} min ${(rideTime % 60).toInt()} s"
        conclude(LivePerformance(quality))
    }

    private fun setGait(i: Int) {
        val n = i.coerceIn(0, if (mode.kind == RideKind.DRESSAGE || mode.kind == RideKind.PLAT) 3 else 4)
        if (n != gaitIdx) { gaitIdx = n; gaitChanges++; lastGaitChangeAt = dist + (if (n > gaitIdx) 1f else 0f) }
        lastGaitChangeAt = dist
    }

    // ===================================================================== dessin
    override fun draw(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        val amb = if (mode.competition) ambienceOf(g, 14f) else ambienceOf(g)
        val ground = h * 0.78f
        val scale = h * 0.27f / a.H
        val ppm = 100f * scale // pixels par mètre
        val camX = dist * ppm
        val horseX = w * 0.26f

        // ciel et fond
        Scenery.sky(c, w, ground - 60f * u, amb, t)
        when (mode.kind) {
            RideKind.BALADE -> drawCountry(c, camX, ground, amb, ppm)
            RideKind.PISTE -> drawTrack(c, camX, ground, amb, ppm)
            RideKind.CROSS -> drawCrossBg(c, camX, ground, amb, ppm)
            else -> drawArenaBg(c, camX, ground, amb, ppm)
        }
        // rivaux (couloirs du fond)
        for (r in rivals.sortedBy { -it.lane }) {
            val rx = horseX + (r.d - dist) * ppm
            if (rx < -300f || rx > w + 300f) continue
            val depth = 0.95f - r.lane * 0.05f
            val rt = Tack(saddle = true, bridle = true, rider = true, riderForward = 1f, padColor = Color.HSVToColor(floatArrayOf(r.lane * 55f, 0.6f, 0.7f)), jacket = Color.HSVToColor(floatArrayOf(r.lane * 55f + 20f, 0.7f, 0.6f)), number = r.lane + 2)
            HorseArt.draw(c, r.look, r.pose, rx, ground - (r.lane + 1) * 9f * u, scale * depth, true, amb.light, rt)
        }
        // obstacles
        for (f in fences) {
            val fx = horseX + (f.x - dist) * ppm
            if (fx < -200f || fx > w + 200f) continue
            if (f.natural == 0) Scenery.jump(c, fx, ground, f.height * ppm, 1.1f * ppm, amb, f.oxer, f.color, fallen = f.state == 2)
            else naturalFence(c, fx, ground, f, ppm, amb)
            // zone d'appel conseillée
            if (f.state == 0 && f == nextFence()) {
                val ideal = idealTakeoff(f); val wdw = window()
                gui.p.shader = null; gui.p.color = Color.argb(70, 80, 200, 90)
                c.drawRect(fx - (ideal + wdw) * ppm, ground - 4f * u, fx - (ideal - wdw) * ppm, ground + 6f * u, gui.p)
            }
        }
        // lettres de dressage
        if (mode.kind == RideKind.DRESSAGE || mode.kind == RideKind.PLAT) for (m in movements) {
            val mx = horseX + (m.at - dist) * ppm
            if (mx < -100f || mx > w + 100f) continue
            gui.p.color = Scenery.lit(Color.WHITE, amb); c.drawRect(mx - 12f * u, ground - 74f * u, mx + 12f * u, ground - 46f * u, gui.p)
            gui.text(c, m.text.substring(3, 4), mx, ground - 54f * u, 16f, Pal.INK, Paint.Align.CENTER, gui.serif)
            gui.p.color = if (m.score < 0f) Color.argb(120, 230, 190, 60) else if (m.score >= 7f) Color.argb(140, 60, 160, 70) else Color.argb(140, 200, 60, 50)
            c.drawRect(mx - 2f * u, ground - 46f * u, mx + 2f * u, ground + 4f * u, gui.p)
        }
        // le cheval monté
        HorseArt.draw(c, a, pose, horseX, ground, scale, true, amb.light, tack)
        // avant-plan
        if (mode.kind != RideKind.PISTE && mode.kind != RideKind.BALADE) Scenery.fence(c, -((camX * 1.3f) % (60f * u)), w + 60f * u, h + 2f * u, 34f * u, amb, 60f * u)
        if (mode.kind == RideKind.PISTE) { gui.p.color = Scenery.lit(Color.WHITE, amb); c.drawRect(0f, h - 22f * u, w, h - 16f * u, gui.p); var px = -((camX * 1.2f) % (80f * u)); while (px < w) { c.drawRect(px, h - 22f * u, px + 4f * u, h, gui.p); px += 80f * u } }
        Scenery.weather(c, w, h, amb, t)
        drawHud(c)
        if (finished) drawResult(c)
    }

    private fun drawCountry(c: Canvas, camX: Float, ground: Float, amb: Ambience, ppm: Float) {
        val u = gui.u; val w = gui.w; val h = gui.h
        // biomes : bocage → forêt → plage → bocage…
        val biome = ((dist / 350f).toInt()) % 3
        Scenery.hills(c, w, ground - 70f * u, 60f * u, camX * 0.05f, 31, Scenery.lit(Color.rgb(120, 140, 150), amb))
        if (biome == 2) {
            Scenery.sea(c, 0f, w, ground - 90f * u, ground - 10f * u, amb, t)
            Scenery.sand(c, 0f, w, ground - 10f * u, h, amb)
            gui.p.color = HorseArt.alpha(Color.rgb(150, 130, 100), 0.4f); c.drawRect(0f, ground - 10f * u, w, ground + 10f * u, gui.p)
            // mouettes
            gui.sp.color = Scenery.lit(Color.rgb(250, 250, 250), amb); gui.sp.strokeWidth = 1.6f * u
            for (k in 0..3) { val bx = (k * 300f + t * 30f - camX * 0.1f) % (w + 100f); val by = ground - 200f * u + sin(t + k) * 10f * u; c.drawLine(bx - 8f * u, by - 3f * u, bx, by, gui.sp); c.drawLine(bx, by, bx + 8f * u, by - 3f * u, gui.sp) }
        } else {
            Scenery.hills(c, w, ground - 20f * u, 40f * u, camX * 0.3f, 12, Scenery.lit(Scenery.mix(Scenery.grassColor(amb), Color.rgb(60, 90, 70), 0.4f), amb))
            val spacing = if (biome == 1) 70f * u else 190f * u
            val off = (camX * 0.6f) % spacing
            var i = 0
            var x = -off
            while (x < w + 200f) { val idx = ((camX * 0.6f) / spacing).toInt() + i; Scenery.tree(c, x, ground - 10f * u, (if (biome == 1) 120f else 80f + (idx * 13 % 30)) * u, amb, idx, poplar = biome == 0 && idx % 3 == 0); x += spacing; i++ }
            Scenery.grass(c, 0f, w, ground - 12f * u, h, amb, camX)
            // chemin
            gui.p.color = Scenery.lit(Color.rgb(170, 146, 110), amb); c.drawRect(0f, ground - 6f * u, w, ground + 14f * u, gui.p)
            if (biome == 0) Scenery.hedge(c, -((camX) % (400f * u)), w + 400f * u, ground - 14f * u, 26f * u, amb)
        }
    }

    private fun drawArenaBg(c: Canvas, camX: Float, ground: Float, amb: Ambience, ppm: Float) {
        val u = gui.u; val w = gui.w; val h = gui.h
        Scenery.hills(c, w, ground - 70f * u, 50f * u, camX * 0.05f, 9, Scenery.lit(Color.rgb(110, 130, 120), amb))
        if (mode.competition) {
            // tribunes, public et bannières des partenaires
            val standTop = ground - 150f * u
            gui.p.color = Scenery.lit(Color.rgb(120, 120, 126), amb); c.drawRect(0f, standTop, w, ground - 40f * u, gui.p)
            val rr = Rng(5)
            for (i in 0 until 520) {
                val x = ((rr.float() * w * 2 - camX * 0.4f) % w + w) % w; val y = standTop + 8f * u + rr.float() * 90f * u
                val yy = y + sin(t * 3f + i) * (if (finished) 2f * u else 0f)
                gui.p.color = Color.HSVToColor(floatArrayOf(rr.float() * 360f, 0.35f, 0.55f * amb.light + 0.1f)); c.drawRoundRect(x - 2.6f * u, yy, x + 2.6f * u, yy + 7f * u, 2f * u, 2f * u, gui.p)
                gui.p.color = Scenery.lit(Color.rgb(222, 186, 160), amb); c.drawCircle(x, yy - 1.5f * u, 2.1f * u, gui.p)
            }
            val names = listOf("BAIE TV", "SELLERIE DU PIN", "CRÉDIT NORMAND", "ALIMENTS CAVALIA", "CLINIQUE VÉTO")
            var bx = -((camX * 0.8f) % (220f * u))
            var k = ((camX * 0.8f) / (220f * u)).toInt()
            while (bx < w) {
                gui.p.color = Scenery.lit(if (k % 2 == 0) Pal.GREEN else Color.rgb(140, 30, 40), amb)
                c.drawRect(bx, ground - 40f * u, bx + 210f * u, ground - 18f * u, gui.p)
                gui.text(c, names[((k).mod(names.size))], bx + 105f * u, ground - 24f * u, 10f, Pal.CREAM, Paint.Align.CENTER, gui.sansB)
                bx += 220f * u; k++
            }
        } else {
            for (i in 0 until 8) { val tx = i * 180f * u - (camX * 0.3f) % (180f * u); Scenery.tree(c, tx, ground - 30f * u, 90f * u, amb, i) }
            Scenery.fence(c, -((camX) % (60f * u)), w + 60f * u, ground - 16f * u, 22f * u, amb, 60f * u)
        }
        Scenery.sand(c, 0f, w, ground - 16f * u, h, amb)
        // traces dans le sable
        gui.sp.color = Color.argb(30, 0, 0, 0); gui.sp.strokeWidth = 1f * u
        var x = -((camX) % (24f * u)); while (x < w) { c.drawLine(x, ground + 8f * u, x + 10f * u, ground + 20f * u, gui.sp); x += 24f * u }
    }

    private fun drawCrossBg(c: Canvas, camX: Float, ground: Float, amb: Ambience, ppm: Float) {
        val u = gui.u; val w = gui.w; val h = gui.h
        Scenery.hills(c, w, ground - 50f * u, 90f * u, camX * 0.08f, 41, Scenery.lit(Color.rgb(96, 128, 100), amb))
        Scenery.hills(c, w, ground - 10f * u, 50f * u, camX * 0.25f, 14, Scenery.lit(Scenery.mix(Scenery.grassColor(amb), Color.rgb(50, 80, 60), 0.3f), amb))
        for (i in 0 until 10) { val tx = i * 150f * u - (camX * 0.5f) % (150f * u); Scenery.tree(c, tx, ground - 18f * u, (100f + i * 7 % 40) * u, amb, i + 3) }
        Scenery.grass(c, 0f, w, ground - 14f * u, h, amb, camX)
    }

    private fun drawTrack(c: Canvas, camX: Float, ground: Float, amb: Ambience, ppm: Float) {
        val u = gui.u; val w = gui.w; val h = gui.h
        Scenery.hills(c, w, ground - 80f * u, 40f * u, camX * 0.03f, 51, Scenery.lit(Color.rgb(110, 128, 120), amb))
        // tribune près de l'arrivée
        val finishX = w * 0.26f + (courseLen - dist) * ppm
        if (finishX in -600f..(w + 600f)) {
            gui.p.color = Scenery.lit(Color.rgb(230, 226, 214), amb); c.drawRect(finishX - 500f * u, ground - 170f * u, finishX + 60f * u, ground - 70f * u, gui.p)
            gui.p.color = Scenery.lit(Color.rgb(60, 80, 70), amb); c.drawRect(finishX - 520f * u, ground - 186f * u, finishX + 80f * u, ground - 168f * u, gui.p)
            val rr = Rng(8)
            for (i in 0 until 300) { gui.p.color = Color.HSVToColor(floatArrayOf(rr.float() * 360f, 0.35f, 0.6f)); c.drawCircle(finishX - 490f * u + rr.float() * 540f * u, ground - 160f * u + rr.float() * 80f * u, 3f * u, gui.p) }
        }
        Scenery.grass(c, 0f, w, ground - 70f * u, h, amb, camX)
        // poteaux de distance
        gui.p.color = Scenery.lit(Color.WHITE, amb)
        c.drawRect(0f, ground - 62f * u, w, ground - 58f * u, gui.p)
        var m = (dist / 200f).toInt() * 200f
        while (true) {
            val px = w * 0.26f + (m - dist) * ppm
            if (px > w + 50f) break
            if (px > -50f) {
                c.drawRect(px - 2f * u, ground - 90f * u, px + 2f * u, ground - 58f * u, gui.p)
                gui.text(c, "${(courseLen - m).toInt().coerceAtLeast(0)}", px, ground - 94f * u, 9f, Pal.CREAM, Paint.Align.CENTER, shadow = true)
            }
            m += 200f
        }
        if (finishX in -50f..(w + 50f)) { gui.p.color = Pal.RED; c.drawRect(finishX - 3f * u, ground - 140f * u, finishX + 3f * u, ground + 30f * u, gui.p); gui.text(c, "ARRIVÉE", finishX, ground - 146f * u, 11f, Pal.RED, Paint.Align.CENTER, gui.sansB) }
    }

    private fun naturalFence(c: Canvas, x: Float, ground: Float, f: Fence, ppm: Float, amb: Ambience) {
        val hgt = f.height * ppm
        val p = gui.p
        p.shader = null
        when (f.natural) {
            1 -> { // tronc
                p.color = Scenery.lit(Color.rgb(110, 76, 48), amb); c.drawRoundRect(x - 1.4f * ppm, ground - hgt, x + 0.3f * ppm, ground, hgt / 2, hgt / 2, p)
                p.color = Scenery.lit(Color.rgb(160, 120, 80), amb); c.drawOval(x - 0.1f * ppm, ground - hgt, x + 0.5f * ppm, ground, p)
            }
            2 -> { // haie
                Scenery.hedge(c, x - 1.6f * ppm, x + 0.4f * ppm, ground, hgt * 1.1f, amb)
            }
            3 -> { // stère / table
                p.color = Scenery.lit(Color.rgb(130, 96, 60), amb); c.drawRect(x - 1.8f * ppm, ground - hgt, x + 0.2f * ppm, ground, p)
                p.color = Scenery.lit(Color.rgb(90, 64, 40), amb); var yy = ground - hgt; while (yy < ground) { c.drawRect(x - 1.8f * ppm, yy, x + 0.2f * ppm, yy + 2f, p); yy += hgt / 4 }
            }
            else -> { // gué
                p.color = Scenery.lit(Color.rgb(80, 140, 180), amb); c.drawOval(x - 3f * ppm, ground - 6f, x + 4f * ppm, ground + 10f, p)
                p.color = Scenery.lit(Color.rgb(110, 76, 48), amb); c.drawRoundRect(x - 1.2f * ppm, ground - hgt * 0.7f, x + 0.2f * ppm, ground, 6f, 6f, p)
            }
        }
        // fanions rouge et blanc
        p.color = Color.RED; c.drawRect(x + 0.5f * ppm, ground - hgt - 20f, x + 0.5f * ppm + 10f, ground - hgt - 12f, p)
        p.color = Color.WHITE; c.drawRect(x - 2f * ppm, ground - hgt - 20f, x - 2f * ppm + 10f, ground - hgt - 12f, p)
    }

    private fun drawHud(c: Canvas) {
        val u = gui.u; val w = gui.w; val h = gui.h
        gui.dark(c, RectF(8f * u, 6f * u, w - 8f * u, 44f * u))
        gui.button(c, RectF(12f * u, 10f * u, 48f * u, 40f * u), "‹", Btn.GHOST, size = 18f) { abandon() }
        val title = when (mode.kind) {
            RideKind.BALADE -> "Balade avec ${horse.name}"
            RideKind.PISTE -> if (mode.competition) "${Levels.name(Discipline.COURSE, mode.level)} — ${courseLen.toInt()} m" else "Galop d'entraînement"
            else -> if (mode.competition) Levels.name(skillD, mode.level) else (exercise?.label ?: "Travail")
        }
        gui.text(c, title, 56f * u, 23f * u, 13f, Pal.CREAM, font = gui.serif, maxW = w * 0.35f)
        gui.text(c, "${pose.gait.label} · ${(v * 3.6f).toInt()} km/h", 56f * u, 37f * u, 10f, Pal.GOLD_L)
        gui.gauge(c, w * 0.42f, 20f * u, 120f * u, "Souffle", stamina, Pal.gauge(stamina), dark = true)
        val info = when (mode.kind) {
            RideKind.OBSTACLES, RideKind.CROSS -> "Obstacle ${fences.count { it.state != 0 } + (if (finished) 0 else 1).coerceAtMost(fences.size - fences.count { it.state != 0 })}/${fences.size} · ${faults} pts · ${"%.1f".format(rideTime)} s / ${timeAllowed.toInt()} s"
            RideKind.DRESSAGE, RideKind.PLAT -> movements.firstOrNull { it.score < 0f }?.text ?: "Saluez le jury"
            RideKind.PISTE -> "Reste ${(courseLen - dist).toInt().coerceAtLeast(0)} m · ${"%.1f".format(rideTime)} s" + if (rivals.isNotEmpty()) " · position ${1 + rivals.count { it.d > dist }}" else ""
            RideKind.BALADE -> "${"%.2f".format(dist / 1000f)} km · ${(rideTime / 60).toInt()} min"
        }
        gui.text(c, info, w - 18f * u, 28f * u, if (mode.kind == RideKind.DRESSAGE) 13f else 11f, if (mode.kind == RideKind.DRESSAGE) Pal.GOLD_L else Pal.CREAM, Paint.Align.RIGHT, gui.sansB, maxW = w * 0.32f)
        if (msgT > 0f && msg.isNotEmpty()) {
            val tw = gui.textW(msg, 12f) + 30f * u
            val r = RectF(w / 2 - tw / 2, 52f * u, w / 2 + tw / 2, 76f * u)
            gui.p.color = Color.argb((200 * msgT.coerceAtMost(1f)).toInt(), 20, 34, 27); c.drawRoundRect(r, 10f * u, 10f * u, gui.p)
            gui.text(c, msg, w / 2, 69f * u, 12f, HorseArt.alpha(Pal.CREAM, msgT.coerceAtMost(1f)), Paint.Align.CENTER)
        }
        if (finished) return
        // commandes : allures (aides du cavalier)
        val maxG = if (mode.kind == RideKind.DRESSAGE || mode.kind == RideKind.PLAT) 3 else 4
        val labels = listOf("Arrêt", "Pas", "Trot", "Galop", "Grand galop")
        val bw = 74f * u; val by = h - 56f * u
        for (i in 0..maxG) {
            val r = RectF(12f * u + i * (bw + 6f * u), by, 12f * u + i * (bw + 6f * u) + bw, h - 10f * u)
            gui.button(c, r, labels[i], if (gaitIdx == i) Btn.GOLD else Btn.NORMAL, size = 11.5f) { setGait(i); app.sound.play(SoundFx.S.CLICK, 0.4f) }
        }
        when (mode.kind) {
            RideKind.OBSTACLES, RideKind.CROSS -> gui.button(c, RectF(w - 170f * u, h - 96f * u, w - 14f * u, h - 12f * u), "SAUTER", Btn.PRIMARY, size = 20f) { requestJump() }
            RideKind.PISTE -> gui.button(c, RectF(w - 170f * u, h - 96f * u, w - 14f * u, h - 12f * u), "POUSSER", Btn.PRIMARY, size = 18f, sub = "dernière ligne droite") { if (gaitIdx < 4) setGait(4); push = 1.5f; app.sound.play(SoundFx.S.SNORT, 0.4f) }
            RideKind.BALADE -> gui.button(c, RectF(w - 170f * u, h - 56f * u, w - 14f * u, h - 10f * u), "Rentrer", Btn.GOLD, size = 14f) { stopBalade() }
            else -> {}
        }
    }

    private fun drawResult(c: Canvas) {
        val u = gui.u; val w = gui.w; val h = gui.h
        gui.modal(c, dim = false)
        val r = RectF(w / 2 - 210f * u, h / 2 - 110f * u, w / 2 + 210f * u, h / 2 + 110f * u)
        gui.paper(c, r)
        gui.text(c, resultText, r.centerX(), r.top + 40f * u, 22f, Pal.INK, Paint.Align.CENTER, gui.serif)
        gui.text(c, resultSub, r.centerX(), r.top + 62f * u, 11f, Pal.INK_L, Paint.Align.CENTER, maxW = r.width() - 30f * u)
        val cr = compResult
        val body = when {
            cr != null -> "Classement : ${if (cr.rank == 1) "1er" else "${cr.rank}e"} sur ${cr.of}${if (cr.prize > 0) " · gains ${fmtMoney(cr.prize)}" else ""}."
            trainMsg.isNotEmpty() -> trainMsg
            else -> ""
        }
        gui.wrap(c, body, r.left + 20f * u, r.top + 92f * u, r.width() - 40f * u, 12f, if (cr != null && cr.rank <= 3) Pal.OK else Pal.INK)
        gui.button(c, RectF(r.centerX() - 90f * u, r.bottom - 48f * u, r.centerX() + 90f * u, r.bottom - 14f * u), "Retour", Btn.GOLD, size = 14f) { app.pop() }
    }

    private fun abandon() {
        if (finished) { app.pop(); return }
        if (mode.competition) {
            eliminated = true
            resultText = "Abandon"; resultSub = "Vous avez salué le jury et quitté la piste."
            finished = true
            conclude(LivePerformance(0f, 0, rideTime, true, "Abandon"))
        } else if (mode.kind == RideKind.BALADE) stopBalade()
        else app.pop()
    }

    override fun onBack(): Boolean { abandon(); return true }
}
