package io.github.zod701.pagurin

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf

// The buttons the focus system does not take: each screen sets what they do
object Keys { var handler: ((Int) -> Boolean)? = null }

class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val store = Store(this)
        setContent { PagurinApp(store, resumed.intValue) }
        window.insetsController?.apply { hide(WindowInsets.Type.systemBars()); systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }   // after setContent: the window's decor exists only then
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }

    // A is the focus system's click and B its back; the shoulder and face buttons go to the screen
    override fun dispatchKeyEvent(e: KeyEvent): Boolean = when (e.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> super.dispatchKeyEvent(KeyEvent(e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_DPAD_CENTER, e.repeatCount))
        KeyEvent.KEYCODE_BUTTON_B -> super.dispatchKeyEvent(KeyEvent(e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_BACK, e.repeatCount))
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) Keys.handler?.invoke(e.keyCode)
            true
        }
        else -> super.dispatchKeyEvent(e)
    }
}
