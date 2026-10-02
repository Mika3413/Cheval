package com.cheval.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import com.cheval.core.AilmentType
import com.cheval.core.Cal
import com.cheval.core.Horse
import com.cheval.core.MsgKind
import com.cheval.core.Personality
import com.cheval.core.Rng
import com.cheval.core.Season
import com.cheval.core.Turnout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pansage au doigt, au plus près du vrai :
 * - la saleté est peinte sur la robe (boue des roulades et des membres, poussière, sueur séchée sous la selle,
 *   taches de litière) et s'efface là où passe l'outil ;
 * - l'étrille travaille en petits cercles, se remplit de poils et de terre et doit être tapée pour être vidée ;
 *   elle fait mal sur la tête et les canons ;
 * - la brosse dure chasse la poussière dans le sens du poil, la brosse douce fait briller (et elle seule va sur la tête) ;
 * - le peigne démêle crins et queue en commençant par le bas ;
 * - le cure-pied se fait pied levé, du talon vers la pince : cailloux, pourriture de fourchette, fer qui bouge ;
 * - le cheval réagit : plaisir quand on lui gratte le garrot, oreilles couchées et coups de queue s'il est brusqué ;
 * - le pansage sert aussi à inspecter : plaies, gale de boue, tiques, membre chaud…
 */
class GroomScreen(app: GameView, private val horse: Horse) : Screen(app) {
    private val game get() = app.game!!

    private enum class Tool(val label: String, val tip: String) {
        ETRILLE("Étrille", "En petits cercles sur le corps et l'encolure, jamais sur la tête ni les canons"),
        BROSSE_DURE("Brosse dure", "Coups secs dans le sens du poil (vers l'arrière et le bas) ; aussi pour les membres"),
        BROSSE_DOUCE("Brosse douce", "Longs passages dans le sens du poil, et la seule brosse pour la tête"),
        PEIGNE("Peigne", "Démêle crinière et queue mèche par mèche, en commençant par le bas"),
        CURE_PIED("Cure-pied", "Touche un sabot pour qu'il donne le pied, puis cure du talon vers la pince"),
    }

    private enum class Zone { OUT, HEAD, NECK, WITHERS, BACK, BODY, BELLY, FLANK, QUARTERS, UPPER_LEG, LOWER_LEG }

    private class Knot(val tail: Boolean, val t: Float, val side: Float, val straw: Boolean, var left: Float = 1f) { val pos = PointF() }
    private class Tick(val x: Float, val y: Float, var on: Boolean = true)
    /** Ce que le pansage permet de découvrir à un endroit précis. */
    private class Finding(val x: Float, val y: Float, val r: Float, val note: String, val painful: Boolean, var found: Boolean = false)

    private class Hoof {
        val dirt = FloatArray(G * G); val dirt0 = FloatArray(G * G); val type = IntArray(G * G)
        var thrush = false; var stone = false; var stoneHits = 0; var stoneX = 0f; var stoneY = 0f
        var done = false; var thrushSeen = false
    }

    private val a0 = Appearance.of(horse, game.day)
    /** Le cheval est dessiné propre : la saleté vient des calques peints ci-dessous. */
    private val shown = Appearance(a0.look, a0.m, a0.H, a0.bcs, a0.muscle, 0f, a0.age, a0.stallion, false, a0.winter)
    private val halter = Tack(halter = true)
    private val basePose = HorsePose().apply { neck = 40f; head = 40f }
    private val pose = HorsePose().apply { neck = 40f; head = 40f }
    private val rng = Rng(horse.id * 31L + game.day)

    private var tool = Tool.ETRILLE
    private var t = 0f
    private var done = false

    // ---------------------------------------------------------------- géométrie et calques
    private var hx = 0f; private var hy = 0f; private var hs = 1f
    private val region = RectF()
    private var bw = 0; private var bh = 0
    private var mask: Bitmap? = null
    private lateinit var mud: Bitmap; private lateinit var dust: Bitmap; private lateinit var sweat: Bitmap; private lateinit var shine: Bitmap; private lateinit var compo: Bitmap
    private lateinit var mudC: Canvas; private lateinit var dustC: Canvas; private lateinit var sweatC: Canvas; private lateinit var shineC: Canvas; private lateinit var compoC: Canvas
    private var buf = IntArray(0)
    private var mud0 = 0f; private var dust0 = 0f; private var sweat0 = 0f; private var maskArea = 1f
    private var mudLeft = 0f; private var dustLeft = 0f; private var sweatLeft = 0f; private var shineCov = 0f
    private var recountT = 0f; private var dirty = true
    private val headC = PointF(); private var headR = 0f
    private val headDir = PointF()
    private val hoofPts = Array(4) { PointF() }
    private val tmp = PointF()

    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shinePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 95 }

    // ---------------------------------------------------------------- état du pansage
    private val knots = ArrayList<Knot>()
    private val ticks = ArrayList<Tick>()
    private val findings = ArrayList<Finding>()
    private val notes = ArrayList<String>()
    private val hooves = arrayOfNulls<Hoof>(4)
    private val hoofDone = BooleanArray(4)
    private val asked = IntArray(4)
    private var hoofOpen = -1
    private var hoofClose = -1f
    private val hoofPanel = RectF()

    private var curryLoad = 0f
    private val shedding = Cal.month(game.day) in 2..4 || Cal.month(game.day) in 8..9
    private val dustCol: Int
    private var pleasure = 0f
    private var irritation = 0f
    private var roughAcc = 0f
    private var lipT = 0f
    private var stampT = 0f
    private var tossT = 0f
    private var msg = ""; private var msgT = 0f; private var msgCol = Pal.CREAM
    private val said = HashSet<String>()

    // doigt
    private var fingerX = -1f; private var fingerY = -1f
    private var lastX = 0f; private var lastY = 0f; private var lastTime = 0L
    private var pdx = 0f; private var pdy = 0f; private var circAcc = 0f
    private val particles = ArrayList<FloatArray>() // x, y, vx, vy, vie, couleur, taille

    init {
        val lum = (Color.red(a0.look.body) * 0.3f + Color.green(a0.look.body) * 0.59f + Color.blue(a0.look.body) * 0.11f)
        dustCol = if (lum > 165f) Color.rgb(150, 128, 96) else Color.rgb(206, 190, 160)
        // crins emmêlés : plus il y a de jours sans pansage et de pré, plus il y a de nœuds
        val days = (game.day - horse.lastGroomDay).coerceIn(0, 12)
        val nk = ((days * 0.5f) + (if (horse.turnout != Turnout.BOX) 2f else 0f) + rng.range(0f, 1.5f)).toInt().coerceIn(0, 7)
        repeat(nk) {
            val tail = rng.chance(0.55f)
            knots += Knot(tail, if (tail) rng.range(0.3f, 0.92f) else rng.range(0.15f, 0.85f), rng.range(-1f, 1f), rng.chance(0.35f))
        }
        app.gui.onRawTouch = { e -> onTouch(e) }
    }

    override fun dispose() {
        gui.onRawTouch = null
        aisle?.recycle(); aisle = null
        mask?.let { it.recycle(); mud.recycle(); dust.recycle(); sweat.recycle(); shine.recycle(); compo.recycle() }
        mask = null
    }

    private fun mxs(mx: Float) = hx + mx * hs
    private fun mys(my: Float) = hy + my * hs

    // ================================================================= calques de saleté
    private fun ensureLayers(w: Float, h: Float) {
        if (mask != null) return
        hs = (h * 0.62f) / (a0.H * 1.25f)
        hx = w * 0.42f; hy = h * 0.8f
        region.set(hx - a0.L * 0.85f * hs, hy - a0.H * 1.62f * hs, hx + a0.L * 1.05f * hs, hy + 4f)
        bw = (region.width() * BS).toInt().coerceAtLeast(8); bh = (region.height() * BS).toInt().coerceAtLeast(8)
        fun mk(): Bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        fun cv(b: Bitmap): Canvas = Canvas(b).apply { scale(BS, BS); translate(-region.left, -region.top) }
        val m = mk(); mask = m
        mud = mk(); dust = mk(); sweat = mk(); shine = mk(); compo = mk()
        mudC = cv(mud); dustC = cv(dust); sweatC = cv(sweat); shineC = cv(shine); compoC = Canvas(compo)
        buf = IntArray(bw * bh)
        HorseArt.draw(cv(m), shown, basePose, hx, hy, hs, tack = halter, shadow = false)
        HorseArt.headPoint(0.5f, 0f, headC)
        HorseArt.headPoint(0f, 0f, tmp); val hx0 = tmp.x; val hy0 = tmp.y
        HorseArt.headPoint(1f, 0f, tmp); headR = hypot(tmp.x - hx0, tmp.y - hy0) * 0.55f
        val hl = hypot(tmp.x - hx0, tmp.y - hy0).coerceAtLeast(1f); headDir.set((tmp.x - hx0) / hl, (tmp.y - hy0) / hl)
        paintDirt()
        placeFindings()
        for (l in listOf(mudC, dustC, sweatC)) applyMask(l)
        maskArea = alphaSum(m) / 255f
        mud0 = alphaSum(mud); dust0 = alphaSum(dust); sweat0 = alphaSum(sweat)
        recount()
    }

    private fun applyMask(cv: Canvas) { cv.save(); cv.setMatrix(Matrix()); cv.drawBitmap(mask!!, 0f, 0f, dstIn); cv.restore() }

    private fun alphaSum(b: Bitmap): Float {
        b.getPixels(buf, 0, bw, 0, 0, bw, bh)
        var s = 0L; var i = 0
        while (i < buf.size) { s += (buf[i] ushr 24); i += 3 }
        return s * 3f
    }

    private fun alphaAt(b: Bitmap, x: Float, y: Float): Float {
        val px = ((x - region.left) * BS).toInt(); val py = ((y - region.top) * BS).toInt()
        if (px < 0 || py < 0 || px >= bw || py >= bh) return 0f
        return (b.getPixel(px, py) ushr 24) / 255f
    }

    private fun recount() {
        mudLeft = if (mud0 > 1f) (alphaSum(mud) / mud0).coerceIn(0f, 1f) else 0f
        dustLeft = if (dust0 > 1f) (alphaSum(dust) / dust0).coerceIn(0f, 1f) else 0f
        sweatLeft = if (sweat0 > 1f) (alphaSum(sweat) / sweat0).coerceIn(0f, 1f) else 0f
        shineCov = (alphaSum(shine) / 255f / (maskArea * 0.3f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        dirty = false
    }

    private fun soft(cv: Canvas, x: Float, y: Float, r: Float, col: Int, a: Float) {
        pf.color = -1
        pf.shader = RadialGradient(x, y, r, intArrayOf(HorseArt.alpha(col, a), HorseArt.alpha(col, a * 0.6f), HorseArt.alpha(col, 0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r, pf); pf.shader = null
    }

    private fun blobs(cv: Canvas, x: Float, y: Float, rx: Float, ry: Float, col: Int, n: Int) {
        repeat(n) {
            val ox = rng.range(-0.6f, 0.6f) * rx; val oy = rng.range(-0.6f, 0.6f) * ry
            pf.shader = null; pf.color = col
            cv.drawPath(Ink.blobPath(x + ox, y + oy, rx * rng.range(0.35f, 0.6f), ry * rng.range(0.35f, 0.6f), rng.nextLong(), 7, 0.35f), pf)
        }
    }

    /** Peint la saleté là où un cheval se salit vraiment. */
    private fun paintDirt() {
        val H = a0.H; val L = a0.L
        val dirt = ((100f - horse.cleanliness) / 100f).coerceIn(0f, 1f)
        val season = Cal.season(game.day)
        val wet = season == Season.HIVER || season == Season.AUTOMNE
        val pre = horse.turnout != Turnout.BOX
        val mudK = dirt * (if (pre) 1f else 0.35f) * (if (wet) 1.3f else 0.8f)
        val wetMud = Color.argb(225, 88, 64, 42); val dryMud = Color.argb(215, 128, 102, 70)
        // roulades : grandes plaques sur l'épaule, les côtes, la hanche, l'encolure
        if (pre && mudK > 0.2f) {
            val spots = listOf(0.3f to -0.82f, 0.02f to -0.8f, -0.32f to -0.86f, 0.42f to -1.04f, -0.15f to -0.92f).shuffledBy(rng)
            val n = (1 + mudK * 4f).toInt().coerceIn(1, 5)
            for ((mx, my) in spots.take(n)) {
                val x = mxs(mx * L); val y = mys(my * H); val r = H * hs * rng.range(0.09f, 0.16f) * (0.7f + mudK * 0.5f)
                blobs(mudC, x, y, r, r * 0.75f, if (wet) wetMud else dryMud, 8)
                blobs(mudC, x, y, r * 0.6f, r * 0.45f, HorseArt.alpha(Color.rgb(156, 130, 94), 0.5f), 3) // croûte sèche en surface
                repeat(14) { pf.color = dryMud; mudC.drawCircle(x + rng.range(-1.6f, 1.6f) * r, y + rng.range(-1.2f, 1.2f) * r, H * hs * rng.range(0.004f, 0.012f), pf) }
            }
        }
        // membres : boue qui remonte des sabots, éclaboussures
        val legMud = (mudK * 1.2f + (if (wet && pre) 0.25f else 0f)).coerceIn(0f, 1f)
        if (legMud > 0.08f) for (i in 0..3) {
            val t0 = 1f - 0.42f * legMud * rng.range(0.7f, 1.1f)
            var tt = t0
            while (tt <= 1f) {
                HorseArt.legPoint(i, tt, tmp)
                val k = (tt - t0) / (1f - t0 + 0.001f)
                pf.shader = null; pf.color = HorseArt.alpha(if (k > 0.5f) Color.rgb(92, 70, 46) else Color.rgb(140, 116, 84), 0.5f + 0.4f * k)
                mudC.drawCircle(tmp.x, tmp.y, H * hs * (0.03f + 0.012f * k), pf)
                tt += 0.02f
            }
            repeat(10) { HorseArt.legPoint(i, rng.range(t0 - 0.12f, 1f), tmp); pf.color = dryMud; mudC.drawCircle(tmp.x + rng.range(-1f, 1f) * H * hs * 0.04f, tmp.y, H * hs * rng.range(0.004f, 0.01f), pf) }
        }
        // ventre : projections
        if (mudK > 0.15f) repeat((40 * mudK).toInt()) {
            val x = mxs(rng.range(-0.3f, 0.28f) * L); val y = mys(-H * rng.range(0.56f, 0.66f))
            pf.color = if (rng.chance(0.5f)) wetMud else dryMud; mudC.drawCircle(x, y, H * hs * rng.range(0.005f, 0.016f), pf)
        }
        // litière : taches de crottin sur la cuisse et les jarrets (très visibles sur un gris)
        if (horse.litter < 60f && horse.turnout != Turnout.PERMANENT && dirt > 0.25f) {
            val stain = HorseArt.alpha(Color.rgb(118, 100, 56), 0.6f)
            blobs(mudC, mxs(-0.4f * L), mys(-0.74f * H), H * hs * 0.08f, H * hs * 0.06f, stain, 5)
            HorseArt.legPoint(1, 0.48f, tmp); blobs(mudC, tmp.x, tmp.y, H * hs * 0.04f, H * hs * 0.05f, stain, 4)
        }
        // poussière et pellicules : un voile sur toute la robe, plus marqué sur le dos
        val dA = 0.05f + dirt * 0.26f
        repeat(110) {
            val mx = rng.range(-0.5f, 0.48f) * L; val my = rng.range(-1.02f, -0.5f) * H
            val back = if (my < -0.88f * H) 1.3f else 1f
            soft(dustC, mxs(mx), mys(my), H * hs * rng.range(0.06f, 0.1f), dustCol, dA * back * rng.range(0.6f, 1f))
        }
        repeat(40) { soft(dustC, mxs(rng.range(0.3f, 0.62f) * L), mys(rng.range(-1.3f, -0.85f) * H), H * hs * 0.07f, dustCol, dA * 0.8f) }
        for (i in 0..3) { var tt = 0.05f; while (tt < 1f) { HorseArt.legPoint(i, tt, tmp); soft(dustC, tmp.x, tmp.y, H * hs * 0.05f, dustCol, dA * 0.8f); tt += 0.08f } }
        repeat(4) { HorseArt.headPoint(rng.range(0.15f, 0.75f), rng.range(-0.15f, 0.12f), tmp); soft(dustC, tmp.x, tmp.y, H * hs * 0.05f, dustCol, dA * 0.9f) }
        pf.shader = null
        repeat((220 * (0.3f + dirt)).toInt()) {
            pf.color = HorseArt.alpha(if (rng.chance(0.6f)) Color.rgb(236, 228, 210) else Color.rgb(120, 100, 76), 0.4f)
            dustC.drawCircle(mxs(rng.range(-0.5f, 0.48f) * L), mys(rng.range(-1.02f, -0.55f) * H), H * hs * 0.0035f, pf)
        }
        // sueur séchée : forme du tapis, passage de sangle, entre les postérieurs
        if (horse.sessionsToday > 0 || horse.workToday > 0f) {
            val wetA = 0.25f; val salt = Color.argb(150, 236, 230, 212)
            val saddle = Ink.blobPath(mxs(0.1f * L), mys(-0.93f * H), 0.21f * L * hs, 0.09f * H * hs, 41, 10, 0.12f)
            pf.color = HorseArt.alpha(Color.BLACK, wetA); sweatC.drawPath(saddle, pf)
            brush.xfermode = null; brush.color = HorseArt.alpha(salt, 0.45f); brush.strokeWidth = H * hs * 0.018f; sweatC.drawPath(saddle, brush)
            brush.color = HorseArt.alpha(Color.BLACK, wetA); brush.strokeWidth = H * hs * 0.07f
            sweatC.drawLine(mxs(0.22f * L), mys(-0.88f * H), mxs(0.2f * L), mys(-0.6f * H), brush)
            soft(sweatC, mxs(0.4f * L), mys(-1.0f * H), H * hs * 0.1f, Color.BLACK, wetA)
            soft(sweatC, mxs(-0.3f * L), mys(-0.66f * H), H * hs * 0.08f, Color.argb(255, 236, 230, 212), 0.5f)
        }
    }

    private fun <T> List<T>.shuffledBy(r: Rng): List<T> { val l = toMutableList(); for (i in l.indices.reversed()) { val j = r.int(i + 1); val x = l[i]; l[i] = l[j]; l[j] = x }; return l }

    /** Place ce que l'inspection peut révéler, selon l'état de santé réel du cheval. */
    private fun placeFindings() {
        val H = a0.H * hs; val L = a0.L
        val name = horse.name
        if (horse.hasAilment(AilmentType.PLAIE)) {
            val leg = rng.int(2); HorseArt.legPoint(leg, rng.range(0.55f, 0.8f), tmp)
            findings += Finding(tmp.x, tmp.y, H * 0.06f, "Plaie sur un membre : la nettoyer, la désinfecter et vérifier le rappel tétanos.", true)
        }
        if (horse.hasAilment(AilmentType.GALE_DE_BOUE)) {
            for (leg in listOf(0, 1)) { HorseArt.legPoint(leg, 0.93f, tmp); findings += Finding(tmp.x, tmp.y, H * 0.05f, "Croûtes dans les paturons : gale de boue. Tondre, laver à la chlorhexidine, bien sécher.", true) }
        }
        for (ty in listOf(AilmentType.TENDINITE, AilmentType.BOITERIE)) if (horse.hasAilment(ty)) {
            val leg = if (ty == AilmentType.TENDINITE) 0 else 1; HorseArt.legPoint(leg, 0.72f, tmp)
            findings += Finding(tmp.x, tmp.y, H * 0.07f, "Le membre est chaud et gonflé : repos, douche froide, avis du vétérinaire.", true)
        }
        if (horse.hasAilment(AilmentType.ABCES)) { HorseArt.legPoint(1, 0.95f, tmp); findings += Finding(tmp.x, tmp.y, H * 0.06f, "Pouls digité bien frappé dans un pied : un abcès se prépare peut-être.", true) }
        if (horse.hasAilment(AilmentType.MELANOME)) findings += Finding(mxs(-0.5f * L), mys(-0.92f * a0.H), H * 0.07f, "Petites boules noires sous la queue : mélanomes, à faire surveiller par le vétérinaire.", false)
        if (horse.hasAilment(AilmentType.PARASITES)) findings += Finding(mxs(0f), mys(-0.8f * a0.H), H * 0.3f, "Poil terne et piqué malgré le pansage : coprologie et vermifuge à prévoir.", false)
        if (a0.bcs < 3.5f) findings += Finding(mxs(0.05f * L), mys(-0.8f * a0.H), H * 0.2f, "On sent nettement les côtes sous la brosse : $name est trop maigre.", false)
        if (a0.bcs > 7f) findings += Finding(mxs(-0.1f * L), mys(-0.85f * a0.H), H * 0.2f, "Dépôts de graisse sur l'encolure et la croupe : $name est trop gros, attention à la fourbure.", false)
        // tiques au retour du pré, à la belle saison
        val season = Cal.season(game.day)
        if (horse.turnout != Turnout.BOX && (season == Season.PRINTEMPS || season == Season.ETE)) {
            val spots = listOf(mxs(rng.range(-0.15f, 0.15f) * L) to mys(-0.59f * a0.H), mxs(-0.27f * L) to mys(-0.6f * a0.H), mxs(0.27f * L) to mys(-0.62f * a0.H))
            repeat(rng.range(0, 2)) { i -> ticks += Tick(spots[i].first + rng.range(-6f, 6f), spots[i].second) }
            if (rng.chance(0.4f)) { HorseArt.headPoint(0.12f, -0.26f, tmp); ticks += Tick(tmp.x, tmp.y) }
        }
    }

    // ================================================================= animation et réactions
    override fun update(dt: Float) {
        t += dt
        pose.breathe = (pose.breathe + dt * 0.25f) % 1f
        pleasure = (pleasure - dt * 0.12f).coerceIn(0f, 1f)
        irritation = (irritation - dt * 0.25f).coerceIn(0f, 1f)
        lipT -= dt; stampT -= dt; tossT -= dt; msgT -= dt
        // encolure tendue et nez en avant quand c'est agréable, tête qui se relève quand c'est désagréable
        val neckT = 40f - 6f * pleasure + (if (tossT > 0f) 14f else 0f)
        val headT = 40f + 22f * pleasure - (if (tossT > 0f) 12f else 0f)
        pose.neck += (neckT - pose.neck) * min(1f, dt * 3f)
        pose.head += (headT - pose.head) * min(1f, dt * 3f)
        val earT = when { irritation > 0.3f -> -1f; pleasure > 0.3f -> 0.05f; else -> 0.5f + 0.3f * sin(t * 0.7f) }
        pose.ears += (earT - pose.ears) * min(1f, dt * 5f)
        val blinkCycle = if (t % 4.2f < 0.14f) 1f else 0f
        pose.blink = max(blinkCycle, 0.6f * pleasure)
        pose.lip += ((if (lipT > 0f) 1f else 0f) - pose.lip) * min(1f, dt * 4f)
        pose.tailSwing = sin(t * 1.3f) * 0.3f + sin(t * 9f) * irritation * 1.4f
        // pied levé pour le curage, ou postérieur qui tape quand il est agacé
        when {
            hoofOpen >= 0 -> { pose.liftLeg = hoofOpen; pose.lift += (1f - pose.lift) * min(1f, dt * 5f) }
            stampT > 0f -> { pose.liftLeg = 1; pose.lift = sin((0.5f - stampT) / 0.5f * Math.PI.toFloat()).coerceAtLeast(0f) * 0.5f }
            else -> { pose.lift += (0f - pose.lift) * min(1f, dt * 5f); if (pose.lift < 0.02f) pose.liftLeg = -1 }
        }
        if (hoofClose >= 0f) { hoofClose -= dt; if (hoofClose < 0f) { hoofOpen = -1; hoofClose = -1f } }
        for (p in particles) { p[0] += p[2] * dt; p[1] += p[3] * dt; p[3] += 260f * gui.u * dt; p[2] *= 0.98f; p[4] -= dt }
        particles.removeAll { it[4] <= 0f || it[1] > hy + 30f * gui.u }
        if (dirty) { recountT -= dt; if (recountT <= 0f && mask != null) { recount(); recountT = 0.25f } }
    }

    private fun say(text: String, col: Int = Pal.CREAM, once: Boolean = false) {
        if (once && !said.add(text)) return
        if (msgT > 0.8f && text == msg) return
        msg = text; msgT = 3.6f; msgCol = col
    }

    private fun note(text: String) {
        if (text in notes) return
        notes += text
        say(text, Pal.GOLD_L)
        app.sound.play(SoundFx.S.PAGE, 0.5f)
    }

    private fun irritate(amount: Float, text: String?) {
        irritation = (irritation + amount).coerceAtMost(1f)
        roughAcc += amount
        if (text != null) say(text, Pal.CREAM, once = false)
        if (irritation > 0.85f && stampT <= 0f) {
            stampT = 0.5f
            say("${horse.name} couche les oreilles et lève un postérieur : attention, il menace de taper !", Pal.CREAM)
            app.sound.play(SoundFx.S.SNORT, 0.6f, 1.2f)
        } else if (irritation > 0.45f && rng.chance(0.08f) && stampT <= 0f) stampT = 0.5f
    }

    private fun please(amount: Float) {
        pleasure = (pleasure + amount * (if (Personality.CALIN in horse.personality) 1.5f else 1f)).coerceAtMost(1f)
    }

    // ================================================================= zones du corps
    private fun zoneAt(x: Float, y: Float): Zone {
        if (mask == null || alphaAt(mask!!, x, y) < 0.3f) return Zone.OUT
        if (hypot(x - headC.x, y - headC.y) < headR) return Zone.HEAD
        val H = a0.H; val L = a0.L
        val mx = (x - hx) / hs; val my = (y - hy) / hs
        return when {
            my > -0.3f * H -> Zone.LOWER_LEG
            hypot(mx - 0.22f * L, my + H) < 0.11f * H -> Zone.WITHERS
            mx > 0.3f * L && my < -0.86f * H -> Zone.NECK
            my > -0.53f * H -> Zone.UPPER_LEG
            my > -0.67f * H && mx > -0.25f * L && mx < 0.25f * L -> Zone.BELLY
            mx > -0.32f * L && mx < -0.12f * L && my > -0.86f * H -> Zone.FLANK
            mx < -0.3f * L -> Zone.QUARTERS
            my < -0.9f * H -> Zone.BACK
            else -> Zone.BODY
        }
    }

    /** Sens du poil à l'écran (cheval tourné vers la droite). */
    private fun hairDir(z: Zone, out: PointF) {
        when (z) {
            Zone.HEAD -> out.set(headDir.x, headDir.y)
            Zone.NECK -> out.set(-0.35f, 0.94f)
            Zone.UPPER_LEG, Zone.LOWER_LEG -> out.set(0f, 1f)
            Zone.QUARTERS -> out.set(-0.5f, 0.87f)
            Zone.BELLY -> out.set(-0.98f, 0.2f)
            else -> out.set(-0.88f, 0.47f)
        }
    }

    // ================================================================= toucher
    private val curryBtn = RectF()
    private fun curryBtnShown() = tool == Tool.ETRILLE && curryLoad > 0.45f && hoofOpen < 0

    private fun onTouch(e: MotionEvent): Boolean {
        if (done || mask == null) return false
        val u = gui.u
        val x = e.x; val y = e.y
        if (y > gui.h - 70f * u || y < 50f * u) return false
        if (curryBtnShown() && curryBtn.contains(x, y)) return false
        if (hoofOpen >= 0) return hoofTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerX = x; fingerY = y; lastX = x; lastY = y; lastTime = e.eventTime; pdx = 0f; pdy = 0f; circAcc = 0f
                for (tk in ticks) if (tk.on && hypot(x - tk.x, y - tk.y) < 16f * u) {
                    tk.on = false
                    note("Tique retirée au tire-tique (on tourne, sans écraser) : surveiller la zone quelques jours.")
                    burst(tk.x, tk.y, Color.rgb(70, 60, 56), 4)
                    return true
                }
                if (tool == Tool.CURE_PIED) { askHoof(x, y); return true }
                if (tool == Tool.ETRILLE && y > hy + 4f * u && curryLoad > 0.15f) { emptyCurry(); return true }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (k in 0 until e.historySize) move(e.getHistoricalX(k), e.getHistoricalY(k), e.getHistoricalEventTime(k))
                move(x, y, e.eventTime)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { fingerX = -1f; return true }
        }
        return true
    }

    private fun move(x: Float, y: Float, time: Long) {
        fingerX = x; fingerY = y
        val dx = x - lastX; val dy = y - lastY
        val len = hypot(dx, dy)
        if (len < 2.5f) return
        val dts = ((time - lastTime).coerceAtLeast(4L)) / 1000f
        val speed = len / dts / gui.u
        val ux = dx / len; val uy = dy / len
        if (pdx != 0f || pdy != 0f) {
            val turn = atan2(pdx * uy - pdy * ux, pdx * ux + pdy * uy)
            circAcc = circAcc * 0.82f + turn * 0.18f
        }
        pdx = ux; pdy = uy
        val circ = (abs(circAcc) / 0.22f).coerceIn(0f, 1f)
        val z = zoneAt(x, y)
        if (z != Zone.OUT && tool != Tool.CURE_PIED) {
            if (tool == Tool.PEIGNE) comb(x, y, speed) else stroke(lastX, lastY, x, y, z, ux, uy, speed, circ)
        } else if (tool == Tool.PEIGNE) comb(x, y, speed)
        lastX = x; lastY = y; lastTime = time
    }

    private fun eraseLine(cv: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, wdt: Float, a: Float) {
        if (a <= 0.004f) return
        erase.strokeWidth = wdt; erase.alpha = (a * 255f).toInt().coerceIn(1, 255)
        cv.drawLine(x0, y0, x1, y1, erase)
    }

    private fun paintLine(cv: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, wdt: Float, col: Int, a: Float) {
        if (a <= 0.004f) return
        brush.xfermode = null; brush.strokeWidth = wdt; brush.color = HorseArt.alpha(col, a)
        cv.drawLine(x0, y0, x1, y1, brush)
    }

    private val hd = PointF()

    /** Un coup d'outil sur la robe. */
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, z: Zone, ux: Float, uy: Float, speed: Float, circ: Float) {
        val u = gui.u
        hairDir(z, hd)
        val align = ux * hd.x + uy * hd.y   // 1 = dans le sens du poil, −1 = à rebrousse-poil
        val mudHere = alphaAt(mud, x1, y1)
        val dustHere = alphaAt(dust, x1, y1)
        val sens = Personality.SENSIBLE in horse.personality
        val fast = speed > (if (sens) 650f else 950f)
        dirty = true
        when (tool) {
            Tool.ETRILLE -> {
                when (z) {
                    Zone.HEAD -> { irritate(0.12f, "Pas d'étrille sur la tête ! Seule la brosse douce y va."); tossT = 0.6f; return }
                    Zone.LOWER_LEG -> { irritate(0.05f, "Sur les canons, l'os est juste sous la peau : l'étrille fait mal. Prends la brosse dure."); return }
                    else -> {}
                }
                val eff = (0.05f + 0.32f * circ) * (1f - curryLoad * 0.85f)
                eraseLine(mudC, x0, y0, x1, y1, 24f * u, eff)
                eraseLine(sweatC, x0, y0, x1, y1, 24f * u, eff * 0.6f)
                // la saleté décollée remonte en surface : à chasser ensuite à la brosse dure
                if (dustHere < 0.45f) paintLine(dustC, x0, y0, x1, y1, 22f * u, dustCol, eff * (0.15f + mudHere) * 0.18f)
                val load = eff * hypot(x1 - x0, y1 - y0) / (700f * u) * (0.35f + mudHere + dustHere * 0.4f) * (if (shedding) 1.8f else 1f)
                curryLoad = (curryLoad + load).coerceAtMost(1f)
                if (curryLoad > 0.85f) say("L'étrille est pleine de poils et de terre : tape-la (bouton ou sur le sol) pour la vider.", once = true)
                if (circ < 0.2f && mudHere > 0.3f) say("L'étrille s'utilise en petits cercles : c'est ce qui décolle la boue.", once = true)
                if (rng.chance(0.12f + circ * 0.15f) && mudHere > 0.15f) burst(x1, y1, Color.rgb(132, 108, 76), 1)
                if (shedding && rng.chance(0.12f)) burst(x1, y1, a0.look.body, 1, hair = true)
                if (shedding) say("C'est la mue : l'étrille arrache des poignées de poils !", once = true)
                when {
                    z == Zone.WITHERS && circ > 0.35f -> {
                        please(0.04f); lipT = 1.2f
                        say("${horse.name} tend l'encolure et remue la lèvre : au pré, les chevaux se grattent le garrot entre eux !", Pal.GOLD_L, once = true)
                    }
                    (z == Zone.BELLY || z == Zone.FLANK) && (sens || fast) -> irritate(0.035f, "Ventre et flancs sont chatouilleux : plus doucement.")
                    fast -> irritate(0.02f, null)
                    else -> please(0.004f)
                }
            }
            Tool.BROSSE_DURE -> {
                if (z == Zone.HEAD) { irritate(0.08f, "La brosse dure est trop rêche pour la tête : prends la brosse douce."); tossT = 0.5f; return }
                val dirEff = if (align > 0f) 0.35f + 0.65f * align else 0.18f
                val flick = 0.7f + 0.3f * (speed / 700f).coerceAtMost(1f)
                eraseLine(dustC, x0, y0, x1, y1, 28f * u, (0.07f + 0.26f * dirEff) * flick)
                eraseLine(sweatC, x0, y0, x1, y1, 28f * u, 0.12f * dirEff)
                val legs = z == Zone.LOWER_LEG || z == Zone.UPPER_LEG
                eraseLine(mudC, x0, y0, x1, y1, 26f * u, if (legs) 0.16f * dirEff else 0.025f)
                if (!legs && mudHere > 0.45f) say("La brosse dure ne décolle pas la boue sèche : passe d'abord l'étrille.", once = true)
                if (align < -0.4f) say("Brosse dans le sens du poil : vers l'arrière et vers le bas.", once = true)
                if ((dustHere > 0.1f || mudHere > 0.1f) && rng.chance(0.2f)) burst(x1, y1, dustCol, 1, vx = ux * 160f * u, vy = uy * 60f * u - 40f * u)
                if (align < -0.4f && sens) irritate(0.02f, null) else if (!fast) please(0.003f)
                if (fast && (z == Zone.BELLY || z == Zone.FLANK) && sens) irritate(0.03f, "Zone sensible : il chasse avec la queue.")
            }
            Tool.BROSSE_DOUCE -> {
                val dirEff = if (align > 0f) 0.4f + 0.6f * align else 0.2f
                eraseLine(dustC, x0, y0, x1, y1, 32f * u, 0.04f + 0.06f * dirEff)
                if (mudHere > 0.35f) { say("Sur la boue, la brosse douce s'encrasse sans rien faire briller : étrille et brosse dure d'abord.", once = true); return }
                val dull = if (horse.hasAilment(AilmentType.PARASITES)) 0.45f else 1f
                paintLine(shineC, x0, y0, x1, y1, 30f * u, Color.WHITE, 0.06f * dirEff * (1f - dustHere) * dull)
                if (z == Zone.HEAD) {
                    if (fast) { irritate(0.05f, "Doucement autour des yeux et des naseaux !"); tossT = 0.4f } else please(0.012f)
                } else if (!fast) please(0.006f)
            }
            else -> {}
        }
        app.sound.play(SoundFx.S.BRUSH, 0.42f, when (tool) { Tool.ETRILLE -> 0.75f; Tool.BROSSE_DURE -> 1.05f; else -> 1.3f }, 110)
        // inspection : ce que la main et la brosse découvrent
        for (f in findings) if (hypot(x1 - f.x, y1 - f.y) < f.r + 12f * u) {
            if (!f.found) { f.found = true; note(f.note) }
            if (f.painful && tool != Tool.BROSSE_DOUCE) irritate(0.05f, "Aïe : c'est douloureux à cet endroit, évite d'y frotter.")
        }
    }

    private fun emptyCurry() {
        curryLoad = 0f
        val u = gui.u
        repeat(16) { particles += floatArrayOf(fingerX.coerceAtLeast(40f * u), hy - 4f * u, rng.range(-90f, 90f) * u, rng.range(-120f, -30f) * u, 0.9f, (if (it % 2 == 0) a0.look.body else Color.rgb(140, 116, 84)).toFloat(), 2.2f * u) }
        app.sound.play(SoundFx.S.HOOF_HARD, 0.45f, 1.5f)
        say("Tac, tac : l'étrille est vidée de ses poils et de sa terre.", once = true)
    }

    private fun burst(x: Float, y: Float, col: Int, n: Int, hair: Boolean = false, vx: Float = 0f, vy: Float = 0f) {
        val u = gui.u
        repeat(n) {
            particles += floatArrayOf(x, y, vx + rng.range(-50f, 50f) * u, vy + rng.range(-70f, -10f) * u, if (hair) 1.4f else 0.8f + rng.float() * 0.4f, col.toFloat(), (if (hair) 3f else 1.8f) * u * rng.range(0.7f, 1.3f))
        }
    }

    // ---------------------------------------------------------------- crins
    private fun comb(x: Float, y: Float, speed: Float) {
        val u = gui.u
        for (k in knots) {
            if (k.left <= 0f || hypot(x - k.pos.x, y - k.pos.y) > 18f * u) continue
            // on démêle du bas vers le haut : un nœud plus bas encore en place, et ça casse les crins
            val lower = knots.any { o -> o !== k && o.tail == k.tail && o.left > 0f && o.t > k.t + 0.05f }
            val fast = speed > 900f
            k.left -= when { lower -> 0.04f; fast -> 0.08f; else -> 0.16f }
            if (lower) say("Commence par le bas : sinon on tire sur tout le reste et on casse les crins.", once = true)
            if (fast || lower) { irritate(0.03f, if (fast) "Ça tire ! Tiens la mèche dans la main et démêle doucement." else null); burst(k.pos.x, k.pos.y, a0.look.mane, 2, hair = true) }
            else please(0.004f)
            if (k.straw && k.left < 0.5f && rng.chance(0.3f)) burst(k.pos.x, k.pos.y, Color.rgb(222, 196, 120), 2, hair = true)
            if (k.left <= 0f) { k.left = 0f; app.sound.play(SoundFx.S.BRUSH, 0.4f, 1.6f) }
            dirty = true
        }
    }

    // ---------------------------------------------------------------- pieds
    private fun legName(i: Int) = when (i) { 0 -> "antérieur gauche"; 1 -> "postérieur gauche"; 2 -> "antérieur droit"; else -> "postérieur droit" }

    private fun askHoof(x: Float, y: Float) {
        val r = a0.H * hs * 0.11f
        var best = -1; var bd = Float.MAX_VALUE
        for (i in 0..3) { val d = hypot(x - hoofPts[i].x, y - (hoofPts[i].y - a0.H * hs * 0.03f)); if (d < r && d < bd) { bd = d; best = i } }
        if (best < 0) { say("Touche un des sabots."); return }
        if (hoofDone[best]) { say("Le pied ${legName(best)} est déjà curé."); return }
        // donner le pied s'apprend : un cheval peu manipulé ou contrarié refuse
        val refuse = (when { horse.bond < 25f -> 0.5f; horse.bond < 50f -> 0.18f; else -> 0.04f } + irritation * 0.4f) / (1 + asked[best])
        asked[best]++
        if (rng.chance(refuse)) {
            say("${horse.name} ne donne pas le pied. Fais glisser ta main le long du membre, appuie-toi sur son épaule et redemande.")
            app.sound.play(SoundFx.S.HOOF_HARD, 0.4f, 0.8f)
            return
        }
        hoofOpen = best; hoofClose = -1f
        if (hooves[best] == null) hooves[best] = makeHoof()
        val days = game.day - horse.lastFarrier
        if (horse.shod && days > 49) note("Le fer bouge et des rivets ressortent : il est temps d'appeler le maréchal.")
        if (!horse.shod && days > 63) note("Corne trop longue, la paroi s'éclate : un parage est nécessaire.")
    }

    private fun makeHoof(): Hoof {
        val hf = Hoof()
        val pack = 0.55f + (100f - horse.litter) / 100f * 0.5f + (if (horse.turnout != Turnout.BOX) 0.25f else 0f)
        for (gy in 0 until G) for (gx in 0 until G) {
            val nx = (gx + 0.5f) / G * 2f - 1f; val ny = (gy + 0.5f) / G * 2f - 1f
            val e = (nx / 0.84f) * (nx / 0.84f) + ((ny + 0.04f) / 0.95f) * ((ny + 0.04f) / 0.95f)
            val i = gy * G + gx
            if (e >= 0.7f) { hf.type[i] = 0; continue }                        // paroi et ligne blanche : rien à curer
            val fw = ((ny + 0.1f) / 0.9f * 0.42f)                               // demi-largeur de la fourchette
            hf.type[i] = when {
                ny > -0.1f && abs(nx) < fw -> 3                                 // fourchette
                ny > -0.2f && abs(nx) < fw + 0.14f -> 2                         // lacunes latérales
                else -> 1                                                       // sole
            }
            val v = when (hf.type[i]) { 2 -> 1.5f; 3 -> 0.45f; else -> rng.range(0.5f, 1f) } * pack
            hf.dirt[i] = v; hf.dirt0[i] = v
        }
        hf.thrush = (horse.litter < 40f || horse.hooves < 45f) && rng.chance(0.65f)
        hf.stone = horse.turnout != Turnout.BOX && rng.chance(0.25f)
        hf.stoneX = rng.range(-0.3f, 0.3f); hf.stoneY = rng.range(0.05f, 0.35f)
        return hf
    }

    private var hoofLX = 0f; private var hoofLY = 0f
    private fun hoofTouch(e: MotionEvent): Boolean {
        val hf = hooves[hoofOpen] ?: return true
        val p = hoofPanel
        val cx = p.centerX(); val cy = p.centerY() - 2f * gui.u; val R = min(p.width(), p.height()) * 0.33f
        val nx = (e.x - cx) / R; val ny = (e.y - cy) / R
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!p.contains(e.x, e.y)) { hoofOpen = -1; say("${horse.name} repose son pied."); return true }
                fingerX = e.x; fingerY = e.y; hoofLX = nx; hoofLY = ny
                if (hf.stone && hypot(nx - hf.stoneX, ny - hf.stoneY) < 0.14f) {
                    hf.stoneHits++
                    app.sound.play(SoundFx.S.HOOF_HARD, 0.5f, 1.6f)
                    if (hf.stoneHits >= 2) { hf.stone = false; burst(e.x, e.y, Color.rgb(150, 146, 140), 3); note("Un caillou était coincé contre la fourchette : retiré, sinon il aurait pu boiter.") }
                    else say("Le caillou est bien coincé : fais levier avec la pointe du cure-pied.")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                fingerX = e.x; fingerY = e.y
                val dx = nx - hoofLX; val dy = ny - hoofLY; val len = hypot(dx, dy)
                if (len < 0.015f) return true
                // du talon (en bas) vers la pince (en haut), en s'éloignant de soi
                val eff = if (dy < -abs(dx) * 0.3f) 1f else 0.35f
                if (eff < 1f) say("Cure du talon vers la pince, toujours en éloignant l'outil de toi.", once = true)
                var removed = 0f
                for (gy in 0 until G) for (gx in 0 until G) {
                    val i = gy * G + gx
                    if (hf.type[i] == 0 || hf.dirt[i] <= 0f) continue
                    val cxn = (gx + 0.5f) / G * 2f - 1f; val cyn = (gy + 0.5f) / G * 2f - 1f
                    if (segDist(cxn, cyn, hoofLX, hoofLY, nx, ny) > 0.12f) continue
                    val d = 0.2f * eff * (if (hf.type[i] == 2) 0.75f else 1f)
                    removed += min(d, hf.dirt[i]); hf.dirt[i] = (hf.dirt[i] - d).coerceAtLeast(0f)
                    if (hf.type[i] == 2 && hf.thrush && hf.dirt[i] < 0.6f && !hf.thrushSeen) {
                        hf.thrushSeen = true
                        note("Matière noire et odeur forte dans les lacunes : pourriture de fourchette. Litière plus sèche et produit asséchant.")
                    }
                }
                if (removed > 0.05f) { burst(e.x, e.y, Color.rgb(110, 88, 60), 1); app.sound.play(SoundFx.S.BRUSH, 0.35f, 0.6f, 120) }
                hoofLX = nx; hoofLY = ny
                if (!hf.done && hoofClean(hf) > 0.9f && !hf.stone) {
                    hf.done = true; hoofDone[hoofOpen] = true; hoofClose = 0.7f
                    say("Pied ${legName(hoofOpen)} curé !", Pal.GOLD_L)
                    please(0.05f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerX = -1f
        }
        return true
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val k = if (l2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * k), py - (ay + dy * k))
    }

    private fun hoofClean(hf: Hoof): Float {
        var s = 0f; var s0 = 0f
        for (i in hf.dirt.indices) { s += hf.dirt[i]; s0 += hf.dirt0[i] }
        return if (s0 <= 0f) 1f else 1f - s / s0
    }

    // ================================================================= bilan
    private fun knotsLeft(): Float = if (knots.isEmpty()) 0f else knots.sumOf { it.left.toDouble() }.toFloat() / knots.size

    private fun readiness(): Float {
        val hoofK = hoofDone.count { it } / 4f
        val sweatK = if (sweat0 > 1f) 1f - sweatLeft else 1f
        return (0.34f * (1f - mudLeft) + 0.24f * (1f - dustLeft) + 0.08f * sweatK + 0.14f * shineCov + 0.06f * (1f - knotsLeft()) + 0.14f * hoofK).coerceIn(0f, 1f)
    }

    // ================================================================= dessin
    override fun draw(c: Canvas) {
        val u = gui.u; val w = gui.w; val h = gui.h
        drawAisle(c, w, h, u)
        ensureLayers(w, h)

        HorseArt.draw(c, shown, pose, hx, hy, hs, tack = halter)
        for (i in 0..3) HorseArt.hoofPoint(i, hoofPts[i])
        // longe d'attache : du licol à l'anneau du mur
        HorseArt.headPoint(0.63f, -0.17f, tmp)
        val ringX = w * 0.8f; val ringY = h * 0.5f
        val rope = Path().apply { moveTo(tmp.x, tmp.y); quadTo((tmp.x + ringX) / 2f, max(tmp.y, ringY) + 30f * u, ringX, ringY + 4f * u) }
        c.drawPath(rope, Ink.stroke(Ink.INK, 3.6f * u)); c.drawPath(rope, Ink.stroke(Color.rgb(196, 60, 50), 2.4f * u))

        // saleté, sueur et brillance, posées sur la robe
        if (mask != null) {
            compoC.drawColor(0, PorterDuff.Mode.CLEAR)
            compoC.drawBitmap(sweat, 0f, 0f, null)
            compoC.drawBitmap(dust, 0f, 0f, null)
            compoC.drawBitmap(mud, 0f, 0f, null)
            compoC.drawBitmap(shine, 0f, 0f, shinePaint)
            compoC.drawBitmap(mask!!, 0f, 0f, dstIn)
            c.drawBitmap(compo, null, region, bmpPaint)
        }
        drawFindings(c)
        drawKnots(c)
        for (tk in ticks) if (tk.on) {
            pf.shader = null; pf.color = Color.rgb(64, 54, 50); c.drawOval(tk.x - 3.2f * u, tk.y - 2.4f * u, tk.x + 3.2f * u, tk.y + 2.4f * u, pf)
            pf.color = Color.rgb(140, 110, 100); c.drawCircle(tk.x - 1f * u, tk.y - 0.8f * u, 0.9f * u, pf)
            for (k in 0..3) Ink.line(c, tk.x - 2f * u + k * 1.3f * u, tk.y + 1.5f * u, tk.x - 2.6f * u + k * 1.7f * u, tk.y + 3.6f * u, 0.5f * u, Color.rgb(50, 40, 36))
        }
        for (p in particles) {
            pf.shader = null; pf.color = HorseArt.alpha(p[5].toInt(), p[4].coerceIn(0f, 1f))
            if (p[6] > 2.5f * u) c.drawLine(p[0], p[1], p[0] + p[6] * 1.6f, p[1] + p[6] * 0.4f, Ink.stroke(pf.color, 0.9f * u)) else c.drawCircle(p[0], p[1], p[6], pf)
        }
        if (hoofOpen >= 0) drawHoofPanel(c)
        if (fingerX >= 0f) drawTool(c, fingerX, fingerY)

        // en-tête
        gui.dark(c, RectF(8f * u, 6f * u, w - 8f * u, 44f * u))
        gui.button(c, RectF(14f * u, 10f * u, 52f * u, 40f * u), "‹", Btn.GHOST, size = 18f) { finish() }
        gui.text(c, "Pansage de ${horse.name}", 62f * u, 24f * u, 15f, Pal.CREAM, font = gui.serif)
        gui.text(c, tool.tip, 62f * u, 38f * u, 10f, Pal.GOLD_L, maxW = w - 330f * u)
        val pr = readiness()
        gui.gauge(c, w - 220f * u, 22f * u, 200f * u, "Avancement", pr * 100f, Pal.GOLD, "${(pr * 100).toInt()} %", dark = true)

        drawChecklist(c)
        // outils
        val bar = RectF(8f * u, h - 62f * u, w - 160f * u, h - 8f * u)
        gui.dark(c, bar)
        val bw2 = (bar.width() - 10f * u) / Tool.values().size
        for ((i, tl) in Tool.values().withIndex()) {
            val r = RectF(bar.left + 5f * u + i * bw2, bar.top + 5f * u, bar.left + (i + 1) * bw2, bar.bottom - 5f * u)
            val sub = when (tl) {
                Tool.CURE_PIED -> "${hoofDone.count { it }}/4"
                Tool.PEIGNE -> if (knots.isEmpty()) "crins nets" else "${knots.count { it.left > 0f }} nœud(s)"
                Tool.ETRILLE -> if (curryLoad > 0.45f) "pleine" else null
                else -> null
            }
            gui.button(c, r, tl.label, if (tl == tool) Btn.TAB_ON else Btn.TAB, size = 12f, sub = sub) {
                tool = tl; app.sound.play(SoundFx.S.CLICK)
                if (tl != Tool.CURE_PIED) hoofOpen = -1
            }
        }
        if (curryBtnShown()) {
            curryBtn.set(14f * u, h - 104f * u, 190f * u, h - 70f * u)
            gui.button(c, curryBtn, "Taper l'étrille", Btn.NORMAL, size = 12f) { emptyCurry() }
        }
        gui.button(c, RectF(w - 150f * u, h - 58f * u, w - 10f * u, h - 12f * u), "Terminer", Btn.GOLD, size = 14f) { finish() }
        if (msgT > 0f) {
            val a = msgT.coerceAtMost(1f)
            val tw = min(w - 40f * u, 620f * u)
            val r = RectF(w * 0.46f - tw / 2f, 52f * u, w * 0.46f + tw / 2f, 52f * u + 30f * u)
            gui.dark(c, r, alpha = (190 * a).toInt())
            gui.text(c, msg, r.centerX(), r.centerY() + 4f * u, 11f, HorseArt.alpha(msgCol, a), Paint.Align.CENTER, maxW = r.width() - 16f * u)
        }
    }

    /** Ce qu'il reste à faire, et ce que l'inspection a révélé. */
    private fun drawChecklist(c: Canvas) {
        val u = gui.u
        val lines = ArrayList<Pair<String, Boolean>>()
        if (mud0 > 1f) lines += "Boue décollée" to (mudLeft < 0.12f)
        lines += "Poussière chassée" to (dustLeft < 0.15f)
        if (sweat0 > 1f) lines += "Traces de sueur" to (sweatLeft < 0.15f)
        lines += "Poil brillant" to (shineCov > 0.75f)
        if (knots.isNotEmpty()) lines += "Crins démêlés" to (knotsLeft() <= 0.01f)
        lines += "Pieds curés" to hoofDone.all { it }
        val r = RectF(12f * u, 92f * u, 210f * u, 92f * u + (22f + lines.size * 15f + (if (notes.isEmpty()) 0f else 18f + notes.size * 26f)) * u)
        Ink.parchment(c, r, u)
        var y = r.top + 16f * u
        gui.text(c, "Pansage", r.left + 10f * u, y, 12f, Pal.INK, font = gui.serif); y += 15f * u
        for ((txt, ok) in lines) {
            gui.text(c, (if (ok) "✓ " else "○ ") + txt, r.left + 12f * u, y, 10.5f, if (ok) Pal.GREEN else Pal.INK_L); y += 15f * u
        }
        if (notes.isNotEmpty()) {
            y += 4f * u
            gui.text(c, "Observations", r.left + 10f * u, y, 12f, Pal.INK, font = gui.serif); y += 6f * u
            for (n in notes) { gui.wrap(c, n, r.left + 12f * u, y + 10f * u, r.width() - 22f * u, 9f, Pal.INK_L); y += 26f * u }
        }
    }

    private fun drawFindings(c: Canvas) {
        val u = gui.u
        for (f in findings) {
            when {
                f.note.startsWith("Plaie") -> {
                    pf.shader = null; pf.color = Color.argb(200, 170, 60, 56)
                    c.drawOval(f.x - 4f * u, f.y - 2f * u, f.x + 4f * u, f.y + 2f * u, pf)
                    Ink.line(c, f.x - 3f * u, f.y, f.x + 3f * u, f.y - 0.5f * u, 0.8f * u, Color.rgb(90, 30, 28))
                }
                f.note.startsWith("Croûtes") -> repeat(5) { k ->
                    pf.shader = null; pf.color = Color.argb(220, 120, 104, 90)
                    c.drawCircle(f.x + (k - 2) * 2.2f * u, f.y + (k % 2) * 1.6f * u, 1.3f * u, pf)
                }
            }
        }
    }

    private fun drawKnots(c: Canvas) {
        val u = gui.u
        for (k in knots) {
            if (k.tail) HorseArt.tailPoint(k.t, k.pos) else HorseArt.manePoint(k.t, 0.55f, k.pos)
            k.pos.x += k.side * a0.H * hs * 0.02f
            if (k.left <= 0f) continue
            val col = HorseArt.shade(a0.look.mane, 0.6f)
            val s = (3f + 2.5f * k.left) * u
            val pth = Path()
            pth.moveTo(k.pos.x - s, k.pos.y)
            for (q in 0..6) { val a = q * 1.9f; pth.quadTo(k.pos.x + cos(a) * s * 1.2f, k.pos.y + sin(a) * s, k.pos.x + cos(a + 0.9f) * s * 0.6f, k.pos.y + sin(a + 0.9f) * s * 0.6f) }
            c.drawPath(pth, Ink.stroke(HorseArt.alpha(col, 0.9f), 1.3f * u))
            c.drawPath(pth, Ink.stroke(HorseArt.alpha(HorseArt.lighten(a0.look.mane, 0.3f), 0.5f), 0.6f * u))
            if (k.straw) { Ink.line(c, k.pos.x - s, k.pos.y - s * 0.3f, k.pos.x + s * 0.9f, k.pos.y + s * 0.4f, 1.2f * u, Color.rgb(222, 196, 120)); Ink.line(c, k.pos.x - s * 0.4f, k.pos.y + s * 0.6f, k.pos.x + s * 0.5f, k.pos.y - s * 0.7f, 1f * u, Color.rgb(200, 170, 96)) }
            if (tool == Tool.PEIGNE) { c.drawCircle(k.pos.x, k.pos.y, s + 6f * u + sin(t * 5f) * 1.5f * u, Ink.stroke(HorseArt.alpha(Pal.GOLD, 0.8f), 1.5f * u)) }
        }
        if (tool == Tool.CURE_PIED && hoofOpen < 0) for (i in 0..3) {
            val col = if (hoofDone[i]) Pal.OK else Pal.GOLD
            c.drawCircle(hoofPts[i].x, hoofPts[i].y - a0.H * hs * 0.03f, a0.H * hs * 0.065f * (1f + 0.08f * sin(t * 5f + i)), Ink.stroke(col, 2.5f * u))
        }
    }

    private fun drawHoofPanel(c: Canvas) {
        val u = gui.u; val w = gui.w; val h = gui.h
        val hf = hooves[hoofOpen] ?: return
        hoofPanel.set(w - 330f * u, 88f * u, w - 12f * u, h - 72f * u)
        val p = hoofPanel
        Ink.parchment(c, p, u)
        gui.text(c, "Pied ${legName(hoofOpen)}", p.left + 14f * u, p.top + 20f * u, 13f, Pal.INK, font = gui.serif)
        gui.text(c, "${(hoofClean(hf) * 100).toInt()} %", p.right - 14f * u, p.top + 20f * u, 12f, Pal.INK_L, Paint.Align.RIGHT)
        val cx = p.centerX(); val cy = p.centerY() - 2f * u; val R = min(p.width(), p.height()) * 0.33f
        gui.text(c, "pince", cx, cy - R * 1.06f, 9.5f, Pal.INK_L, Paint.Align.CENTER)
        gui.text(c, "talons", cx, cy + R * 1.2f, 9.5f, Pal.INK_L, Paint.Align.CENTER)
        // paroi, ligne blanche, sole
        val horn = a0.look.hoof
        val wall = RectF(cx - R * 0.92f, cy - R * 0.98f, cx + R * 0.92f, cy + R * 0.9f)
        pf.shader = null; pf.color = HorseArt.shade(horn, 0.85f); c.drawOval(wall, pf)
        c.drawOval(wall, Ink.stroke(Ink.INK, 1.6f * u))
        pf.color = Color.rgb(226, 214, 180); c.drawOval(RectF(cx - R * 0.8f, cy - R * 0.86f, cx + R * 0.8f, cy + R * 0.8f), pf)
        pf.color = Color.rgb(196, 178, 146); c.drawOval(RectF(cx - R * 0.76f, cy - R * 0.82f, cx + R * 0.76f, cy + R * 0.76f), pf)
        // glomes (bulbes des talons) et fourchette
        pf.color = Color.rgb(150, 122, 104)
        c.drawCircle(cx - R * 0.3f, cy + R * 0.78f, R * 0.18f, pf); c.drawCircle(cx + R * 0.3f, cy + R * 0.78f, R * 0.18f, pf)
        val frog = Path().apply { moveTo(cx, cy - R * 0.1f); lineTo(cx + R * 0.42f, cy + R * 0.8f); lineTo(cx - R * 0.42f, cy + R * 0.8f); close() }
        pf.color = Color.rgb(160, 132, 112); c.drawPath(frog, pf); c.drawPath(frog, Ink.stroke(HorseArt.alpha(Ink.INK, 0.6f), 1f * u))
        Ink.line(c, cx, cy + R * 0.25f, cx, cy + R * 0.75f, 1.6f * u, HorseArt.alpha(Ink.INK, 0.5f))
        // fer : bande d'acier le long de la paroi, ouverte aux talons, avec ses étampures
        if (horse.shod) {
            val shoe = RectF(cx - R * 0.88f, cy - R * 0.94f, cx + R * 0.88f, cy + R * 0.86f)
            c.drawArc(shoe, 140f, 260f, false, Ink.stroke(Color.rgb(120, 124, 130), R * 0.12f))
            c.drawArc(shoe, 140f, 260f, false, Ink.stroke(HorseArt.alpha(Color.WHITE, 0.3f), R * 0.03f))
            for (q in 0..5) { val a = Math.toRadians((160.0 + q * 44.0)).toFloat(); pf.color = Color.rgb(70, 70, 76); c.drawCircle(cx + cos(a) * R * 0.88f, cy - R * 0.04f + sin(a) * R * 0.9f, R * 0.025f, pf) }
        }
        // pourriture de fourchette (sous la saleté), puis saleté tassée cellule par cellule
        val cs = R * 2f / G
        for (gy in 0 until G) for (gx in 0 until G) {
            val i = gy * G + gx
            if (hf.type[i] == 0) continue
            val x = cx + ((gx + 0.5f) / G * 2f - 1f) * R; val y = cy + ((gy + 0.5f) / G * 2f - 1f) * R
            if (hf.type[i] == 2 && hf.thrush) { pf.color = Color.argb(200, 34, 28, 24); c.drawRect(x - cs * 0.5f, y - cs * 0.5f, x + cs * 0.5f, y + cs * 0.5f, pf) }
            val d = hf.dirt[i]
            if (d > 0.04f) {
                val k = (d / 1.2f).coerceIn(0f, 1f)
                val rc = Rng(i * 7919L + 13L)
                val jx = rc.range(-0.3f, 0.3f) * cs; val jy = rc.range(-0.3f, 0.3f) * cs
                val kind = rc.int(8)
                pf.color = HorseArt.alpha(when (kind) { 0 -> Color.rgb(196, 170, 104); 1, 2 -> Color.rgb(120, 96, 66); else -> Color.rgb(86, 66, 46) }, 0.45f + 0.55f * k)
                c.drawCircle(x + jx, y + jy, cs * (0.55f + 0.35f * k), pf)
                if (kind == 0 && k > 0.4f) Ink.line(c, x - cs, y + jy, x + cs, y - jy, 0.8f * u, Color.rgb(222, 196, 120)) // brin de paille
            }
        }
        if (hf.stone) {
            val sx = cx + hf.stoneX * R; val sy = cy + hf.stoneY * R
            pf.color = Color.rgb(156, 152, 146); c.drawOval(sx - R * 0.09f, sy - R * 0.06f, sx + R * 0.09f, sy + R * 0.07f, pf)
            c.drawOval(sx - R * 0.09f, sy - R * 0.06f, sx + R * 0.09f, sy + R * 0.07f, Ink.stroke(Ink.INK, 1f * u))
            pf.color = HorseArt.alpha(Color.WHITE, 0.5f); c.drawCircle(sx - R * 0.03f, sy - R * 0.02f, R * 0.025f, pf)
        }
        // flèche : du talon vers la pince
        val ax = p.left + 22f * u
        Ink.line(c, ax, cy + R * 0.6f, ax, cy - R * 0.6f, 2f * u, HorseArt.alpha(Pal.GREEN, 0.8f))
        Ink.line(c, ax, cy - R * 0.6f, ax - 6f * u, cy - R * 0.45f, 2f * u, HorseArt.alpha(Pal.GREEN, 0.8f)); Ink.line(c, ax, cy - R * 0.6f, ax + 6f * u, cy - R * 0.45f, 2f * u, HorseArt.alpha(Pal.GREEN, 0.8f))
        gui.text(c, "Touche en dehors pour reposer le pied", p.left + 14f * u, p.bottom - 8f * u, 9f, Pal.INK_L)
    }

    private fun drawTool(c: Canvas, x: Float, y: Float) {
        val u = gui.u
        pf.shader = null
        when (if (hoofOpen >= 0) Tool.CURE_PIED else tool) {
            Tool.ETRILLE -> {
                // étrille en caoutchouc : dents en anneaux, qui se remplit de poils et de terre
                pf.color = Color.rgb(190, 44, 40); c.drawOval(x - 17f * u, y - 11f * u, x + 17f * u, y + 11f * u, pf)
                for (k in 0..2) c.drawOval(x - (13f - k * 4f) * u, y - (8f - k * 2.6f) * u, x + (13f - k * 4f) * u, y + (8f - k * 2.6f) * u, Ink.stroke(Color.rgb(130, 20, 20), 1.6f * u))
                if (curryLoad > 0.05f) { pf.color = HorseArt.alpha(Scenery.mix(a0.look.body, Color.rgb(140, 116, 84), 0.5f), 0.85f * curryLoad); c.drawOval(x - 14f * u, y - 8f * u, x + 14f * u, y + 8f * u, pf) }
                c.drawOval(x - 17f * u, y - 11f * u, x + 17f * u, y + 11f * u, Ink.stroke(Ink.INK, 1.2f * u))
            }
            Tool.BROSSE_DURE -> {
                pf.color = Color.rgb(150, 104, 62); c.drawRoundRect(x - 22f * u, y - 12f * u, x + 22f * u, y - 2f * u, 4f * u, 4f * u, pf)
                c.drawRoundRect(x - 22f * u, y - 12f * u, x + 22f * u, y - 2f * u, 4f * u, 4f * u, Ink.stroke(Ink.INK, 1.1f * u))
                var bx = x - 19f * u; while (bx <= x + 19f * u) { Ink.line(c, bx, y - 2f * u, bx + 1f * u, y + 10f * u, 1.3f * u, Color.rgb(70, 56, 40)); bx += 3f * u }
            }
            Tool.BROSSE_DOUCE -> {
                pf.color = Color.rgb(226, 214, 192); c.drawRoundRect(x - 22f * u, y - 2f * u, x + 22f * u, y + 7f * u, 5f * u, 5f * u, pf)
                pf.color = Color.rgb(110, 62, 34); c.drawRoundRect(x - 23f * u, y - 11f * u, x + 23f * u, y - 1f * u, 6f * u, 6f * u, pf)
                Ink.line(c, x - 14f * u, y - 9f * u, x + 14f * u, y - 9f * u, 2f * u, Color.rgb(80, 44, 24))
                c.drawRoundRect(x - 23f * u, y - 11f * u, x + 23f * u, y + 7f * u, 6f * u, 6f * u, Ink.stroke(Ink.INK, 1.1f * u))
            }
            Tool.PEIGNE -> {
                pf.color = Color.rgb(60, 90, 160); c.drawRoundRect(x - 20f * u, y - 10f * u, x + 20f * u, y - 4f * u, 2f * u, 2f * u, pf)
                var bx = x - 18f * u; while (bx <= x + 18f * u) { Ink.line(c, bx, y - 4f * u, bx, y + 7f * u, 1.4f * u, Color.rgb(60, 90, 160)); bx += 3.2f * u }
            }
            Tool.CURE_PIED -> {
                Ink.line(c, x + 4f * u, y - 4f * u, x + 20f * u, y - 20f * u, 6f * u, Color.rgb(200, 60, 50))
                Ink.line(c, x + 4f * u, y - 4f * u, x, y + 2f * u, 2.4f * u, Color.rgb(170, 170, 176))
                Ink.line(c, x, y + 2f * u, x - 3f * u, y + 1f * u, 2f * u, Color.rgb(170, 170, 176))
            }
        }
    }

    private fun finish() {
        if (done) return
        done = true
        if (mask != null) recount()
        val pr = readiness()
        if (pr > 0.05f) {
            val rough = (roughAcc / 3f).coerceIn(0f, 1f)
            game.spend(0.75f)
            val r = game.groom(horse, pr, hoofDone.count { it }, rough)
            for (n in notes) game.log("Pansage de ${horse.name} : $n", MsgKind.INFO, horse.id)
            Looks.invalidate(horse.id)
            val tip = when {
                mudLeft > 0.4f -> " Il reste de la boue : l'étrille d'abord, en cercles."
                dustLeft > 0.5f -> " Il reste de la poussière : brosse dure dans le sens du poil."
                hoofDone.count { it } < 4 -> " Pense à curer les quatre pieds chaque jour."
                else -> ""
            }
            gui.toast(r.msg + tip, Pal.GREEN)
            if (pr > 0.85f && rough < 0.3f) app.sound.play(SoundFx.S.SNORT, 0.7f)
        }
        app.pop()
    }

    override fun onBack(): Boolean { if (hoofOpen >= 0) { hoofOpen = -1; return true }; finish(); return true }

    // ---------------------------------------------------------------- pour les captures d'écran
    /** Simule quelques gestes (outil, points en fractions de longueur et de hauteur du cheval) : utilisé par les tests de captures. */
    fun demo(toolIndex: Int, model: FloatArray, openHoof: Int = -1) {
        val w = gui.w; val h = gui.h
        ensureLayers(w, h)
        tool = Tool.values()[toolIndex]
        val pts = FloatArray(model.size) { i -> if (i % 2 == 0) hx + model[i] * a0.L * hs else hy + model[i] * a0.H * hs }
        var time = 0L
        lastX = pts[0]; lastY = pts[1]; lastTime = 0L; pdx = 0f; pdy = 0f; circAcc = 0f
        var i = 2
        while (i + 1 < pts.size) { time += 16L; move(pts[i], pts[i + 1], time); i += 2 }
        fingerX = pts[pts.size - 2]; fingerY = pts[pts.size - 1]
        if (openHoof >= 0) { hoofOpen = openHoof; if (hooves[openHoof] == null) hooves[openHoof] = makeHoof() }
        recount()
    }

    companion object {
        /** Résolution des calques de saleté par rapport à l'écran. */
        const val BS = 0.5f
        /** Grille de la sole pour le curage. */
        const val G = 30
    }

    /** Allée d'écurie : bardage en planches, boxes, fenêtre, sellerie, foin, sol pavé et paille. */
    private var aisle: android.graphics.Bitmap? = null
    private fun drawAisle(c: Canvas, w: Float, h: Float, u: Float) {
        val cached = aisle
        if (cached != null && cached.width == w.toInt() && cached.height == h.toInt()) { c.drawBitmap(cached, 0f, 0f, null); return }
        cached?.recycle()
        val bmp = android.graphics.Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val cc = Canvas(bmp)
        paintAisle(cc, w, h, u)
        aisle = bmp
        c.drawBitmap(bmp, 0f, 0f, null)
    }

    private fun paintAisle(c: Canvas, w: Float, h: Float, u: Float) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = com.cheval.core.Rng(77)
        val floorY = h * 0.74f
        // mur en planches horizontales, chacune avec sa nuance et ses veinures
        val plankH = 18f * u
        var yy = 0f; var row = 0
        while (yy < floorY) {
            val k = 0.88f + r.float() * 0.2f
            p.color = HorseArt.shade(Color.rgb(132, 94, 62), k); c.drawRect(0f, yy, w, yy + plankH, p)
            p.color = Color.argb(28, 255, 230, 190); c.drawRect(0f, yy, w, yy + 2f * u, p)
            p.color = Color.argb(70, 0, 0, 0); c.drawRect(0f, yy + plankH - 1.5f * u, w, yy + plankH, p)
            // joints décalés et veinures
            var jx = (row % 3) * 70f * u
            while (jx < w) { Ink.line(c, jx, yy + 1f, jx, yy + plankH - 1f, 1f * u, Color.argb(80, 40, 26, 16)); jx += 230f * u }
            repeat(6) {
                val gx = r.float() * w; val gy = yy + plankH * (0.3f + r.float() * 0.4f); val gl = r.range(30f, 90f) * u
                Ink.line(c, gx, gy, gx + gl, gy + r.range(-1.5f, 1.5f) * u, 0.7f * u, Color.argb(40, 40, 26, 16))
            }
            if (r.chance(0.4f)) { val nx = r.float() * w; c.drawOval(nx, yy + plankH * 0.35f, nx + 7f * u, yy + plankH * 0.65f, Ink.stroke(Color.argb(70, 40, 26, 16), 0.8f * u)) }
            yy += plankH; row++
        }
        // poutres verticales
        for (bx in listOf(0.06f, 0.3f, 0.62f, 0.93f)) {
            val x = w * bx
            p.color = Color.rgb(98, 66, 42); c.drawRect(x - 9f * u, 0f, x + 9f * u, floorY, p)
            p.color = Color.argb(40, 255, 230, 190); c.drawRect(x - 9f * u, 0f, x - 5f * u, floorY, p)
            c.drawRect(x - 9f * u, 0f, x + 9f * u, floorY, Ink.stroke(Ink.INK, 1.2f * u))
        }
        // fenêtre avec lumière du jour
        val win = RectF(w * 0.38f, h * 0.16f, w * 0.54f, h * 0.36f)
        p.shader = LinearGradient(0f, win.top, 0f, win.bottom, Color.rgb(196, 222, 236), Color.rgb(226, 236, 214), Shader.TileMode.CLAMP); c.drawRect(win, p); p.shader = null
        p.color = Color.rgb(120, 160, 110); c.drawRect(win.left, win.bottom - win.height() * 0.25f, win.right, win.bottom, p)
        c.drawRect(win, Ink.stroke(Color.rgb(236, 226, 206), 5f * u)); c.drawRect(win, Ink.stroke(Ink.INK, 1.2f * u))
        Ink.line(c, win.centerX(), win.top, win.centerX(), win.bottom, 3f * u, Color.rgb(236, 226, 206)); Ink.line(c, win.left, win.centerY(), win.right, win.centerY(), 3f * u, Color.rgb(236, 226, 206))
        // toile d'araignée dans le coin
        for (q in 0..3) { val ang = q * 0.45f; Ink.line(c, win.left + 3f * u, win.top + 3f * u, win.left + 3f * u + kotlin.math.cos(ang) * 22f * u, win.top + 3f * u + kotlin.math.sin(ang) * 22f * u, 0.5f * u, Color.argb(120, 255, 255, 255)) }
        // porte de box à gauche : bas en planches, haut à barreaux
        val bd = RectF(w * 0.08f, h * 0.2f, w * 0.28f, floorY)
        p.color = Color.rgb(30, 22, 16); c.drawRect(bd, p)
        val mid = bd.top + bd.height() * 0.45f
        p.color = Color.rgb(84, 120, 88); c.drawRect(bd.left, mid, bd.right, bd.bottom, p)
        var px = bd.left + 6f * u; while (px < bd.right) { Ink.line(c, px, mid, px, bd.bottom, 1f * u, Color.argb(90, 20, 30, 20)); px += 12f * u }
        Ink.line(c, bd.left, mid, bd.right, bd.bottom, 3f * u, Color.rgb(64, 96, 70)); Ink.line(c, bd.right, mid, bd.left, bd.bottom, 3f * u, Color.rgb(64, 96, 70))
        var bx2 = bd.left + 8f * u; while (bx2 < bd.right) { Ink.line(c, bx2, bd.top, bx2, mid, 2.5f * u, Color.rgb(60, 60, 60)); bx2 += 14f * u }
        c.drawRect(bd, Ink.stroke(Ink.INK, 1.5f * u))
        // plaque du box
        val pl = RectF(bd.centerX() - 28f * u, mid - 22f * u, bd.centerX() + 28f * u, mid - 6f * u)
        Ink.parchment(c, pl, u, border = true)
        // sellerie à droite : selle sur porte-selle, filet sur un crochet, licol
        val sx = w * 0.78f; val sy = h * 0.32f
        Ink.line(c, sx - 26f * u, sy + 10f * u, sx + 26f * u, sy + 10f * u, 5f * u, Color.rgb(90, 60, 40))
        val sp = Path(); sp.moveTo(sx - 34f * u, sy + 6f * u); sp.cubicTo(sx - 30f * u, sy - 18f * u, sx - 10f * u, sy - 6f * u, sx, sy - 6f * u)
        sp.cubicTo(sx + 14f * u, sy - 6f * u, sx + 26f * u, sy - 20f * u, sx + 34f * u, sy + 2f * u); sp.lineTo(sx + 30f * u, sy + 10f * u); sp.lineTo(sx - 30f * u, sy + 12f * u); sp.close()
        Ink.wash(c, sp, Color.rgb(110, 62, 34), 1.5f * u)
        Ink.line(c, sx + 6f * u, sy + 8f * u, sx + 8f * u, sy + 50f * u, 2f * u, Color.rgb(80, 46, 26))
        c.drawRect(sx + 3f * u, sy + 48f * u, sx + 13f * u, sy + 56f * u, Ink.stroke(Color.rgb(170, 170, 170), 2f * u))
        val hk = w * 0.86f; val hky = h * 0.18f
        c.drawCircle(hk, hky, 3f * u, Ink.fill(Color.rgb(160, 160, 160)))
        c.drawOval(hk - 14f * u, hky, hk + 14f * u, hky + 52f * u, Ink.stroke(Color.rgb(70, 40, 24), 2.5f * u))
        Ink.line(c, hk - 10f * u, hky + 30f * u, hk + 10f * u, hky + 30f * u, 2f * u, Color.rgb(70, 40, 24))
        c.drawCircle(hk, hky + 54f * u, 4f * u, Ink.stroke(Color.rgb(180, 180, 180), 1.6f * u))
        // anneaux d'attache avec longes
        for (rx in listOf(w * 0.15f, w * 0.8f)) {
            c.drawCircle(rx, h * 0.5f, 6f * u, Ink.stroke(Color.rgb(170, 170, 170), 2f * u))
            Ink.line(c, rx, h * 0.5f + 6f * u, rx + 4f * u, h * 0.5f + 30f * u, 2.5f * u, Color.rgb(196, 60, 50))
        }
        // sol pavé
        p.color = Color.rgb(150, 142, 130); c.drawRect(0f, floorY, w, h, p)
        var fy = floorY; var frow = 0
        while (fy < h) {
            val ph = 9f * u + (fy - floorY) * 0.12f
            var fx = -(frow % 2) * ph
            while (fx < w) {
                val kk = 0.85f + r.float() * 0.25f
                c.drawRoundRect(fx + 1f * u, fy + 1f * u, fx + ph * 2f - 1f * u, fy + ph - 1f * u, 3f * u, 3f * u, Ink.fill(HorseArt.shade(Color.rgb(160, 150, 136), kk)))
                fx += ph * 2f
            }
            fy += ph; frow++
        }
        p.shader = LinearGradient(0f, floorY, 0f, floorY + 30f * u, Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP); c.drawRect(0f, floorY, w, floorY + 30f * u, p); p.shader = null
        // brins de paille éparpillés
        repeat(160) {
            val x = r.float() * w; val y = floorY + r.float() * (h - floorY)
            val a = r.range(0f, 3.14f); val l = r.range(6f, 16f) * u
            Ink.line(c, x, y, x + kotlin.math.cos(a) * l, y + kotlin.math.sin(a) * l * 0.4f, 1.2f * u, if (r.chance(0.5f)) Color.rgb(222, 196, 120) else Color.rgb(196, 166, 92))
        }
        // botte de foin et seau
        val hb = RectF(w * 0.86f, floorY - 30f * u, w * 0.99f, floorY + 14f * u)
        Ink.wash(c, Path().apply { addRoundRect(hb, 6f * u, 6f * u, Path.Direction.CW) }, Color.rgb(214, 186, 106), 1.4f * u)
        repeat(40) { val x = hb.left + r.float() * hb.width(); val y = hb.top + r.float() * hb.height(); Ink.line(c, x, y, x + r.range(-8f, 8f) * u, y + r.range(-3f, 3f) * u, 0.8f * u, Color.argb(140, 150, 120, 60)) }
        Ink.line(c, hb.left + hb.width() * 0.3f, hb.top, hb.left + hb.width() * 0.3f, hb.bottom, 1.5f * u, Color.rgb(170, 60, 40)); Ink.line(c, hb.left + hb.width() * 0.7f, hb.top, hb.left + hb.width() * 0.7f, hb.bottom, 1.5f * u, Color.rgb(170, 60, 40))
        val bk = Path(); val bxc = w * 0.08f; val byc = floorY + 30f * u
        bk.moveTo(bxc - 18f * u, byc - 26f * u); bk.lineTo(bxc + 18f * u, byc - 26f * u); bk.lineTo(bxc + 14f * u, byc + 4f * u); bk.lineTo(bxc - 14f * u, byc + 4f * u); bk.close()
        Ink.wash(c, bk, Color.rgb(60, 110, 170), 1.4f * u)
        c.drawOval(bxc - 18f * u, byc - 30f * u, bxc + 18f * u, byc - 22f * u, Ink.fill(Color.rgb(120, 170, 200)))
        c.drawArc(bxc - 18f * u, byc - 46f * u, bxc + 18f * u, byc - 14f * u, 180f, 180f, false, Ink.stroke(Color.rgb(170, 170, 170), 1.5f * u))
        // lumière de la fenêtre et grain du papier
        p.shader = RadialGradient(w * 0.46f, h * 0.26f, w * 0.55f, Color.argb(80, 255, 225, 160), Color.argb(0, 255, 225, 160), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        Ink.grain(c, RectF(0f, 0f, w, h), 110)
    }

}
