package com.cheval.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import com.cheval.core.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class ScreenshotTest {
    private val w = 2340
    private val h = 1080

    private fun save(view: GameView, name: String) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.drawFrame(Canvas(bmp), 0.016f)
        view.drawFrame(Canvas(bmp), 0.016f)
        val dir = File(System.getProperty("shots.dir") ?: "out/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(view: GameView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun screens() {
        val view = GameView(RuntimeEnvironment.getApplication())
        view.layoutForTest(w, h)
        view.stepForTest(1.5f)
        save(view, "01_menu")
        view.push(NewGameScreen(view)); view.stepForTest(0.5f)
        save(view, "02_new_game")
        // partie de démonstration
        val g = Game(42); g.newGameSetup()
        g.takeStarter(g.starterChoices()[0])
        g.money = 250000
        g.build(BuildingType.CARRIERE); g.build(BuildingType.ECURIE); g.build(BuildingType.CLUB_HOUSE)
        g.candidates.firstOrNull { it.role == Role.SOIGNEUR }?.let { g.hire(it) }
        repeat(5) { g.buy(g.market.first { l -> g.horse(l.horseId)!!.age(g.day) >= 3 }) }
        val mare = g.owned().firstOrNull { it.mare }
        for (x in g.owned()) { g.vaccinate(x); x.turnout = Turnout.JOUR }
        g.advance(24.0 * 70 + 3.0) // mai, 10 h
        view.setGameForTest(g)
        view.replaceAll(HubScreen(view))
        view.timeSpeed = 0
        view.stepForTest(3f)
        save(view, "03_domaine_matin")
        g.advance(9.5)
        view.stepForTest(3f)
        save(view, "04_domaine_soir")
        g.advance(5.0)
        view.stepForTest(1f)
        save(view, "05_domaine_nuit")
        val hz = g.owned().first()
        hz.cleanliness = 35f
        val hs = HorseScreen(view, hz)
        view.push(hs); view.stepForTest(0.5f)
        save(view, "06_fiche_soins")
        view.pop()
        for (tab in listOf(2, 3, 4)) {
            val s = HorseScreen(view, g.owned()[1]); view.push(s)
            val f = HorseScreen::class.java.getDeclaredField("tab"); f.isAccessible = true; f.setInt(s, tab)
            view.stepForTest(0.2f); save(view, "07_fiche_onglet_$tab"); view.pop()
        }
        view.push(GroomScreen(view, hz)); view.stepForTest(0.5f)
        save(view, "08_pansage")
        view.pop()
        g.advance(24.0 - g.hourOfDay + 15.0)
        view.push(RideScreen(view, hz, RideMode(RideKind.BALADE, false), -1, Exercise.EXTERIEUR))
        view.stepForTest(0.3f)
        val rs = view.screen as RideScreen
        val fDist = RideScreen::class.java.getDeclaredField("dist"); fDist.isAccessible = true
        val fGait = RideScreen::class.java.getDeclaredField("gaitIdx"); fGait.isAccessible = true
        fGait.setInt(rs, 4); view.stepForTest(4f); fDist.setFloat(rs, 760f); view.stepForTest(0.5f)
        save(view, "09_balade_plage")
        view.pop()
        view.push(RideScreen(view, hz, RideMode(RideKind.OBSTACLES, true, 2), -1, Exercise.PARCOURS))
        val rs2 = view.screen as RideScreen
        fGait.setInt(rs2, 3); view.stepForTest(6.1f)
        save(view, "10_cso")
        view.pop()
        view.push(RideScreen(view, hz, RideMode(RideKind.PISTE, true, 3, 1600), -1, Exercise.GALOP))
        val rs3 = view.screen as RideScreen
        fGait.setInt(rs3, 4); view.stepForTest(8f)
        save(view, "11_course")
        view.pop()
        view.push(RideScreen(view, hz, RideMode(RideKind.DRESSAGE, true, 2), -1, Exercise.DRESSAGE))
        fGait.setInt(view.screen as RideScreen, 2); view.stepForTest(5f)
        save(view, "12_dressage")
        view.pop()
        view.push(HorsesScreen(view)); view.stepForTest(0.2f); save(view, "13_chevaux"); view.pop()
        view.push(CompetitionScreen(view)); view.stepForTest(0.2f); save(view, "14_concours"); view.pop()
        val bs = BreedingScreen(view); view.push(bs)
        if (mare != null) { val f = BreedingScreen::class.java.getDeclaredField("mare"); f.isAccessible = true; f.set(bs, mare)
            val f2 = BreedingScreen::class.java.getDeclaredField("stallion"); f2.isAccessible = true; f2.set(bs, g.availableStallions().first()) }
        view.stepForTest(0.2f); save(view, "15_elevage"); view.pop()
        view.push(MarketScreen(view)); view.stepForTest(0.2f); save(view, "16_marche"); view.pop()
        view.push(ManageScreen(view)); view.stepForTest(0.2f); save(view, "17_domaine_batiments"); view.pop()
        view.push(ManageScreen(view, 3)); view.stepForTest(0.2f); save(view, "18_finances"); view.pop()
        view.push(JournalScreen(view)); view.stepForTest(0.2f); save(view, "19_journal"); view.pop()
        view.push(HelpScreen(view)); view.stepForTest(0.2f); save(view, "20_guide"); view.pop()
    }
}
