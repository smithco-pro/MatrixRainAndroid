package com.aftersix.matrixrain.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Small status line drawn over other apps during a Workday. It also keeps the screen on while other apps are in
 * front, which is what lets their foreground time accrue (screen-off would pause the Workday).
 * Not focusable or touchable, and at 0.8 alpha so touches beneath it are not treated as obscured.
 */
class HudOverlay(private val context: Context) {
    private var view: TextView? = null
    private val windows get() = context.getSystemService(WindowManager::class.java)

    val showing get() = view != null

    fun show(text: String) {
        view?.let {
            it.text = text
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        val label = TextView(context).apply {
            this.text = text
            setTextColor(Color.rgb(0, 230, 70))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(12, 4, 12, 4)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            alpha = 0.8f
        }
        runCatching { windows.addView(label, params) }.onSuccess { view = label }
    }

    fun hide() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }
}
