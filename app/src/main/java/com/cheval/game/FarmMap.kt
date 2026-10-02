package com.cheval.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.cheval.core.BuildingType
import com.cheval.core.Game
import com.cheval.core.Rng
import com.cheval.core.Season
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Un paddock de la carte : ellipse irrégulière clôturée (coordonnées carte, en pixels). */
class Paddock(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val seed: Long) {
    val pts: FloatArray
    init {
        val r = Rng(seed)
        val n = 14
        pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = i / n.toFloat() * 2 * PI.toFloat()
            val k = r.range(0.9f, 1.08f)
            pts[i * 2] = cx + cos(a) * rx * k; pts[i * 2 + 1] = cy + sin(a) * ry * k
        }
    }
    fun contains(x: Float, y: Float): Boolean { val dx = (x - cx) / (rx * 0.82f); val dy = (y - cy) / (ry * 0.78f); return dx * dx + dy * dy < 1f }
    fun randomPoint(r: Float, s: Float): Pair<Float, Float> { val a = r * 2 * PI.toFloat(); val d = kotlin.math.sqrt(s) * 0.75f; return (cx + cos(a) * rx * d) to (cy + sin(a) * ry * d * 0.9f) }
}

/** Une parcelle constructible de la carte. */
class Plot(val type: BuildingType, val fx: Float, val fy: Float, val w: Float, val h: Float)

/**
 * Dessin de la carte du domaine vue de trois quarts, façon illustration : encre et aquarelle.
 * La couche statique (paysage, bâtiments) est mise en cache dans une image ; les chevaux et la lumière sont animés par-dessus.
 */
class FarmMapArt {
    var mapW = 1f; var mapH = 1f; var k = 1f
    private var cache: Bitmap? = null
    private var cacheKey = ""
    private val cacheScale = 0.7f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    val plots = listOf(
        Plot(BuildingType.ECURIE, 0.355f, 0.53f, 0.13f, 0.17f),
        Plot(BuildingType.GRENIER, 0.27f, 0.37f, 0.055f, 0.13f),
        Plot(BuildingType.CLUB_HOUSE, 0.2f, 0.73f, 0.08f, 0.14f),
        Plot(BuildingType.CARRIERE, 0.43f, 0.82f, 0.16f, 0.16f),
        Plot(BuildingType.MANEGE, 0.66f, 0.84f, 0.15f, 0.2f),
        Plot(BuildingType.MARCHEUR, 0.47f, 0.6f, 0.035f, 0.07f),
        Plot(BuildingType.POULINAGE, 0.46f, 0.43f, 0.04f, 0.09f),
        Plot(BuildingType.INFIRMERIE, 0.24f, 0.55f, 0.04f, 0.09f),
        Plot(BuildingType.DOUCHE, 0.29f, 0.66f, 0.03f, 0.07f),
        Plot(BuildingType.SELLERIE, 0.43f, 0.38f, 0.03f, 0.08f),
        Plot(BuildingType.PISTE, 0.86f, 0.5f, 0.17f, 0.18f),
        Plot(BuildingType.CROSS, 0.93f, 0.82f, 0.12f, 0.14f),
        Plot(BuildingType.CAMION, 0.08f, 0.5f, 0.05f, 0.07f),
    )

    fun paddocks(level: Int): List<Paddock> {
        val all = listOf(
            Paddock(0.58f * mapW, 0.47f * mapH, 0.09f * mapW, 0.13f * mapH, 11),
            Paddock(0.75f * mapW, 0.3f * mapH, 0.07f * mapW, 0.08f * mapH, 12),
            Paddock(0.78f * mapW, 0.62f * mapH, 0.07f * mapW, 0.1f * mapH, 13),
            Paddock(0.66f * mapW, 0.24f * mapH, 0.05f * mapW, 0.06f * mapH, 14),
        )
        return all.take(level.coerceIn(1, all.size))
    }

    fun resize(screenW: Float, screenH: Float) {
        mapH = screenH
        mapW = max(screenW * 2.2f, screenH * 3.9f)
        k = screenH / 360f
        cacheKey = ""
    }

    fun px(fx: Float) = fx * mapW
    fun py(fy: Float) = fy * mapH

    /** Échelle d'un objet selon sa profondeur (plus grand au premier plan). */
    fun depthScale(y: Float) = 0.62f + 0.6f * (y / mapH)

    // ===================================================================== couche statique
    fun drawStatic(c: Canvas, g: Game, amb: Ambience) {
        val key = buildString {
            for (b in BuildingType.values()) append(g.level(b))
            append('|'); g.junk.forEach { append(it.id).append(',') }
            append('|').append(amb.season).append(amb.snowGround)
        }
        if (key != cacheKey || cache == null) {
            cache?.recycle()
            val bw = (mapW * cacheScale).toInt().coerceAtLeast(1); val bh = (mapH * cacheScale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cc = Canvas(bmp)
            cc.scale(cacheScale, cacheScale)
            paintStatic(cc, g, amb)
            cache = bmp; cacheKey = key
        }
        val bmp = cache!!
        c.save(); c.scale(1f / cacheScale, 1f / cacheScale)
        c.drawBitmap(bmp, 0f, 0f, null)
        c.restore()
    }

    private fun paintStatic(c: Canvas, g: Game, amb: Ambience) {
        val day = Ambience(12f, amb.season, com.cheval.core.Sky.SOLEIL, 6f, 21f, 15f, 10f, amb.snowGround)
        val W = mapW; val H = mapH
        // ciel
        p.shader = LinearGradient(0f, 0f, 0f, H * 0.25f, Color.rgb(176, 208, 222), Color.rgb(232, 236, 220), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H * 0.3f, p); p.shader = null
        // montagnes lointaines
        ridge(c, H * 0.17f, H * 0.08f, 3, Color.rgb(150, 176, 186), 0.6f)
        ridge(c, H * 0.2f, H * 0.06f, 4, Color.rgb(128, 160, 150), 0.8f)
        // la baie : mer à gauche (village) et à droite (falaises)
        p.color = Color.rgb(112, 160, 180)
        path.reset(); path.moveTo(W * 0.02f, H * 0.2f); path.cubicTo(W * 0.1f, H * 0.17f, W * 0.2f, H * 0.19f, W * 0.3f, H * 0.21f)
        path.lineTo(W * 0.3f, H * 0.26f); path.cubicTo(W * 0.2f, H * 0.25f, W * 0.08f, H * 0.27f, W * 0.02f, H * 0.26f); path.close()
        Ink.wash(c, path, p.color, 0.8f * k, 0.25f)
        path.reset(); path.moveTo(W * 0.86f, H * 0.18f); path.cubicTo(W * 0.92f, H * 0.17f, W, H * 0.16f, W, H * 0.16f); path.lineTo(W, H * 0.3f); path.cubicTo(W * 0.96f, H * 0.28f, W * 0.9f, H * 0.24f, W * 0.86f, H * 0.18f); path.close()
        Ink.wash(c, path, Color.rgb(112, 160, 180), 0.8f * k, 0.25f)
        // village sur la rive
        val rv = Rng(31)
        for (i in 0 until 26) {
            val x = W * (0.03f + rv.float() * 0.22f); val y = H * (0.2f + rv.float() * 0.07f)
            house(c, x, y, 5f * k * rv.range(0.8f, 1.3f), if (rv.chance(0.6f)) Color.rgb(196, 110, 86) else Color.rgb(150, 120, 110))
        }
        // collines proches
        ridge(c, H * 0.29f, H * 0.07f, 7, Scenery.mix(Scenery.grassColor(day), Color.rgb(80, 110, 80), 0.35f), 1f)
        // sol
        val grass = Scenery.grassColor(day)
        p.shader = LinearGradient(0f, H * 0.25f, 0f, H, HorseArt.shade(grass, 0.92f), HorseArt.lighten(grass, 0.06f), Shader.TileMode.CLAMP)
        c.drawRect(0f, H * 0.26f, W, H, p); p.shader = null
        // taches d'aquarelle dans l'herbe
        val rg = Rng(5)
        repeat(90) {
            val x = rg.float() * W; val y = H * (0.3f + rg.float() * 0.7f)
            val pp = Ink.blobPath(x, y, k * rg.range(14f, 40f) * depthScale(y), k * rg.range(5f, 12f) * depthScale(y), rg.nextLong(), 6, 0.3f)
            c.drawPath(pp, Ink.fill(HorseArt.alpha(if (rg.chance(0.5f)) HorseArt.shade(grass, 0.82f) else HorseArt.lighten(grass, 0.12f), 0.45f)))
        }
        Ink.grain(c, RectF(0f, H * 0.26f, W, H), 150)
        // chemins de terre
        dirtPath(c, floatArrayOf(0f, 0.58f, 0.12f, 0.56f, 0.22f, 0.5f, 0.31f, 0.56f, 0.38f, 0.64f), 11f)
        dirtPath(c, floatArrayOf(0.31f, 0.56f, 0.4f, 0.7f, 0.43f, 0.78f), 8f)
        dirtPath(c, floatArrayOf(0.38f, 0.62f, 0.5f, 0.58f, 0.6f, 0.62f, 0.75f, 0.74f, 0.9f, 0.7f, 1f, 0.66f), 7f)
        dirtPath(c, floatArrayOf(0.22f, 0.5f, 0.17f, 0.44f), 6f)
        // tufts d'herbe
        val rt = Rng(9)
        repeat(700) {
            val x = rt.float() * W; val y = H * (0.3f + rt.float() * 0.7f)
            val s = depthScale(y) * k
            Ink.line(c, x, y, x - 1.5f * s, y - 4f * s, 0.7f * s, HorseArt.alpha(HorseArt.shade(grass, 0.55f), 0.7f))
            Ink.line(c, x + 1.5f * s, y, x + 3f * s, y - 3.5f * s, 0.7f * s, HorseArt.alpha(HorseArt.shade(grass, 0.55f), 0.7f))
        }
        // paddocks (intérieur et clôture du fond)
        for (pd in paddocks(g.level(BuildingType.PRE))) paddockGround(c, pd, grass)
        // décor : arbres, buissons, mare, potager, rochers
        val trees = listOf(0.05f to 0.4f, 0.12f to 0.33f, 0.19f to 0.3f, 0.33f to 0.29f, 0.4f to 0.31f, 0.49f to 0.28f, 0.53f to 0.31f, 0.63f to 0.18f,
            0.7f to 0.42f, 0.69f to 0.58f, 0.83f to 0.38f, 0.87f to 0.25f, 0.95f to 0.36f, 0.97f to 0.55f, 0.03f to 0.86f, 0.13f to 0.94f, 0.3f to 0.95f,
            0.55f to 0.95f, 0.6f to 0.74f, 0.8f to 0.92f, 0.98f to 0.95f, 0.24f to 0.86f)
        val items = ArrayList<Pair<Float, () -> Unit>>()
        for ((i, t) in trees.withIndex()) items += py(t.second) to { tree(c, px(t.first), py(t.second), 34f * k * depthScale(py(t.second)), i, day) }
        val rb = Rng(19)
        repeat(26) {
            val x = rb.float(); val y = 0.32f + rb.float() * 0.66f
            if (plots.none { pl -> kotlin.math.abs(pl.fx - x) < pl.w * 0.7f && kotlin.math.abs(pl.fy - y) < pl.h * 0.6f } && paddocks(4).none { it.contains(px(x), py(y)) })
                items += py(y) to { bush(c, px(x), py(y), 10f * k * depthScale(py(y)), it, day) }
        }
        items += py(0.47f) to { pond(c, px(0.155f), py(0.48f)) }
        items += py(0.42f) to { garden(c, px(0.22f), py(0.41f)) }
        // bâtiments et parcelles
        for (pl in plots) items += py(pl.fy) to { building(c, g, pl, day) }
        // la caravane du joueur et la vieille voiture
        items += py(0.43f) to { caravan(c, px(0.13f), py(0.43f), 1f) }
        items += py(0.58f) to { oldCar(c, px(0.05f), py(0.6f)) }
        // tableau d'affichage et boîte aux lettres à l'entrée
        items += py(0.56f) to { board(c, px(0.1f), py(0.56f)) }
        items += py(0.6f) to { mailbox(c, px(0.15f), py(0.6f)) }
        // déchets
        for (j in g.junk) items += py(j.y) to { junkPile(c, px(j.x), py(j.y), j.kind, j.id.toLong()) }
        for ((_, f) in items.sortedBy { it.first }) f()
    }

    private fun ridge(c: Canvas, base: Float, height: Float, seed: Int, col: Int, ink: Float) {
        val r = Rng(seed.toLong())
        path.reset(); path.moveTo(0f, base + height)
        val edge = Path(); var first = true
        var x = 0f
        val a1 = r.range(2f, 4f); val a2 = r.range(6f, 11f); val ph = r.range(0f, 6f)
        while (x <= mapW + 10f) {
            val f = x / mapW
            val y = base - height * (0.5f + 0.3f * sin(f * a1 * 3.14f + ph) + 0.2f * sin(f * a2 * 3.14f + ph * 2))
            path.lineTo(x, y); if (first) { edge.moveTo(x, y); first = false } else edge.lineTo(x, y)
            x += 10f
        }
        path.lineTo(mapW, base + height); path.close()
        c.drawPath(path, Ink.fill(col))
        c.save(); c.clipPath(path); Ink.grain(c, RectF(0f, base - height * 2, mapW, base + height), 120); c.restore()
        c.drawPath(edge, Ink.stroke(HorseArt.alpha(Ink.INK, 0.6f * ink), 1f * k))
    }

    private fun house(c: Canvas, x: Float, y: Float, s: Float, roof: Int) {
        c.drawRect(x - s, y - s, x + s, y, Ink.fill(Color.rgb(236, 228, 210)))
        path.reset(); path.moveTo(x - s * 1.2f, y - s); path.lineTo(x, y - s * 1.9f); path.lineTo(x + s * 1.2f, y - s); path.close()
        c.drawPath(path, Ink.fill(roof))
        c.drawRect(x - s, y - s, x + s, y, Ink.stroke(Ink.INK_SOFT, 0.5f * k)); c.drawPath(path, Ink.stroke(Ink.INK_SOFT, 0.5f * k))
    }

    private fun dirtPath(c: Canvas, pts: FloatArray, width: Float) {
        path.reset()
        path.moveTo(px(pts[0]), py(pts[1]))
        var i = 2
        while (i + 3 < pts.size) { path.quadTo(px(pts[i]), py(pts[i + 1]), (px(pts[i]) + px(pts[i + 2])) / 2, (py(pts[i + 1]) + py(pts[i + 3])) / 2); i += 2 }
        path.lineTo(px(pts[pts.size - 2]), py(pts[pts.size - 1]))
        c.drawPath(path, Ink.stroke(Color.rgb(150, 120, 84), width * k + 2f * k))
        c.drawPath(path, Ink.stroke(Color.rgb(196, 168, 122), width * k))
        val pe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width * k * 0.3f; color = Color.argb(60, 255, 245, 220); pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * k, 9f * k), 0f) }
        c.drawPath(path, pe)
    }

    fun tree(c: Canvas, x: Float, y: Float, s: Float, i: Int, amb: Ambience) {
        c.drawOval(x - s * 0.5f, y - s * 0.08f, x + s * 0.5f, y + s * 0.1f, Ink.fill(Color.argb(60, 30, 50, 20)))
        // tronc
        path.reset(); path.moveTo(x - s * 0.07f, y); path.lineTo(x - s * 0.04f, y - s * 0.7f); path.lineTo(x + s * 0.05f, y - s * 0.7f); path.lineTo(x + s * 0.08f, y); path.close()
        Ink.wash(c, path, Color.rgb(110, 84, 60), 0.9f * k, 0.3f, 60)
        if (amb.season == Season.HIVER) {
            val r = Rng(i.toLong())
            repeat(6) { val a = -PI.toFloat() / 2 + r.range(-1f, 1f); Ink.line(c, x, y - s * 0.6f, x + cos(a) * s * 0.5f, y - s * 0.6f + sin(a) * s * 0.5f, 1f * k) }
            return
        }
        val col = Scenery.foliage(amb, i)
        val r = Rng(i * 77L)
        for (b in 0 until 3) {
            val bx = x + r.range(-0.3f, 0.3f) * s; val by = y - s * r.range(0.85f, 1.15f)
            val pp = Ink.blobPath(bx, by, s * r.range(0.38f, 0.5f), s * r.range(0.32f, 0.42f), r.nextLong(), 9, 0.25f)
            Ink.wash(c, pp, HorseArt.shade(col, r.range(0.9f, 1.08f)), 1f * k, 0.3f, 130)
        }
        // touches de lumière et ombres
        c.drawPath(Ink.blobPath(x - s * 0.15f, y - s * 1.15f, s * 0.18f, s * 0.12f, i * 3L, 6, 0.3f), Ink.fill(HorseArt.alpha(Color.rgb(230, 240, 180), 0.35f)))
        repeat(5) { val a = r.range(0f, 6.28f); val d = r.range(0.1f, 0.35f) * s; Ink.line(c, x + cos(a) * d, y - s + sin(a) * d * 0.7f, x + cos(a) * d + 3f * k, y - s + sin(a) * d * 0.7f + 2f * k, 0.8f * k, HorseArt.alpha(Ink.INK, 0.5f)) }
    }

    private fun bush(c: Canvas, x: Float, y: Float, s: Float, i: Int, amb: Ambience) {
        val col = HorseArt.shade(Scenery.foliage(amb, i + 1), 0.9f)
        Ink.wash(c, Ink.blobPath(x, y - s * 0.4f, s, s * 0.6f, i * 13L, 7, 0.3f), col, 0.8f * k, 0.3f, 110)
        if (amb.season == Season.PRINTEMPS || amb.season == Season.ETE) {
            val r = Rng(i.toLong())
            repeat(4) { c.drawCircle(x + r.range(-0.7f, 0.7f) * s, y - s * r.range(0.2f, 0.7f), 1.4f * k, Ink.fill(if (i % 2 == 0) Color.rgb(250, 240, 120) else Color.rgb(240, 160, 180))) }
        }
    }

    private fun pond(c: Canvas, x: Float, y: Float) {
        val pp = Ink.blobPath(x, y, 34f * k, 12f * k, 44, 8, 0.2f)
        Ink.wash(c, pp, Color.rgb(110, 160, 170), 1f * k, 0.3f, 80)
        repeat(6) { i -> val rx = x - 30f * k + i * 4f * k; Ink.line(c, rx, y - 6f * k, rx + 1f * k, y - 18f * k, 1f * k, Color.rgb(80, 110, 60)) }
        c.drawOval(x - 10f * k, y - 3f * k, x + 4f * k, y + 1f * k, Ink.fill(Color.argb(90, 255, 255, 255)))
    }

    private fun garden(c: Canvas, x: Float, y: Float) {
        for (row in 0..3) {
            val ry = y + row * 4f * k
            Ink.line(c, x - 22f * k + row * 2f * k, ry, x + 18f * k + row * 2f * k, ry, 2.5f * k, Color.rgb(140, 104, 70))
            for (q in 0..6) c.drawCircle(x - 20f * k + q * 6f * k + row * 2f * k, ry - 2f * k, 2f * k, Ink.fill(Color.rgb(90, 150, 70)))
        }
        // tournesols
        for (q in 0..3) { val sx = x + 26f * k + q * 5f * k; Ink.line(c, sx, y + 8f * k, sx, y - 12f * k, 1f * k, Color.rgb(70, 110, 50)); c.drawCircle(sx, y - 13f * k, 3f * k, Ink.fill(Color.rgb(240, 196, 50))); c.drawCircle(sx, y - 13f * k, 1.2f * k, Ink.fill(Color.rgb(110, 70, 30))) }
    }

    private fun paddockGround(c: Canvas, pd: Paddock, grass: Int) {
        path.reset()
        val n = pd.pts.size / 2
        path.moveTo(pd.pts[0], pd.pts[1])
        for (i in 1..n) { val j = i % n; val m = (i + 1) % n; path.quadTo(pd.pts[j * 2], pd.pts[j * 2 + 1], (pd.pts[j * 2] + pd.pts[m * 2]) / 2, (pd.pts[j * 2 + 1] + pd.pts[m * 2 + 1]) / 2) }
        path.close()
        c.drawPath(path, Ink.fill(HorseArt.alpha(HorseArt.lighten(grass, 0.1f), 0.6f)))
        // coin boueux près de la barrière et abreuvoir
        c.drawPath(Ink.blobPath(pd.cx - pd.rx * 0.6f, pd.cy + pd.ry * 0.55f, pd.rx * 0.22f, pd.ry * 0.12f, pd.seed, 7, 0.3f), Ink.fill(HorseArt.alpha(Color.rgb(140, 110, 76), 0.6f)))
        val tx = pd.cx + pd.rx * 0.35f; val ty = pd.cy - pd.ry * 0.45f
        c.drawRect(tx - 9f * k, ty - 4f * k, tx + 9f * k, ty + 2f * k, Ink.fill(Color.rgb(150, 156, 166))); c.drawRect(tx - 9f * k, ty - 4f * k, tx + 9f * k, ty + 2f * k, Ink.stroke(Ink.INK, 0.8f * k))
        c.drawRect(tx - 7f * k, ty - 3f * k, tx + 7f * k, ty - 1f * k, Ink.fill(Color.rgb(120, 170, 190)))
        fence(c, pd, back = true)
    }

    /** Clôture en bois du paddock : moitié arrière (dans la couche statique) ou avant (par-dessus les chevaux). */
    fun fence(c: Canvas, pd: Paddock, back: Boolean) {
        val n = pd.pts.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x1 = pd.pts[i * 2]; val y1 = pd.pts[i * 2 + 1]; val x2 = pd.pts[j * 2]; val y2 = pd.pts[j * 2 + 1]
            val isBack = (y1 + y2) / 2 < pd.cy
            if (isBack != back) continue
            if (!back && i == n / 4) continue // l'entrée (barrière ouverte)
            val s1 = depthScale(y1) * k; val s2 = depthScale(y2) * k
            val post = 13f
            for (rail in listOf(0.45f, 0.85f)) {
                Ink.line(c, x1, y1 - post * s1 * rail, x2, y2 - post * s2 * rail, 2.4f * (s1 + s2) / 2, Ink.INK)
                Ink.line(c, x1, y1 - post * s1 * rail, x2, y2 - post * s2 * rail, 1.4f * (s1 + s2) / 2, Color.rgb(150, 108, 70))
            }
            for ((x, y, s) in listOf(Triple(x1, y1, s1), Triple((x1 + x2) / 2, (y1 + y2) / 2, (s1 + s2) / 2))) {
                c.drawRect(x - 1.6f * s, y - post * s, x + 1.6f * s, y + 1f * s, Ink.fill(Color.rgb(128, 92, 60)))
                c.drawRect(x - 1.6f * s, y - post * s, x + 1.6f * s, y + 1f * s, Ink.stroke(Ink.INK, 0.8f * s))
            }
        }
    }

    // ===================================================================== bâtiments en vue de trois quarts
    /** Boîte oblique : façade, pignon côté droit, toit à deux pans. Retourne le rectangle de façade. */
    fun box(c: Canvas, x: Float, y: Float, w: Float, h: Float, d: Float, wall: Int, roof: Int, rh: Float = h * 0.55f, ruined: Boolean = false): RectF {
        val dx = d * 0.55f; val dy = d * 0.38f
        val L = x - w / 2; val R = x + w / 2; val T = y - h
        val iw = 0.9f * k
        // ombre portée
        c.drawPath(Ink.blobPath(x + dx * 0.4f, y - dy * 0.3f, w * 0.6f + dx * 0.4f, dy * 0.7f + 4f * k, (x * 3).toLong(), 6, 0.1f), Ink.fill(Color.argb(55, 30, 40, 20)))
        // pignon (côté droit)
        path.reset(); path.moveTo(R, y); path.lineTo(R + dx, y - dy); path.lineTo(R + dx, T - dy); path.lineTo(R + dx / 2, T - dy / 2 - rh); path.lineTo(R, T); path.close()
        Ink.wash(c, path, HorseArt.shade(wall, 0.8f), iw, 0.2f, 90)
        // façade
        path.reset(); path.addRect(L, T, R, y, Path.Direction.CW)
        Ink.wash(c, path, wall, iw, 0.2f, 110)
        // planches verticales
        var px0 = L + 5f * k
        while (px0 < R - 2f) { Ink.line(c, px0, T + 2f, px0, y - 1f, 0.5f * k, HorseArt.alpha(Ink.INK, 0.3f)); px0 += 5.5f * k }
        // toit
        val oh = 4f * k
        path.reset()
        path.moveTo(L - oh, T + oh * 0.4f); path.lineTo(R + oh * 0.5f, T + oh * 0.4f)
        path.lineTo(R + dx / 2 + oh * 0.5f, T - dy / 2 - rh); path.lineTo(L + dx / 2 - oh, T - dy / 2 - rh); path.close()
        Ink.wash(c, path, roof, iw, 0.25f, 130)
        // tuiles / tôles
        val rows = 5
        for (r in 1 until rows) {
            val f = r / rows.toFloat()
            Ink.line(c, L - oh + (dx / 2) * f, T + oh * 0.4f - (dy / 2 + rh + oh * 0.4f) * f, R + oh * 0.5f + (dx / 2) * f, T + oh * 0.4f - (dy / 2 + rh + oh * 0.4f) * f, 0.6f * k, HorseArt.alpha(Ink.INK, 0.35f))
        }
        if (ruined) {
            // trous dans le toit, planches arrachées
            val r = Rng((x * 7).toLong())
            repeat(3) {
                val fx = r.range(0.2f, 0.8f); val fy = r.range(0.2f, 0.7f)
                val hx = L + (R - L) * fx + dx / 2 * fy; val hy = T - (dy / 2 + rh) * fy
                c.drawPath(Ink.blobPath(hx, hy, 6f * k, 3f * k, r.nextLong(), 5, 0.4f), Ink.fill(Color.rgb(50, 40, 32)))
            }
            Ink.line(c, L + w * 0.1f, T + h * 0.3f, L + w * 0.3f, T + h * 0.5f, 2f * k, Color.rgb(110, 84, 60))
            c.drawPath(Ink.blobPath(L + w * 0.2f, y - 2f * k, w * 0.15f, 3f * k, 9, 6, 0.4f), Ink.fill(HorseArt.alpha(Color.rgb(90, 130, 60), 0.7f)))
        }
        return RectF(L, T, R, y)
    }

    /** Rectangles des portes de box (pour dessiner les têtes des chevaux par-dessus). */
    val boxDoors = ArrayList<RectF>()

    private fun building(c: Canvas, g: Game, pl: Plot, amb: Ambience) {
        val lvl = g.level(pl.type)
        val x = px(pl.fx); val y = py(pl.fy); val s = depthScale(y)
        val w = pl.w * mapW; val h = pl.h * mapH
        if (pl.type == BuildingType.ECURIE) boxDoors.clear()
        if (lvl == 0 && pl.type != BuildingType.ECURIE && pl.type != BuildingType.GRENIER) { emptyPlot(c, x, y, w, h * 0.5f, pl.type); return }
        when (pl.type) {
            BuildingType.ECURIE -> {
                if (lvl == 0) {
                    // le vieil abri de deux boxes, comme on l'a trouvé
                    val f = box(c, x, y, w * 0.55f, h * 0.45f, w * 0.25f, Color.rgb(150, 116, 84), Color.rgb(120, 110, 104), ruined = true)
                    doors(c, f, 2)
                    // auvent bancal
                    path.reset(); path.moveTo(f.left - w * 0.3f, f.top + 6f * k); path.lineTo(f.left, f.top); path.lineTo(f.left, f.top + 5f * k); path.lineTo(f.left - w * 0.3f, f.top + 12f * k); path.close()
                    Ink.wash(c, path, Color.rgb(140, 120, 100), 0.8f * k)
                    Ink.line(c, f.left - w * 0.28f, f.top + 12f * k, f.left - w * 0.28f, f.bottom, 1.8f * k, Color.rgb(110, 84, 60))
                } else {
                    val f = box(c, x, y, w, h * 0.5f, w * 0.3f, Color.rgb(176, 132, 92), Color.rgb(140, 86, 70))
                    doors(c, f, min(12, g.boxes()).coerceAtMost(8))
                }
            }
            BuildingType.GRENIER -> {
                val f = box(c, x, y, w, h * 0.55f, w * 0.7f, if (lvl >= 2) Color.rgb(176, 76, 60) else Color.rgb(150, 112, 80), Color.rgb(96, 84, 80), ruined = lvl < 2)
                c.drawRect(f.left + f.width() * 0.25f, f.top + f.height() * 0.3f, f.right - f.width() * 0.25f, f.bottom, Ink.fill(Color.rgb(70, 52, 40)))
                c.drawRect(f.left + f.width() * 0.28f, f.top + f.height() * 0.4f, f.right - f.width() * 0.28f, f.bottom - f.height() * 0.2f, Ink.fill(Color.rgb(222, 196, 120)))
            }
            BuildingType.CLUB_HOUSE -> {
                val f = box(c, x, y, w, h * 0.5f, w * 0.4f, Color.rgb(236, 228, 210), Color.rgb(80, 104, 90))
                for (i in 0..2) { val wx = f.left + f.width() * (0.12f + i * 0.3f); c.drawRect(wx, f.top + f.height() * 0.25f, wx + f.width() * 0.16f, f.top + f.height() * 0.55f, Ink.fill(Color.rgb(150, 180, 196))); c.drawRect(wx, f.top + f.height() * 0.25f, wx + f.width() * 0.16f, f.top + f.height() * 0.55f, Ink.stroke(Ink.INK, 0.7f * k)) }
                // terrasse et parasols
                c.drawCircle(f.left - 10f * k, f.bottom - 14f * k, 9f * k, Ink.fill(Color.rgb(200, 60, 60))); Ink.line(c, f.left - 10f * k, f.bottom - 14f * k, f.left - 10f * k, f.bottom, 1f * k)
            }
            BuildingType.CARRIERE -> arena(c, x, y, w, h, false)
            BuildingType.MANEGE -> {
                val f = box(c, x, y, w, h * 0.5f, w * 0.35f, Color.rgb(196, 176, 146), Color.rgb(110, 116, 122), rh = h * 0.35f)
                var xx = f.left + 6f * k; while (xx < f.right) { c.drawRect(xx, f.top + 3f * k, xx + 5f * k, f.top + 9f * k, Ink.fill(Color.rgb(180, 200, 210))); xx += 14f * k }
                c.drawRect(f.centerX() - 10f * k, f.bottom - 22f * k, f.centerX() + 10f * k, f.bottom, Ink.fill(Color.rgb(90, 70, 52)))
            }
            BuildingType.PISTE -> {
                val pp = Ink.blobPath(x, y, w * 0.5f, h * 0.35f, 71, 12, 0.03f)
                Ink.wash(c, pp, Color.rgb(200, 176, 132), 1f * k, 0.2f)
                c.drawPath(Ink.blobPath(x, y, w * 0.36f, h * 0.2f, 71, 12, 0.03f), Ink.fill(Scenery.grassColor(amb)))
                c.drawPath(Ink.blobPath(x, y, w * 0.5f, h * 0.35f, 71, 12, 0.03f), Ink.stroke(Color.WHITE, 1.5f * k))
            }
            BuildingType.CROSS -> {
                c.drawRoundRect(x - w * 0.4f, y - 6f * k, x - w * 0.1f, y, 3f * k, 3f * k, Ink.fill(Color.rgb(120, 86, 56))); c.drawRoundRect(x - w * 0.4f, y - 6f * k, x - w * 0.1f, y, 3f * k, 3f * k, Ink.stroke(Ink.INK, 0.8f * k))
                Ink.wash(c, Ink.blobPath(x + w * 0.2f, y - h * 0.15f, w * 0.18f, h * 0.08f, 5, 7, 0.2f), Color.rgb(110, 160, 180), 0.8f * k)
                bush(c, x, y - h * 0.3f, 12f * k, 3, amb)
            }
            BuildingType.MARCHEUR -> {
                c.drawOval(x - w, y - h * 0.4f, x + w, y + h * 0.1f, Ink.stroke(Color.rgb(120, 92, 60), 3f * k))
                Ink.line(c, x, y - h * 0.15f, x, y - h * 0.9f, 2f * k); c.drawCircle(x, y - h * 0.9f, 3f * k, Ink.fill(Color.rgb(160, 160, 160)))
            }
            BuildingType.CAMION -> trailer(c, x, y, s)
            else -> box(c, x, y, w, h * 0.5f, w * 0.6f, Color.rgb(196, 170, 136), Color.rgb(130, 100, 84))
        }
    }

    private fun doors(c: Canvas, f: RectF, n: Int) {
        val dw = f.width() / (n + 0.4f)
        for (i in 0 until n) {
            val l = f.left + dw * (0.2f + i) + dw * 0.08f; val r = l + dw * 0.84f
            val top = f.top + f.height() * 0.25f
            val open = RectF(l, top, r, top + (f.bottom - top) * 0.45f)
            c.drawRect(open, Ink.fill(Color.rgb(46, 36, 28)))
            boxDoors += RectF(open)
            val door = RectF(l, open.bottom, r, f.bottom)
            c.drawRect(door, Ink.fill(Color.rgb(84, 120, 88)))
            Ink.line(c, door.left, door.top, door.right, door.bottom, 0.9f * k); Ink.line(c, door.right, door.top, door.left, door.bottom, 0.9f * k)
            c.drawRect(l, top, r, f.bottom, Ink.stroke(Ink.INK, 1f * k))
        }
    }

    private fun arena(c: Canvas, x: Float, y: Float, w: Float, h: Float, ghost: Boolean) {
        val dx = w * 0.3f
        path.reset()
        path.moveTo(x - w / 2, y); path.lineTo(x + w / 2, y); path.lineTo(x + w / 2 + dx, y - h * 0.45f); path.lineTo(x - w / 2 + dx, y - h * 0.45f); path.close()
        Ink.wash(c, path, Color.rgb(222, 200, 156), 1f * k, 0.25f, 150)
        // lice blanche
        c.drawPath(path, Ink.stroke(Color.WHITE, 2.2f * k))
        // obstacles
        val cols = intArrayOf(Color.rgb(200, 50, 50), Color.rgb(50, 90, 170), Color.rgb(230, 170, 40))
        for (i in 0..2) {
            val jx = x - w * 0.3f + i * w * 0.3f + dx * 0.5f; val jy = y - h * 0.22f + (i % 2) * 6f * k
            Ink.line(c, jx - 8f * k, jy, jx - 8f * k, jy - 12f * k, 1.4f * k, Color.WHITE)
            Ink.line(c, jx + 8f * k, jy - 4f * k, jx + 8f * k, jy - 16f * k, 1.4f * k, Color.WHITE)
            Ink.line(c, jx - 8f * k, jy - 8f * k, jx + 8f * k, jy - 12f * k, 2.2f * k, cols[i])
        }
    }

    private fun emptyPlot(c: Canvas, x: Float, y: Float, w: Float, h: Float, type: BuildingType) {
        // terrain à construire : piquets et ficelle, panneau
        path.reset(); path.addRect(x - w / 2, y - h, x + w / 2, y, Path.Direction.CW)
        c.drawPath(path, Ink.fill(HorseArt.alpha(Color.rgb(170, 150, 100), 0.25f)))
        val pe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * k; color = HorseArt.alpha(Ink.INK, 0.6f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * k, 3f * k), 0f) }
        c.drawPath(path, pe)
        for ((px0, py0) in listOf(x - w / 2 to y - h, x + w / 2 to y - h, x - w / 2 to y, x + w / 2 to y)) Ink.line(c, px0, py0, px0, py0 - 7f * k, 1.4f * k, Color.rgb(120, 90, 60))
        signPost(c, x, y - h * 0.3f)
    }

    fun signPost(c: Canvas, x: Float, y: Float) {
        Ink.line(c, x, y, x, y - 18f * k, 2f * k, Color.rgb(110, 80, 52))
        val r = RectF(x - 10f * k, y - 24f * k, x + 10f * k, y - 14f * k)
        c.drawRect(r, Ink.fill(Ink.WOOD_L)); c.drawRect(r, Ink.stroke(Ink.INK, 0.8f * k))
        Ink.line(c, r.left + 4f * k, r.centerY(), r.right - 4f * k, r.centerY(), 1f * k, HorseArt.alpha(Ink.INK, 0.6f))
    }

    fun caravan(c: Canvas, x: Float, y: Float, s0: Float) {
        val s = 1.25f * k * depthScale(y) * s0
        c.drawOval(x - 30f * s, y - 3f * s, x + 34f * s, y + 5f * s, Ink.fill(Color.argb(60, 30, 40, 20)))
        path.reset()
        path.moveTo(x - 26f * s, y - 4f * s); path.lineTo(x - 26f * s, y - 22f * s)
        path.cubicTo(x - 26f * s, y - 34f * s, x - 12f * s, y - 38f * s, x, y - 38f * s)
        path.cubicTo(x + 14f * s, y - 38f * s, x + 26f * s, y - 34f * s, x + 26f * s, y - 22f * s)
        path.lineTo(x + 26f * s, y - 4f * s); path.close()
        Ink.wash(c, path, Color.rgb(236, 168, 60), 1.1f * k)
        // toit clair
        path.reset(); path.moveTo(x - 26f * s, y - 26f * s); path.cubicTo(x - 24f * s, y - 36f * s, x - 10f * s, y - 38f * s, x, y - 38f * s); path.cubicTo(x + 10f * s, y - 38f * s, x + 24f * s, y - 36f * s, x + 26f * s, y - 26f * s)
        path.lineTo(x + 26f * s, y - 22f * s); path.cubicTo(x + 10f * s, y - 30f * s, x - 10f * s, y - 30f * s, x - 26f * s, y - 22f * s); path.close()
        Ink.wash(c, path, Color.rgb(244, 236, 218), 0.8f * k)
        // porte, fenêtres, roue, marchepied
        c.drawRoundRect(x - 6f * s, y - 26f * s, x + 4f * s, y - 4f * s, 5f * s, 5f * s, Ink.fill(Color.rgb(240, 230, 210))); c.drawRoundRect(x - 6f * s, y - 26f * s, x + 4f * s, y - 4f * s, 5f * s, 5f * s, Ink.stroke(Ink.INK, 0.9f * k))
        c.drawRoundRect(x + 9f * s, y - 24f * s, x + 21f * s, y - 15f * s, 2f * s, 2f * s, Ink.fill(Color.rgb(150, 190, 200))); c.drawRoundRect(x + 9f * s, y - 24f * s, x + 21f * s, y - 15f * s, 2f * s, 2f * s, Ink.stroke(Ink.INK, 0.9f * k))
        c.drawRoundRect(x - 22f * s, y - 24f * s, x - 12f * s, y - 15f * s, 2f * s, 2f * s, Ink.fill(Color.rgb(150, 190, 200))); c.drawRoundRect(x - 22f * s, y - 24f * s, x - 12f * s, y - 15f * s, 2f * s, 2f * s, Ink.stroke(Ink.INK, 0.9f * k))
        c.drawCircle(x + 14f * s, y - 3f * s, 5f * s, Ink.fill(Color.rgb(60, 56, 54))); c.drawCircle(x + 14f * s, y - 3f * s, 5f * s, Ink.stroke(Ink.INK, 0.9f * k))
        // cheminée
        c.drawRect(x + 14f * s, y - 44f * s, x + 18f * s, y - 36f * s, Ink.fill(Color.rgb(120, 110, 104)))
        // pots de fleurs et chaise
        c.drawCircle(x - 30f * s, y - 2f * s, 3f * s, Ink.fill(Color.rgb(196, 100, 60))); c.drawCircle(x - 30f * s, y - 6f * s, 3f * s, Ink.fill(Color.rgb(90, 150, 70)))
    }

    private fun oldCar(c: Canvas, x: Float, y: Float) {
        val s = 1.1f * k * depthScale(y)
        path.reset(); path.moveTo(x - 24f * s, y - 4f * s); path.lineTo(x - 24f * s, y - 12f * s); path.lineTo(x - 12f * s, y - 14f * s); path.lineTo(x - 6f * s, y - 22f * s); path.lineTo(x + 10f * s, y - 22f * s); path.lineTo(x + 16f * s, y - 14f * s); path.lineTo(x + 26f * s, y - 12f * s); path.lineTo(x + 26f * s, y - 4f * s); path.close()
        Ink.wash(c, path, Color.rgb(150, 158, 170), 1f * k)
        c.drawRect(x - 4f * s, y - 20f * s, x + 8f * s, y - 14f * s, Ink.fill(Color.rgb(180, 200, 210)))
        for (wx in listOf(x - 14f * s, x + 16f * s)) { c.drawCircle(wx, y - 3f * s, 4.5f * s, Ink.fill(Color.rgb(50, 46, 44))); c.drawCircle(wx, y - 3f * s, 4.5f * s, Ink.stroke(Ink.INK, 0.8f * k)) }
    }

    private fun trailer(c: Canvas, x: Float, y: Float, s0: Float) {
        val s = 1.2f * k * s0
        path.reset(); path.moveTo(x - 26f * s, y - 4f * s); path.lineTo(x - 26f * s, y - 26f * s); path.quadTo(x - 26f * s, y - 32f * s, x - 18f * s, y - 32f * s); path.lineTo(x + 20f * s, y - 32f * s); path.quadTo(x + 26f * s, y - 32f * s, x + 26f * s, y - 24f * s); path.lineTo(x + 26f * s, y - 4f * s); path.close()
        Ink.wash(c, path, Color.rgb(222, 184, 190), 1f * k)
        c.drawRect(x - 20f * s, y - 26f * s, x + 4f * s, y - 18f * s, Ink.fill(Color.rgb(90, 80, 84)))
        // silhouette de cheval peinte
        c.drawCircle(x + 14f * s, y - 18f * s, 4f * s, Ink.fill(Color.rgb(160, 110, 120)))
        for (wx in listOf(x - 8f * s, x + 6f * s)) { c.drawCircle(wx, y - 3f * s, 4.5f * s, Ink.fill(Color.rgb(50, 46, 44))) }
    }

    private fun board(c: Canvas, x: Float, y: Float) {
        val s = k * depthScale(y)
        Ink.line(c, x - 9f * s, y, x - 9f * s, y - 26f * s, 2f * s, Color.rgb(110, 80, 52)); Ink.line(c, x + 9f * s, y, x + 9f * s, y - 26f * s, 2f * s, Color.rgb(110, 80, 52))
        val r = RectF(x - 14f * s, y - 32f * s, x + 14f * s, y - 16f * s)
        c.drawRect(r, Ink.fill(Ink.WOOD)); c.drawRect(r, Ink.stroke(Ink.INK, 1f * k))
        for (i in 0..2) c.drawRect(r.left + 3f * s + i * 8.5f * s, r.top + 3f * s, r.left + 9f * s + i * 8.5f * s, r.bottom - 3f * s, Ink.fill(Ink.PARCH))
        c.drawCircle(r.left + 6f * s, r.top + 4f * s, 1f * s, Ink.fill(Color.RED))
    }

    private fun mailbox(c: Canvas, x: Float, y: Float) {
        val s = k * depthScale(y)
        Ink.line(c, x, y, x, y - 14f * s, 2f * s, Color.rgb(110, 80, 52))
        c.drawRoundRect(x - 7f * s, y - 22f * s, x + 7f * s, y - 13f * s, 3f * s, 3f * s, Ink.fill(Color.rgb(60, 110, 160)))
        c.drawRoundRect(x - 7f * s, y - 22f * s, x + 7f * s, y - 13f * s, 3f * s, 3f * s, Ink.stroke(Ink.INK, 1f * k))
        Ink.line(c, x + 7f * s, y - 21f * s, x + 7f * s, y - 27f * s, 1.2f * s, Color.RED)
    }

    private fun junkPile(c: Canvas, x: Float, y: Float, kind: Int, seed: Long) {
        val s = k * depthScale(y)
        val r = Rng(seed)
        when (kind) {
            0 -> repeat(4) { val ox = r.range(-12f, 12f) * s; val oy = r.range(-4f, 2f) * s; c.drawOval(x + ox - 7f * s, y + oy - 4f * s, x + ox + 7f * s, y + oy + 3f * s, Ink.fill(Color.rgb(46, 44, 44))); c.drawOval(x + ox - 3f * s, y + oy - 2f * s, x + ox + 3f * s, y + oy + 1f * s, Ink.fill(Color.rgb(100, 120, 80))); c.drawOval(x + ox - 7f * s, y + oy - 4f * s, x + ox + 7f * s, y + oy + 3f * s, Ink.stroke(Ink.INK, 0.8f * k)) }
            1 -> { Ink.wash(c, Ink.blobPath(x, y - 6f * s, 18f * s, 9f * s, seed, 8, 0.4f), Color.rgb(130, 110, 100), 1f * k); repeat(5) { Ink.line(c, x + r.range(-14f, 14f) * s, y - r.range(2f, 12f) * s, x + r.range(-14f, 14f) * s, y - r.range(2f, 14f) * s, 1.6f * s, Color.rgb(110, 90, 80)) } }
            2 -> repeat(8) { val a = r.range(-0.5f, 0.5f); Ink.line(c, x - 18f * s, y - r.range(0f, 8f) * s, x + 18f * s * kotlin.math.cos(a), y - r.range(0f, 12f) * s, 1.8f * s, Color.rgb(110, 84, 56)) }
            3 -> { c.drawRoundRect(x - 14f * s, y - 16f * s, x - 4f * s, y, 2f * s, 2f * s, Ink.fill(Color.rgb(60, 100, 170))); c.drawRoundRect(x - 14f * s, y - 16f * s, x - 4f * s, y, 2f * s, 2f * s, Ink.stroke(Ink.INK, 0.8f * k))
                   Ink.wash(c, Ink.blobPath(x + 8f * s, y - 4f * s, 12f * s, 6f * s, seed, 6, 0.5f), Color.rgb(90, 130, 170), 0.8f * k); c.drawCircle(x - 18f * s, y - 1f * s, 3f * s, Ink.fill(Color.rgb(240, 120, 40))) }
            4 -> oldCar(c, x, y)
            else -> repeat(4) { i -> Ink.line(c, x - 18f * s + i * 10f * s, y - r.range(0f, 4f) * s, x - 14f * s + i * 10f * s, y - 12f * s, 1.8f * s, Color.rgb(130, 96, 64)); Ink.line(c, x - 20f * s, y - 7f * s + i, x + 20f * s, y - 9f * s, 1.4f * s, Color.rgb(130, 96, 64)) }
        }
    }
}
