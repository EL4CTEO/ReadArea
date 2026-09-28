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
import java.awt.TexturePaint
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.random.Random

/** Everything drawn around the text: paper, header and footer, the spine of a spread, the bookmark ribbon. */
object PageChrome {
    private var texture: BufferedImage? = null
    private var textureKey = 0
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

    fun drawBackground(g: Graphics2D, theme: ReadingTheme, w: Int, h: Int) {
        g.color = color(theme.background, 255)
        g.fillRect(0, 0, w, h)
        if (theme.texture) {
            val tex = texture(theme)
            val old = g.paint
            g.paint = TexturePaint(tex, Rectangle2D.Float(0f, 0f, tex.width.toFloat(), tex.height.toFloat()))
            g.fillRect(0, 0, w, h)
            g.paint = old
        }
    }

    /** A faint paper grain, generated once per page color. */
    @Synchronized
    private fun texture(theme: ReadingTheme): BufferedImage {
        val key = theme.background
        texture?.let { if (textureKey == key) return it }
        val size = 256
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val rnd = Random(7)
        val darken = !theme.dark
        repeat(2600) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val a = rnd.nextInt(4, 14)
            g.color = if (darken) Color(70, 50, 20, a) else Color(255, 255, 255, a)
            val r = rnd.nextFloat() * 0.9f + 0.3f
            g.fill(Ellipse2D.Float(x - r, y - r, r * 2, r * 2))
        }
        g.stroke = BasicStroke(0.6f)
        repeat(140) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val len = rnd.nextFloat() * 9f + 3f
            val ang = rnd.nextFloat() * Math.PI.toFloat()
            g.color = if (darken) Color(90, 60, 30, rnd.nextInt(5, 12)) else Color(255, 255, 255, rnd.nextInt(4, 9))
            g.draw(Line2D.Float(x, y, x + len * kotlin.math.cos(ang), y + len * kotlin.math.sin(ang)))
        }
        g.dispose()
        texture = img
        textureKey = key
        return img
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

    private fun ellipsize(g: Graphics2D, text: String, max: Float): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(text) <= max) return text
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end) + "…") > max) end--
        return text.substring(0, end).trimEnd() + "…"
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
