package com.cheval.game

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import com.cheval.core.Game
import com.cheval.core.Msg
import com.cheval.core.MsgKind
import java.io.File

/** Un écran du jeu. */
abstract class Screen(val app: GameView) {
    val gui get() = app.gui
    open fun resize(w: Int, h: Int) {}
    abstract fun update(dt: Float)
    abstract fun draw(c: Canvas)
    /** Retourne vrai si le bouton retour a été géré. */
    open fun onBack(): Boolean = false
    open fun onShow() {}
    open fun dispose() {}
}

/**
 * Vue unique : boucle d'animation sur le thread UI (rendu accéléré matériellement),
 * pile d'écrans, sauvegarde automatique.
 */
class GameView(context: Context) : View(context) {
    val gui = Gui()
    val sound = SoundFx(context)
    private val prefs = context.getSharedPreferences("haras", Context.MODE_PRIVATE)
    private val saveFile = File(context.filesDir, "haras.json")
    var game: Game? = null
        private set
    private val stack = ArrayList<Screen>()
    private var lastFrame = 0L
    private var running = true
    private var lastSavedDay = -1
    /** Vitesse du temps au domaine (heures de jeu par seconde réelle). */
    var timeSpeed = 1
    val speeds = floatArrayOf(0f, 0.2f, 1f, 6f)

    init {
        isFocusable = true
        keepScreenOn = true
        sound.enabled = prefs.getBoolean("sound", true)
        stack += MenuScreen(this)
    }

    val screen get() = stack.last()

    fun push(s: Screen) {
        stack += s
        if (width > 0) s.resize(width, height)
        s.onShow()
        invalidate()
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).dispose()
        screen.onShow()
    }

    fun replaceAll(s: Screen) {
        stack.forEach { it.dispose() }
        stack.clear()
        push(s)
    }

    fun toggleSound() {
        sound.enabled = !sound.enabled
        prefs.edit().putBoolean("sound", sound.enabled).apply()
    }

    // ------------------------------------------------------------------ partie
    fun hasSave() = saveFile.exists()

    fun startGame(g: Game) {
        game = g
        attachListener(g)
        lastSavedDay = g.day
        save()
        replaceAll(HubScreen(this))
    }

    fun loadGame(): Boolean = try {
        val g = Game.fromJson(saveFile.readText())
        game = g
        attachListener(g)
        lastSavedDay = g.day
        replaceAll(HubScreen(this))
        true
    } catch (e: Exception) {
        gui.toast("Sauvegarde illisible : ${e.message}", Pal.RED)
        false
    }

    private fun attachListener(g: Game) {
        g.listener = { m: Msg ->
            when (m.kind) {
                MsgKind.URGENT -> { gui.toast(m.text, Pal.RED); sound.play(SoundFx.S.BAD) }
                MsgKind.BAD -> gui.toast(m.text, Pal.LEATHER)
                MsgKind.GOOD -> { gui.toast(m.text, Pal.GREEN); sound.play(SoundFx.S.COIN, 0.6f) }
                MsgKind.INFO -> {}
            }
        }
    }

    fun save() {
        val g = game ?: return
        try {
            val tmp = File(saveFile.parentFile, "haras.tmp")
            tmp.writeText(g.toJson())
            tmp.renameTo(saveFile)
        } catch (_: Exception) {}
    }

    fun deleteSave() { saveFile.delete(); game = null }

    /** Fait avancer le temps de jeu (appelé par les écrans « vivants »). */
    fun tickGame(dt: Float) {
        val g = game ?: return
        // Le temps avance au rythme des actions du joueur (journée de travail), plus en continu.
        if (g.day != lastSavedDay) { lastSavedDay = g.day; save() }
    }

    // ------------------------------------------------------------------ saisie de texte
    fun askText(title: String, initial: String, done: (String) -> Unit) {
        val act = context as? Activity ?: return
        val input = EditText(act).apply { setText(initial); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; setSelection(initial.length) }
        AlertDialog.Builder(act).setTitle(title).setView(input)
            .setPositiveButton("Valider") { _, _ -> val t = input.text.toString().trim(); if (t.isNotEmpty()) done(t.take(40)) }
            .setNegativeButton("Annuler", null).show()
    }

    // ------------------------------------------------------------------ boucle
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gui.resize(w, h)
        stack.forEach { it.resize(w, h) }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = now
        frame(canvas, dt)
        if (running) postInvalidateOnAnimation()
    }

    private fun frame(c: Canvas, dt: Float) {
        gui.update(dt)
        val s = screen
        s.update(dt)
        gui.begin()
        s.draw(c)
        gui.drawToasts(c)
        gui.end()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gui.onTouch(event)
        return true
    }

    fun onBackPressedInGame(): Boolean {
        if (screen.onBack()) return true
        if (stack.size > 1) { pop(); return true }
        return false
    }

    fun pause() { running = false; save() }
    fun resume() { running = true; lastFrame = 0L; invalidate() }
    fun release() { save(); stack.forEach { it.dispose() }; sound.release() }

    // ------------------------------------------------------------------ outils de test (captures)
    fun layoutForTest(w: Int, h: Int) { gui.resize(w, h); stack.forEach { it.resize(w, h) } }
    fun drawFrame(c: Canvas, dt: Float = 0.016f) = frame(c, dt)
    fun stepForTest(seconds: Float) { var t = 0f; while (t < seconds) { screen.update(0.05f); gui.update(0.05f); t += 0.05f } }
    fun setGameForTest(g: Game) { game = g; attachListener(g) }
}
