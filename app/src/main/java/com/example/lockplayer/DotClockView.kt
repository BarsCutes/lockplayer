package com.example.lockplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/** Часы из точек в стиле точечных дисплеев. Рисуются сами (сетка 5x7), без внешних шрифтов. */
class DotClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }

    private val handler = Handler(Looper.getMainLooper())
    private var text = "00:00"
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    // 7 строк по 5 бит, старший бит слева
    private val glyphs = mapOf(
        '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
        '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
        '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111),
        '3' to intArrayOf(0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110),
        '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
        '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
        '6' to intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
        '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
        '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
        '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100)
    )

    private companion object {
        const val COLS = 27 // 4 цифры по 5 + двоеточие + промежутки
        const val ROWS = 7
    }

    private fun update() {
        val c = Calendar.getInstance()
        val t = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        if (t != text) {
            text = t
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun cell(w: Int): Float {
        val max = 10f * resources.displayMetrics.density
        return minOf(w.toFloat() / COLS, max)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = (cell(w) * ROWS).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val cs = cell(w)
        val r = cs * 0.38f
        val startX = paddingLeft + (w - cs * COLS) / 2f
        val top = paddingTop.toFloat()

        var col = 0
        for ((i, ch) in text.withIndex()) {
            if (ch == ':') {
                // двоеточие: две красные точки
                val cx = startX + (col + 0.5f) * cs
                canvas.drawCircle(cx, top + 2.5f * cs, r, accent)
                canvas.drawCircle(cx, top + 4.5f * cs, r, accent)
                col += 1 + 2 // колонка + промежуток справа (слева уже добавлен)
                continue
            }
            val g = glyphs[ch] ?: continue
            for (row in 0 until ROWS) {
                for (cIdx in 0 until 5) {
                    val on = (g[row] shr (4 - cIdx)) and 1 == 1
                    val cx = startX + (col + cIdx + 0.5f) * cs
                    val cy = top + (row + 0.5f) * cs
                    canvas.drawCircle(cx, cy, r, if (on) lit else dim)
                }
            }
            col += 5
            // промежуток: 1 между цифрами, 2 вокруг двоеточия
            col += if (i == 1) 2 else 1
        }
    }
}
