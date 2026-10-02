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

enum class RideKind { BALADE, PLAT, DRESSAGE, OBSTACLES, CROSS, PISTE, HUNTER, TROT, ENDURANCE, WESTERN }

class RideMode(val kind: RideKind, val competition: Boolean, val level: Int = 0, val distance: Int = 0) {
    companion object {
        fun forExercise(ex: Exercise) = when (ex) {
            Exercise.EXTERIEUR -> RideMode(RideKind.BALADE, false)
            Exercise.FOND -> RideMode(RideKind.ENDURANCE, false, 0)
            Exercise.DRESSAGE -> RideMode(RideKind.DRESSAGE, false, 1)
            Exercise.GYMNASTIQUE -> RideMode(RideKind.OBSTACLES, false, 0)
            Exercise.PARCOURS -> RideMode(RideKind.OBSTACLES, false, 1)
            Exercise.CROSS -> RideMode(RideKind.CROSS, false, 1)
            Exercise.GALOP -> RideMode(RideKind.PISTE, false, 0, 1000)
            Exercise.SULKY -> RideMode(RideKind.TROT, false, 0, 1600)
            Exercise.WESTERN -> RideMode(RideKind.WESTERN, false, 0)
            else -> RideMode(RideKind.PLAT, false)
        }
        fun forEvent(e: CompEvent) = when (e.discipline) {
            Discipline.CSO -> RideMode(RideKind.OBSTACLES, true, e.level)
            Discipline.DRESSAGE -> RideMode(RideKind.DRESSAGE, true, e.level)
            Discipline.COMPLET -> RideMode(RideKind.CROSS, true, e.level)
            Discipline.COURSE -> RideMode(RideKind.PISTE, true, e.level, e.distance)
            Discipline.HUNTER -> RideMode(RideKind.HUNTER, true, e.level)
            Discipline.TROT_ATTELE -> RideMode(RideKind.TROT, true, e.level, e.distance)
            Discipline.ENDURANCE -> RideMode(RideKind.ENDURANCE, true, e.level)
            Discipline.WESTERN -> RideMode(RideKind.WESTERN, true, e.level)
            else -> RideMode(RideKind.PLAT, true, e.level)
        }
        val LIVE = setOf(Discipline.CSO, Discipline.DRESSAGE, Discipline.COMPLET, Discipline.COURSE, Discipline.HUNTER, Discipline.TROT_ATTELE, Discipline.ENDURANCE, Discipline.WESTERN)
        /** Les courses sont jouées à échelle réduite (distances ÷ 2) ; les temps affichés sont ceux de la course réelle. */
        const val RACE_SCALE = 2f
    }
}

/**
 * Monter et mener : balade, dressage rythmé, saut d'obstacles, hunter, cross, course de galop,
 * trot attelé, endurance avec contrôles vétérinaires, barrel race. Le cheval répond selon son niveau,
 * sa forme, sa confiance, son caractère et la précision du cavalier.
 */
class RideScreen(app: GameView, private val horse: Horse, private val mode: RideMode, private val eventId: Int = -1, private val exercise: Exercise? = null) : Screen(app) {
    private val game get() = app.game!!
    private val rng = Rng(System.nanoTime())
    private val app2 = app
    private var t = 0f
    private val pose = HorsePose()
    private val isTrot = mode.kind == RideKind.TROT
    private val tack = Tack(saddle = !isTrot, bridle = true, rider = !isTrot, number = if (mode.competition) 1 + rng.int(98) else 0)
    private val a get() = Looks.of(horse, game.day)

    // ---------------- état du cheval monté
    private var dist = 0f
    private var v = 0f
    private var gaitIdx = 1
    private var stamina = 100f
    private var rideTime = 0f
    private var gaitChanges = 0
    private var finished = false
    private var resultText = ""
    private var resultSub = ""
    private var quality = 0f
    private var spook = 0f
    private var push = 0f
    private val skillD = when (mode.kind) {
        RideKind.DRESSAGE, RideKind.PLAT -> Discipline.DRESSAGE; RideKind.OBSTACLES -> Discipline.CSO; RideKind.CROSS -> Discipline.COMPLET
        RideKind.PISTE -> Discipline.COURSE; RideKind.BALADE, RideKind.ENDURANCE -> Discipline.ENDURANCE; RideKind.HUNTER -> Discipline.HUNTER
        RideKind.TROT -> Discipline.TROT_ATTELE; RideKind.WESTERN -> Discipline.WESTERN
    }
    private val hSkill = horse.skill(skillD)
    private val rSkill = game.rider.skill(skillD)
    private val maxSpeed = 13f + horse.pot(Trait.VITESSE) / 100f * 5f * horse.ageFactor(game.day)
    private var countdown = if (mode.kind == RideKind.PISTE || (isTrot && mode.competition)) 3.5f else 0f

    // ---------------- obstacles (CSO, hunter, cross)
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
    private val takeoffs = ArrayList<Float>()
    private var speedVar = 0f
    private var courseLen = 0f
    private var timeAllowed = 0f

    // ---------------- dressage : figures et cadence
    private enum class Fig { GAIT, ARRET, RECULER, ALLONGER }
    private class Movement(val at: Float, val gait: Int, val fig: Fig, val text: String, val letter: String) { var score = -1f; var holdOk = 0f }
    private val movements = ArrayList<Movement>()
    private var lastGaitChangeAt = 0f
    private var cadence = 60f
    private var lastBeat = -1f
    private var beatFlash = 0f
    private var extending = false
    private var reinBack = 0f

    // ---------------- courses (galop et trot attelé)
    private class Rival(val name: String, val pace: Float, val kick: Float, val stamina: Float, val look: Appearance, val lane: Int) {
        var d = 0f; var v = 0f; var st = 100f; val pose = HorsePose(); var finish = -1f; var broke = 0f
    }
    private val rivals = ArrayList<Rival>()
    private var finishTime = -1f
    private var lane = 2
    private var whips = 0
    private var drafting = false
    private var boxedIn = false
    private var breakT = 0f      // trot attelé : temps passé au galop (faute d'allure)
    private var trotTarget = 11f
    private var dq = ""

    // ---------------- endurance
    private var hr = 40f
    private var gate = -1f
    private var gateIdx = 0
    private val gates = ArrayList<Float>()

    // ---------------- western
    private val barrels = ArrayList<Float>()
    private val barrelDone = ArrayList<Int>() // 0 à faire, 1 tourné, 2 renversé
    private var turning = 0f
    private var penalties = 0f

    private var msg = ""
    private var msgT = 0f

    init {
        pose.gait = Gait.PAS
        stamina = horse.energy
        when (mode.kind) {
            RideKind.OBSTACLES -> setupJumps(false)
            RideKind.HUNTER -> setupJumps(false)
            RideKind.CROSS -> setupJumps(true)
            RideKind.DRESSAGE, RideKind.PLAT -> setupDressage()
            RideKind.PISTE -> setupRace(false)
            RideKind.TROT -> setupRace(true)
            RideKind.ENDURANCE -> setupEndurance()
            RideKind.WESTERN -> setupBarrels()
            RideKind.BALADE -> {}
        }
        if (isTrot) { gaitIdx = 2; v = 0f }
        if (mode.competition) app.sound.play(SoundFx.S.BELL)
        say(intro())
    }

    private fun intro() = when (mode.kind) {
        RideKind.BALADE -> "Balade libre : accélérez, ralentissez, profitez du paysage. Rentrez quand vous voulez."
        RideKind.PLAT -> "Travail sur le plat : faites les transitions aux lettres et gardez la cadence."
        RideKind.DRESSAGE -> "Reprise : transitions pile à la lettre, et touchez RYTHME à chaque foulée pour la cadence."
        RideKind.OBSTACLES -> "Abordez chaque obstacle au galop et appuyez sur SAUTER au point de battue (zone verte)."
        RideKind.HUNTER -> "Hunter : galop régulier, battues parfaites. Le style est noté, pas le chrono."
        RideKind.CROSS -> "Cross : gardez un bon galop, sautez au bon moment, attention au gué !"
        RideKind.PISTE -> if (mode.competition) "Départ dans les stalles… Restez abrité derrière un concurrent, puis attaquez dans la ligne droite." else "Galop d'entraînement : tenez un rythme régulier."
        RideKind.TROT -> "Trot attelé : allez vite sans jamais galoper. Si votre cheval se met au galop, ralentissez tout de suite !"
        RideKind.ENDURANCE -> "Endurance : gérez la fréquence cardiaque. Au contrôle vétérinaire, elle doit redescendre sous 64."
        RideKind.WESTERN -> "Barrel race : ralentissez avant chaque tonneau et touchez TOURNER au bon moment, puis sprintez !"
    }

    private fun say(s: String) { msg = s; msgT = 4f }

    // ===================================================================== mise en place
    private fun setupJumps(natural: Boolean) {
        val hunter = mode.kind == RideKind.HUNTER
        val n = if (mode.competition) (if (natural) 10 else 8 + mode.level / 2) else if (mode.level == 0) 5 else 8
        val baseH = when {
            hunter -> Levels.HUNTER_HEIGHT[mode.level]
            mode.competition -> if (natural) Levels.CSO_HEIGHT[mode.level] - 0.1f else Levels.CSO_HEIGHT[mode.level]
            mode.level == 0 -> 0.7f
            else -> min(1.2f, 0.8f + hSkill / 100f * 0.6f)
        }
        var x = 40f
        val cols = if (hunter) intArrayOf(Color.rgb(250, 250, 245), Color.rgb(60, 110, 60), Color.rgb(120, 90, 60)) else intArrayOf(Color.rgb(200, 40, 40), Color.rgb(30, 90, 170), Color.rgb(230, 160, 30), Color.rgb(40, 140, 70), Color.rgb(120, 60, 150))
        for (i in 0 until n) {
            val hgt = baseH * (if (i == n - 1) 1.03f else rng.range(0.9f, 1f))
            val natType = if (natural) rng.int(4) + 1 else 0
            fences += Fence(x, hgt, !natural && rng.chance(if (hunter) 0.25f else 0.4f), natType, cols[i % cols.size])
            x += if (natural) rng.range(55f, 90f) else if (hunter) 34f else rng.range(26f, 40f)
        }
        courseLen = x + 20f
        val speed = if (natural) 9.5f else 5.8f
        timeAllowed = courseLen / speed
    }

    private fun setupDressage() {
        val names = listOf("A", "K", "E", "H", "C", "M", "B", "F")
        val n = if (mode.kind == RideKind.DRESSAGE) 10 + mode.level else 7
        var x = 30f
        val gaitNames = listOf("arrêt", "pas moyen", "trot de travail", "galop de travail")
        var prev = 1
        for (i in 0 until n) {
            val letter = names[i % names.size]
            val r = rng.float()
            val m = when {
                mode.kind == RideKind.DRESSAGE && i > 1 && r < 0.12f && prev >= 1 -> Movement(x, 0, Fig.ARRET, "En $letter : arrêt, immobilité 3 s", letter)
                mode.kind == RideKind.DRESSAGE && mode.level >= 1 && i > 2 && r < 0.22f && prev == 0 -> Movement(x, 0, Fig.RECULER, "En $letter : reculer de 4 pas", letter)
                mode.kind == RideKind.DRESSAGE && r < 0.36f && prev == 2 -> Movement(x, 2, Fig.ALLONGER, "De $letter : trot allongé (maintenir ALLONGER)", letter)
                else -> {
                    var g = rng.range(1, 3)
                    if (g == prev) g = if (g == 3) 2 else g + 1
                    Movement(x, g, Fig.GAIT, "En $letter : ${gaitNames[g]}", letter)
                }
            }
            movements += m
            prev = m.gait
            x += rng.range(24f, 34f)
        }
        courseLen = x + 15f
    }

    private fun setupRace(trot: Boolean) {
        val realDist = if (mode.distance > 0) mode.distance.toFloat() else if (trot) 2100f else 1000f
        courseLen = realDist / RideMode.RACE_SCALE
        if (!mode.competition) return
        val n = 6
        val g = Game(rng.nextLong())
        for (i in 0 until n) {
            val h = g.generateHorse(if (trot) Breed.TROTTEUR else Breed.PUR_SANG, if (rng.chance(0.5f)) Sex.ETALON else Sex.JUMENT, if (trot) 5 else 4, withAncestors = false)
            val q = (Levels.EXPECT[mode.level] + rng.gauss(0.0, 8.0).toFloat()) / 100f
            val pace = if (trot) 12.6f + q * 2.6f else 14.2f + q * 3.2f
            rivals += Rival(h.name, pace, 0.6f + rng.float() * 0.8f, 70f + q * 40f, Appearance.of(h, g.day), if (i >= lane) i + 1 else i)
        }
    }

    private fun setupEndurance() {
        val loops = if (mode.competition) 3 else 2
        val loopLen = 500f
        for (i in 1..loops) gates += i * loopLen
        courseLen = loops * loopLen
    }

    private fun setupBarrels() {
        barrels += listOf(35f, 70f, 105f)
        barrels.forEach { barrelDone += 0 }
        courseLen = 150f
    }

    // ===================================================================== mise à jour
    private val gaitSpeeds get() = floatArrayOf(0f, 1.7f, 3.6f, if (mode.kind == RideKind.CROSS) 9f else 6.2f, maxSpeed)

    override fun update(dt: Float) {
        t += dt
        msgT -= dt; beatFlash -= dt
        if (finished) { idleAnim(dt); return }
        if (countdown > 0f) {
            val before = countdown.toInt()
            countdown -= dt
            if (countdown.toInt() != before && countdown > 0f) app.sound.play(SoundFx.S.CLICK)
            if (countdown <= 0f) { say("Partez !"); app.sound.play(SoundFx.S.BELL); if (!isTrot) gaitIdx = 4 }
            idleAnim(dt); return
        }
        rideTime += dt
        var target = if (isTrot) trotTarget else gaitSpeeds[gaitIdx]
        if (stamina < 15f && gaitIdx >= 3 && !isTrot) { target = gaitSpeeds[2] + 1f; if (rng.chance(dt * 0.5f)) say("${horse.name} est essoufflé…") }
        if (mode.kind == RideKind.PISTE && gaitIdx == 4) target *= (0.86f + hSkill / 100f * 0.14f) * (1f + push * 0.06f)
        if (extending && gaitIdx == 2) target = 4.6f + hSkill / 100f * 0.8f
        if (reinBack > 0f) { target = -0.5f; reinBack -= dt }
        if (turning > 0f) target = 1.2f
        if (spook > 0f) { target = 0f; spook -= dt }
        if (boxedIn) target = min(target, rivals.filter { it.lane == lane && it.d > dist }.minByOrNull { it.d }?.v ?: target)
        val acc = if (target > v) 2.2f + gaitIdx * 0.5f else 4.5f
        v += (target - v).coerceIn(-acc * dt, acc * dt)
        if (gate < 0f) dist += max(0f, v) * dt
        // effort
        val cost = when { v < 2f -> -1.5f; v < 4.5f -> 0.25f; v < 8f -> 0.55f; else -> 1.6f + (v - 12f).coerceAtLeast(0f) * 0.4f } *
            (1.4f - horse.fitness / 160f) * (1.25f - horse.pot(Trait.ENDURANCE) / 250f) * (if (drafting) 0.72f else 1f)
        stamina = (stamina - cost * dt * (1f + push * 1.5f)).coerceIn(0f, 100f)
        push = max(0f, push - dt)
        // allure et cadence des membres (le trotteur reste au trot sauf faute d'allure)
        val gait = when {
            isTrot -> if (breakT > 0f) Gait.GALOP else if (v < 0.3f) Gait.ARRET else if (v < 2.2f) Gait.PAS else Gait.TROT
            v < 0.3f -> Gait.ARRET; v < 2.6f -> Gait.PAS; v < 4.8f -> Gait.TROT; v < 10f -> Gait.GALOP; else -> Gait.GRAND_GALOP
        }
        if (jumpStart < 0f) pose.gait = gait
        val H = a.H / 100f
        val cycleDist = if (gait == Gait.ARRET) 1f else (gait.stride / gait.duty * H) * (if (isTrot && gait == Gait.TROT) 1.7f else 1f)
        val oldPhase = pose.phase
        pose.phase = (pose.phase + (if (gait == Gait.ARRET) dt * 0.2f else abs(v) / cycleDist * dt)) % 1f
        if (gait != Gait.ARRET && jumpStart < 0f) hoofSounds(oldPhase, pose.phase, gait)
        pose.speedBlend += (((v - 4f) / 10f).coerceIn(0f, 1f) - pose.speedBlend) * dt * 2f
        val collected = mode.kind == RideKind.DRESSAGE || mode.kind == RideKind.PLAT || mode.kind == RideKind.HUNTER
        pose.neck += ((if (gait == Gait.GRAND_GALOP) 28f else if (gait == Gait.ARRET) 48f else if (collected) 50f else 40f) - pose.neck) * dt * 2f
        pose.head += ((if (gait == Gait.GRAND_GALOP) 55f else if (collected) 18f else 35f) - pose.head) * dt * 2f
        pose.ears = if (jumpFence != null || nextFence()?.let { it.x - dist < 15f } == true) 1f else sin(t * 0.8f) * 0.4f
        pose.tailSwing = sin(t * 2f) * 0.5f
        tack.riderForward += ((if (jumpStart >= 0f || gait == Gait.GRAND_GALOP) 1f else if (gait == Gait.GALOP && !collected) 0.45f else 0f) - tack.riderForward) * dt * 4f
        tack.riderPost = if (gait == Gait.TROT && !collected) (sin(pose.phase * 6.283f * 2f) * 0.5f + 0.5f) else 0f
        if (mode.kind == RideKind.BALADE && Personality.PEUREUX in horse.personality && spook <= 0f && rng.chance(dt * 0.02f * (1.4f - horse.confidence / 100f))) {
            spook = 1.4f; say("Un faisan s'envole ! ${horse.name} fait un écart."); app.sound.play(SoundFx.S.SNORT)
            horse.confidence = (horse.confidence - 1f).coerceAtLeast(0f)
        }
        when (mode.kind) {
            RideKind.OBSTACLES, RideKind.CROSS, RideKind.HUNTER -> updateJumps(dt)
            RideKind.DRESSAGE, RideKind.PLAT -> updateDressage(dt, oldPhase)
            RideKind.PISTE, RideKind.TROT -> updateRace(dt)
            RideKind.ENDURANCE -> updateEndurance(dt)
            RideKind.WESTERN -> updateBarrels(dt)
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
                val hard = mode.kind == RideKind.PISTE || isTrot
                app2.sound.play(if (hard) SoundFx.S.HOOF_HARD else SoundFx.S.HOOF_SOFT, 0.25f + 0.1f * g.ordinal, 0.9f + rng.float() * 0.2f, 30)
            }
        }
    }

    // ---------------------------------------------------------------- obstacles
    private fun nextFence(): Fence? = fences.firstOrNull { it.state == 0 && it.x > dist - 1f }
    private fun idealTakeoff(f: Fence) = 1.2f + f.height * 1.1f + (if (f.oxer) 0.3f else 0f)
    private fun window() = 0.6f + hSkill / 100f * 1.1f + rSkill / 100f * 0.5f + horse.confidence / 100f * 0.3f

    private fun updateJumps(dt: Float) {
        if (mode.kind == RideKind.HUNTER && gaitIdx == 3) speedVar += abs(v - 6.2f) * dt
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
        val tooSlow = (if (f.height > 0.9f) 4.4f else 3f) > v
        if (d < 0.4f) refuse(f, if (tooSlow) "trop lent" else "pas d'appel")
    }

    private fun requestJump() {
        if (jumpStart >= 0f || finished) return
        val f = nextFence() ?: return
        val d = f.x - dist
        val ideal = idealTakeoff(f)
        if (d > ideal + 6f) { say("Trop tôt ! Attendez la zone verte."); return }
        val err = abs(d - ideal)
        val w = window()
        jumpQuality = (1f - err / (w * 2.2f)).coerceIn(0f, 1f)
        takeoffs += jumpQuality
        val courage = horse.pot(Trait.COURAGE) / 100f + horse.confidence / 400f + (if (Personality.COURAGEUX in horse.personality) 0.2f else 0f) - (if (Personality.PEUREUX in horse.personality) 0.15f else 0f)
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
        horse.confidence = (horse.confidence - 3f).coerceAtLeast(0f)
        faults += if (mode.kind == RideKind.CROSS) 20 else 4
        val elimAt = if (mode.kind == RideKind.CROSS) 3 else 2
        if (refusals >= elimAt && mode.competition) { eliminated = true; f.state = 3; say("Deuxième refus : élimination."); finishCourse(); return }
        say("Refus ($why) ! Reprenez de l'élan et représentez-vous.")
    }

    private fun landJump() {
        val f = jumpFence ?: return
        jumpStart = -1f; jumpFence = null; pose.jump = -1f
        val ability = (hSkill * 0.6f + horse.pot(Trait.TECHNIQUE) * 0.4f) / 100f
        val heightStress = (f.height / (0.7f + ability * 1.1f)).coerceIn(0.4f, 1.6f)
        val tired = if (stamina < 25f) 0.15f else 0f
        val pRail = (0.04f + (1f - jumpQuality) * 0.45f * heightStress + tired + max(0f, heightStress - 1f) * 0.4f) * (if (f.natural > 0) 0.4f else 1f)
        if (rng.chance(pRail.coerceIn(0.01f, 0.9f))) {
            f.state = 2
            if (f.natural == 0) { faults += 4; app.sound.play(SoundFx.S.RAIL); say("Barre !") } else say("Un peu juste, mais ça passe.")
        } else {
            f.state = 1
            app.sound.play(SoundFx.S.HOOF_SOFT, 0.8f)
            if (jumpQuality > 0.8f) { say("Superbe saut !"); horse.confidence = (horse.confidence + 0.5f).coerceAtMost(100f) }
        }
        stamina -= 3f + f.height * 2f
        if (f.natural == 4) app.sound.play(SoundFx.S.WATER, 0.8f)
        if (fences.all { it.state != 0 }) say("Dernier obstacle franchi : passez la ligne d'arrivée !")
    }

    private fun finishCourse() {
        if (finished) return
        finished = true
        val time = rideTime
        val clean = fences.count { it.state == 1 }.toFloat() / fences.size.coerceAtLeast(1)
        if (mode.kind == RideKind.HUNTER) {
            // note de style : régularité du galop, qualité des battues, fautes
            val reg = (1f - speedVar / max(1f, rideTime) / 1.5f).coerceIn(0f, 1f)
            val tk = takeoffs.average().toFloat().takeIf { !it.isNaN() } ?: 0f
            var note = 40f + reg * 25f + tk * 25f + hSkill * 0.12f - fences.count { it.state == 2 } * 15f - refusals * 12f
            note = note.coerceIn(10f, 98f)
            quality = note / 100f
            resultText = if (eliminated) "Éliminé" else "Note ${note.toInt()}/100"
            resultSub = "Régularité ${(reg * 100).toInt()} % · battues ${(tk * 100).toInt()} % · ${fences.count { it.state == 2 }} barre(s)"
            conclude(LivePerformance(quality, 0, time, eliminated, if (eliminated) "Éliminé (2 refus)" else "Note ${note.toInt()}/100"))
            return
        }
        val timeFaults = if (rideTime > timeAllowed) (if (mode.kind == RideKind.CROSS) ((rideTime - timeAllowed) * 0.4f).toInt() else ((rideTime - timeAllowed) / 4f).toInt() + 1) else 0
        val total = faults + timeFaults
        quality = (clean * 0.8f + (if (rideTime <= timeAllowed) 0.2f else 0.05f) - refusals * 0.1f).coerceIn(0f, 1.1f)
        if (mode.kind == RideKind.CROSS) {
            resultText = if (eliminated) "Éliminé" else "$total pts de pénalité"
            resultSub = "Temps ${"%.1f".format(time)} s (temps idéal ${"%.0f".format(timeAllowed)} s) · $refusals refus"
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

    // ---------------------------------------------------------------- dressage
    private fun updateDressage(dt: Float, oldPhase: Float) {
        // cadence : une « foulée » à chaque cycle ; le joueur doit taper dans le rythme
        if (pose.gait != Gait.ARRET && oldPhase > pose.phase) { lastBeat = t; beatFlash = 0.18f; if (rideTime - lastTap > 1.6f) cadence = max(0f, cadence - 3f) }
        for (m in movements) {
            if (m.score >= 0f) continue
            // l'arrêt doit être tenu 3 s autour de la lettre
            if (m.fig == Fig.ARRET && abs(dist - m.at) < 2.5f && v < 0.3f) m.holdOk += dt
            if (m.fig == Fig.RECULER && abs(dist - m.at) < 4f && reinBack > 0f) m.holdOk += dt
            if (m.fig == Fig.ALLONGER && dist in m.at..(m.at + 12f) && extending && gaitIdx == 2) m.holdOk += dt
            val judgeAt = when (m.fig) { Fig.ALLONGER -> m.at + 12f; else -> m.at + 3f }
            if (dist > judgeAt || (m.fig == Fig.ARRET && m.holdOk >= 3f)) {
                var s = when (m.fig) {
                    Fig.GAIT -> { val ok = gaitIdx.coerceAtMost(3) == m.gait; val timing = abs(lastGaitChangeAt - m.at); if (!ok) 2f else (10f - (timing - 1.5f).coerceAtLeast(0f) * 1.2f).coerceIn(4f, 10f) }
                    Fig.ARRET -> (m.holdOk / 3f * 10f).coerceIn(1f, 10f)
                    Fig.RECULER -> if (m.holdOk > 0.6f) 8.5f else 2f
                    Fig.ALLONGER -> (m.holdOk / (12f / 4.5f) * 10f).coerceIn(2f, 10f)
                }
                s = s * (0.55f + hSkill / 100f * 0.45f) + (rSkill - 50f) / 50f + (horse.confidence - 50f) / 100f
                m.score = s.coerceIn(0f, 10f)
                if (m.fig == Fig.ARRET && m.holdOk >= 3f) { dist = max(dist, m.at + 3.2f) }
                say(if (m.score >= 7f) "Bien ! (${"%.1f".format(m.score)})" else "${m.text.substringAfter(": ")} : ${"%.1f".format(m.score)}")
            }
        }
        if (dist >= courseLen && !finished) {
            finished = true
            val avg = movements.map { it.score.coerceAtLeast(0f) }.average().toFloat()
            val pct = (avg * 7.2f * 0.75f + cadence / 100f * 72f * 0.25f + 6f + (hSkill - 50f) * 0.08f).coerceIn(40f, 88f)
            quality = (pct / 100f).coerceIn(0f, 1f) + 0.1f
            resultText = "${"%.2f".format(pct)} %"
            resultSub = "Figures ${"%.1f".format(avg)}/10 · cadence ${cadence.toInt()} % · ${movements.count { it.score >= 7f }}/${movements.size} réussies"
            conclude(LivePerformance(pct / 100f, 0, rideTime, false, "${"%.3f".format(pct)} %"))
        }
    }

    private var lastTap = 0f
    private fun tapRhythm() {
        lastTap = rideTime
        if (pose.gait == Gait.ARRET) return
        val sinceBeat = t - lastBeat
        val cycleT = 1f / max(0.4f, pose.gait.freq)
        val err = min(sinceBeat, abs(cycleT - sinceBeat)) / cycleT
        if (err < 0.14f) { cadence = min(100f, cadence + 4f); beatFlash = 0.25f } else cadence = max(0f, cadence - 2.5f)
        app.sound.play(SoundFx.S.CLICK, 0.25f)
    }

    // ---------------------------------------------------------------- courses
    private fun updateRace(dt: Float) {
        val trot = isTrot
        for (r in rivals) {
            if (r.finish >= 0f) { r.v = max(0f, r.v - dt * 3f); r.d += r.v * dt; continue }
            val left = courseLen - r.d
            var target = r.pace
            if (left < 200f) target += r.kick * (r.st / 100f) * 1.6f
            if (r.st < 10f) target -= 2f
            if (trot && r.broke > 0f) { r.broke -= dt; target -= 3f }
            else if (trot && rng.chance(dt * 0.01f)) r.broke = 1.5f
            r.v += (target - r.v).coerceIn(-3f * dt, 2.5f * dt)
            r.d += r.v * dt
            r.st = (r.st - dt * (0.6f + max(0f, r.v - 14f) * 0.5f) * (100f / r.stamina)).coerceAtLeast(0f)
            if (r.d >= courseLen) r.finish = rideTime
            val gt = if (trot) (if (r.broke > 0f) Gait.GALOP else Gait.TROT) else if (r.v > 10f) Gait.GRAND_GALOP else Gait.GALOP
            r.pose.gait = gt
            r.pose.phase = (r.pose.phase + r.v / (gt.stride / gt.duty * r.look.H / 100f * (if (trot && gt == Gait.TROT) 1.7f else 1f)) * dt) % 1f
            r.pose.speedBlend = 0.8f; r.pose.neck = if (trot) 40f else 28f; r.pose.head = if (trot) 40f else 55f
        }
        // aspiration et couloirs
        val ahead = rivals.filter { it.lane == lane && it.d > dist }.minByOrNull { it.d }
        val gap = ahead?.let { it.d - dist } ?: 99f
        drafting = gap in 1.5f..7f
        boxedIn = gap < 1.5f
        if (trot) {
            // faute d'allure : au-delà de ses moyens au trot, le trotteur se met au galop
            val safe = 11f + hSkill / 100f * 4f + horse.confidence / 100f
            if (breakT <= 0f && trotTarget > safe && rng.chance(dt * (trotTarget - safe) * 0.35f)) { breakT = 0.01f; say("Il se met au galop ! Ralentissez vite !"); app.sound.play(SoundFx.S.SNORT) }
            if (breakT > 0f) {
                breakT += dt
                if (breakT > 2.6f) { dq = "Disqualifié : allure irrégulière"; breakT = 0f; endRace(true); return }
            }
        }
        if (whips > 8 && dq.isEmpty() && mode.competition) { dq = "Distancé : usage abusif de la cravache"; endRace(true); return }
        if (dist >= courseLen && finishTime < 0f) endRace(false)
    }

    private fun endRace(disq: Boolean) {
        if (finished) return
        finishTime = rideTime
        finished = true
        val field = rivals.size + 1
        val realT = finishTime * RideMode.RACE_SCALE
        if (disq) {
            quality = 0.1f
            resultText = "Disqualifié"; resultSub = dq
            conclude(LivePerformance(0f, 0, realT, true, dq))
            return
        }
        val rank = 1 + rivals.count { it.finish in 0f..finishTime }
        val closest = rivals.filter { it.finish >= 0f }.minOfOrNull { abs(it.finish - finishTime) } ?: 9f
        quality = if (mode.competition) (1f - (rank - 1f) / field) else (stamina / 100f * 0.4f + 0.6f)
        val km = courseLen * RideMode.RACE_SCALE / 1000f
        resultText = if (mode.competition) (if (rank == 1) "Victoire !" else "${rank}e sur $field") else if (isTrot) "Séance terminée" else "Galop terminé"
        resultSub = if (isTrot) "Réduction kilométrique : 1'${"%04.1f".format(realT / km - 60f)} · ${(courseLen * RideMode.RACE_SCALE).toInt()} m"
                    else "${(courseLen * RideMode.RACE_SCALE).toInt()} m en ${(realT / 60).toInt()}'${"%05.2f".format(realT % 60)}" + if (closest < 0.06f && mode.competition) " · photo-finish !" else ""
        val detail = if (isTrot) "Réd. km 1'${"%04.1f".format(realT / km - 60f)}" else "${(realT / 60).toInt()}'${"%05.2f".format(realT % 60)}"
        conclude(LivePerformance(quality, 0, realT, false, detail))
    }

    // ---------------------------------------------------------------- endurance
    private fun updateEndurance(dt: Float) {
        val targetHr = 38f + v * 9f + (100f - stamina) * 0.25f + (if (v > 4f) 10f else 0f) - horse.fitness * 0.08f
        hr += (targetHr - hr) * min(1f, dt * 0.5f)
        if (gate >= 0f) {
            gate += dt
            if (hr <= 64f && v < 2f) { gate = -1f; gateIdx++; say("Contrôle vétérinaire passé (${hr.toInt()} bpm) ! Repartez."); app.sound.play(SoundFx.S.GOOD, 0.5f) }
            else if (gate > 30f) { eliminated = true; endEndurance(); return }
            return
        }
        val nextGate = gates.getOrNull(gateIdx)
        if (nextGate != null && dist >= nextGate) {
            if (gateIdx == gates.size - 1) { endEndurance(); return }
            gate = 0f; v = 0f; gaitIdx = 1
            say("Contrôle vétérinaire : faites redescendre le cœur sous 64 bpm (30 s maximum).")
        }
    }

    private fun endEndurance() {
        if (finished) return
        finished = true
        val km = courseLen * 0.04f // 500 m de jeu = 20 km réels
        val speedKmh = km / max(1f, rideTime) * 3600f / 8f // le temps est compressé
        quality = if (eliminated) 0.1f else (speedKmh / 20f).coerceIn(0.3f, 1.1f)
        resultText = if (eliminated) "Éliminé" else "${"%.0f".format(km)} km bouclés"
        resultSub = if (eliminated) "Fréquence cardiaque trop élevée au contrôle vétérinaire" else "Vitesse moyenne ${"%.1f".format(speedKmh)} km/h · cœur au repos ${hr.toInt()} bpm"
        conclude(LivePerformance(quality, 0, rideTime, eliminated, if (eliminated) "Éliminé au contrôle vétérinaire (fréquence cardiaque)" else "${"%.0f".format(km)} km à ${"%.1f".format(speedKmh)} km/h"))
    }

    // ---------------------------------------------------------------- barrel race
    private fun updateBarrels(dt: Float) {
        if (turning > 0f) {
            turning -= dt
            if (turning <= 0f) say("Tournez… et accélérez !")
            return
        }
        for ((i, bx) in barrels.withIndex()) {
            if (barrelDone[i] != 0) continue
            if (dist > bx + 2f) { eliminated = true; say("Tonneau oublié : parcours non respecté !"); finishBarrel(); return }
            break
        }
        if (barrelDone.all { it != 0 } && dist >= courseLen) finishBarrel()
    }

    private fun requestTurn() {
        val i = barrelDone.indexOfFirst { it == 0 }
        if (i < 0 || turning > 0f) return
        val d = barrels[i] - dist
        if (d > 3.5f) { say("Trop tôt pour tourner !"); return }
        val fast = v > 7.5f
        barrelDone[i] = if (fast && rng.chance(0.6f)) 2 else 1
        if (barrelDone[i] == 2) { penalties += 5f; say("Tonneau renversé : +5 s !"); app.sound.play(SoundFx.S.RAIL) } else app.sound.play(SoundFx.S.HOOF_SOFT, 0.8f)
        // le demi-tour coûte du temps selon l'agilité du cheval
        turning = 1.6f - hSkill / 100f * 0.6f - (if (abs(d) < 1.5f) 0.2f else 0f)
        dist = barrels[i] + 2f
        v = 1.5f
    }

    private fun finishBarrel() {
        if (finished) return
        finished = true
        val total = rideTime + penalties
        quality = if (eliminated) 0.1f else (18f / total).coerceIn(0.3f, 1.1f)
        resultText = if (eliminated) "Éliminé" else "${"%.3f".format(total)} s"
        resultSub = if (eliminated) "Parcours non respecté" else "${barrelDone.count { it == 2 }} tonneau(x) renversé(s)"
        conclude(LivePerformance(quality, 0, total, eliminated, if (eliminated) "Éliminé" else "${"%.3f".format(total)} s"))
    }

    // ---------------------------------------------------------------- fin de séance
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
            g.spend(min(g.hoursLeft(), minutes / 60f + 0.25f))
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
        resultSub = "${"%.1f".format(dist / 1000f)} km parcourus en ${(rideTime / 60).toInt()} min ${(rideTime % 60).toInt()} s"
        conclude(LivePerformance(quality))
    }

    private fun setGait(i: Int) {
        val maxG = if (mode.kind == RideKind.DRESSAGE || mode.kind == RideKind.PLAT || mode.kind == RideKind.HUNTER) 3 else 4
        val n = i.coerceIn(0, maxG)
        if (n != gaitIdx) { gaitIdx = n; gaitChanges++ }
        lastGaitChangeAt = dist
    }

    // ===================================================================== dessin
    override fun draw(c: Canvas) {
        val g = game
        val u = gui.u; val w = gui.w; val h = gui.h
        val amb = if (mode.competition) ambienceOf(g, 14f) else ambienceOf(g)
        val ground = h * 0.78f
        val scale = h * 0.27f / a.H
        val ppm = 100f * scale
        val camX = dist * ppm
        val horseX = w * (if (isTrot) 0.34f else 0.26f)

        Scenery.sky(c, w, ground - 60f * u, amb, t)
        when (mode.kind) {
            RideKind.BALADE, RideKind.ENDURANCE -> drawCountry(c, camX, ground, amb, ppm)
            RideKind.PISTE, RideKind.TROT -> drawTrack(c, camX, ground, amb, ppm)
            RideKind.CROSS -> drawCrossBg(c, camX, ground, amb, ppm)
            else -> drawArenaBg(c, camX, ground, amb, ppm)
        }
        // contrôles vétérinaires (endurance)
        for ((i, gx) in gates.withIndex()) {
            val x = horseX + (gx - dist) * ppm
            if (x < -200f || x > w + 200f) continue
            val tent = RectF(x - 40f * u, ground - 70f * u, x + 40f * u, ground - 20f * u)
            gui.p.shader = null; gui.p.color = Color.WHITE; c.drawRect(tent, gui.p)
            gui.p.color = Pal.RED; c.drawRect(tent.left, tent.top, tent.right, tent.top + 10f * u, gui.p)
            gui.text(c, if (i == gates.size - 1) "ARRIVÉE" else "VÉTO ${i + 1}", x, tent.centerY() + 6f * u, 11f, Ink.INK, Paint.Align.CENTER, Ink.hand)
        }
        // tonneaux (western)
        for ((i, bx) in barrels.withIndex()) {
            val x = horseX + (bx - dist) * ppm
            if (x < -100f || x > w + 100f) continue
            val st = barrelDone[i]
            if (st == 2) { gui.p.color = Color.rgb(60, 110, 170); c.drawRoundRect(x - 26f * u, ground - 14f * u, x + 10f * u, ground, 4f * u, 4f * u, gui.p) }
            else {
                val br = RectF(x - 11f * u, ground - 34f * u, x + 11f * u, ground)
                gui.p.color = if (st == 1) Color.rgb(90, 140, 90) else Color.rgb(60, 110, 170); c.drawRoundRect(br, 5f * u, 5f * u, gui.p)
                gui.sp.color = Ink.INK; gui.sp.strokeWidth = 1.5f * u; c.drawRoundRect(br, 5f * u, 5f * u, gui.sp)
                gui.p.color = Color.WHITE; c.drawRect(br.left, br.top + 8f * u, br.right, br.top + 11f * u, gui.p)
            }
            if (st == 0 && i == barrelDone.indexOfFirst { it == 0 }) { gui.p.color = Color.argb(70, 80, 200, 90); c.drawRect(x - 3.5f * ppm, ground - 4f * u, x, ground + 6f * u, gui.p) }
        }
        // rivaux
        for (r in rivals.sortedBy { -it.lane }) {
            val rx = horseX + (r.d - dist) * ppm
            if (rx < -300f || rx > w + 300f) continue
            val depth = 1f - r.lane * 0.05f
            val ry = ground - r.lane * 9f * u
            val rt = Tack(saddle = !isTrot, bridle = true, rider = !isTrot, riderForward = 1f, padColor = Color.HSVToColor(floatArrayOf(r.lane * 55f, 0.6f, 0.7f)), jacket = Color.HSVToColor(floatArrayOf(r.lane * 55f + 20f, 0.7f, 0.6f)), number = r.lane + 2)
            if (isTrot) sulky(c, rx, ry, scale * depth, r.look, back = true, jacket = rt.jacket)
            HorseArt.draw(c, r.look, r.pose, rx, ry, scale * depth, true, amb.light, rt)
            if (isTrot) sulky(c, rx, ry, scale * depth, r.look, back = false, jacket = rt.jacket)
        }
        // obstacles
        for (f in fences) {
            val fx = horseX + (f.x - dist) * ppm
            if (fx < -200f || fx > w + 200f) continue
            if (f.natural == 0) Scenery.jump(c, fx, ground, f.height * ppm, 1.1f * ppm, amb, f.oxer, f.color, fallen = f.state == 2)
            else naturalFence(c, fx, ground, f, ppm, amb)
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
            gui.sp.color = Ink.INK; gui.sp.strokeWidth = 1.2f * u; c.drawRect(mx - 12f * u, ground - 74f * u, mx + 12f * u, ground - 46f * u, gui.sp)
            gui.text(c, m.letter, mx, ground - 54f * u, 16f, Ink.INK, Paint.Align.CENTER, Ink.hand)
            gui.p.color = if (m.score < 0f) Color.argb(120, 230, 190, 60) else if (m.score >= 7f) Color.argb(140, 60, 160, 70) else Color.argb(140, 200, 60, 50)
            c.drawRect(mx - 2f * u, ground - 46f * u, mx + 2f * u, ground + 4f * u, gui.p)
            if (m.fig == Fig.ALLONGER) { gui.p.color = Color.argb(50, 230, 190, 60); c.drawRect(mx, ground - 2f * u, mx + 12f * ppm, ground + 4f * u, gui.p) }
        }
        // le cheval
        val playerY = if (rivals.isNotEmpty()) ground - lane * 9f * u else ground
        val turnK = if (turning > 0f) abs(kotlin.math.cos(Math.PI.toFloat() * (1f - turning / 1.4f).coerceIn(0f, 1f) * 2f)).coerceAtLeast(0.15f) else 1f
        c.save(); c.scale(turnK, 1f, horseX, playerY)
        if (isTrot) sulky(c, horseX, playerY, scale, a, back = true, jacket = tack.jacket)
        HorseArt.draw(c, a, pose, horseX, playerY, scale, true, amb.light, tack)
        if (isTrot) sulky(c, horseX, playerY, scale, a, back = false, jacket = tack.jacket)
        c.restore()
        // avant-plan
        if (mode.kind != RideKind.PISTE && !isTrot && mode.kind != RideKind.BALADE && mode.kind != RideKind.ENDURANCE) Scenery.fence(c, -((camX * 1.3f) % (60f * u)), w + 60f * u, h + 2f * u, 34f * u, amb, 60f * u)
        if (mode.kind == RideKind.PISTE || isTrot) { gui.p.color = Scenery.lit(Color.WHITE, amb); c.drawRect(0f, h - 22f * u, w, h - 16f * u, gui.p); var px = -((camX * 1.2f) % (80f * u)); while (px < w) { c.drawRect(px, h - 22f * u, px + 4f * u, h, gui.p); px += 80f * u } }
        Scenery.weather(c, w, h, amb, t)
        Ink.grain(c, RectF(0f, 0f, w, h), 70)
        drawHud(c)
        if (countdown > 0f) gui.text(c, if (countdown > 0.5f) "${countdown.toInt().coerceAtLeast(1)}" else "Partez !", w / 2, h * 0.42f, 48f, Pal.CREAM, Paint.Align.CENTER, Ink.hand, shadow = true)
        if (finished) drawResult(c)
    }

    /** Sulky de course : roue lointaine (derrière le cheval) ou roue proche, brancards et driver. */
    private fun sulky(c: Canvas, x: Float, ground: Float, scale: Float, look: Appearance, back: Boolean, jacket: Int) {
        val H = look.H * scale; val L = look.L * scale
        val wheelR = H * 0.24f
        val wx = x - L * 0.72f - (if (back) H * 0.04f else 0f)
        val wy = ground - wheelR
        val sp = gui.sp
        sp.shader = null
        if (back) {
            sp.color = Color.rgb(90, 90, 96); sp.strokeWidth = 2f * gui.u; c.drawCircle(wx, wy, wheelR, sp)
            return
        }
        // brancards jusqu'à l'épaule
        sp.color = Color.rgb(60, 60, 64); sp.strokeWidth = 3f * gui.u
        c.drawLine(wx, wy, x + L * 0.25f, ground - H * 0.62f, sp)
        // roue proche à rayons
        sp.color = Color.rgb(40, 40, 44); sp.strokeWidth = 3f * gui.u; c.drawCircle(wx, wy, wheelR, sp)
        sp.strokeWidth = 1f * gui.u
        for (k in 0 until 8) { val an = k * 0.785f + t * 6f; c.drawLine(wx, wy, wx + kotlin.math.cos(an) * wheelR, wy + kotlin.math.sin(an) * wheelR, sp) }
        // driver assis, jambes vers l'avant, mains hautes
        val seatX = wx + wheelR * 0.2f; val seatY = wy - wheelR * 0.75f
        sp.color = Color.rgb(236, 230, 214); sp.strokeWidth = 8f * gui.u
        c.drawLine(seatX, seatY, seatX + H * 0.32f, seatY + H * 0.05f, sp)
        sp.color = jacket; sp.strokeWidth = 12f * gui.u
        c.drawLine(seatX, seatY, seatX - H * 0.05f, seatY - H * 0.3f, sp)
        sp.strokeWidth = 5f * gui.u
        c.drawLine(seatX - H * 0.04f, seatY - H * 0.26f, seatX + H * 0.15f, seatY - H * 0.22f, sp)
        sp.color = Color.rgb(46, 30, 20); sp.strokeWidth = 1.2f * gui.u
        c.drawLine(seatX + H * 0.15f, seatY - H * 0.22f, x + L * 0.55f, ground - H * 1.08f, sp)
        gui.p.shader = null; gui.p.color = Color.rgb(230, 194, 166); c.drawCircle(seatX - H * 0.06f, seatY - H * 0.38f, H * 0.06f, gui.p)
        gui.p.color = jacket; c.drawArc(seatX - H * 0.13f, seatY - H * 0.46f, seatX + H * 0.01f, seatY - H * 0.32f, 180f, 180f, true, gui.p)
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
        val finishX = w * (if (isTrot) 0.34f else 0.26f) + (courseLen - dist) * ppm
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
            val px = w * (if (isTrot) 0.34f else 0.26f) + (m - dist) * ppm
            if (px > w + 50f) break
            if (px > -50f) {
                c.drawRect(px - 2f * u, ground - 90f * u, px + 2f * u, ground - 58f * u, gui.p)
                gui.text(c, "${((courseLen - m) * RideMode.RACE_SCALE).toInt().coerceAtLeast(0)}", px, ground - 94f * u, 9f, Pal.CREAM, Paint.Align.CENTER, shadow = true)
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
        Ink.plank(c, RectF(6f * u, 4f * u, w - 6f * u, 46f * u), 6f * u, u, Ink.WOOD_D)
        gui.button(c, RectF(12f * u, 9f * u, 48f * u, 41f * u), "‹", size = 18f) { abandon() }
        val title = when (mode.kind) {
            RideKind.BALADE -> "Balade avec ${horse.name}"
            RideKind.PISTE -> if (mode.competition) "${Levels.name(Discipline.COURSE, mode.level)} — ${(courseLen * RideMode.RACE_SCALE).toInt()} m" else "Galop d'entraînement"
            RideKind.TROT -> if (mode.competition) "${Levels.name(Discipline.TROT_ATTELE, mode.level)} — ${(courseLen * RideMode.RACE_SCALE).toInt()} m" else "Entraînement au sulky"
            else -> if (mode.competition) Levels.name(skillD, mode.level) else (exercise?.label ?: "Travail")
        }
        gui.text(c, title, 56f * u, 24f * u, 13f, Pal.CREAM, font = Ink.hand, maxW = w * 0.34f)
        gui.text(c, "${pose.gait.label} · ${(abs(v) * 3.6f).toInt()} km/h", 56f * u, 39f * u, 10f, Ink.WOOD_L)
        if (mode.kind == RideKind.ENDURANCE) gui.gauge(c, w * 0.4f, 21f * u, 120f * u, "Cœur", ((hr - 30f) / 1.6f).coerceIn(0f, 100f), if (hr > 64f) Pal.RED else Pal.OK, "${hr.toInt()} bpm", dark = true)
        else if (mode.kind == RideKind.DRESSAGE || mode.kind == RideKind.PLAT) gui.gauge(c, w * 0.4f, 21f * u, 120f * u, "Cadence", cadence, Pal.gauge(cadence), dark = true)
        else gui.gauge(c, w * 0.4f, 21f * u, 120f * u, "Souffle", stamina, Pal.gauge(stamina), dark = true)
        val info = when (mode.kind) {
            RideKind.OBSTACLES, RideKind.CROSS -> "Obstacle ${min(fences.size, fences.count { it.state != 0 } + 1)}/${fences.size} · $faults pts · ${"%.1f".format(rideTime)} s / ${timeAllowed.toInt()} s"
            RideKind.HUNTER -> "Obstacle ${min(fences.size, fences.count { it.state != 0 } + 1)}/${fences.size} · gardez un galop régulier"
            RideKind.DRESSAGE, RideKind.PLAT -> movements.firstOrNull { it.score < 0f }?.text ?: "Saluez le jury"
            RideKind.PISTE, RideKind.TROT -> "Reste ${((courseLen - dist) * RideMode.RACE_SCALE).toInt().coerceAtLeast(0)} m" + (if (rivals.isNotEmpty()) " · ${1 + rivals.count { it.d > dist }}e" else "") +
                (if (drafting) " · abrité" else if (boxedIn) " · enfermé !" else "") + (if (mode.kind == RideKind.PISTE && mode.competition) " · cravache $whips/8" else "")
            RideKind.ENDURANCE -> if (gate >= 0f) "Contrôle vétérinaire : ${(30f - gate).toInt()} s" else "Étape ${gateIdx + 1}/${gates.size} · ${"%.1f".format(dist * 0.04f)} km"
            RideKind.WESTERN -> "Tonneau ${min(3, barrelDone.count { it != 0 } + 1)}/3 · ${"%.2f".format(rideTime + penalties)} s"
            RideKind.BALADE -> "${"%.2f".format(dist / 1000f)} km · ${(rideTime / 60).toInt()} min"
        }
        gui.text(c, info, w - 18f * u, 30f * u, if (mode.kind == RideKind.DRESSAGE) 12.5f else 11f, Pal.CREAM, Paint.Align.RIGHT, Ink.hand, maxW = w * 0.36f)
        if (msgT > 0f && msg.isNotEmpty()) {
            val tw = gui.textW(msg, 12f) + 30f * u
            val r = RectF(w / 2 - tw / 2, 54f * u, w / 2 + tw / 2, 78f * u)
            Ink.parchment(c, r, u)
            gui.text(c, msg, w / 2, 71f * u, 12f, HorseArt.alpha(Ink.INK, msgT.coerceAtMost(1f)), Paint.Align.CENTER)
        }
        if (finished || countdown > 0f) return
        val by = h - 56f * u
        val bw = 74f * u
        if (isTrot) {
            gui.button(c, RectF(12f * u, by, 12f * u + bw * 1.4f, h - 10f * u), "Ralentir", size = 13f) { trotTarget = max(3f, trotTarget - 1f); if (breakT > 0f && trotTarget < 12f) { breakT = 0f; say("Il reprend le trot. Ouf !") } }
            gui.button(c, RectF(18f * u + bw * 1.4f, by, 18f * u + bw * 2.8f, h - 10f * u), "Accélérer", Btn.GOLD, size = 13f) { trotTarget = min(18f, trotTarget + 1f) }
            gui.text(c, "Allure visée : ${(trotTarget * 3.6f).toInt()} km/h", 12f * u, by - 8f * u, 11f, Pal.CREAM, shadow = true)
        } else {
            val maxG = if (mode.kind == RideKind.DRESSAGE || mode.kind == RideKind.PLAT || mode.kind == RideKind.HUNTER) 3 else 4
            val labels = listOf("Arrêt", "Pas", "Trot", "Galop", "Grand galop")
            for (i in 0..maxG) {
                val r = RectF(12f * u + i * (bw + 6f * u), by, 12f * u + i * (bw + 6f * u) + bw, h - 10f * u)
                gui.button(c, r, labels[i], if (gaitIdx == i) Btn.GOLD else Btn.NORMAL, size = 11.5f) { setGait(i); app.sound.play(SoundFx.S.CLICK, 0.4f) }
            }
        }
        if ((mode.kind == RideKind.PISTE || isTrot) && rivals.isNotEmpty()) {
            gui.button(c, RectF(w - 270f * u, h - 52f * u, w - 186f * u, h - 30f * u), "▲ corde", Btn.GHOST, size = 10.5f) { changeLane(-1) }
            gui.button(c, RectF(w - 270f * u, h - 28f * u, w - 186f * u, h - 6f * u), "▼ large", Btn.GHOST, size = 10.5f) { changeLane(1) }
        }
        val big = RectF(w - 176f * u, h - 96f * u, w - 14f * u, h - 12f * u)
        when (mode.kind) {
            RideKind.OBSTACLES, RideKind.CROSS, RideKind.HUNTER -> gui.button(c, big, "SAUTER", Btn.PRIMARY, size = 20f) { requestJump() }
            RideKind.PISTE -> gui.button(c, big, "POUSSER", Btn.PRIMARY, size = 18f, sub = if (mode.competition) "cravache ${whips}/8" else "dernière ligne droite") {
                if (gaitIdx < 4) setGait(4); push = 1.5f; whips++; app.sound.play(SoundFx.S.SNORT, 0.4f)
            }
            RideKind.WESTERN -> gui.button(c, big, "TOURNER", Btn.PRIMARY, size = 18f, sub = "au tonneau") { requestTurn() }
            RideKind.DRESSAGE, RideKind.PLAT -> {
                // bouton de cadence qui pulse à chaque foulée
                val pulse = if (beatFlash > 0f) 1.05f else 1f
                val rr = RectF(big.centerX() - big.width() / 2 * pulse, big.centerY() - big.height() / 2 * pulse, big.centerX() + big.width() / 2 * pulse, big.centerY() + big.height() / 2 * pulse)
                gui.button(c, rr, "RYTHME", if (beatFlash > 0f) Btn.GOLD else Btn.PRIMARY, size = 18f, sub = "à chaque foulée") { tapRhythm() }
                if (mode.kind == RideKind.DRESSAGE) {
                    gui.button(c, RectF(w - 270f * u, h - 52f * u, w - 186f * u, h - 30f * u), if (extending) "Allonger ✓" else "Allonger", if (extending) Btn.GOLD else Btn.GHOST, size = 10.5f) { extending = !extending }
                    gui.button(c, RectF(w - 270f * u, h - 28f * u, w - 186f * u, h - 6f * u), "Reculer", Btn.GHOST, size = 10.5f) { if (v < 0.5f) { reinBack = 1.2f; say("Reculer : un, deux, trois, quatre…") } else say("Il faut être à l'arrêt pour reculer.") }
                }
            }
            RideKind.BALADE, RideKind.ENDURANCE -> gui.button(c, RectF(w - 170f * u, h - 56f * u, w - 14f * u, h - 10f * u), if (mode.kind == RideKind.BALADE) "Rentrer" else "Abandonner", Btn.GOLD, size = 14f) {
                if (mode.kind == RideKind.BALADE) stopBalade() else { eliminated = true; endEndurance() }
            }
            RideKind.TROT -> gui.button(c, big, "ENCOURAGER", Btn.PRIMARY, size = 15f, sub = "de la voix") { push = 1f; trotTarget = min(18f, trotTarget + 0.5f) }
        }
    }

    private fun changeLane(d: Int) {
        val nl = (lane + d).coerceIn(0, 6)
        if (rivals.any { it.lane == nl && abs(it.d - dist) < 2.5f }) { say("Impossible : un concurrent vous bloque."); return }
        lane = nl
        if (rivals.any { it.lane == lane }) {} // les couloirs sont libres en dehors des concurrents
    }

    private fun drawResult(c: Canvas) {
        val u = gui.u; val w = gui.w; val h = gui.h
        gui.modal(c, dim = false)
        val r = RectF(w / 2 - 220f * u, h / 2 - 115f * u, w / 2 + 220f * u, h / 2 + 115f * u)
        Ink.parchment(c, r, u)
        gui.text(c, resultText, r.centerX(), r.top + 42f * u, 24f, Ink.INK, Paint.Align.CENTER, Ink.hand)
        gui.text(c, resultSub, r.centerX(), r.top + 64f * u, 11f, Ink.INK, Paint.Align.CENTER, maxW = r.width() - 30f * u)
        val cr = compResult
        val body = when {
            cr != null -> "Classement : ${if (cr.rank == 1) "1er" else "${cr.rank}e"} sur ${cr.of}${if (cr.prize > 0) " · gains ${fmtMoney(cr.prize)}" else ""}."
            trainMsg.isNotEmpty() -> trainMsg + " Confiance : ${horse.confidence.toInt()}."
            else -> ""
        }
        gui.wrap(c, body, r.left + 22f * u, r.top + 94f * u, r.width() - 44f * u, 12f, if (cr != null && cr.rank <= 3) Pal.OK else Ink.INK)
        gui.button(c, RectF(r.centerX() - 90f * u, r.bottom - 50f * u, r.centerX() + 90f * u, r.bottom - 14f * u), "Retour", Btn.PRIMARY, size = 14f) { app.pop() }
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
