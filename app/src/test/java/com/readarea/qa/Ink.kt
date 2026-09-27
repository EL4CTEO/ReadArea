package com.readarea.qa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.readarea.reader.engine.ChapterPages

object Ink {
    fun wordInk(cp: ChapterPages, a: Int, b: Int): RectF? {
        val l = cp.layout
        val first = l.getLineForOffset(a)
        val last = l.getLineForOffset(b - 1)
        val top = l.getLineTop(first)
        val bottom = l.getLineBottom(last)
        val bmp = Bitmap.createBitmap(l.width, bottom - top, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val sp = cp.text as Spannable
        val h1 = ForegroundColorSpan(Color.TRANSPARENT)
        val h2 = ForegroundColorSpan(Color.TRANSPARENT)
        sp.setSpan(h1, 0, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(h2, b, cp.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val paintColor = l.paint.color
        l.paint.color = Color.BLACK
        c.translate(0f, -top.toFloat())
        c.clipRect(0, top, l.width, bottom)
        l.draw(c)
        l.paint.color = paintColor
        sp.removeSpan(h1)
        sp.removeSpan(h2)
        var x0 = Int.MAX_VALUE
        var x1 = -1
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width) {
            if (Color.red(bmp.getPixel(x, y)) < 150) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
            }
        }
        bmp.recycle()
        return if (x1 < 0) null else RectF(x0.toFloat(), top.toFloat(), x1.toFloat(), bottom.toFloat())
    }

    fun pathBounds(cp: ChapterPages, a: Int, b: Int): RectF {
        val p = Path()
        cp.path(a, b, p)
        return RectF().also { p.computeBounds(it, true) }
    }
}
