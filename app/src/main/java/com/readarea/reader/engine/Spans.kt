package com.readarea.reader.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan
import android.text.style.UpdateAppearance
import android.util.LruCache
import com.readarea.core.ImageUtil
import com.readarea.core.format.ResourceProvider

class SpanColors {
    @Volatile var text: Int = 0xFF000000.toInt()
    @Volatile var secondary: Int = 0xFF888888.toInt()
    @Volatile var link: Int = 0xFF2F6FDB.toInt()
    @Volatile var accent: Int = 0xFF2F6FDB.toInt()
}

class LinkSpan(val href: String, private val colors: SpanColors) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = colors.link
    }
}

class ParagraphSpacingSpan(private val before: Int, private val after: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val sp = text as? Spanned ?: return
        val spanStart = sp.getSpanStart(this)
        val spanEnd = sp.getSpanEnd(this)
        if (start == spanStart && before > 0) {
            fm.ascent -= before
            fm.top -= before
        }
        if (end == spanEnd && after > 0) {
            fm.descent += after
            fm.bottom += after
        }
    }
}

class QuoteMarginSpan(private val margin: Int, private val barWidth: Float, private val colors: SpanColors) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = margin

    override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout) {
        val style = p.style
        val color = p.color
        p.style = Paint.Style.FILL
        p.color = colors.secondary
        p.alpha = 110
        val left = x + margin * 0.35f * dir
        c.drawRect(left, top.toFloat(), left + barWidth * dir, bottom.toFloat(), p)
        p.style = style
        p.color = color
    }
}

class ImageCache(private val resources: ResourceProvider, maxBytes: Int) {
    private val sizes = HashMap<String, Pair<Int, Int>?>()
    private val bitmaps = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun size(path: String): Pair<Int, Int>? = synchronized(sizes) {
        if (sizes.containsKey(path)) return sizes[path]
        val s = resources.read(path)?.let { ImageUtil.imageSize(it) }
        sizes[path] = s
        s
    }

    fun get(path: String, w: Int, h: Int): Bitmap? {
        val key = "$path@$w"
        bitmaps.get(key)?.let { return it }
        val bytes = resources.read(path) ?: return null
        val bmp = ImageUtil.decodeSampled(bytes, w, h) ?: return null
        bitmaps.put(key, bmp)
        return bmp
    }

    fun clear() = bitmaps.evictAll()
}

class BlockImageSpan(
    private val path: String,
    private val width: Int,
    private val height: Int,
    private val lineSpacing: Float,
    private val cache: ImageCache,
) : ReplacementSpan() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val h = (height / lineSpacing.coerceAtLeast(1f)).toInt()
            fm.ascent = -h
            fm.top = -h
            fm.descent = 0
            fm.bottom = 0
        }
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
        val bmp = cache.get(path, width, height) ?: return
        val avail = (bottom - top).toFloat()
        val h = minOf(height.toFloat(), avail)
        val w = width * (h / height)
        val dx = x + (width - w) / 2f
        val dy = top + (avail - h) / 2f
        rect.set(dx, dy, dx + w, dy + h)
        canvas.drawBitmap(bmp, null, rect, paint)
    }
}

class RuleSpan(private val width: Int, private val height: Int, private val colors: SpanColors) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = -height
            fm.top = -height
            fm.descent = 0
            fm.bottom = 0
        }
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val cy = (top + bottom) / 2f
        val color = paint.color
        val style = paint.style
        paint.color = colors.secondary
        paint.style = Paint.Style.FILL
        val cx = x + width / 2f
        val r = height * 0.07f
        paint.alpha = 150
        canvas.drawCircle(cx, cy, r * 1.3f, paint)
        canvas.drawCircle(cx - width * 0.16f, cy, r, paint)
        canvas.drawCircle(cx + width * 0.16f, cy, r, paint)
        paint.alpha = 70
        canvas.drawRect(x, cy - r * 0.25f, cx - width * 0.22f, cy + r * 0.25f, paint)
        canvas.drawRect(cx + width * 0.22f, cy - r * 0.25f, x + width, cy + r * 0.25f, paint)
        paint.color = color
        paint.style = style
    }
}
