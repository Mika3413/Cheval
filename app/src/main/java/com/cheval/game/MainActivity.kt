package com.cheval.game

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** Activité unique, plein écran, paysage. */
class MainActivity : Activity() {
    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        view = GameView(this)
        setContentView(view)
        hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        view.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() { super.onPause(); view.pause() }
    override fun onResume() { super.onResume(); view.resume() }
    override fun onDestroy() { view.release(); super.onDestroy() }

    @Deprecated("Géré pour toutes les versions d'Android")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!view.onBackPressedInGame()) super.onBackPressed()
    }
}
