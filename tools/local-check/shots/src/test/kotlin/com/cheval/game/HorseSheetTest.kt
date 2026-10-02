package com.cheval.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.cheval.core.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HorseSheetTest {
    private fun save(bmp: Bitmap, name: String) {
        val dir = File(System.getProperty("shots.dir") ?: "out/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun sheet() {
        val g = Game(21)
        val breeds = listOf(Breed.SELLE_FRANCAIS, Breed.ARABE, Breed.FRISON, Breed.APPALOOSA, Breed.PAINT_HORSE, Breed.HAFLINGER, Breed.LUSITANIEN, Breed.PERCHERON)
        val bmp = Bitmap.createBitmap(2400, 1400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFDCE6D0.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f }
        for ((i, b) in breeds.withIndex()) {
            val h = g.generateHorse(b, Sex.JUMENT, 7, withAncestors = false)
            val a = Appearance.of(h, g.day)
            val pose = HorsePose()
            val x = 300f + (i % 4) * 600f; val y = 600f + (i / 4) * 650f
            HorseArt.draw(c, a, pose, x, y, 2.6f)
            c.drawText("${b.label} — ${h.coatName(g.day)}", x - 250f, y + 40f, p)
        }
        save(bmp, "horses_breeds")
        // allures
        val h = g.generateHorse(Breed.SELLE_FRANCAIS, Sex.HONGRE, 8, withAncestors = false)
        val a = Appearance.of(h, g.day)
        val bmp2 = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        val c2 = Canvas(bmp2); c2.drawColor(0xFFDCE6D0.toInt())
        val gaits = listOf(Gait.PAS, Gait.TROT, Gait.GALOP, Gait.GRAND_GALOP)
        for ((r, gt) in gaits.withIndex()) for (k in 0..3) {
            val pose = HorsePose().apply { gait = gt; phase = k / 4f; neck = 35f; head = 40f; speedBlend = if (gt.ordinal >= 3) 0.7f else 0.2f }
            HorseArt.draw(c2, a, pose, 300f + k * 600f, 340f + r * 390f, 1.7f, tack = Tack(saddle = true, bridle = true, rider = r % 2 == 0))
        }
        save(bmp2, "horses_gaits")
        val bmp3 = Bitmap.createBitmap(2400, 500, Bitmap.Config.ARGB_8888)
        val c3 = Canvas(bmp3); c3.drawColor(0xFFDCE6D0.toInt())
        for (k in 0..5) {
            val pose = HorsePose().apply { gait = Gait.GALOP; jump = k / 5f; jumpHeight = 1.3f }
            HorseArt.draw(c3, a, pose, 200f + k * 400f, 460f, 1.4f, tack = Tack(saddle = true, bridle = true, rider = true, riderForward = 1f))
        }
        save(bmp3, "horses_jump")
    }
}
