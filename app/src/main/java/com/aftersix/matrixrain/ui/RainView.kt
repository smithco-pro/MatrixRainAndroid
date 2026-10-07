package com.aftersix.matrixrain.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.random.Random

/**
 * Matrix rain, ported from MatrixRain's Display.cs: the same digit, letter and symbol glyph set, columns falling at 55–155 px/s
 * (scaled by density) with 6–18 cell trails, capped frame rate. Not a GPU benchmark; it stops drawing when
 * hidden or disabled.
 */
class RainView(context: Context) : View(context) {
    var framesPerSecond = 15
        set(value) { field = value.coerceIn(1, 30) }

    var rainEnabled = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val cell = 16f * density
    private val random = Random(SystemClock.uptimeMillis())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = cell * 0.9f
    }
    private val shades = IntArray(TRAIL_MAX) { i ->
        val fade = 1f - i / TRAIL_MAX.toFloat()
        Color.argb((40 + 215 * fade).toInt(), 0, (90 + 140 * fade).toInt(), (40 * fade).toInt())
    }

    private class Column(var y: Float, var speed: Float, var length: Int, val glyphs: CharArray)

    private var columns = emptyArray<Column>()
    private var lastFrame = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        columns = Array((w / cell).toInt() + 1) { newColumn(startAbove = false) }
    }

    private fun newColumn(startAbove: Boolean): Column {
        val rows = (height / cell).toInt() + TRAIL_MAX + 1
        return Column(
            y = if (startAbove) -random.nextInt(0, height.coerceAtLeast(1)).toFloat() else random.nextInt(-height, height.coerceAtLeast(1)).toFloat(),
            speed = random.nextInt(55, 156) * density,
            length = random.nextInt(6, 19),
            glyphs = CharArray(rows) { GLYPHS[random.nextInt(GLYPHS.length)] },
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (!rainEnabled || !isShown) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.5f)
        lastFrame = now
        for ((i, column) in columns.withIndex()) {
            column.y += column.speed * dt
            val headRow = (column.y / cell).toInt()
            val x = i * cell
            for (t in 0 until column.length) {
                val row = headRow - t
                if (row < 0) continue
                val y = row * cell + cell
                if (y - cell > height) continue
                paint.color = if (t == 0) Color.rgb(200, 255, 200) else shades[t.coerceAtMost(TRAIL_MAX - 1)]
                // Occasionally mutate a glyph so the trail shimmers.
                if (random.nextInt(40) == 0) column.glyphs[row % column.glyphs.size] = GLYPHS[random.nextInt(GLYPHS.length)]
                canvas.drawText(column.glyphs, row % column.glyphs.size, 1, x, y, paint)
            }
            if ((headRow - column.length) * cell > height) columns[i] = newColumn(startAbove = true)
        }
        postInvalidateDelayed(1000L / framesPerSecond)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrame = 0L
        if (visibility == VISIBLE) invalidate()
    }

    companion object {
        private const val GLYPHS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<>:+=*/"
        private const val TRAIL_MAX = 18
    }
}
