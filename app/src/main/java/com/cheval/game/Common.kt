package com.cheval.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.cheval.core.Cal
import com.cheval.core.Game
import com.cheval.core.Ground
import com.cheval.core.Horse
import com.cheval.core.Season
import com.cheval.core.Sky
import com.cheval.core.Trait

fun ambienceOf(g: Game, hourOverride: Float? = null): Ambience = Ambience(
    hour = hourOverride ?: g.hourOfDay,
    season = Cal.season(g.day),
    sky = g.weather.sky,
    sunrise = Cal.sunrise(g.day),
    sunset = Cal.sunset(g.day),
    temp = g.weather.tempAt(g.hourOfDay),
    wind = g.weather.wind,
    snowGround = g.weather.sky == Sky.NEIGE || (g.weather.ground == Ground.GELE && Cal.season(g.day) == Season.HIVER && g.weather.tempMax < 1f),
)

/** Cache léger des apparences (recalculées une fois par seconde au plus). */
object Looks {
    private val cache = HashMap<Int, Pair<Long, Appearance>>()
    fun of(h: Horse, day: Int): Appearance {
        val now = System.currentTimeMillis()
        val e = cache[h.id]
        if (e != null && now - e.first < 1000) return e.second
        val a = Appearance.of(h, day, winter = Cal.season(day) == Season.HIVER && !h.clipped)
        cache[h.id] = now to a
        return a
    }
    fun invalidate(id: Int) { cache.remove(id) }
}

/** Dessine un cheval entier dans un cadre, centré au sol. */
fun drawHorseFit(c: Canvas, h: Horse, day: Int, rect: RectF, pose: HorsePose = HorsePose(), light: Float = 1f, facingRight: Boolean = true, tack: Tack? = null) {
    val a = Looks.of(h, day)
    val scale = minOf(rect.width() / (a.L * 1.75f), rect.height() / (a.H * 1.5f))
    HorseArt.draw(c, a, pose, rect.centerX() - (if (facingRight) 1 else -1) * a.L * 0.08f * scale, rect.bottom - rect.height() * 0.06f, scale, facingRight, light, tack)
}

/** Note de potentiel 0..5 étoiles (le meilleur potentiel sportif). */
fun potentialStars(h: Horse): Float = ((h.bestDiscipline().potential(h) - 30f) / 10f).coerceIn(0.5f, 5f)
fun traitStars(h: Horse, t: Trait): Float = ((h.pot(t) - 25f) / 12f).coerceIn(0f, 5f)

/** Fond de papier crème pour les écrans de gestion. */
fun paperBackground(c: Canvas, gui: Gui) {
    val p = gui.p
    p.color = -1; p.shader = LinearGradient(0f, 0f, 0f, gui.h, Pal.CREAM, Pal.CREAM_D, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, gui.w, gui.h, p)
    p.shader = null
}

/** Barre de titre verte avec bouton retour. */
fun titleBar(c: Canvas, gui: Gui, title: String, subtitle: String? = null, onBack: (() -> Unit)?) {
    val u = gui.u
    val p = gui.p
    p.color = -1; p.shader = LinearGradient(0f, 0f, 0f, 44f * u, Pal.GREEN_L, Pal.GREEN, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, gui.w, 44f * u, p)
    p.shader = null
    p.color = Pal.GOLD; c.drawRect(0f, 44f * u, gui.w, 45.5f * u, p)
    val x0 = if (onBack != null) 54f * u else 14f * u
    if (onBack != null) gui.button(c, RectF(8f * u, 7f * u, 46f * u, 37f * u), "‹", Btn.GHOST, size = 20f) { onBack() }
    gui.text(c, title, x0, if (subtitle != null) 21f * u else 28f * u, 17f, Pal.CREAM, font = gui.serif)
    if (subtitle != null) gui.text(c, subtitle, x0, 36f * u, 10.5f, Pal.GOLD_L)
}

fun sexIcon(h: Horse): String = when (h.sex) { com.cheval.core.Sex.JUMENT -> "♀"; else -> "♂" }

fun moneyColor(v: Int) = if (v < 0) Pal.RED else Pal.OK

fun Paint.reset2(): Paint { shader = null; return this }

fun drawDivider(c: Canvas, gui: Gui, x0: Float, x1: Float, y: Float) {
    gui.p.shader = null; gui.p.color = Color.argb(70, 120, 100, 70); c.drawRect(x0, y, x1, y + 1f * gui.u, gui.p)
}
