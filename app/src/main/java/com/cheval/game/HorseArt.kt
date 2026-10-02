package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.cheval.core.Coat
import com.cheval.core.CoatLook
import com.cheval.core.Horse
import com.cheval.core.Morpho
import com.cheval.core.Rng
import com.cheval.core.Sex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Allures, avec leur schéma de foulée réel (ordre des posers, phase d'appui, longueur et cadence). */
enum class Gait(
    val label: String,
    /** Décalage de phase des membres : AG (antérieur gauche), PG, AD, PD. */
    val offsets: FloatArray,
    val duty: Float,
    val stride: Float,   // course du sabot pendant l'appui, en hauteurs au garrot
    val freq: Float,     // foulées par seconde
    val liftFront: Float,
    val liftHind: Float,
    val bob: Float,
    val pitch: Float,
    val nod: Float,
) {
    ARRET("Arrêt", floatArrayOf(0f, 0f, 0f, 0f), 1f, 0f, 0.25f, 0f, 0f, 0.003f, 0f, 1.5f),
    PAS("Pas", floatArrayOf(0.25f, 0f, 0.75f, 0.5f), 0.62f, 0.62f, 0.95f, 0.1f, 0.08f, 0.012f, 0.6f, 5f),
    TROT("Trot", floatArrayOf(0f, 0.5f, 0.5f, 0f), 0.42f, 0.6f, 1.35f, 0.2f, 0.15f, 0.03f, 0.8f, 1f),
    GALOP("Galop", floatArrayOf(0.45f, 0.25f, 0.25f, 0f), 0.36f, 0.7f, 1.65f, 0.22f, 0.17f, 0.045f, 4.5f, 8f),
    GRAND_GALOP("Grand galop", floatArrayOf(0.42f, 0.12f, 0.3f, 0f), 0.27f, 0.9f, 2.2f, 0.26f, 0.2f, 0.035f, 3.5f, 7f);

    /** Vitesse (hauteurs au garrot par seconde) sans glissement des sabots. */
    val speed get() = if (this == ARRET) 0f else stride / duty * freq
}

/** Attitude du cheval à un instant. Les angles sont en degrés. */
class HorsePose {
    var gait = Gait.ARRET
    var phase = 0f
    /** Angle de l'encolure au-dessus de l'horizontale (45 au repos, 65 en alerte, −45 en broutant). */
    var neck = 45f
    /** Angle du chanfrein par rapport à la verticale (0 = vertical, 45 = détendu, 80 = nez au vent). */
    var head = 42f
    var ears = 0f      // −1 couchées, 0 neutres, 1 pointées en avant
    var tailSwing = 0f
    var tailLift = 0f
    var blink = 0f
    /** Saut : −1 = aucun, 0..1 = de la battue à la réception. */
    var jump = -1f
    var jumpHeight = 1.2f
    var breathe = 0f
    var speedBlend = 0f // 0..1 crins au vent
    /** 0 debout … 1 couché sur le sternum (repos, sommeil). */
    var lie = 0f

    fun copyFrom(o: HorsePose) { gait = o.gait; phase = o.phase; neck = o.neck; head = o.head; ears = o.ears; tailSwing = o.tailSwing; tailLift = o.tailLift; blink = o.blink; jump = o.jump; jumpHeight = o.jumpHeight; breathe = o.breathe; speedBlend = o.speedBlend; lie = o.lie }
}

/** Ce qu'il faut savoir d'un cheval pour le dessiner. */
class Appearance(
    val look: CoatLook,
    val m: Morpho,
    heightCm: Float,
    val bcs: Float,
    val muscle: Float,
    val dirt: Float,
    val age: Float,
    val stallion: Boolean,
    var rug: Boolean,
    val winter: Boolean,
) {
    val foal = ((3.5f - age) / 3.5f).coerceIn(0f, 1f) // 1 = nouveau-né
    val H = heightCm
    val L = H * 0.98f * m.bodyLength * (1f - foal * 0.2f)
    val leg = m.legLength * (1f + foal * 0.24f)
    val neckLen = H * 0.43f * m.neckLength * (1f - foal * 0.25f)
    val headLen = H * 0.39f * m.headSize * (1f + foal * 0.12f)
    val girth = 1f + (bcs - 5f) * 0.035f

    companion object {
        fun of(h: Horse, day: Int, winter: Boolean = false): Appearance =
            Appearance(h.look(day), h.morpho, h.heightAt(day), h.bcs, h.muscle, 100f - h.cleanliness, h.age(day), h.sex == Sex.ETALON, h.rugged, winter)
    }
}

/** Selle, filet, cavalier. */
class Tack(var saddle: Boolean = false, var bridle: Boolean = false, var rider: Boolean = false, var riderPost: Float = 0f, var riderForward: Float = 0f,
           var padColor: Int = 0xFF1F3B63.toInt(), var jacket: Int = 0xFF1C2A44.toInt(), var number: Int = 0)

/**
 * Rendu d'un cheval de profil, construit sur un squelette : tronc, encolure et tête articulés,
 * membres en cinématique inverse (coude-genou-boulet, cuisse-jarret-boulet), robe génétique
 * (dégradés, pommelures, taches, balzanes, liste), propreté, état corporel, couverture et harnachement.
 * Unités du modèle : centimètres, y vers le bas, sol en y = 0, cheval tourné vers la droite.
 */
object HorseArt {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bodyPath = Path()
    private val neckPath = Path()
    private val headPath = Path()
    private val tmp = Path()
    private val tmp2 = Path()

    // ---------------------------------------------------------------- couleurs
    fun shade(c: Int, k: Float): Int = Color.argb(Color.alpha(c), (Color.red(c) * k).toInt().coerceIn(0, 255), (Color.green(c) * k).toInt().coerceIn(0, 255), (Color.blue(c) * k).toInt().coerceIn(0, 255))
    fun lighten(c: Int, k: Float): Int = Coat.mix(c, Color.WHITE, k)
    fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    private fun mix(a: Int, b: Int, t: Float) = Coat.mix(a, b, t)

    // ---------------------------------------------------------------- repère du tronc
    private var cosP = 1f
    private var sinP = 0f
    private var bodyDy = 0f
    private var pivotY = 0f
    private fun bx(x: Float, y: Float): Float = x * cosP - (y - pivotY) * sinP
    private fun by(x: Float, y: Float): Float = x * sinP + (y - pivotY) * cosP + pivotY + bodyDy

    private class P(var x: Float = 0f, var y: Float = 0f) { fun set(a: Float, b: Float): P { x = a; y = b; return this } }

    private class Leg {
        val top = P(); val mid = P(); val fet = P(); val cor = P(); val hoofDir = P()
        var front = false; var near = false; var white = 0; var stance = true
    }
    private val legs = Array(4) { Leg() }

    // tête : origine à la nuque, axe nuque → bout du nez
    private var hpx = 0f; private var hpy = 0f; private var hdx = 0f; private var hdy = 1f; private var hnx = 1f; private var hny = 0f; private var hL = 1f
    private fun hx(u: Float, v: Float) = hpx + hdx * u * hL + hnx * v * hL
    private fun hy(u: Float, v: Float) = hpy + hdy * u * hL + hny * v * hL

    /** Membre à deux segments : place l'articulation intermédiaire (genou vers l'avant, jarret vers l'arrière). */
    private fun ik(ax: Float, ay: Float, tx: Float, ty: Float, l1: Float, l2: Float, bendForward: Boolean, out: P) {
        val dx = tx - ax; val dy = ty - ay
        var d = sqrt(dx * dx + dy * dy)
        d = d.coerceIn(abs(l1 - l2) + 0.01f, (l1 + l2) * 0.999f)
        val a = acos(((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)).coerceIn(-1f, 1f))
        val base = atan2(dy, dx)
        val s1 = base - a; val s2 = base + a
        val x1 = cos(s1); val x2 = cos(s2)
        val pick = if (bendForward) (if (x1 > x2) s1 else s2) else (if (x1 < x2) s1 else s2)
        out.set(ax + cos(pick) * l1, ay + sin(pick) * l1)
    }

    /**
     * Dessine le cheval. [x], [groundY] : position à l'écran du point au sol sous le milieu du tronc.
     * [scale] : pixels par centimètre. [light] : 0 nuit … 1 plein jour.
     */
    /** Style illustré : contour d'encre autour de la silhouette et grain d'aquarelle. */
    var inkMode = true
    private var sil = false
    private var inkW = 1f
    private val silFilter = android.graphics.PorterDuffColorFilter(Ink.INK, android.graphics.PorterDuff.Mode.SRC_IN)
    private val grainMatrix = android.graphics.Matrix()

    fun draw(c: Canvas, a: Appearance, pose: HorsePose, x: Float, groundY: Float, scale: Float, facingRight: Boolean = true,
             light: Float = 1f, tack: Tack? = null, shadow: Boolean = true) {
        if (inkMode) {
            // 1er passage : silhouette élargie, entièrement à l'encre ; le 2e passage la recouvre et ne laisse que le contour.
            inkW = max(a.H * 0.009f, 1.8f / scale)
            sil = true
            fill.colorFilter = silFilter; fill.style = Paint.Style.FILL_AND_STROKE; fill.strokeWidth = inkW * 2f; fill.strokeJoin = Paint.Join.ROUND
            stroke.colorFilter = silFilter
            try { drawInternal(c, a, pose, x, groundY, scale, facingRight, light * 0f + 1f, tack, false) } finally {
                sil = false
                fill.colorFilter = null; fill.style = Paint.Style.FILL; fill.strokeWidth = 0f
                stroke.colorFilter = null
            }
        }
        drawInternal(c, a, pose, x, groundY, scale, facingRight, light, tack, shadow)
        if (inkMode) {
            // grain d'aquarelle à l'échelle de l'écran
            c.save(); c.translate(x, groundY); c.scale(if (facingRight) scale else -scale, scale)
            grainMatrix.setScale(1f / scale, 1f / scale)
            val gpaint = Ink.grainPaint(110); gpaint.shader.setLocalMatrix(grainMatrix)
            c.drawPath(bodyPath, gpaint); c.drawPath(neckPath, gpaint); c.drawPath(headPath, gpaint)
            gpaint.shader.setLocalMatrix(null)
            c.restore()
        }
    }

    private var curScale = 1f

    private fun drawInternal(c: Canvas, a: Appearance, pose: HorsePose, x: Float, groundY: Float, scale: Float, facingRight: Boolean,
             light: Float, tack: Tack?, shadow: Boolean) {
        curScale = scale
        c.save()
        c.translate(x, groundY)
        c.scale(if (facingRight) scale else -scale, scale)
        val H = a.H; val L = a.L
        val g = pose.gait
        val look = a.look

        // ---------------- mouvement du tronc
        val ph = pose.phase
        val tw = (2 * PI * ph).toFloat()
        var bob = when (g) {
            Gait.PAS, Gait.TROT -> -abs(sin(tw)) * g.bob * H + g.bob * H * 0.5f
            Gait.ARRET -> sin(pose.breathe * 2f * PI.toFloat()) * g.bob * H
            else -> sin(tw) * g.bob * H
        }
        var pitchDeg = when (g) { Gait.GALOP, Gait.GRAND_GALOP -> sin(tw + 0.6f) * g.pitch; else -> sin(tw * 2) * g.pitch * 0.3f }
        var jumpLift = 0f
        val jt = pose.jump
        if (jt >= 0f) {
            val apex = pose.jumpHeight * 100f * (H / 165f) * 0.9f
            jumpLift = if (jt in 0.2f..0.8f) { val u = (jt - 0.2f) / 0.6f; 4f * u * (1 - u) * apex } else 0f
            pitchDeg = when {
                jt < 0.25f -> -24f * (jt / 0.25f)
                jt < 0.5f -> -24f + 24f * ((jt - 0.25f) / 0.25f)
                jt < 0.8f -> 22f * ((jt - 0.5f) / 0.3f)
                else -> 22f * (1f - (jt - 0.8f) / 0.2f)
            }
            bob = 0f
        }
        val pr = Math.toRadians(pitchDeg.toDouble()).toFloat()
        cosP = cos(pr); sinP = sin(pr)
        pivotY = -H * 0.7f
        val lie = pose.lie.coerceIn(0f, 1f)
        bodyDy = bob - jumpLift + lie * H * 0.4f * a.leg
        val nod = if (jt >= 0f) 0f else sin(tw * (if (g == Gait.PAS) 2f else 1f)) * g.nod

        // ---------------- ossature
        val legH = H * 0.57f * a.leg
        val chestY = -legH
        val topY = -H
        val elbowX = L * 0.31f; val elbowY = chestY - H * 0.03f
        val hipJX = -L * 0.34f; val hipJY = chestY + H * 0.0f
        val foreArm = H * 0.29f * a.leg; val fCannon = H * 0.19f * a.leg
        val gaskin = H * 0.265f * a.leg; val hCannon = H * 0.225f * a.leg
        val pastern = H * 0.06f * a.leg
        val hoofH = H * 0.055f

        // ---------------- membres
        for (i in 0..3) {
            val lg = legs[i]
            lg.front = i == 0 || i == 2
            lg.near = i < 2
            lg.white = look.legWhite[if (lg.front) (if (lg.near) 0 else 1) else (if (lg.near) 2 else 3)]
            val jx = if (lg.front) elbowX else hipJX
            val jy = if (lg.front) elbowY else hipJY
            val depthOff = if (lg.near) 0f else -H * 0.035f
            lg.top.set(bx(jx + depthOff, jy), by(jx + depthOff, jy))
            val restX = (if (lg.front) L * 0.33f else -L * 0.44f) + depthOff + (if (!lg.near && g == Gait.ARRET) H * 0.04f * (if (lg.front) -1f else 1f) else 0f)
            var hx: Float; var hy: Float
            var stanceNow = true
            var swingT = 0f
            if (g == Gait.ARRET) { hx = restX; hy = 0f }
            else {
                val p = ((ph - g.offsets[i]) % 1f + 1f) % 1f
                val st = g.stride * H
                if (p < g.duty) { hx = restX + st * (0.5f - p / g.duty); hy = 0f }
                else {
                    stanceNow = false
                    val t = (p - g.duty) / (1f - g.duty)
                    swingT = t
                    val e = t * t * (3 - 2 * t)
                    hx = restX + st * (-0.5f + e)
                    hy = -(if (lg.front) g.liftFront else g.liftHind) * H * sin(PI.toFloat() * t)
                }
            }
            if (jt >= 0f) {
                stanceNow = false
                val ref = if (lg.front) elbowX else hipJX
                when {
                    jt < 0.2f -> if (lg.front) { hx = ref + H * 0.12f; hy = -H * 0.28f * (jt / 0.2f) } else { hx = ref + H * 0.18f; hy = 0f; stanceNow = true }
                    jt < 0.55f -> {
                        val k = (jt - 0.2f) / 0.35f
                        if (lg.front) { hx = bx(ref + H * 0.05f, chestY + H * 0.12f); hy = by(ref + H * 0.05f, chestY + H * 0.12f) }
                        else { hx = bx(ref - H * (0.15f + 0.25f * k), chestY + H * (0.36f - 0.16f * k)); hy = by(ref - H * (0.15f + 0.25f * k), chestY + H * (0.36f - 0.16f * k)) }
                    }
                    jt < 0.8f -> {
                        val k = (jt - 0.55f) / 0.25f
                        if (lg.front) { hx = bx(ref + H * (0.2f + 0.08f * k), chestY + H * 0.5f); hy = by(ref + H * (0.2f + 0.08f * k), chestY + H * 0.5f) }
                        else { hx = bx(ref - H * (0.3f - 0.2f * k), chestY + H * 0.3f); hy = by(ref - H * (0.3f - 0.2f * k), chestY + H * 0.3f) }
                    }
                    else -> { val k = (jt - 0.8f) / 0.2f
                        if (lg.front) { hx = ref + H * 0.06f; hy = 0f; stanceNow = true } else { hx = ref - H * 0.06f * (1 - k); hy = -H * 0.14f * (1 - k) } }
                }
                hy = min(hy, 0f)
                swingT = if (stanceNow) 0f else 0.6f
            }
            if (lie > 0f) {
                // membres repliés sous le corps
                val fx = if (lg.front) elbowX + H * 0.16f else hipJX + H * 0.3f
                hx += (fx + depthOff - hx) * lie
                hy += (0f - hy) * lie
                swingT += (0.9f - swingT) * lie
                stanceNow = lie < 0.5f && stanceNow
            }
            lg.stance = stanceNow
            // Paturon : incliné à ~55° en appui, fléchi pendant le soutien
            val pasternAng = if (stanceNow) -122f else (-122f + 95f * sin(PI.toFloat() * swingT))
            val pa = Math.toRadians(pasternAng.toDouble()).toFloat()
            lg.cor.set(hx, hy - hoofH)
            lg.fet.set(lg.cor.x + cos(pa) * pastern, lg.cor.y + sin(pa) * pastern)
            lg.hoofDir.set(-cos(pa), -sin(pa))
            if (lg.front) ik(lg.top.x, lg.top.y, lg.fet.x, lg.fet.y, foreArm, fCannon, true, lg.mid)
            else ik(lg.top.x, lg.top.y, lg.fet.x, lg.fet.y, gaskin, hCannon, false, lg.mid)
        }

        // ---------------- ombre portée
        if (shadow && !sil) {
            fill.shader = null
            fill.color = Color.argb((60 * light).toInt() + 25, 0, 0, 0)
            val sw = L * 0.6f * (1f - min(0.5f, jumpLift / H))
            c.drawOval(-sw, -H * 0.025f, sw, H * 0.025f, fill)
        }

        val dirtK = (a.dirt / 100f).coerceIn(0f, 1f)
        val lightK = 0.32f + 0.68f * light
        val bodyCol = shade(mix(look.body, 0xFF8A7B66.toInt(), dirtK * 0.22f), lightK)
        val topCol = lighten(bodyCol, 0.08f + look.shine * 0.08f)
        val botCol = shade(bodyCol, 0.62f)
        val grad = LinearGradient(0f, topY - H * 0.45f, 0f, chestY + H * 0.08f, intArrayOf(topCol, bodyCol, botCol), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        val farGrad = LinearGradient(0f, topY - H * 0.45f, 0f, chestY + H * 0.08f, intArrayOf(shade(topCol, 0.7f), shade(bodyCol, 0.7f), shade(botCol, 0.7f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)

        // ---------------- tronc (chemin)
        val belly = chestY + H * 0.02f + (a.girth - 1f) * H * 0.35f
        val muscle = 0.5f + a.muscle / 200f
        bodyPath.reset()
        fun mt(px: Float, py: Float) = bodyPath.moveTo(bx(px, py), by(px, py))
        fun ct(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = bodyPath.cubicTo(bx(x1, y1), by(x1, y1), bx(x2, y2), by(x2, y2), bx(x3, y3), by(x3, y3))
        val witX = L * 0.25f
        val dip = H * (0.045f + (1f - muscle) * 0.02f + a.foal * 0.01f)
        val croupY = topY + H * 0.005f
        val tailX = -L * 0.47f; val tailY = topY + H * (0.07f - a.m.tailSet * 0.03f)
        mt(witX, topY)
        ct(L * 0.12f, topY + dip * 0.6f, L * 0.02f, topY + dip, -L * 0.1f, topY + dip * 0.95f)
        ct(-L * 0.2f, topY + dip * 0.85f, -L * 0.27f, croupY - H * 0.015f * muscle, -L * 0.33f, croupY)
        ct(-L * 0.4f, croupY + H * 0.01f, -L * 0.45f, tailY - H * 0.02f, tailX, tailY)
        // pointe de la fesse puis arrière de la cuisse
        ct(-L * 0.53f, tailY + H * 0.04f, -L * (0.545f + muscle * 0.01f), tailY + H * 0.14f, -L * 0.52f, topY + H * 0.27f)
        ct(-L * 0.5f, chestY - H * 0.06f, -L * 0.46f, chestY + H * 0.0f, -L * 0.42f, chestY + H * 0.06f)
        // pli du grasset, ventre
        ct(-L * 0.34f, chestY + H * 0.08f, -L * 0.28f, chestY + H * 0.02f, -L * 0.22f, belly - H * 0.02f)
        ct(-L * 0.1f, belly + H * 0.02f * a.girth, L * 0.1f, belly + H * 0.025f, L * 0.24f, chestY + H * 0.015f)
        // passage de sangle, coude, poitrail, pointe de l'épaule
        ct(L * 0.32f, chestY + H * 0.01f, L * 0.4f, chestY + H * 0.0f, L * 0.45f, chestY - H * 0.06f)
        ct(L * 0.5f, chestY - H * 0.1f, L * 0.53f, chestY - H * 0.17f, L * 0.52f, topY + H * 0.3f)
        ct(L * 0.48f, topY + H * 0.2f, L * 0.36f, topY + H * 0.04f, witX, topY)
        bodyPath.close()

        // ---------------- encolure
        val nb1x = L * 0.2f; val nb1y = topY + H * 0.01f            // base, sur le garrot
        val nb2x = L * 0.5f; val nb2y = topY + H * 0.33f            // base, devant le poitrail
        val ncx = L * 0.38f; val ncy = topY + H * 0.12f
        val nAng = Math.toRadians((pose.neck + nod * 0.4f).toDouble()).toFloat()
        val pollBx = ncx + cos(nAng) * a.neckLen; val pollBy = ncy - sin(nAng) * a.neckLen
        val pollX = bx(pollBx, pollBy); val pollY = by(pollBx, pollBy)
        val hAng = Math.toRadians((pose.head + nod * 0.6f - pitchDeg * 0.5f).toDouble()).toFloat()
        hpx = pollX; hpy = pollY; hdx = sin(hAng); hdy = cos(hAng); hnx = cos(hAng); hny = -sin(hAng); hL = a.headLen
        val npx = -sin(nAng - pr); val npy = -cos(nAng - pr) // normale « dessus de l'encolure » en coordonnées écran
        val ndx = cos(nAng - pr); val ndy = -sin(nAng - pr)
        val crestH = H * (0.025f + a.m.neckArch * 0.045f + (if (a.stallion) 0.03f else 0f)) * (1f - a.foal * 0.6f) * (0.8f + muscle * 0.3f)
        val bcx = bx(ncx, ncy); val bcy = by(ncx, ncy)
        val nl = a.neckLen
        // crête : du garrot à la nuque
        val c1x = bcx + ndx * nl * 0.35f + npx * (H * 0.12f + crestH); val c1y = bcy + ndy * nl * 0.35f + npy * (H * 0.12f + crestH)
        val c2x = bcx + ndx * nl * 0.78f + npx * (H * 0.08f + crestH * 0.7f); val c2y = bcy + ndy * nl * 0.78f + npy * (H * 0.08f + crestH * 0.7f)
        // gorge : sous la tête vers le poitrail
        val g1x = bcx + ndx * nl * 0.6f - npx * H * 0.075f; val g1y = bcy + ndy * nl * 0.6f - npy * H * 0.075f
        val g2x = bcx + ndx * nl * 0.15f - npx * H * 0.13f; val g2y = bcy + ndy * nl * 0.15f - npy * H * 0.13f
        neckPath.reset()
        neckPath.moveTo(bx(nb1x - L * 0.02f, nb1y + H * 0.03f), by(nb1x - L * 0.02f, nb1y + H * 0.03f))
        neckPath.lineTo(bx(nb1x, nb1y - H * 0.005f), by(nb1x, nb1y - H * 0.005f))
        neckPath.cubicTo(c1x, c1y, c2x, c2y, hx(0.0f, 0.1f), hy(0.0f, 0.1f))
        neckPath.lineTo(hx(0.2f, -0.24f), hy(0.2f, -0.24f))
        neckPath.cubicTo(hx(0.1f, -0.32f) * 0.5f + g1x * 0.5f, hy(0.1f, -0.32f) * 0.5f + g1y * 0.5f, g2x, g2y, bx(nb2x, nb2y), by(nb2x, nb2y))
        neckPath.lineTo(bx(L * 0.3f, topY + H * 0.32f), by(L * 0.3f, topY + H * 0.32f))
        neckPath.close()

        // ---------------- tête (profil selon la race : concave, rectiligne ou busqué)
        val prof = a.m.headProfile
        headPath.reset()
        headPath.moveTo(hx(-0.03f, 0.08f), hy(-0.03f, 0.08f))
        headPath.cubicTo(hx(0.06f, 0.17f), hy(0.06f, 0.17f), hx(0.18f, 0.2f), hy(0.18f, 0.2f), hx(0.3f, 0.19f), hy(0.3f, 0.19f))
        headPath.cubicTo(hx(0.5f, 0.17f + prof * 0.035f), hy(0.5f, 0.17f + prof * 0.035f), hx(0.74f, 0.145f + prof * 0.02f), hy(0.74f, 0.145f + prof * 0.02f), hx(0.92f, 0.12f), hy(0.92f, 0.12f))
        headPath.cubicTo(hx(1.01f, 0.105f), hy(1.01f, 0.105f), hx(1.03f, -0.01f), hy(1.03f, -0.01f), hx(0.98f, -0.05f), hy(0.98f, -0.05f))
        headPath.cubicTo(hx(0.96f, -0.09f), hy(0.96f, -0.09f), hx(0.9f, -0.115f), hy(0.9f, -0.115f), hx(0.84f, -0.1f), hy(0.84f, -0.1f))
        headPath.cubicTo(hx(0.7f, -0.1f), hy(0.7f, -0.1f), hx(0.56f, -0.14f), hy(0.56f, -0.14f), hx(0.44f, -0.23f), hy(0.44f, -0.23f))
        headPath.cubicTo(hx(0.34f, -0.32f), hy(0.34f, -0.32f), hx(0.14f, -0.32f), hy(0.14f, -0.32f), hx(0.1f, -0.22f), hy(0.1f, -0.22f))
        headPath.lineTo(hx(-0.03f, -0.06f), hy(-0.03f, -0.06f))
        headPath.close()

        // ---------------- dessin, du plus lointain au plus proche
        legUpper(c, a, legs[3], bodyCol, 0.72f, H)
        legUpper(c, a, legs[2], bodyCol, 0.72f, H)
        drawLeg(c, a, legs[3], farGrad, lightK, 0.7f, H, look)
        drawLeg(c, a, legs[2], farGrad, lightK, 0.7f, H, look)
        drawTail(c, a, pose, bx(tailX, tailY), by(tailX, tailY), lightK)
        drawEar(c, a, pose, shade(bodyCol, 0.7f), far = true)
        legUpper(c, a, legs[1], bodyCol, 1f, H)
        legUpper(c, a, legs[0], bodyCol, 1f, H)

        fill.shader = grad
        c.drawPath(neckPath, fill)
        c.drawPath(bodyPath, fill)
        fill.shader = null
        detailShading(c, a, bodyCol, look, H, L, chestY, topY, light)
        coatPatterns(c, a, look, H, L, topY, chestY, lightK)
        if (a.bcs < 3.8f) ribs(c, bodyCol, H, L, topY, chestY, a.bcs)
        if (!sil) {
            // sillon de la jugulaire et attache encolure-épaule
            c.save(); c.clipPath(neckPath)
            stroke.shader = null
            stroke.color = alpha(shade(bodyCol, 0.45f), 0.22f * light); stroke.strokeWidth = H * 0.007f
            tmp.reset()
            tmp.moveTo(bcx + ndx * nl * 0.72f - npx * H * 0.035f, bcy + ndy * nl * 0.72f - npy * H * 0.035f)
            tmp.quadTo(bcx + ndx * nl * 0.4f - npx * H * 0.06f, bcy + ndy * nl * 0.4f - npy * H * 0.06f, bcx + ndx * nl * 0.05f - npx * H * 0.075f, bcy + ndy * nl * 0.05f - npy * H * 0.075f)
            c.drawPath(tmp, stroke)
            stroke.color = alpha(shade(bodyCol, 0.45f), 0.14f * light); stroke.strokeWidth = H * 0.009f
            tmp.reset(); tmp.moveTo(bx(L * 0.24f, topY + H * 0.03f), by(L * 0.24f, topY + H * 0.03f))
            tmp.quadTo(bx(L * 0.4f, topY + H * 0.12f), by(L * 0.4f, topY + H * 0.12f), bx(L * 0.47f, topY + H * 0.3f), by(L * 0.47f, topY + H * 0.3f))
            c.drawPath(tmp, stroke)
            // reflet le long de la crête
            stroke.color = alpha(Color.WHITE, (0.06f + look.shine * 0.1f) * light); stroke.strokeWidth = H * 0.02f
            tmp.reset(); tmp.moveTo(bcx + ndx * nl * 0.15f + npx * H * 0.09f, bcy + ndy * nl * 0.15f + npy * H * 0.09f)
            tmp.quadTo(c1x - npx * H * 0.02f, c1y - npy * H * 0.02f, c2x - npx * H * 0.03f, c2y - npy * H * 0.03f)
            c.drawPath(tmp, stroke)
            c.restore()
        }

        fill.shader = grad
        c.drawPath(headPath, fill)
        fill.shader = null
        headPatterns(c, look, lightK)
        drawFace(c, look, pose, lightK, hAng)
        drawMane(c, a, pose, bx(nb1x - L * 0.03f, nb1y + H * 0.02f), by(nb1x - L * 0.03f, nb1y + H * 0.02f), c1x, c1y, c2x, c2y, npx, npy, lightK)
        drawEar(c, a, pose, bodyCol, far = false)

        if (a.rug && tack?.saddle != true) drawRug(c, H, L, topY, chestY, lightK)
        if (tack != null && tack.saddle) drawSaddle(c, H, L, topY, chestY, tack, lightK)

        drawLeg(c, a, legs[1], grad, lightK, 1f, H, look)
        drawLeg(c, a, legs[0], grad, lightK, 1f, H, look)

        if (tack != null && tack.saddle) {
            stroke.shader = null
            stroke.color = shade(0xFF3A2A1E.toInt(), lightK); stroke.strokeWidth = H * 0.032f
            c.drawLine(bx(L * 0.21f, topY + H * 0.22f), by(L * 0.21f, topY + H * 0.22f), bx(L * 0.23f, chestY + H * 0.02f), by(L * 0.23f, chestY + H * 0.02f), stroke)
        }
        if (tack != null && tack.bridle) drawBridle(c, lightK)
        if (tack != null && tack.rider) drawRider(c, H, L, topY, tack, lightK)
        c.restore()
    }

    // ---------------------------------------------------------------- détails du corps
    private fun soft(c: Canvas, cx: Float, cy: Float, r: Float, col: Int, a0: Float) {
        if (sil) return
        fill.color = -1; fill.shader = RadialGradient(cx, cy, r, intArrayOf(alpha(col, a0), alpha(col, a0 * 0.5f), alpha(col, 0f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun detailShading(c: Canvas, a: Appearance, bodyCol: Int, look: CoatLook, H: Float, L: Float, chestY: Float, topY: Float, light: Float) {
        if (sil) return
        c.save()
        c.clipPath(bodyPath)
        val hiA = (0.07f + look.shine * 0.14f) * light
        // volumes : épaule, côtes, croupe
        soft(c, bx(L * 0.33f, topY + H * 0.22f), by(L * 0.33f, topY + H * 0.22f), H * 0.2f, Color.WHITE, hiA)
        soft(c, bx(-L * 0.33f, topY + H * 0.14f), by(-L * 0.33f, topY + H * 0.14f), H * 0.24f, Color.WHITE, hiA * 1.1f)
        soft(c, bx(0f, topY + H * 0.16f), by(0f, topY + H * 0.16f), H * 0.2f, Color.WHITE, hiA * 0.6f)
        // creux : arrière de l'épaule, flanc, pli de la cuisse
        val dk = Color.BLACK
        soft(c, bx(L * 0.16f, topY + H * 0.3f), by(L * 0.16f, topY + H * 0.3f), H * 0.13f, dk, 0.1f)
        soft(c, bx(-L * 0.2f, topY + H * 0.2f), by(-L * 0.2f, topY + H * 0.2f), H * 0.12f, dk, 0.12f)
        soft(c, bx(-L * 0.3f, chestY - H * 0.05f), by(-L * 0.3f, chestY - H * 0.05f), H * 0.12f, dk, 0.12f)
        // reflet du poil sur le dos
        fill.color = -1; fill.shader = LinearGradient(0f, topY - H * 0.02f, 0f, topY + H * 0.14f, alpha(Color.WHITE, look.shine * 0.16f * light), alpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
        c.drawRect(-L, topY - H * 0.1f, L, topY + H * 0.16f, fill)
        // ombre sous le ventre
        fill.color = -1; fill.shader = LinearGradient(0f, chestY - H * 0.1f, 0f, chestY + H * 0.06f, alpha(Color.BLACK, 0f), alpha(Color.BLACK, 0.25f), Shader.TileMode.CLAMP)
        c.drawRect(-L, chestY - H * 0.12f, L, chestY + H * 0.1f, fill)
        fill.shader = null
        // ligne de l'épaule, très douce
        stroke.shader = null
        stroke.color = alpha(Color.BLACK, 0.07f); stroke.strokeWidth = H * 0.02f
        tmp.reset()
        tmp.moveTo(bx(L * 0.24f, topY + H * 0.04f), by(L * 0.24f, topY + H * 0.04f))
        tmp.cubicTo(bx(L * 0.26f, topY + H * 0.18f), by(L * 0.26f, topY + H * 0.18f), bx(L * 0.3f, chestY - H * 0.1f), by(L * 0.3f, chestY - H * 0.1f), bx(L * 0.36f, chestY - H * 0.02f), by(L * 0.36f, chestY - H * 0.02f))
        c.drawPath(tmp, stroke)
        // modelé à l'encre : épine de l'omoplate, triceps, pointe de la hanche, pli du grasset, sillon de la fesse
        val inkC = shade(bodyCol, 0.42f)
        fun curve(a0: Float, b0: Float, a1: Float, b1: Float, a2: Float, b2: Float, al: Float, wk: Float) {
            stroke.color = alpha(inkC, al * 0.7f * light); stroke.strokeWidth = H * wk * 1.3f
            tmp.reset(); tmp.moveTo(bx(a0, b0), by(a0, b0)); tmp.quadTo(bx(a1, b1), by(a1, b1), bx(a2, b2), by(a2, b2)); c.drawPath(tmp, stroke)
        }
        curve(L * 0.22f, topY + H * 0.07f, L * 0.27f, topY + H * 0.18f, L * 0.34f, topY + H * 0.27f, 0.22f, 0.007f)
        curve(L * 0.36f, chestY - H * 0.17f, L * 0.27f, chestY - H * 0.15f, L * 0.23f, chestY - H * 0.03f, 0.22f, 0.008f)
        curve(L * 0.48f, topY + H * 0.28f, L * 0.44f, topY + H * 0.33f, L * 0.45f, topY + H * 0.38f, 0.25f, 0.006f)
        curve(-L * 0.2f, topY + H * 0.06f, -L * 0.24f, topY + H * 0.02f, -L * 0.29f, topY + H * 0.07f, 0.2f, 0.006f)
        curve(-L * 0.19f, topY + H * 0.2f, -L * 0.25f, chestY - H * 0.12f, -L * 0.29f, chestY + H * 0.03f, 0.3f, 0.009f)
        curve(-L * 0.43f, topY + H * 0.1f, -L * 0.47f, topY + H * 0.24f, -L * 0.44f, chestY - H * 0.02f, 0.22f, 0.007f)
        curve(-L * 0.12f, chestY - H * 0.05f, L * 0.0f, chestY - H * 0.015f, L * 0.14f, chestY - H * 0.04f, 0.12f, 0.006f)
        // muscle : un peu plus de relief si le cheval est musclé
        if (a.muscle > 55f) {
            curve(-L * 0.33f, topY + H * 0.08f, -L * 0.38f, topY + H * 0.18f, -L * 0.36f, topY + H * 0.3f, (a.muscle - 55f) / 45f * 0.18f, 0.006f)
            curve(L * 0.3f, topY + H * 0.3f, L * 0.36f, chestY - H * 0.2f, L * 0.42f, chestY - H * 0.16f, (a.muscle - 55f) / 45f * 0.16f, 0.006f)
        }
        // poil : petites hachures dans le sens du poil (vers l'arrière et le bas)
        if (H * curScale > 90f) {
            val rp = Rng(look.seed xor 0x9A1)
            stroke.strokeWidth = H * 0.0035f
            repeat(140) {
                val px = rp.range(-0.5f, 0.5f) * L; val py = rp.range(topY + H * 0.03f, chestY)
                val dark = rp.chance(0.5f)
                stroke.color = if (dark) alpha(Color.BLACK, 0.045f) else alpha(Color.WHITE, 0.05f + look.shine * 0.04f)
                val ll = H * rp.range(0.012f, 0.025f)
                c.drawLine(bx(px, py), by(px, py), bx(px - ll * 0.8f, py + ll * 0.5f), by(px - ll * 0.8f, py + ll * 0.5f), stroke)
            }
        }
        // boue
        if (a.dirt > 35f) {
            val r = Rng(look.seed xor 0xD1A7)
            val mud = alpha(0xFF6B5638.toInt(), ((a.dirt - 35f) / 65f) * 0.5f)
            repeat(9) {
                val px = r.range(-0.45f, 0.45f) * L; val py = r.range(topY + H * 0.12f, chestY)
                soft(c, bx(px, py), by(px, py), H * r.range(0.04f, 0.08f), mud, 0.9f)
            }
        }
        c.restore()
    }

    private fun ribs(c: Canvas, bodyCol: Int, H: Float, L: Float, topY: Float, chestY: Float, bcs: Float) {
        if (sil) return
        c.save(); c.clipPath(bodyPath)
        stroke.color = alpha(shade(bodyCol, 0.55f), (3.8f - bcs) * 0.3f)
        stroke.strokeWidth = H * 0.008f
        for (k in 0..5) {
            val rx = L * (0.14f - k * 0.055f)
            tmp.reset(); tmp.moveTo(bx(rx, topY + H * 0.14f), by(rx, topY + H * 0.14f))
            tmp.quadTo(bx(rx - L * 0.035f, chestY - H * 0.12f), by(rx - L * 0.035f, chestY - H * 0.12f), bx(rx - L * 0.015f, chestY - H * 0.03f), by(rx - L * 0.015f, chestY - H * 0.03f))
            c.drawPath(tmp, stroke)
        }
        c.restore()
    }

    /** Tache organique : plusieurs ellipses qui se chevauchent. */
    private fun blob(c: Canvas, r: Rng, cx: Float, cy: Float, rx: Float, ry: Float, col: Int) {
        fill.color = col
        val n = 6
        for (i in 0 until n) {
            val ox = r.range(-0.55f, 0.55f) * rx; val oy = r.range(-0.55f, 0.55f) * ry
            val sx = rx * r.range(0.45f, 0.75f); val sy = ry * r.range(0.45f, 0.75f)
            c.save(); c.translate(cx + ox, cy + oy); c.rotate(r.range(-40f, 40f))
            c.drawOval(-sx, -sy, sx, sy, fill)
            c.restore()
        }
    }

    private fun coatPatterns(c: Canvas, a: Appearance, look: CoatLook, H: Float, L: Float, topY: Float, chestY: Float, lightK: Float) {
        if (sil) return
        val r = Rng(look.seed)
        val white = shade(0xFFF4F2EC.toInt(), lightK)
        c.save(); c.clipPath(bodyPath)
        if (look.dunStripe != 0) {
            stroke.color = alpha(shade(look.dunStripe, lightK), 0.8f); stroke.strokeWidth = H * 0.016f
            tmp.reset(); tmp.moveTo(bx(L * 0.25f, topY), by(L * 0.25f, topY))
            tmp.cubicTo(bx(L * 0.05f, topY + H * 0.045f), by(L * 0.05f, topY + H * 0.045f), bx(-L * 0.25f, topY + H * 0.035f), by(-L * 0.25f, topY + H * 0.035f), bx(-L * 0.47f, topY + H * 0.06f), by(-L * 0.47f, topY + H * 0.06f))
            c.drawPath(tmp, stroke)
        }
        if (look.dapples > 0.05f) {
            repeat(55) {
                val px = r.range(-0.5f, 0.42f) * L; val py = r.range(topY + H * 0.05f, chestY + H * 0.02f)
                val rr = H * r.range(0.02f, 0.035f)
                soft(c, bx(px, py), by(px, py), rr, white, 0.55f * look.dapples)
            }
        }
        if (look.greyLevel > 0.92f) {
            fill.color = alpha(shade(0xFF8C6A4A.toInt(), lightK), 0.45f)
            repeat(80) { val px = r.range(-0.5f, 0.5f) * L; val py = r.range(topY, chestY + H * 0.05f); c.drawCircle(bx(px, py), by(px, py), H * 0.0045f, fill) }
        }
        if (look.roan) {
            fill.color = alpha(white, 0.5f)
            repeat(1100) { val px = r.range(-0.5f, 0.45f) * L; val py = r.range(topY, chestY + H * 0.05f); c.drawCircle(bx(px, py), by(px, py), H * 0.004f, fill) }
        }
        if (look.sabino == 1) repeat(5) { blob(c, r, bx(r.range(-0.3f, 0.3f) * L, chestY), by(0f, chestY - H * r.range(0f, 0.06f)), H * 0.12f, H * 0.06f, alpha(white, 0.9f)) }
        if (look.sabino == 2) { fill.color = white; c.drawPaint(fill) }
        if (look.tobiano) {
            val n = r.range(2, 3)
            for (k in 0 until n) {
                val px = r.range(-0.42f, 0.15f) * L
                val w = r.range(0.1f, 0.18f) * L
                // grandes plaques verticales qui traversent la ligne du dos
                for (j in 0..3) blob(c, r, bx(px + r.range(-0.3f, 0.3f) * w, topY + H * (0.05f + j * 0.12f)), by(px, topY + H * (0.05f + j * 0.12f)), w, H * 0.12f, white)
            }
            blob(c, r, bx(0f, chestY), by(0f, chestY), L * 0.3f, H * 0.08f, white)
        }
        if (look.frame) repeat(3) {
            val px = r.range(-0.3f, 0.25f) * L; val py = r.range(topY + H * 0.2f, chestY - H * 0.08f)
            blob(c, r, bx(px, py), by(px, py), r.range(0.12f, 0.18f) * L, H * r.range(0.06f, 0.1f), white)
        }
        when (look.leopard) {
            1, 4 -> {
                tmp.reset()
                tmp.moveTo(bx(-L * 0.05f, topY - H * 0.1f), by(-L * 0.05f, topY - H * 0.1f))
                tmp.cubicTo(bx(-L * 0.02f, topY + H * 0.2f), by(-L * 0.02f, topY + H * 0.2f), bx(-L * 0.2f, topY + H * 0.32f), by(-L * 0.2f, topY + H * 0.32f), bx(-L * 0.45f, topY + H * 0.3f), by(-L * 0.45f, topY + H * 0.3f))
                tmp.lineTo(bx(-L * 0.7f, topY), by(-L * 0.7f, topY)); tmp.lineTo(bx(-L * 0.3f, topY - H * 0.2f), by(-L * 0.3f, topY - H * 0.2f)); tmp.close()
                fill.color = white; c.drawPath(tmp, fill)
                if (look.leopard == 1) { c.save(); c.clipPath(tmp); spots(c, look, bx(-L * 0.3f, topY + H * 0.12f), by(-L * 0.3f, topY + H * 0.12f), L * 0.25f, 16, lightK); c.restore() }
            }
            2, 3 -> { fill.color = white; c.drawPaint(fill); spots(c, look, bx(0f, topY + H * 0.22f), by(0f, topY + H * 0.22f), L * 0.55f, if (look.leopard == 2) 50 else 8, lightK) }
        }
        c.restore()
        c.save(); c.clipPath(neckPath)
        if (look.roan) { fill.color = alpha(white, 0.3f); repeat(300) { val px = r.range(0.2f, 0.75f) * L; val py = r.range(topY - H * 0.4f, topY + H * 0.3f); c.drawCircle(bx(px, py), by(px, py), H * 0.004f, fill) } }
        if (look.leopard == 2 || look.leopard == 3) { fill.color = white; c.drawPaint(fill); spots(c, look, bx(L * 0.5f, topY - H * 0.05f), by(L * 0.5f, topY - H * 0.05f), L * 0.3f, if (look.leopard == 2) 14 else 3, lightK) }
        if (look.tobiano && Rng(look.seed xor 99).chance(0.6f)) blob(c, Rng(look.seed xor 98), bx(L * 0.32f, topY + H * 0.1f), by(L * 0.32f, topY + H * 0.1f), H * 0.12f, H * 0.14f, white)
        if (look.sabino == 2) { fill.color = white; c.drawPaint(fill) }
        if (look.dapples > 0.05f) repeat(12) { val px = r.range(0.25f, 0.62f) * L; val py = r.range(topY - H * 0.3f, topY + H * 0.25f); soft(c, bx(px, py), by(px, py), H * 0.025f, white, 0.45f * look.dapples) }
        // muscle de l'encolure
        soft(c, bx(L * 0.42f, topY + H * 0.05f), by(L * 0.42f, topY + H * 0.05f), H * 0.14f, Color.WHITE, 0.08f * lightK)
        c.restore()
    }

    private fun spots(c: Canvas, look: CoatLook, cx: Float, cy: Float, radius: Float, n: Int, lightK: Float) {
        val r = Rng(look.seed xor 0x1E0)
        fill.color = shade(mix(look.points, look.body, 0.35f), lightK)
        repeat(n) {
            val ang = r.range(0f, 6.283f); val d = sqrt(r.float()) * radius
            val rr = radius * r.range(0.035f, 0.075f)
            c.drawOval(cx + cos(ang) * d - rr * 1.25f, cy + sin(ang) * d * 0.6f - rr, cx + cos(ang) * d + rr * 1.25f, cy + sin(ang) * d * 0.6f + rr, fill)
        }
    }

    // ---------------------------------------------------------------- membres
    private fun legUpper(c: Canvas, a: Appearance, lg: Leg, col: Int, k: Float, H: Float) {
        val mass = 0.85f + a.m.mass * 0.15f
        val wTop = H * (if (lg.front) 0.12f else 0.15f) * mass * (0.9f + a.muscle / 500f)
        val wMid = H * (if (lg.front) 0.062f else 0.072f) * mass
        fill.color = -1; fill.shader = LinearGradient(0f, lg.top.y - H * 0.1f, 0f, lg.mid.y, shade(col, 0.9f * k), shade(col, 0.72f * k), Shader.TileMode.CLAMP)
        seg(c, lg.top.x, lg.top.y - H * 0.08f, lg.mid.x, lg.mid.y, wTop, wMid)
        fill.shader = null
    }

    private fun drawLeg(c: Canvas, a: Appearance, lg: Leg, grad: Shader, lightK: Float, k: Float, H: Float, look: CoatLook) {
        val front = lg.front
        val mass = 0.85f + a.m.mass * 0.15f
        val wTop = H * (if (front) 0.12f else 0.15f) * mass * (0.9f + a.muscle / 500f)
        val wMid = H * (if (front) 0.062f else 0.072f) * mass
        val wCan = H * 0.045f * mass * (1f + a.foal * 0.1f)
        val wFet = wCan * 1.3f
        val wPas = wCan * 0.95f
        val pts = shade(mix(look.points, 0xFF7A6A55.toInt(), (a.dirt / 100f) * 0.3f), lightK * k)
        val white = shade(0xFFF2F0EA.toInt(), lightK * k)
        val pointsDiffer = look.points != look.body
        // segment supérieur : même dégradé que le corps pour une jonction invisible
        if (pointsDiffer) {
            // les extrémités noires du bai remontent jusqu'au genou / jarret
            fill.color = -1; fill.shader = LinearGradient(lg.top.x * 0.4f + lg.mid.x * 0.6f, lg.top.y * 0.4f + lg.mid.y * 0.6f, lg.mid.x, lg.mid.y, alpha(pts, 0f), pts, Shader.TileMode.CLAMP)
            seg(c, lg.top.x * 0.45f + lg.mid.x * 0.55f, lg.top.y * 0.45f + lg.mid.y * 0.55f, lg.mid.x, lg.mid.y, wTop * 0.7f, wMid)
            fill.shader = null
            fill.color = pts
        } else {
            fill.color = shade(Coat.mix(look.body, Color.BLACK, 0.12f), lightK * k)
        }
        val legCol = fill.color
        // genou plat / jarret anguleux
        if (front) c.drawCircle(lg.mid.x + wMid * 0.08f, lg.mid.y, wMid * 0.48f, fill)
        else {
            c.drawCircle(lg.mid.x, lg.mid.y, wMid * 0.45f, fill)
            c.drawCircle(lg.mid.x - wMid * 0.38f, lg.mid.y - wMid * 0.35f, wMid * 0.3f, fill) // pointe du jarret
        }
        seg(c, lg.mid.x, lg.mid.y, lg.fet.x, lg.fet.y, wMid * 0.78f, wCan)
        c.drawCircle(lg.fet.x - wFet * 0.12f, lg.fet.y, wFet * 0.5f, fill)
        seg(c, lg.fet.x, lg.fet.y, lg.cor.x, lg.cor.y, wPas, wPas * 0.98f)
        // tendon : liseré clair à l'avant, ombre à l'arrière
        stroke.shader = null
        stroke.color = alpha(Color.WHITE, 0.08f * k); stroke.strokeWidth = wCan * 0.25f
        c.drawLine(lg.mid.x + wCan * 0.25f, lg.mid.y + wCan * 0.4f, lg.fet.x + wCan * 0.25f, lg.fet.y - wCan * 0.5f, stroke)
        stroke.color = alpha(Color.BLACK, 0.18f * k)
        c.drawLine(lg.mid.x - wCan * 0.3f, lg.mid.y + wCan * 0.5f, lg.fet.x - wCan * 0.35f, lg.fet.y - wCan * 0.3f, stroke)
        // balzanes
        val lvl = if (look.sabino == 2) 4 else lg.white
        if (lvl > 0) {
            fill.color = white
            when (lvl) {
                1 -> seg(c, lg.cor.x - (lg.cor.x - lg.fet.x) * 0.3f, lg.cor.y - (lg.cor.y - lg.fet.y) * 0.3f, lg.cor.x, lg.cor.y, wPas * 1.03f, wPas)
                2 -> seg(c, lg.fet.x * 0.5f + lg.cor.x * 0.5f, lg.fet.y * 0.5f + lg.cor.y * 0.5f, lg.cor.x, lg.cor.y, wPas * 1.03f, wPas)
                3 -> { seg(c, lg.fet.x, lg.fet.y, lg.cor.x, lg.cor.y, wPas * 1.03f, wPas); c.drawCircle(lg.fet.x - wFet * 0.12f, lg.fet.y, wFet * 0.52f, fill)
                       seg(c, lg.mid.x * 0.45f + lg.fet.x * 0.55f, lg.mid.y * 0.45f + lg.fet.y * 0.55f, lg.fet.x, lg.fet.y, wCan * 1.05f, wCan * 1.05f) }
                else -> { seg(c, lg.fet.x, lg.fet.y, lg.cor.x, lg.cor.y, wPas * 1.03f, wPas); c.drawCircle(lg.fet.x - wFet * 0.12f, lg.fet.y, wFet * 0.52f, fill)
                          seg(c, lg.mid.x, lg.mid.y, lg.fet.x, lg.fet.y, wMid * 0.8f, wCan * 1.05f); c.drawCircle(lg.mid.x, lg.mid.y, wMid * 0.47f, fill) }
            }
            // limite irrégulière de la balzane
            fill.color = alpha(white, 0.6f)
        }
        // fanons
        if (a.m.feather > 0.1f) {
            fill.color = if (lvl >= 2) white else shade(look.mane, lightK * k)
            val fl = H * 0.07f * a.m.feather
            tmp.reset()
            tmp.moveTo(lg.fet.x - wFet * 0.6f, lg.fet.y - fl * 0.5f)
            tmp.quadTo(lg.fet.x - wFet * 1.5f, lg.cor.y + fl * 0.2f, lg.cor.x - wPas * 1.3f, lg.cor.y + fl * 0.55f)
            tmp.quadTo(lg.cor.x, lg.cor.y + fl * 0.75f, lg.cor.x + wPas * 1f, lg.cor.y + fl * 0.35f)
            tmp.quadTo(lg.fet.x + wFet * 0.4f, lg.fet.y, lg.fet.x + wFet * 0.25f, lg.fet.y - fl * 0.3f)
            tmp.close(); c.drawPath(tmp, fill)
        }
        // sabot (corne rayée sous une balzane)
        val hd = lg.hoofDir
        val nx = -hd.y; val ny = hd.x
        val hl = H * 0.055f; val hw = wPas * 0.6f
        tmp.reset()
        tmp.moveTo(lg.cor.x + nx * hw, lg.cor.y + ny * hw)
        tmp.lineTo(lg.cor.x - nx * hw * 0.9f, lg.cor.y - ny * hw * 0.9f)
        tmp.lineTo(lg.cor.x - nx * hw * 1.05f + hd.x * hl * 0.8f, lg.cor.y - ny * hw * 1.05f + hd.y * hl * 0.8f)
        tmp.lineTo(lg.cor.x + nx * hw * 1.45f + hd.x * hl, lg.cor.y + ny * hw * 1.45f + hd.y * hl)
        tmp.close()
        val hoof = if (lvl >= 1) 0xFFC9B48E.toInt() else look.hoof
        fill.color = -1; fill.shader = LinearGradient(lg.cor.x, lg.cor.y, lg.cor.x + hd.x * hl, lg.cor.y + hd.y * hl, shade(lighten(hoof, 0.12f), lightK * k), shade(hoof, lightK * k * 0.8f), Shader.TileMode.CLAMP)
        c.drawPath(tmp, fill)
        fill.shader = null
        if (!sil) {
            // couronne claire, stries de pousse de la corne, bord de sole sombre
            stroke.shader = null
            stroke.color = alpha(lighten(shade(hoof, lightK * k), 0.25f), 0.7f); stroke.strokeWidth = hl * 0.12f
            c.drawLine(lg.cor.x + nx * hw, lg.cor.y + ny * hw, lg.cor.x - nx * hw * 0.9f, lg.cor.y - ny * hw * 0.9f, stroke)
            stroke.color = alpha(Color.BLACK, 0.18f); stroke.strokeWidth = hl * 0.05f
            for (q in 1..2) {
                val t = q / 3f
                c.drawLine(lg.cor.x + nx * hw * (1f + 0.45f * t) + hd.x * hl * t, lg.cor.y + ny * hw * (1f + 0.45f * t) + hd.y * hl * t,
                    lg.cor.x - nx * hw * (0.9f + 0.15f * t) + hd.x * hl * 0.8f * t, lg.cor.y - ny * hw * (0.9f + 0.15f * t) + hd.y * hl * 0.8f * t, stroke)
            }
            stroke.color = alpha(Color.BLACK, 0.4f); stroke.strokeWidth = hl * 0.1f
            c.drawLine(lg.cor.x - nx * hw * 1.05f + hd.x * hl * 0.8f, lg.cor.y - ny * hw * 1.05f + hd.y * hl * 0.8f, lg.cor.x + nx * hw * 1.45f + hd.x * hl, lg.cor.y + ny * hw * 1.45f + hd.y * hl, stroke)
            // châtaigne (face interne, au-dessus du genou ou sous le jarret) et ergot
            fill.color = alpha(shade(0xFF3A3028.toInt(), lightK), 0.4f * k)
            if (front) { val cx = lg.top.x * 0.25f + lg.mid.x * 0.75f; val cy = lg.top.y * 0.25f + lg.mid.y * 0.75f; c.drawOval(cx - wMid * 0.38f, cy - wMid * 0.22f, cx - wMid * 0.08f, cy + wMid * 0.22f, fill) }
            c.drawCircle(lg.fet.x - wFet * 0.55f, lg.fet.y + wFet * 0.15f, wFet * 0.13f, fill)
        }
        if (a.dirt > 30f) {
            fill.color = alpha(0xFF5E4A30.toInt(), (a.dirt - 30f) / 70f * 0.65f)
            seg(c, lg.fet.x, lg.fet.y - H * 0.03f, lg.cor.x, lg.cor.y, wFet * 0.95f, wPas * 1.05f)
        }
        fill.color = legCol
    }

    /** Segment de membre effilé (légèrement galbé) aux extrémités arrondies. */
    private fun seg(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w1: Float, w2: Float) {
        val dx = x2 - x1; val dy = y2 - y1
        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
        val px = -dy / d; val py = dx / d
        val bulge = (w1 + w2) * 0.27f
        tmp2.reset()
        tmp2.moveTo(x1 + px * w1 / 2, y1 + py * w1 / 2)
        tmp2.quadTo((x1 + x2) / 2 + px * bulge, (y1 + y2) / 2 + py * bulge, x2 + px * w2 / 2, y2 + py * w2 / 2)
        tmp2.lineTo(x2 - px * w2 / 2, y2 - py * w2 / 2)
        tmp2.quadTo((x1 + x2) / 2 - px * bulge, (y1 + y2) / 2 - py * bulge, x1 - px * w1 / 2, y1 - py * w1 / 2)
        tmp2.close()
        c.drawPath(tmp2, fill)
        c.drawCircle(x1, y1, w1 / 2, fill)
        c.drawCircle(x2, y2, w2 / 2, fill)
    }

    // ---------------------------------------------------------------- queue, crinière, tête
    private fun drawTail(c: Canvas, a: Appearance, pose: HorsePose, x0: Float, y0: Float, lightK: Float) {
        val H = a.H
        val col = shade(a.look.mane, lightK)
        val wind = pose.speedBlend
        val lift = (a.m.tailSet * 0.4f + pose.tailLift + wind * 0.5f).coerceIn(0f, 1.3f)
        val len = H * (0.6f - a.foal * 0.25f) * (0.85f + a.m.maneLength * 0.25f)
        // tronçon de queue (os) puis crins qui tombent
        val dockAng = Math.toRadians((200.0 - 55.0 * lift)).toFloat()   // vers l'arrière
        val dockL = H * 0.12f
        val dx = x0 + cos(dockAng) * dockL; val dy = y0 - sin(dockAng) * dockL * -1f + H * 0.04f
        val sway = pose.tailSwing * H * 0.06f
        val ex = dx - len * (0.12f + wind * 0.65f) + sway; val ey = dy + len * (1f - wind * 0.55f - lift * 0.15f)
        val w0 = H * 0.045f; val w1 = H * (0.075f + a.m.maneLength * 0.04f)
        tmp.reset()
        tmp.moveTo(x0 + H * 0.01f, y0 - w0 * 0.6f)
        tmp.quadTo(dx + w0 * 0.2f, dy - w0 * 1.1f, dx - w0, dy - w0 * 0.2f)
        tmp.cubicTo(dx - w1 * 1.4f - wind * len * 0.2f, dy + len * 0.35f, ex - w1 * 0.8f, ey - len * 0.25f, ex - w1 * 0.4f, ey)
        tmp.quadTo(ex + w1 * 0.3f, ey + H * 0.02f, ex + w1 * 0.9f, ey - H * 0.01f)
        tmp.cubicTo(ex + w1 * 0.4f + sway * 0.5f, ey - len * 0.35f, dx + w1 * 0.9f, dy + len * 0.25f, dx + w0 * 0.6f, dy + w0 * 0.6f)
        tmp.quadTo(x0 + w0 * 0.2f, y0 + w0, x0 + H * 0.01f, y0 + w0 * 0.3f)
        tmp.close()
        fill.color = -1; fill.shader = LinearGradient(x0, y0, ex, ey, shade(col, 0.82f), col, Shader.TileMode.CLAMP)
        c.drawPath(tmp, fill)
        fill.shader = null
        if (sil) return
        // mèches : chaque mèche a sa longueur, son ondulation et sa nuance
        val r = Rng(a.look.seed xor 0x7A11)
        val nLocks = 9
        for (k in 0 until nLocks) {
            val f = k / (nLocks - 1f) - 0.5f
            val lk = 0.78f + r.float() * 0.3f
            val lex = dx + (ex - dx) * lk + f * w1 * 1.3f + sway * 0.3f * f
            val ley = dy + (ey - dy) * lk
            val bend = (r.float() - 0.5f) * w1 * 0.9f - wind * len * 0.05f
            val lw = w1 * (0.32f + r.float() * 0.18f)
            tmp2.reset()
            tmp2.moveTo(dx + f * w0 * 0.8f - lw * 0.3f, dy + H * 0.01f)
            tmp2.cubicTo(dx - w1 * 0.9f + f * w1 * 0.6f + bend, dy + (ley - dy) * 0.35f, lex - w1 * 0.3f + bend * 0.6f, ley - (ley - dy) * 0.3f, lex, ley)
            tmp2.cubicTo(lex + lw * 0.2f - bend * 0.4f, ley - (ley - dy) * 0.32f, dx - w1 * 0.5f + f * w1 * 0.6f + bend + lw, dy + (ley - dy) * 0.33f, dx + f * w0 * 0.8f + lw * 0.3f, dy + H * 0.01f)
            tmp2.close()
            val shadeK = if (k % 3 == 0) 0.78f else if (k % 3 == 1) 1.08f else 0.93f
            fill.color = alpha(shade(col, shadeK), 0.55f)
            c.drawPath(tmp2, fill)
        }
        // fils de crin : ombres puis reflets
        stroke.shader = null; stroke.strokeCap = Paint.Cap.ROUND
        for (pass in 0..1) {
            stroke.color = if (pass == 0) alpha(shade(col, 0.55f), 0.45f) else alpha(lighten(col, 0.35f), 0.35f)
            stroke.strokeWidth = H * (if (pass == 0) 0.0045f else 0.003f)
            for (k in 0..10) {
                val f = k / 10f - 0.5f + (if (pass == 1) 0.04f else 0f)
                val lk = 0.6f + r.float() * 0.38f
                tmp2.reset(); tmp2.moveTo(dx + f * w0, dy + H * 0.02f)
                tmp2.quadTo(dx - w1 * 0.7f + f * w1 * 1.1f, (dy + ey) / 2f, dx + (ex - dx) * lk + f * w1 * 1.3f, dy + (ey - dy) * lk)
                c.drawPath(tmp2, stroke)
            }
        }
        // pointes effilées qui dépassent
        stroke.color = alpha(col, 0.8f); stroke.strokeWidth = H * 0.004f
        repeat(6) {
            val f = r.float() - 0.5f
            val sx = ex + f * w1 * 1.6f; val sy = ey - H * 0.01f
            c.drawLine(sx, sy, sx - wind * H * 0.04f + f * H * 0.01f, sy + H * (0.015f + r.float() * 0.03f) * (1f - wind * 0.6f), stroke)
        }
    }

    private fun drawMane(c: Canvas, a: Appearance, pose: HorsePose, wx: Float, wy: Float, m1x: Float, m1y: Float, m2x: Float, m2y: Float,
                         npx: Float, npy: Float, lightK: Float) {
        val H = a.H
        val col = shade(a.look.mane, lightK)
        val len = H * (0.04f + a.m.maneLength * 0.2f) * (1f - a.foal * 0.55f)
        val wind = pose.speedBlend
        val n = 16
        val px = hx(0.0f, 0.1f); val py = hy(0.0f, 0.1f)
        val xs = FloatArray(n + 1); val ys = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat(); val u = 1 - t
            xs[i] = u * u * u * px + 3 * u * u * t * m2x + 3 * u * t * t * m1x + t * t * t * wx
            ys[i] = u * u * u * py + 3 * u * u * t * m2y + 3 * u * t * t * m1y + t * t * t * wy
        }
        // la crinière part légèrement au-dessus de la crête et retombe du côté visible
        tmp.reset()
        tmp.moveTo(xs[0] + npx * H * 0.01f, ys[0] + npy * H * 0.01f)
        for (i in 1..n) tmp.lineTo(xs[i] + npx * H * 0.012f, ys[i] + npy * H * 0.012f)
        val r = Rng(a.look.seed xor 0x3A3E)
        var prevX = xs[n]; var prevY = ys[n]
        for (i in n downTo 0) {
            val t = i / n.toFloat()
            val taper = (sin(PI.toFloat() * (0.08f + t * 0.84f))).coerceAtLeast(0.3f)
            val l = len * taper * (0.85f + r.float() * 0.3f)
            val wave = sin(pose.phase * 12.566f + i * 0.9f) * wind * l * 0.15f
            val tx = xs[i] - wind * l * 0.8f + wave + l * 0.08f
            val ty = ys[i] + l * (1f - wind * 0.55f) - npy * 0f
            tmp.quadTo(prevX, prevY, (prevX + tx) / 2f, (prevY + ty) / 2f)
            prevX = tx; prevY = ty
        }
        tmp.lineTo(xs[0], ys[0])
        tmp.close()
        fill.color = -1; fill.shader = LinearGradient(xs[n / 2], ys[n / 2] - len * 0.2f, xs[n / 2], ys[n / 2] + len, lighten(col, 0.06f), shade(col, 0.85f), Shader.TileMode.CLAMP)
        c.drawPath(tmp, fill)
        fill.shader = null
        stroke.shader = null
        if (!sil) {
            // mèches : creux sombres entre les mèches, puis reflets sur le dessus de chacune
            val rr = Rng(a.look.seed xor 0x51DE)
            for (pass in 0..2) {
                stroke.color = when (pass) { 0 -> alpha(shade(col, 0.5f), 0.5f); 1 -> alpha(shade(col, 0.75f), 0.4f); else -> alpha(lighten(col, 0.4f), 0.32f) }
                stroke.strokeWidth = H * (if (pass == 2) 0.0028f else 0.004f)
                val step = if (pass == 1) 1 else 2
                var i = 1 + pass % 2
                while (i < n) {
                    val t = i / n.toFloat()
                    val l = len * (0.55f + rr.float() * 0.4f) * sin(PI.toFloat() * (0.08f + t * 0.84f)).coerceAtLeast(0.3f)
                    val ox = if (pass == 2) H * 0.004f else 0f
                    tmp2.reset(); tmp2.moveTo(xs[i] + ox, ys[i] + H * 0.006f)
                    tmp2.quadTo(xs[i] + ox + l * 0.12f, ys[i] + l * 0.5f, xs[i] + ox - wind * l * 0.7f + l * 0.06f, ys[i] + l * (1f - wind * 0.5f))
                    c.drawPath(tmp2, stroke)
                    i += step
                }
            }
            // pointes libres qui dépassent sous la crinière
            stroke.color = alpha(col, 0.75f); stroke.strokeWidth = H * 0.0035f
            for (i in 2 until n - 1 step 2) {
                val t = i / n.toFloat()
                val l = len * sin(PI.toFloat() * (0.08f + t * 0.84f)).coerceAtLeast(0.3f)
                val tx = xs[i] - wind * l * 0.8f + l * 0.08f; val ty = ys[i] + l * (1f - wind * 0.55f)
                c.drawLine(tx, ty - H * 0.01f, tx - wind * H * 0.02f + H * 0.004f, ty + H * 0.012f, stroke)
            }
        }
        // toupet
        if (a.m.maneLength > 0.12f && a.foal < 0.8f) {
            val fl = hL * (0.18f + a.m.maneLength * 0.15f)
            tmp.reset()
            tmp.moveTo(hx(-0.02f, 0.12f), hy(-0.02f, 0.12f))
            tmp.quadTo(hx(0.1f, 0.26f), hy(0.1f, 0.26f), hx(0.04f + fl / hL, 0.2f - wind * 0.1f), hy(0.04f + fl / hL, 0.2f - wind * 0.1f))
            tmp.quadTo(hx(0.12f, 0.12f), hy(0.12f, 0.12f), hx(0.03f, 0.05f), hy(0.03f, 0.05f))
            tmp.close()
            fill.color = col
            c.drawPath(tmp, fill)
            if (!sil) {
                stroke.color = alpha(shade(col, 0.6f), 0.5f); stroke.strokeWidth = H * 0.003f
                for (q in 0..2) {
                    val v = 0.13f + q * 0.03f
                    tmp2.reset(); tmp2.moveTo(hx(0.0f, v), hy(0.0f, v))
                    tmp2.quadTo(hx(0.08f, v + 0.04f), hy(0.08f, v + 0.04f), hx(0.02f + fl / hL * (0.75f + q * 0.1f), v + 0.03f - wind * 0.1f), hy(0.02f + fl / hL * (0.75f + q * 0.1f), v + 0.03f - wind * 0.1f))
                    c.drawPath(tmp2, stroke)
                }
            }
        }
    }

    private fun drawEar(c: Canvas, a: Appearance, pose: HorsePose, col: Int, far: Boolean) {
        val el = hL * 0.25f * a.m.earSize * (1f + a.foal * 0.15f)
        val off = if (far) 0.06f else 0f
        val bu = 0.01f + off; val bv = 0.11f
        // direction : vers le haut, inclinée vers l'avant si attentif, couchée si mécontent
        val tilt = 0.35f + pose.ears * 0.35f
        val tu = bu - 0.9f * el / hL + tilt * 0.3f * 0f
        val tv = bv + tilt * el / hL
        val tipX = hx(tu, tv); val tipY = hy(tu, tv)
        tmp.reset()
        tmp.moveTo(hx(bu + 0.05f, bv + 0.02f), hy(bu + 0.05f, bv + 0.02f))
        tmp.quadTo(hx((bu + tu) / 2f + 0.02f, (bv + tv) / 2f + 0.07f), hy((bu + tu) / 2f + 0.02f, (bv + tv) / 2f + 0.07f), tipX, tipY)
        tmp.quadTo(hx((bu + tu) / 2f + 0.02f, (bv + tv) / 2f - 0.06f), hy((bu + tu) / 2f + 0.02f, (bv + tv) / 2f - 0.06f), hx(bu - 0.05f, bv - 0.02f), hy(bu - 0.05f, bv - 0.02f))
        tmp.close()
        fill.color = col
        c.drawPath(tmp, fill)
        if (!far) {
            fill.color = alpha(Color.BLACK, 0.25f)
            tmp.reset()
            tmp.moveTo(hx(bu + 0.02f, bv + 0.01f), hy(bu + 0.02f, bv + 0.01f))
            tmp.quadTo(hx((bu + tu) / 2f + 0.01f, (bv + tv) / 2f + 0.04f), hy((bu + tu) / 2f + 0.01f, (bv + tv) / 2f + 0.04f), hx(tu * 0.85f + bu * 0.15f, tv * 0.85f + bv * 0.15f), hy(tu * 0.85f + bu * 0.15f, tv * 0.85f + bv * 0.15f))
            tmp.quadTo(hx((bu + tu) / 2f, (bv + tv) / 2f - 0.02f), hy((bu + tu) / 2f, (bv + tv) / 2f - 0.02f), hx(bu - 0.02f, bv), hy(bu - 0.02f, bv))
            tmp.close(); c.drawPath(tmp, fill)
        }
    }

    private fun headPatterns(c: Canvas, look: CoatLook, lightK: Float) {
        if (sil) return
        c.save(); c.clipPath(headPath)
        if (look.leopard >= 2) spots(c, look, hx(0.4f, 0f), hy(0.4f, 0f), hL * 0.4f, 7, lightK)
        if (look.greyLevel > 0f && look.greyLevel < 0.9f) { fill.color = alpha(shade(0xFFF2F0EA.toInt(), lightK), look.greyLevel * 0.45f); c.drawPaint(fill) }
        if (look.sabino == 2) { fill.color = shade(0xFFF2F0EA.toInt(), lightK); c.drawPaint(fill) }
        c.restore()
    }

    private fun drawFace(c: Canvas, look: CoatLook, pose: HorsePose, lightK: Float, hAng: Float) {
        if (sil) return
        val white = shade(0xFFF4F2EC.toInt(), lightK)
        c.save(); c.clipPath(headPath)
        fill.color = white
        when (look.faceWhite) {
            1 -> { tmp.reset(); tmp.moveTo(hx(0.2f, 0.22f), hy(0.2f, 0.22f)); tmp.quadTo(hx(0.23f, 0.12f), hy(0.23f, 0.12f), hx(0.27f, 0.13f), hy(0.27f, 0.13f)); tmp.quadTo(hx(0.31f, 0.15f), hy(0.31f, 0.15f), hx(0.33f, 0.22f), hy(0.33f, 0.22f)); tmp.close(); c.drawPath(tmp, fill) }
            2 -> { tmp.reset(); tmp.moveTo(hx(0.17f, 0.22f), hy(0.17f, 0.22f)); tmp.quadTo(hx(0.22f, 0.12f), hy(0.22f, 0.12f), hx(0.3f, 0.14f), hy(0.3f, 0.14f)); tmp.lineTo(hx(0.6f, 0.125f), hy(0.6f, 0.125f)); tmp.quadTo(hx(0.9f, 0.07f), hy(0.9f, 0.07f), hx(1.05f, 0.08f), hy(1.05f, 0.08f)); tmp.lineTo(hx(1.05f, 0.25f), hy(1.05f, 0.25f)); tmp.close(); c.drawPath(tmp, fill) }
            3 -> { tmp.reset(); tmp.moveTo(hx(0.12f, 0.24f), hy(0.12f, 0.24f)); tmp.quadTo(hx(0.2f, 0.06f), hy(0.2f, 0.06f), hx(0.36f, 0.05f), hy(0.36f, 0.05f)); tmp.quadTo(hx(0.75f, 0.01f), hy(0.75f, 0.01f), hx(1.05f, -0.14f), hy(1.05f, -0.14f)); tmp.lineTo(hx(1.1f, 0.25f), hy(1.1f, 0.25f)); tmp.close(); c.drawPath(tmp, fill) }
        }
        // bout du nez : peau, plus sombre ou rose sous le blanc
        val skin = if (look.faceWhite >= 3) 0xFFD9A49A.toInt() else look.skin
        soft(c, hx(0.97f, 0f), hy(0.97f, 0f), hL * 0.17f, shade(skin, lightK), 0.85f)
        // modelé : joue ronde, creux sous l'œil, chanfrein
        soft(c, hx(0.26f, -0.14f), hy(0.26f, -0.14f), hL * 0.17f, Color.WHITE, 0.1f * lightK)
        soft(c, hx(0.42f, 0.02f), hy(0.42f, 0.02f), hL * 0.08f, Color.BLACK, 0.12f)
        stroke.shader = null
        stroke.color = alpha(Color.BLACK, 0.16f); stroke.strokeWidth = hL * 0.014f
        tmp.reset(); tmp.moveTo(hx(0.15f, -0.29f), hy(0.15f, -0.29f)); tmp.quadTo(hx(0.36f, -0.34f), hy(0.36f, -0.34f), hx(0.46f, -0.17f), hy(0.46f, -0.17f)); c.drawPath(tmp, stroke)
        c.restore()
        // œil
        val ex = hx(0.29f, 0.1f); val ey = hy(0.29f, 0.1f)
        val er = hL * 0.045f
        val open = 1f - pose.blink
        c.save(); c.translate(ex, ey); c.rotate(Math.toDegrees(-hAng.toDouble()).toFloat() + 90f)
        fill.color = alpha(Color.BLACK, 0.22f)
        c.drawOval(-er * 1.6f, -er * 1.2f, er * 1.6f, er * 1.3f, fill)
        if (open > 0.15f) {
            fill.color = 0xFF1E1410.toInt()
            c.drawOval(-er * 1.1f, -er * 0.9f * open, er * 1.1f, er * 0.9f * open, fill)
            if (look.blueEyes) { fill.color = 0xFF86ADC8.toInt(); c.drawOval(-er * 0.7f, -er * 0.65f * open, er * 0.7f, er * 0.65f * open, fill) }
            fill.color = alpha(Color.WHITE, 0.85f)
            c.drawCircle(er * 0.35f, -er * 0.3f * open, er * 0.22f, fill)
        } else {
            stroke.color = 0xFF1E1410.toInt(); stroke.strokeWidth = er * 0.3f
            c.drawLine(-er, 0f, er, 0f, stroke)
        }
        stroke.color = alpha(Color.BLACK, 0.28f); stroke.strokeWidth = er * 0.22f
        c.drawArc(-er * 1.45f, -er * 1.5f, er * 1.45f, er * 0.9f, 200f, 140f, false, stroke)
        if (open > 0.15f) {
            // iris brun autour de la pupille, paupière supérieure et cils
            stroke.color = alpha(0xFF6A4026.toInt(), 0.55f); stroke.strokeWidth = er * 0.18f
            if (!look.blueEyes) c.drawArc(-er * 0.75f, -er * 0.62f * open, er * 0.75f, er * 0.62f * open, 20f, 140f, false, stroke)
            stroke.color = 0xFF1E1410.toInt(); stroke.strokeWidth = er * 0.2f
            c.drawArc(-er * 1.15f, -er * 0.95f * open, er * 1.15f, er * 0.95f * open, 195f, 150f, false, stroke)
            stroke.strokeWidth = er * 0.1f
            for (q in 0..3) {
                val ang = Math.toRadians(205.0 + q * 32.0)
                val lx = cos(ang).toFloat() * er * 1.12f; val ly = sin(ang).toFloat() * er * 0.93f * open
                c.drawLine(lx, ly, lx * 1.25f + er * 0.15f, ly * 1.35f - er * 0.12f, stroke)
            }
        }
        // creux au-dessus de l'œil (salière)
        stroke.color = alpha(Color.BLACK, 0.14f); stroke.strokeWidth = er * 0.3f
        c.drawArc(-er * 2.2f, -er * 2.6f, er * 1.4f, -er * 0.4f, 200f, 110f, false, stroke)
        c.restore()
        // naseau : ouverture en virgule, rebord éclairé
        tmp.reset()
        tmp.moveTo(hx(0.86f, 0.085f), hy(0.86f, 0.085f))
        tmp.quadTo(hx(0.95f, 0.085f), hy(0.95f, 0.085f), hx(0.955f, 0.03f), hy(0.955f, 0.03f))
        tmp.quadTo(hx(0.95f, 0.0f), hy(0.95f, 0.0f), hx(0.925f, 0.02f), hy(0.925f, 0.02f))
        tmp.quadTo(hx(0.92f, 0.06f), hy(0.92f, 0.06f), hx(0.86f, 0.085f), hy(0.86f, 0.085f))
        tmp.close()
        fill.color = alpha(0xFF1E1410.toInt(), 0.75f); c.drawPath(tmp, fill)
        stroke.color = alpha(Color.WHITE, 0.18f * lightK); stroke.strokeWidth = hL * 0.012f
        tmp.reset(); tmp.moveTo(hx(0.85f, 0.105f), hy(0.85f, 0.105f)); tmp.quadTo(hx(0.97f, 0.11f), hy(0.97f, 0.11f), hx(0.98f, 0.03f), hy(0.98f, 0.03f)); c.drawPath(tmp, stroke)
        // bouche, lèvre inférieure et menton
        stroke.color = alpha(Color.BLACK, 0.5f); stroke.strokeWidth = hL * 0.011f
        tmp.reset(); tmp.moveTo(hx(0.86f, -0.055f), hy(0.86f, -0.055f)); tmp.quadTo(hx(0.94f, -0.04f), hy(0.94f, -0.04f), hx(1.0f, -0.045f), hy(1.0f, -0.045f)); c.drawPath(tmp, stroke)
        stroke.color = alpha(Color.BLACK, 0.22f); stroke.strokeWidth = hL * 0.01f
        tmp.reset(); tmp.moveTo(hx(0.8f, -0.095f), hy(0.8f, -0.095f)); tmp.quadTo(hx(0.84f, -0.07f), hy(0.84f, -0.07f), hx(0.9f, -0.085f), hy(0.9f, -0.085f)); c.drawPath(tmp, stroke)
        // os de la ganache et veines du chanfrein
        stroke.color = alpha(Color.BLACK, 0.12f); stroke.strokeWidth = hL * 0.012f
        tmp.reset(); tmp.moveTo(hx(0.48f, -0.04f), hy(0.48f, -0.04f)); tmp.quadTo(hx(0.62f, 0.0f), hy(0.62f, 0.0f), hx(0.78f, -0.02f), hy(0.78f, -0.02f)); c.drawPath(tmp, stroke)
    }

    // ---------------------------------------------------------------- équipement
    private fun drawRug(c: Canvas, H: Float, L: Float, topY: Float, chestY: Float, lightK: Float) {
        val col = shade(0xFF1F3B63.toInt(), lightK)
        tmp.reset()
        tmp.moveTo(bx(L * 0.3f, topY - H * 0.01f), by(L * 0.3f, topY - H * 0.01f))
        tmp.cubicTo(bx(L * 0.1f, topY + H * 0.025f), by(L * 0.1f, topY + H * 0.025f), bx(-L * 0.25f, topY + H * 0.02f), by(-L * 0.25f, topY + H * 0.02f), bx(-L * 0.46f, topY + H * 0.05f), by(-L * 0.46f, topY + H * 0.05f))
        tmp.quadTo(bx(-L * 0.56f, topY + H * 0.2f), by(-L * 0.56f, topY + H * 0.2f), bx(-L * 0.5f, chestY - H * 0.06f), by(-L * 0.5f, chestY - H * 0.06f))
        tmp.lineTo(bx(L * 0.45f, chestY - H * 0.06f), by(L * 0.45f, chestY - H * 0.06f))
        tmp.quadTo(bx(L * 0.55f, topY + H * 0.3f), by(L * 0.55f, topY + H * 0.3f), bx(L * 0.45f, topY + H * 0.14f), by(L * 0.45f, topY + H * 0.14f))
        tmp.close()
        fill.color = -1; fill.shader = LinearGradient(0f, topY, 0f, chestY, lighten(col, 0.15f), shade(col, 0.72f), Shader.TileMode.CLAMP)
        c.drawPath(tmp, fill); fill.shader = null
        stroke.shader = null
        stroke.color = shade(0xFFC9A44C.toInt(), lightK); stroke.strokeWidth = H * 0.01f
        c.drawLine(bx(-L * 0.5f, chestY - H * 0.06f), by(-L * 0.5f, chestY - H * 0.06f), bx(L * 0.45f, chestY - H * 0.06f), by(L * 0.45f, chestY - H * 0.06f), stroke)
        stroke.color = shade(0xFF14243C.toInt(), lightK); stroke.strokeWidth = H * 0.016f
        c.drawLine(bx(L * 0.05f, chestY - H * 0.07f), by(L * 0.05f, chestY - H * 0.07f), bx(-L * 0.05f, chestY + H * 0.03f), by(-L * 0.05f, chestY + H * 0.03f), stroke)
        c.drawLine(bx(-L * 0.1f, chestY - H * 0.07f), by(-L * 0.1f, chestY - H * 0.07f), bx(0f, chestY + H * 0.03f), by(0f, chestY + H * 0.03f), stroke)
    }

    private fun drawSaddle(c: Canvas, H: Float, L: Float, topY: Float, chestY: Float, tack: Tack, lightK: Float) {
        tmp.reset()
        tmp.moveTo(bx(L * 0.21f, topY + H * 0.005f), by(L * 0.21f, topY + H * 0.005f))
        tmp.quadTo(bx(L * 0.02f, topY + H * 0.05f), by(L * 0.02f, topY + H * 0.05f), bx(-L * 0.16f, topY + H * 0.04f), by(-L * 0.16f, topY + H * 0.04f))
        tmp.quadTo(bx(-L * 0.19f, topY + H * 0.17f), by(-L * 0.19f, topY + H * 0.17f), bx(-L * 0.13f, topY + H * 0.25f), by(-L * 0.13f, topY + H * 0.25f))
        tmp.lineTo(bx(L * 0.17f, topY + H * 0.25f), by(L * 0.17f, topY + H * 0.25f))
        tmp.quadTo(bx(L * 0.24f, topY + H * 0.12f), by(L * 0.24f, topY + H * 0.12f), bx(L * 0.21f, topY + H * 0.005f), by(L * 0.21f, topY + H * 0.005f))
        tmp.close()
        fill.color = shade(tack.padColor, lightK); c.drawPath(tmp, fill)
        stroke.shader = null
        stroke.color = shade(0xFFE8E2D4.toInt(), lightK); stroke.strokeWidth = H * 0.006f; c.drawPath(tmp, stroke)
        if (tack.number > 0) {
            fill.color = shade(Color.WHITE, lightK)
            val cx = bx(-L * 0.06f, topY + H * 0.19f); val cy = by(-L * 0.06f, topY + H * 0.19f)
            c.drawRoundRect(cx - H * 0.045f, cy - H * 0.032f, cx + H * 0.045f, cy + H * 0.032f, H * 0.008f, H * 0.008f, fill)
            fill.color = Color.BLACK; fill.textSize = H * 0.045f; fill.textAlign = Paint.Align.CENTER
            c.drawText(tack.number.toString(), cx, cy + H * 0.016f, fill)
        }
        val leather = shade(0xFF4A2E1C.toInt(), lightK)
        tmp.reset()
        tmp.moveTo(bx(L * 0.18f, topY - H * 0.035f), by(L * 0.18f, topY - H * 0.035f))
        tmp.quadTo(bx(L * 0.02f, topY + H * 0.05f), by(L * 0.02f, topY + H * 0.05f), bx(-L * 0.12f, topY - H * 0.02f), by(-L * 0.12f, topY - H * 0.02f))
        tmp.lineTo(bx(-L * 0.11f, topY + H * 0.045f), by(-L * 0.11f, topY + H * 0.045f))
        tmp.quadTo(bx(0f, topY + H * 0.075f), by(0f, topY + H * 0.075f), bx(L * 0.17f, topY + H * 0.045f), by(L * 0.17f, topY + H * 0.045f))
        tmp.close()
        fill.color = -1; fill.shader = LinearGradient(0f, topY - H * 0.04f, 0f, topY + H * 0.07f, lighten(leather, 0.15f), shade(leather, 0.8f), Shader.TileMode.CLAMP)
        c.drawPath(tmp, fill); fill.shader = null
        tmp.reset()
        tmp.moveTo(bx(L * 0.17f, topY + H * 0.035f), by(L * 0.17f, topY + H * 0.035f))
        tmp.quadTo(bx(L * 0.22f, topY + H * 0.14f), by(L * 0.22f, topY + H * 0.14f), bx(L * 0.13f, topY + H * 0.21f), by(L * 0.13f, topY + H * 0.21f))
        tmp.lineTo(bx(-L * 0.01f, topY + H * 0.19f), by(-L * 0.01f, topY + H * 0.19f))
        tmp.lineTo(bx(-L * 0.04f, topY + H * 0.06f), by(-L * 0.04f, topY + H * 0.06f))
        tmp.close()
        fill.color = shade(leather, 1.12f); c.drawPath(tmp, fill)
    }

    private fun drawBridle(c: Canvas, lightK: Float) {
        stroke.shader = null
        stroke.color = shade(0xFF2E1D14.toInt(), lightK); stroke.strokeWidth = hL * 0.035f
        c.drawLine(hx(0.02f, 0.13f), hy(0.02f, 0.13f), hx(0.1f, -0.2f), hy(0.1f, -0.2f), stroke)    // têtière / sous-gorge
        c.drawLine(hx(0.08f, -0.02f), hy(0.08f, -0.02f), hx(0.86f, -0.045f), hy(0.86f, -0.045f), stroke) // montant
        c.drawLine(hx(0.66f, 0.15f), hy(0.66f, 0.15f), hx(0.7f, -0.1f), hy(0.7f, -0.1f), stroke)     // muserolle
        c.drawLine(hx(0.08f, 0.17f), hy(0.08f, 0.17f), hx(0.04f, 0.04f), hy(0.04f, 0.04f), stroke)   // frontal
        fill.shader = null
        fill.color = shade(0xFFC8C8C8.toInt(), lightK)
        c.drawCircle(hx(0.87f, -0.05f), hy(0.87f, -0.05f), hL * 0.035f, fill)
    }

    private fun limb(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, col: Int) {
        stroke.shader = null; stroke.color = col; stroke.strokeWidth = w
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun drawRider(c: Canvas, H: Float, L: Float, topY: Float, tack: Tack, lightK: Float) {
        val u = H / 165f
        val fwd = tack.riderForward
        val sx = bx(L * 0.01f, topY - H * 0.015f); var sy = by(L * 0.01f, topY - H * 0.015f)
        sy -= (tack.riderPost * 9f + fwd * 8f) * u
        val lean = Math.toRadians(6.0 + fwd * 40.0).toFloat()
        val torso = 52f * u
        val shX = sx + sin(lean) * torso; val shY = sy - cos(lean) * torso
        val kneeX = bx(L * 0.17f, topY + H * 0.17f) - fwd * 4f * u; val kneeY = by(L * 0.17f, topY + H * 0.17f) - fwd * 6f * u
        val ankX = kneeX - 6f * u; val ankY = kneeY + 40f * u - fwd * 4f * u
        val elbX = shX + sin(lean) * 8f * u + 6f * u; val elbY = shY + 26f * u
        val handX = elbX + 22f * u; val handY = elbY + 2f * u
        val k = lightK
        val breeches = shade(0xFFEDE7DA.toInt(), k); val boot = shade(0xFF15110F.toInt(), k); val jacket = shade(tack.jacket, k)
        // bras opposé (dans l'ombre)
        limb(c, shX - 3f * u, shY + 2f * u, elbX - 4f * u, elbY, 9f * u, shade(jacket, 0.7f))
        // jambe
        limb(c, sx, sy, kneeX, kneeY, 17f * u, breeches)
        limb(c, kneeX, kneeY, ankX, ankY, 12f * u, boot)
        limb(c, ankX, ankY + 2f * u, ankX + 12f * u, ankY + 3f * u, 8f * u, boot)
        stroke.strokeWidth = 2.5f * u; stroke.color = shade(0xFFB8B8B8.toInt(), k)
        c.drawLine(ankX - 2f * u, ankY + 6f * u, ankX + 11f * u, ankY + 6f * u, stroke)
        stroke.color = shade(0xFF2E1D14.toInt(), k); stroke.strokeWidth = 2f * u
        c.drawLine(ankX, ankY + 5f * u, sx + 4f * u, sy + 12f * u, stroke) // étrivière
        // buste
        tmp.reset()
        val px = cos(lean); val py = sin(lean)
        tmp.moveTo(sx - px * 11f * u, sy - py * 11f * u + 4f * u)
        tmp.lineTo(sx + px * 12f * u, sy + py * 12f * u + 2f * u)
        tmp.lineTo(shX + px * 10f * u, shY + py * 10f * u)
        tmp.quadTo(shX, shY - 6f * u, shX - px * 10f * u, shY - py * 10f * u)
        tmp.close()
        fill.shader = null; fill.color = jacket; c.drawPath(tmp, fill)
        // bras et main
        limb(c, shX, shY + 2f * u, elbX, elbY, 10f * u, jacket)
        limb(c, elbX, elbY, handX, handY, 8.5f * u, jacket)
        fill.color = shade(0xFFF0F0F0.toInt(), k); c.drawCircle(handX, handY, 4.2f * u, fill)
        stroke.color = shade(0xFF2E1D14.toInt(), k); stroke.strokeWidth = 1.8f * u
        c.drawLine(handX, handY, hx(0.87f, -0.05f), hy(0.87f, -0.05f), stroke)
        // tête et casque
        val hdX = shX + sin(lean) * 15f * u; val hdY = shY - cos(lean) * 15f * u
        fill.color = shade(Color.WHITE, k); c.drawCircle(shX + sin(lean) * 4f * u, shY - cos(lean) * 4f * u, 5.5f * u, fill)
        fill.color = shade(0xFFE6C1A3.toInt(), k); c.drawCircle(hdX, hdY, 10.5f * u, fill)
        fill.color = shade(0xFF0F1418.toInt(), k)
        c.drawArc(hdX - 12f * u, hdY - 13.5f * u, hdX + 12f * u, hdY + 8f * u, 175f, 190f, true, fill)
        c.drawRoundRect(hdX + 2f * u, hdY - 4f * u, hdX + 16f * u, hdY - 1f * u, 2f * u, 2f * u, fill)
        fill.color = alpha(Color.WHITE, 0.25f * k); c.drawCircle(hdX - 3f * u, hdY - 8f * u, 3f * u, fill)
    }
}
