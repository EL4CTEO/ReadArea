package com.readarea.desktop.reader.engine

import com.readarea.core.theme.ReadingTheme
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.random.Random

/** Everything drawn around the text: paper, header and footer, the spine of a spread, the bookmark ribbon. */
object PageChrome {
    private val chromeFont = Font(Font.SANS_SERIF, Font.PLAIN, 12)

    fun color(argb: Int, alpha: Int = (argb ushr 24)): Color = Color((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, alpha.coerceIn(0, 255))

    fun setupQuality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    /**
     * The page: its color and, for light themes, a faint paper grain. The grain is baked with the page
     * color into an opaque tile at the screen's pixel density and copied straight onto the device, which
     * is some 30 times faster than filling a translucent texture through a scaled transform.
     */
    fun drawBackground(g: Graphics2D, theme: ReadingTheme, w: Int, h: Int) {
        val tx = g.transform
        if (!theme.texture || tx.shearX != 0.0 || tx.shearY != 0.0 || tx.scaleX <= 0.0 || tx.scaleY <= 0.0) {
            g.color = color(theme.background, 255)
            g.fillRect(0, 0, w, h)
            return
        }
        val tile = paperTile(theme, tx.scaleX)
        val x0 = kotlin.math.floor(tx.translateX).toInt()
        val y0 = kotlin.math.floor(tx.translateY).toInt()
        val dw = kotlin.math.ceil(w * tx.scaleX).toInt()
        val dh = kotlin.math.ceil(h * tx.scaleY).toInt()
        val savedClip = g.clip
        g.transform = java.awt.geom.AffineTransform()
        g.clipRect(x0, y0, dw, dh)
        var y = 0
        while (y < dh) {
            var x = 0
            while (x < dw) {
                g.drawImage(tile, x0 + x, y0 + y, null)
                x += tile.width
            }
            y += tile.height
        }
        g.transform = tx
        g.clip = savedClip
    }

    private data class TileKey(val background: Int, val dark: Boolean, val scale: Int)

    private val tiles = LinkedHashMap<TileKey, BufferedImage>()

    /** The paper grain over the page color, 256 logical pixels square, drawn at [scale] device pixels each. */
    private fun paperTile(theme: ReadingTheme, scale: Double): BufferedImage = synchronized(tiles) {
        val key = TileKey(theme.background, theme.dark, (scale * 100).toInt())
        tiles[key]?.let { return it }
        val logical = 256
        val size = kotlin.math.ceil(logical * scale).toInt().coerceIn(1, 2048)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color(theme.background, 255)
        g.fillRect(0, 0, size, size)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(size / logical.toDouble(), size / logical.toDouble())
        val rnd = Random(7)
        val darken = !theme.dark
        repeat(2600) {
            val x = rnd.nextFloat() * logical
            val y = rnd.nextFloat() * logical
            val a = rnd.nextInt(4, 14)
            g.color = if (darken) Color(70, 50, 20, a) else Color(255, 255, 255, a)
            val r = rnd.nextFloat() * 0.9f + 0.3f
            // Grains near an edge are drawn again on the far side, so tiles meet without seams.
            for (dx in listOf(0, -logical, logical)) for (dy in listOf(0, -logical, logical)) {
                if ((dx != 0 && x + dx !in -2f..logical + 2f) || (dy != 0 && y + dy !in -2f..logical + 2f)) continue
                g.fill(Ellipse2D.Float(x + dx - r, y + dy - r, r * 2, r * 2))
            }
        }
        g.stroke = BasicStroke(0.6f)
        repeat(140) {
            val x = rnd.nextFloat() * logical
            val y = rnd.nextFloat() * logical
            val len = rnd.nextFloat() * 9f + 3f
            val ang = rnd.nextFloat() * Math.PI.toFloat()
            g.color = if (darken) Color(90, 60, 30, rnd.nextInt(5, 12)) else Color(255, 255, 255, rnd.nextInt(4, 9))
            for (dx in listOf(0, -logical, logical)) for (dy in listOf(0, -logical, logical)) {
                g.draw(Line2D.Float(x + dx, y + dy, x + dx + len * kotlin.math.cos(ang), y + dy + len * kotlin.math.sin(ang)))
            }
        }
        g.dispose()
        // A few page colors and screen densities at most; keep the newest.
        if (tiles.size >= 6) tiles.remove(tiles.keys.first())
        tiles[key] = img
        img
    }

    fun drawSpine(g: Graphics2D, s: PageSetup) {
        if (s.columns < 2) return
        val cx = s.width / 2f
        val w = 26f
        val old = g.paint
        g.paint = LinearGradientPaint(
            cx - w, 0f, cx + w, 0f, floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            arrayOf(Color(0, 0, 0, 0), Color(0, 0, 0, 0x14), Color(0, 0, 0, 0x26), Color(0, 0, 0, 0x14), Color(0, 0, 0, 0)),
        )
        g.fill(Rectangle2D.Float(cx - w, 0f, w * 2, s.height.toFloat()))
        g.paint = old
    }

    /**
     * Shortens a running header to fit, measuring a few dozen times however long it is: it's drawn on every
     * page, and a chapter title can be any length.
     */
    private fun ellipsize(g: Graphics2D, text: String, max: Float): String {
        val fm = g.fontMetrics
        if (text.length <= 1000 && fm.stringWidth(text) <= max) return text
        var lo = 0
        var hi = minOf(text.length, 1000)
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fm.stringWidth(text.substring(0, mid) + "…") <= max) lo = mid else hi = mid - 1
        }
        if (lo > 0 && Character.isHighSurrogate(text[lo - 1])) lo--
        return text.substring(0, lo).trimEnd() + "…"
    }

    fun drawChrome(g: Graphics2D, s: PageSetup, theme: ReadingTheme, header: String?, pageLabel: String, progress: Float, bookmarked: Boolean, col: Int = 0) {
        val left = if (s.columns > 1) s.columnLeft(col) else s.marginH + s.sideExtra
        val right = if (s.columns > 1) left + s.contentWidth else s.width - s.marginH - s.sideExtra
        val lastColumn = s.columns < 2 || left >= s.width / 2f
        g.font = chromeFont
        g.color = color(theme.secondary, 255)
        val fm = g.fontMetrics
        if (s.settings.showHeader && !header.isNullOrBlank()) {
            val y = s.marginV * 0.5f + 12f
            val avail = (right - left) - (if (bookmarked) 28f else 0f)
            val t = ellipsize(g, header, avail)
            g.drawString(t, (left + right) / 2f - fm.stringWidth(t) / 2f, y)
        }
        if (bookmarked) drawRibbon(g, theme, right)
        if (!s.settings.showFooter) return
        val baseY = s.height - s.marginV * 0.5f - 6f
        g.drawString(pageLabel, left, baseY)
        if (!lastColumn) return
        val pct = "${(progress * 100).toInt().coerceIn(0, 100)}%"
        g.drawString(pct, right - fm.stringWidth(pct), baseY)
        if (s.settings.showProgressLine) {
            val y = s.height - s.marginV * 0.5f + 1f
            val x0 = s.marginH + s.sideExtra
            val x1 = s.width - s.marginH - s.sideExtra
            g.color = color(theme.secondary, 45)
            g.fill(Rectangle2D.Float(x0, y, x1 - x0, 1.2f))
            g.color = color(theme.accent, 190)
            val filled = (x1 - x0) * progress.coerceIn(0f, 1f)
            if (s.rtl) g.fill(Rectangle2D.Float(x1 - filled, y, filled, 1.2f)) else g.fill(Rectangle2D.Float(x0, y, filled, 1.2f))
        }
    }

    private fun drawRibbon(g: Graphics2D, theme: ReadingTheme, right: Float) {
        val w = 15f
        val h = 30f
        val x = right - w
        val p = Path2D.Float()
        p.moveTo(x, 0f)
        p.lineTo(x + w, 0f)
        p.lineTo(x + w, h)
        p.lineTo(x + w / 2, h - 6f)
        p.lineTo(x, h)
        p.closePath()
        g.color = color(theme.accent, 230)
        g.fill(p)
    }

    /** The ornament standing in for a horizontal rule: three dots between two thin lines. */
    fun drawRule(g: Graphics2D, cx: Float, cy: Float, width: Float, height: Float, theme: ReadingTheme) {
        val r = height * 0.07f
        g.color = color(theme.secondary, 150)
        g.fill(Ellipse2D.Float(cx - r * 1.3f, cy - r * 1.3f, r * 2.6f, r * 2.6f))
        g.fill(Ellipse2D.Float(cx - width * 0.16f - r, cy - r, r * 2, r * 2))
        g.fill(Ellipse2D.Float(cx + width * 0.16f - r, cy - r, r * 2, r * 2))
        g.color = color(theme.secondary, 70)
        g.fill(Rectangle2D.Float(cx - width / 2, cy - r * 0.25f, width / 2 - width * 0.22f, r * 0.5f))
        g.fill(Rectangle2D.Float(cx + width * 0.22f, cy - r * 0.25f, width / 2 - width * 0.22f, r * 0.5f))
    }

    /** Dims the page and warms its colors, for reading at night. */
    fun drawNightOverlay(g: Graphics2D, w: Int, h: Int, dim: Float, warmth: Float) {
        if (warmth > 0.01f) {
            val old = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (warmth * 0.28f).coerceIn(0f, 0.35f))
            g.color = Color(255, 140, 40)
            g.fillRect(0, 0, w, h)
            g.composite = old
        }
        if (dim > 0.01f) {
            g.color = Color(0, 0, 0, (dim * 255).toInt().coerceIn(0, 210))
            g.fillRect(0, 0, w, h)
        }
    }

    fun shadowGradient(x0: Float, x1: Float, from: Int, to: Int): GradientPaint = GradientPaint(x0, 0f, Color(0, 0, 0, from), x1, 0f, Color(0, 0, 0, to))
}
