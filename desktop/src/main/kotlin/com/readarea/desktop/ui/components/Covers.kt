package com.readarea.desktop.ui.components

import com.readarea.core.format.BookFormat
import com.readarea.desktop.book.Images
import com.readarea.desktop.data.Book
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.ui.theme.AppTheme
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.AttributedString
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.sin

/** Cover thumbnails, loaded in the background and kept in a memory-bounded cache. */
object CoverCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 12).coerceIn(24L shl 20, 160L shl 20)
    private val images = object : LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {}
    private val failed = HashSet<String>()
    private val loading = HashSet<String>()
    private var bytes = 0L
    private val loader = Executors.newFixedThreadPool(2) { r -> Thread(r, "cover-loader").apply { isDaemon = true } }

    fun get(path: String): BufferedImage? = synchronized(images) { images[path] }

    /** The cover at [path], or null while it loads; [repaint] runs on the UI thread once it's ready. */
    fun request(path: String, repaint: () -> Unit): BufferedImage? {
        synchronized(images) {
            images[path]?.let { return it }
            if (path in failed || !loading.add(path)) return null
        }
        loader.execute {
            val img = runCatching {
                val f = File(path)
                if (f.isFile && f.length() < 8L * 1024 * 1024) Images.decode(f.readBytes(), 200, 300)?.let { Images.fit(it, 480, 720) } else null
            }.getOrNull()
            synchronized(images) {
                loading.remove(path)
                if (img == null) failed.add(path)
                else {
                    images[path] = img
                    bytes += img.width.toLong() * img.height * 4
                    val it = images.entries.iterator()
                    while (bytes > maxBytes && it.hasNext()) {
                        val e = it.next()
                        if (e.key == path) continue
                        bytes -= e.value.width.toLong() * e.value.height * 4
                        it.remove()
                    }
                }
            }
            if (img != null) SwingUtilities.invokeLater(repaint)
        }
        return null
    }

    fun invalidate(path: String?) {
        if (path == null) return
        synchronized(images) {
            images.remove(path)?.let { bytes -= it.width.toLong() * it.height * 4 }
            failed.remove(path)
        }
    }
}

/** Draws a book's cover: its own image, or a designed cover from its title when it has none. */
object CoverPainter {
    private data class P(val top: Color, val bottom: Color, val ink: Color, val accent: Color)

    private val palettes = listOf(
        P(Color(0x2E4057), Color(0x1B2838), Color(0xF2E9DC), Color(0xE0A84C)),
        P(Color(0x9A5B34), Color(0x6E3D22), Color(0xFBEFDD), Color(0xF3C27A)),
        P(Color(0x4F7A5A), Color(0x30503A), Color(0xF1F5EA), Color(0xD9C27A)),
        P(Color(0x7D4E7A), Color(0x4F2D4D), Color(0xF7ECF4), Color(0xE8B4C8)),
        P(Color(0xB0463C), Color(0x7A2A24), Color(0xFFF1E8), Color(0xF2C14E)),
        P(Color(0x3F6E8C), Color(0x244257), Color(0xEAF3F8), Color(0x9FD3E8)),
        P(Color(0xE9DFCC), Color(0xD7C8AC), Color(0x3A2E22), Color(0xA0522D)),
        P(Color(0x1F1F24), Color(0x0E0E12), Color(0xEDE6DA), Color(0xC9A45C)),
        P(Color(0x5E5CA8), Color(0x3A3875), Color(0xF0EFFB), Color(0xF5B971)),
        P(Color(0xD9A441), Color(0xB07C24), Color(0x2B2014), Color(0x7A3E1D)),
    )

    private fun c(color: Color, a: Float) = Color(color.red, color.green, color.blue, (a * 255).toInt().coerceIn(0, 255))

    fun paint(g0: Graphics2D, book: Book, x: Float, y: Float, w: Float, h: Float, radius: Float = 6f, shadow: Boolean = true, repaint: () -> Unit = {}) {
        val g = g0.create() as Graphics2D
        g.smooth()
        val shape = Path2D.Float().apply {
            val r1 = radius * 0.5f
            val r2 = radius
            moveTo(x + r1, y)
            lineTo(x + w - r2, y)
            quadTo(x + w, y, x + w, y + r2)
            lineTo(x + w, y + h - r2)
            quadTo(x + w, y + h, x + w - r2, y + h)
            lineTo(x + r1, y + h)
            quadTo(x, y + h, x, y + h - r1)
            lineTo(x, y + r1)
            quadTo(x, y, x + r1, y)
            closePath()
        }
        if (shadow) {
            for (i in 3 downTo 1) {
                g.color = Color(0, 0, 0, if (AppTheme.palette.dark) 30 else 14)
                g.fill(RoundRectangle2D.Float(x - i * 0.5f + 1f, y + i * 1.2f, w + i, h + i * 0.6f, radius * 2, radius * 2))
            }
        }
        val img = book.coverPath?.let { CoverCache.request(it, repaint) }
        g.clip(shape)
        if (img != null) {
            // Fill the cover area, cropping the image rather than letterboxing it.
            val k = maxOf(w / img.width, h / img.height)
            val dw = img.width * k
            val dh = img.height * k
            g.drawImage(img, (x + (w - dw) / 2).toInt(), (y + (h - dh) / 2).toInt(), dw.toInt(), dh.toInt(), null)
        } else {
            generated(g, book.title, book.author, book.bookFormat, x, y, w, h)
        }
        // A hint of the spine: a darker edge and a highlight line.
        g.paint = GradientPaint(x, 0f, Color(0, 0, 0, 56), x + w * 0.09f, 0f, Color(255, 255, 255, 0))
        g.fill(Rectangle2D.Float(x, y, w * 0.09f, h))
        g.color = Color(255, 255, 255, 46)
        g.fill(Rectangle2D.Float(x + w * 0.035f, y, 1.2f, h))
        g.dispose()
    }

    fun generated(g: Graphics2D, title: String, author: String, format: BookFormat, x: Float, y: Float, w: Float, h: Float) {
        val hash = abs(("$title|$author").hashCode().let { if (it == Int.MIN_VALUE) 0 else it })
        val p = palettes[hash % palettes.size]
        val pattern = (hash / palettes.size) % 5
        g.paint = GradientPaint(0f, y, p.top, 0f, y + h, p.bottom)
        g.fill(Rectangle2D.Float(x, y, w, h))
        val a = p.accent
        when (pattern) {
            0 -> for (i in 0 until 7) {
                val yy = y + h * (0.62f + i * 0.055f)
                val path = Path2D.Float()
                path.moveTo(x, yy)
                var xx = 0f
                while (xx <= w) {
                    path.lineTo(x + xx, yy + sin((xx / w) * 6.28f + i) * h * 0.012f)
                    xx += w / 24f
                }
                g.color = c(a, 0.35f - i * 0.04f)
                g.stroke = BasicStroke(w * 0.012f)
                g.draw(path)
            }
            1 -> {
                g.color = c(a, 0.85f)
                g.fill(Ellipse2D.Float(x + w * 0.72f - w * 0.22f, y + h * 0.72f - w * 0.22f, w * 0.44f, w * 0.44f))
                g.stroke = BasicStroke(w * 0.01f)
                g.color = c(p.ink, 0.12f)
                g.draw(Ellipse2D.Float(x + w * 0.72f - w * 0.34f, y + h * 0.72f - w * 0.34f, w * 0.68f, w * 0.68f))
                g.color = c(p.ink, 0.08f)
                g.draw(Ellipse2D.Float(x + w * 0.72f - w * 0.46f, y + h * 0.72f - w * 0.46f, w * 0.92f, w * 0.92f))
            }
            2 -> {
                val step = w / 9f
                g.color = c(a, 0.16f)
                g.stroke = BasicStroke(w * 0.02f)
                for (i in -12..12) g.draw(Line2D.Float(x + i * step, y + h, x + i * step + h * 0.6f, y + h * 0.4f))
            }
            3 -> {
                val step = w / 8f
                for (r in 0 until 5) for (col in 0 until 8) {
                    g.color = c(a, 0.18f + ((r + col) % 3) * 0.08f)
                    val cx = x + step * col + step / 2
                    val cy = y + h * 0.64f + r * step * 0.9f
                    g.fill(Ellipse2D.Float(cx - w * 0.018f, cy - w * 0.018f, w * 0.036f, w * 0.036f))
                }
            }
            else -> {
                g.color = c(a, 0.9f)
                g.fill(RoundRectangle2D.Float(x + w * 0.1f, y + h * 0.08f, w * 0.8f, h * 0.012f, 4f, 4f))
                g.fill(RoundRectangle2D.Float(x + w * 0.1f, y + h * 0.6f, w * 0.8f, h * 0.012f, 4f, 4f))
                g.color = c(p.ink, 0.08f)
                g.fill(Rectangle2D.Float(x + w * 0.1f, y + h * 0.64f, w * 0.8f, h * 0.26f))
            }
        }
        val label = title.ifBlank { tr("untitled") }
        val words = label.split(' ', '-')
        val longest = words.maxOfOrNull { it.length }?.coerceAtLeast(4) ?: 4
        var size = (w / (longest * 0.62f)).coerceIn(7f, (w / 110f * 15f).coerceIn(9f, 26f))
        var font = AppTheme.headline(size).deriveFont(mapOf(TextAttribute.WEIGHT to TextAttribute.WEIGHT_SEMIBOLD))
        // Shrink until the longest word fits on a line, so small covers don't split words.
        val longestWord = words.maxByOrNull { g.getFontMetrics(font).stringWidth(it) }.orEmpty()
        while (size > 5f && g.getFontMetrics(font).stringWidth(longestWord) > w * 0.82f) {
            size -= 0.5f
            font = font.deriveFont(size)
        }
        g.color = p.ink
        drawWrapped(g, label, font, x + w * 0.09f, y + h * 0.1f, w * 0.82f, 4)
        val small = AppTheme.ui((w / 11f).coerceIn(6f, 12f))
        g.font = small
        val fm = g.fontMetrics
        var by = y + h - h * 0.06f
        g.color = c(p.ink, 0.55f)
        val fmt = format.label.uppercase()
        g.drawString(fmt, x + (w - fm.stringWidth(fmt)) / 2f, by)
        by -= fm.height * 1.1f
        if (author.isNotBlank()) {
            g.color = c(p.ink, 0.85f)
            var a2 = author
            while (a2.length > 1 && fm.stringWidth(a2) > w * 0.86f) a2 = a2.dropLast(2) + "…"
            g.drawString(a2, x + (w - fm.stringWidth(a2)) / 2f, by)
        }
    }

    private fun drawWrapped(g: Graphics2D, text: String, font: Font, x: Float, y: Float, width: Float, maxLines: Int) {
        if (text.isEmpty()) return
        val attr = AttributedString(text, mapOf(TextAttribute.FONT to font))
        val lbm = LineBreakMeasurer(attr.iterator, g.fontRenderContext)
        var yy = y
        var lines = 0
        while (lbm.position < text.length && lines < maxLines) {
            val layout = lbm.nextLayout(width)
            yy += layout.ascent
            layout.draw(g, x + (width - layout.visibleAdvance) / 2f, yy)
            yy += layout.descent + layout.leading
            lines++
        }
    }
}
