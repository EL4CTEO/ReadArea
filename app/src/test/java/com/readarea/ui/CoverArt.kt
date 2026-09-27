package com.readarea.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

object CoverArt {
    fun make(title: String, author: String, top: Int, bottom: Int, ink: Int, style: Int, w: Int = 600, h: Int = 900, jpeg: Boolean = false): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = ink
        when (style) {
            0 -> {
                p.alpha = 230
                c.drawCircle(w * 0.5f, h * 0.62f, w * 0.26f, p)
                p.alpha = 90
                for (i in 0 until 6) c.drawRect(0f, h * (0.8f + i * 0.03f), w.toFloat(), h * (0.8f + i * 0.03f) + 4f, p)
            }
            1 -> {
                p.alpha = 120
                val path = android.graphics.Path().apply {
                    moveTo(0f, h.toFloat())
                    lineTo(w * 0.3f, h * 0.55f)
                    lineTo(w * 0.5f, h * 0.72f)
                    lineTo(w * 0.75f, h * 0.45f)
                    lineTo(w.toFloat(), h * 0.7f)
                    lineTo(w.toFloat(), h.toFloat())
                    close()
                }
                c.drawPath(path, p)
                p.alpha = 255
                c.drawCircle(w * 0.72f, h * 0.3f, w * 0.08f, p)
            }
            2 -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 6f
                p.alpha = 200
                c.drawRect(w * 0.08f, h * 0.06f, w * 0.92f, h * 0.94f, p)
                p.strokeWidth = 2f
                c.drawRect(w * 0.11f, h * 0.08f, w * 0.89f, h * 0.92f, p)
                p.style = Paint.Style.FILL
                p.alpha = 255
                c.drawCircle(w * 0.5f, h * 0.66f, 10f, p)
                c.drawCircle(w * 0.42f, h * 0.66f, 6f, p)
                c.drawCircle(w * 0.58f, h * 0.66f, 6f, p)
            }
            else -> {
                p.alpha = 70
                for (i in 0 until 14) c.drawCircle(w * ((i * 37) % 100) / 100f, h * (0.45f + ((i * 53) % 50) / 100f), w * (0.03f + (i % 4) * 0.02f), p)
            }
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = ink
        tp.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        tp.textSize = w * 0.105f
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, tp, (w * 0.8f).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
        c.save()
        c.translate(w * 0.1f, h * 0.12f)
        layout.draw(c)
        c.restore()
        tp.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        tp.textSize = w * 0.05f
        tp.letterSpacing = 0.12f
        tp.textAlign = Paint.Align.CENTER
        c.drawText(author.uppercase(), w / 2f, h * 0.12f + layout.height + w * 0.1f, tp)
        return ByteArrayOutputStream().also { bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) }.toByteArray()
    }
}
