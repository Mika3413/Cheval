package com.cheval.game

import android.graphics.*
import com.cheval.core.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Génère l'icône du lanceur (tête de cheval bai sur médaillon vert et or) dans app/src/main/res. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IconTest {
    @Test
    fun icon() {
        val g = Game(7)
        val h = g.generateHorse(Breed.SELLE_FRANCAIS, Sex.ETALON, 8, withAncestors = false)
        for (l in Locus.values()) { h.genome.alleles[l.ordinal * 2] = 0; h.genome.alleles[l.ordinal * 2 + 1] = 0 }
        h.genome.alleles[Locus.EXTENSION.ordinal * 2] = 1; h.genome.alleles[Locus.AGOUTI.ordinal * 2] = 1
        h.genome.breeding[Trait.BLANC_TETE.ordinal] = 60f; h.genome.env[Trait.BLANC_TETE.ordinal] = 0f
        h.cleanliness = 100f
        val a = Appearance.of(h, g.day)
        val res = File("../../../app/src/main/res")
        for ((dir, size) in listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val s = size.toFloat()
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(s * 0.4f, s * 0.35f, s * 0.6f, Color.rgb(70, 112, 86), Color.rgb(24, 46, 36), Shader.TileMode.CLAMP)
            c.drawCircle(s / 2, s / 2, s * 0.48f, p)
            p.shader = null
            c.save()
            val clip = Path().apply { addCircle(s / 2, s / 2, s * 0.44f, Path.Direction.CW) }
            c.clipPath(clip)
            val pose = HorsePose().apply { neck = 55f; head = 28f; ears = 1f }
            val sc = s * 1.35f / a.H
            HorseArt.draw(c, a, pose, s * 0.5f - a.H * 0.72f * sc, s * 0.42f + a.H * 1.22f * sc, sc, true, 1f, shadow = false)
            c.restore()
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.04f; color = Color.rgb(201, 164, 76) }
            c.drawCircle(s / 2, s / 2, s * 0.46f, sp)
            val d = File(res, "mipmap-$dir").apply { mkdirs() }
            FileOutputStream(File(d, "ic_launcher.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
