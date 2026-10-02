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
        val fTuto = MenuScreen::class.java.getDeclaredField("chooseTuto"); fTuto.isAccessible = true
        fTuto.setBoolean(view.screen, true); view.stepForTest(0.2f)
        save(view, "01b_menu_apprentissage")
        fTuto.setBoolean(view.screen, false)
        // apprentissage complet : premières étapes
        view.startTutorial(true); view.stepForTest(1f)
        save(view, "01c_tuto_bienvenue")
        val tu = view.tutorial!!
        val fIdx = Tutorial::class.java.getDeclaredField("index"); fIdx.isAccessible = true
        fIdx.setInt(tu, 2); view.stepForTest(3f)
        save(view, "01d_tuto_ecurie")
        fIdx.setInt(tu, 4); view.stepForTest(1f)
        save(view, "01e_tuto_chevaux")
        view.replaceAll(MenuScreen(view))
        view.startTutorial(false); view.stepForTest(1f)
        fIdx.setInt(view.tutorial!!, 3); view.stepForTest(1f)
        save(view, "01f_tuto_rapide_temps")
        view.replaceAll(MenuScreen(view))
        view.push(NewGameScreen(view)); view.stepForTest(0.5f)
        save(view, "02_new_game")
        // partie de démonstration : le domaine à l'abandon du début
        val g0 = Game(41); g0.newGameSetup(); g0.takeStarter(g0.starterChoices()[0])
        g0.advance(3.0)
        view.setGameForTest(g0)
        view.replaceAll(HubScreen(view))
        view.stepForTest(4f)
        save(view, "03_domaine_debut")
        // un domaine plus avancé
        val g = Game(42); g.newGameSetup(withStaff = true)
        g.takeStarter(g.starterChoices()[0])
        g.money = 250000
        g.build(BuildingType.CARRIERE); g.build(BuildingType.ECURIE); g.build(BuildingType.CLUB_HOUSE); g.build(BuildingType.PRE); g.build(BuildingType.GRENIER); g.build(BuildingType.GRENIER)
        g.junk.clear()
        g.candidates.firstOrNull { it.role == Role.SOIGNEUR }?.let { g.hire(it) }
        repeat(6) { g.buy(g.market.first { l -> g.horse(l.horseId)!!.age(g.day) >= 3 }) }
        val mare = g.owned().firstOrNull { it.mare }
        for (x in g.owned()) { g.vaccinate(x); x.turnout = Turnout.JOUR }
        g.advance(24.0 * 70 + 4.0) // mai, 11 h
        g.owned().take(5).forEach { g.setPlace(it, Place.PRE) }
        view.setGameForTest(g)
        view.replaceAll(HubScreen(view))
        view.stepForTest(6f)
        save(view, "04_domaine")
        val hub = view.screen
        val fCam = HubScreen::class.java.getDeclaredField("camX"); fCam.isAccessible = true
        fCam.setFloat(hub, 1600f); view.stepForTest(0.5f)
        save(view, "05_domaine_paddocks")
        g.advance(10.0); view.stepForTest(8f)
        save(view, "05b_domaine_nuit")
        g.sleepUntilMorning()
        val hz = g.owned().first()
        hz.cleanliness = 35f
        view.push(HorseScreen(view, hz)); view.stepForTest(0.5f)
        save(view, "06_fiche_soins")
        view.pop()
        for (tab in listOf(1, 2, 3)) {
            val s = HorseScreen(view, g.owned()[1]); view.push(s)
            val f = HorseScreen::class.java.getDeclaredField("tab"); f.isAccessible = true; f.setInt(s, tab)
            view.stepForTest(0.2f); save(view, "07_fiche_onglet_$tab"); view.pop()
        }
        view.push(GroomScreen(view, hz)); view.stepForTest(0.5f)
        save(view, "08_pansage")
        view.pop()
        val fDist = RideScreen::class.java.getDeclaredField("dist"); fDist.isAccessible = true
        val fGait = RideScreen::class.java.getDeclaredField("gaitIdx"); fGait.isAccessible = true
        fun ride(mode: RideMode, ex: Exercise, gait: Int, secs: Float, name: String, setDist: Float = -1f) {
            view.push(RideScreen(view, hz, mode, -1, ex))
            val rs = view.screen as RideScreen
            view.stepForTest(4f)
            fGait.setInt(rs, gait); view.stepForTest(secs)
            if (setDist >= 0f) { fDist.setFloat(rs, setDist); view.stepForTest(0.5f) }
            save(view, name); view.pop()
        }
        ride(RideMode(RideKind.BALADE, false), Exercise.EXTERIEUR, 4, 3f, "09_balade_plage", 760f)
        ride(RideMode(RideKind.OBSTACLES, true, 2), Exercise.PARCOURS, 3, 2f, "10_cso")
        ride(RideMode(RideKind.PISTE, true, 3, 1600), Exercise.GALOP, 4, 5f, "11_course")
        ride(RideMode(RideKind.DRESSAGE, true, 2), Exercise.DRESSAGE, 2, 3f, "12_dressage")
        ride(RideMode(RideKind.TROT, true, 3, 2100), Exercise.SULKY, 2, 5f, "12b_trot_attele")
        ride(RideMode(RideKind.ENDURANCE, true, 1), Exercise.FOND, 3, 4f, "12c_endurance")
        ride(RideMode(RideKind.WESTERN, true, 1), Exercise.WESTERN, 4, 2f, "12d_barrel")
        ride(RideMode(RideKind.HUNTER, true, 1), Exercise.GYMNASTIQUE, 3, 2.5f, "12e_hunter")
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
