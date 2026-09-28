package com.readarea.desktop.ui.theme

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Line icons drawn on a 24-unit grid with round strokes, so they stay crisp at any scale and follow the
 * theme colors without image files.
 */
class VectorIcon(val name: String, private val size: Int = 20, private val stroke: Float = 1.8f, private val color: (() -> Color)? = null) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size

    private companion object {
        val DIRECTIONAL = setOf("back", "arrow-left", "arrow-right", "chevron-left", "chevron-right", "undo")
    }

    fun withColor(c: () -> Color) = VectorIcon(name, size, stroke, c)
    fun sized(s: Int) = VectorIcon(name, s, stroke, color)

    override fun paintIcon(c: Component?, g0: Graphics, x: Int, y: Int) {
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.translate(x, y)
            val k = size / 24.0
            g.scale(k, k)
            // Icons that point somewhere face the other way in right-to-left layouts.
            if (name in DIRECTIONAL && c != null && !c.componentOrientation.isLeftToRight) {
                g.translate(24.0, 0.0)
                g.scale(-1.0, 1.0)
            }
            val enabled = c?.isEnabled ?: true
            val base = color?.invoke() ?: (c as? JComponent)?.foreground ?: AppTheme.palette.onSurface
            g.color = if (enabled) base else base.alpha(90)
            g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            Icons.draw(name, g)
        } finally {
            g.dispose()
        }
    }
}

object Icons {
    fun get(name: String, size: Int = 20) = VectorIcon(name, size)

    private fun line(g: Graphics2D, x1: Number, y1: Number, x2: Number, y2: Number) = g.draw(Line2D.Double(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble()))

    private fun path(g: Graphics2D, fill: Boolean = false, build: Path2D.Double.() -> Unit) {
        val p = Path2D.Double().apply(build)
        if (fill) g.fill(p) else g.draw(p)
    }

    private fun rr(g: Graphics2D, x: Number, y: Number, w: Number, h: Number, r: Number, fill: Boolean = false) {
        val s = RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), r.toDouble() * 2, r.toDouble() * 2)
        if (fill) g.fill(s) else g.draw(s)
    }

    private fun circle(g: Graphics2D, cx: Number, cy: Number, r: Number, fill: Boolean = false) {
        val s = Ellipse2D.Double(cx.toDouble() - r.toDouble(), cy.toDouble() - r.toDouble(), r.toDouble() * 2, r.toDouble() * 2)
        if (fill) g.fill(s) else g.draw(s)
    }

    fun draw(name: String, g: Graphics2D) {
        when (name) {
            "home" -> {
                path(g) { moveTo(3.5, 10.5); lineTo(12.0, 3.5); lineTo(20.5, 10.5) }
                path(g) { moveTo(5.5, 9.0); lineTo(5.5, 20.0); lineTo(10.0, 20.0); lineTo(10.0, 14.0); lineTo(14.0, 14.0); lineTo(14.0, 20.0); lineTo(18.5, 20.0); lineTo(18.5, 9.0) }
            }
            "library" -> {
                rr(g, 3.5, 4, 4, 16, 1)
                rr(g, 9.5, 4, 4, 16, 1)
                path(g) { moveTo(15.4, 5.2); lineTo(19.2, 4.2); lineTo(22.0, 19.0); lineTo(18.2, 20.0); closePath() }
                line(g, 3.5, 8, 7.5, 8)
                line(g, 9.5, 8, 13.5, 8)
            }
            "book", "read" -> {
                path(g) { moveTo(12.0, 6.5); curveTo(9.5, 4.8, 6.0, 4.5, 3.0, 5.2); lineTo(3.0, 18.5); curveTo(6.0, 17.8, 9.5, 18.1, 12.0, 19.8); curveTo(14.5, 18.1, 18.0, 17.8, 21.0, 18.5); lineTo(21.0, 5.2); curveTo(18.0, 4.5, 14.5, 4.8, 12.0, 6.5); closePath() }
                line(g, 12, 6.5, 12, 19.8)
            }
            "shelves" -> {
                rr(g, 3.5, 3.5, 17, 17, 3)
                line(g, 3.5, 12, 20.5, 12)
                line(g, 7, 7, 7, 12)
                line(g, 9.5, 7.5, 9.5, 12)
                line(g, 13, 15, 13, 20.5)
                line(g, 16, 16, 16, 20.5)
            }
            "notes", "highlighter" -> {
                path(g) { moveTo(14.5, 4.5); lineTo(19.5, 9.5); lineTo(10.0, 19.0); lineTo(5.0, 19.0); lineTo(5.0, 14.0); closePath() }
                line(g, 12.5, 6.5, 17.5, 11.5)
                line(g, 4, 21, 20, 21)
            }
            "note", "edit" -> {
                path(g) { moveTo(16.5, 3.8); lineTo(20.2, 7.5); lineTo(8.5, 19.2); lineTo(4.0, 20.0); lineTo(4.8, 15.5); closePath() }
                line(g, 14.5, 5.8, 18.2, 9.5)
            }
            "stats" -> {
                line(g, 4, 20.5, 20, 20.5)
                rr(g, 5, 11, 3.2, 7, 0.8)
                rr(g, 10.4, 6.5, 3.2, 11.5, 0.8)
                rr(g, 15.8, 13.5, 3.2, 4.5, 0.8)
            }
            "settings" -> {
                circle(g, 12, 12, 3)
                path(g) {
                    val n = 8
                    for (i in 0 until n * 2) {
                        val a = Math.PI * 2 * i / (n * 2) - Math.PI / 2
                        val r = if (i % 2 == 0) 9.0 else 7.2
                        val px = 12 + Math.cos(a) * r
                        val py = 12 + Math.sin(a) * r
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    closePath()
                }
            }
            "search" -> {
                circle(g, 10.5, 10.5, 6)
                line(g, 15, 15, 20, 20)
            }
            "add", "plus" -> {
                line(g, 12, 5, 12, 19)
                line(g, 5, 12, 19, 12)
            }
            "minus" -> line(g, 5, 12, 19, 12)
            "close" -> {
                line(g, 6, 6, 18, 18)
                line(g, 18, 6, 6, 18)
            }
            "check" -> path(g) { moveTo(5.0, 12.5); lineTo(10.0, 17.0); lineTo(19.0, 7.0) }
            "back", "arrow-left" -> {
                line(g, 19, 12, 5, 12)
                path(g) { moveTo(11.0, 6.0); lineTo(5.0, 12.0); lineTo(11.0, 18.0) }
            }
            "arrow-right" -> {
                line(g, 5, 12, 19, 12)
                path(g) { moveTo(13.0, 6.0); lineTo(19.0, 12.0); lineTo(13.0, 18.0) }
            }
            "chevron-left" -> path(g) { moveTo(15.0, 5.0); lineTo(8.0, 12.0); lineTo(15.0, 19.0) }
            "chevron-right" -> path(g) { moveTo(9.0, 5.0); lineTo(16.0, 12.0); lineTo(9.0, 19.0) }
            "chevron-down" -> path(g) { moveTo(6.0, 9.0); lineTo(12.0, 15.0); lineTo(18.0, 9.0) }
            "chevron-up" -> path(g) { moveTo(6.0, 15.0); lineTo(12.0, 9.0); lineTo(18.0, 15.0) }
            "skip-back" -> {
                path(g) { moveTo(18.0, 5.5); lineTo(9.0, 12.0); lineTo(18.0, 18.5); closePath() }
                line(g, 6, 5.5, 6, 18.5)
            }
            "skip-forward" -> {
                path(g) { moveTo(6.0, 5.5); lineTo(15.0, 12.0); lineTo(6.0, 18.5); closePath() }
                line(g, 18, 5.5, 18, 18.5)
            }
            "folder" -> path(g) { moveTo(3.5, 6.5); lineTo(3.5, 18.5); quadTo(3.5, 19.5, 4.5, 19.5); lineTo(19.5, 19.5); quadTo(20.5, 19.5, 20.5, 18.5); lineTo(20.5, 8.5); quadTo(20.5, 7.5, 19.5, 7.5); lineTo(11.5, 7.5); lineTo(9.5, 5.0); lineTo(4.5, 5.0); quadTo(3.5, 5.0, 3.5, 6.5) }
            "file", "open" -> {
                path(g) { moveTo(6.0, 3.5); lineTo(14.0, 3.5); lineTo(19.0, 8.5); lineTo(19.0, 20.5); lineTo(6.0, 20.5); closePath() }
                path(g) { moveTo(14.0, 3.5); lineTo(14.0, 8.5); lineTo(19.0, 8.5) }
            }
            "grid" -> {
                rr(g, 4, 4, 6.5, 6.5, 1.5)
                rr(g, 13.5, 4, 6.5, 6.5, 1.5)
                rr(g, 4, 13.5, 6.5, 6.5, 1.5)
                rr(g, 13.5, 13.5, 6.5, 6.5, 1.5)
            }
            "list", "toc" -> {
                for (y in listOf(6.5, 12.0, 17.5)) {
                    circle(g, 5, y, 0.9, true)
                    line(g, 9, y, 20, y)
                }
            }
            "sort" -> {
                line(g, 4, 7, 20, 7)
                line(g, 7, 12, 17, 12)
                line(g, 10, 17, 14, 17)
            }
            "filter" -> path(g) { moveTo(4.0, 5.0); lineTo(20.0, 5.0); lineTo(14.0, 12.5); lineTo(14.0, 19.0); lineTo(10.0, 17.0); lineTo(10.0, 12.5); closePath() }
            "heart", "heart-filled" -> path(g, fill = name == "heart-filled") {
                moveTo(12.0, 19.5)
                curveTo(5.0, 15.0, 3.0, 11.5, 3.5, 8.5)
                curveTo(4.0, 5.5, 7.8, 3.8, 10.2, 5.8)
                lineTo(12.0, 7.4)
                lineTo(13.8, 5.8)
                curveTo(16.2, 3.8, 20.0, 5.5, 20.5, 8.5)
                curveTo(21.0, 11.5, 19.0, 15.0, 12.0, 19.5)
                closePath()
            }
            "star", "star-filled" -> path(g, fill = name == "star-filled") {
                for (i in 0 until 10) {
                    val a = Math.PI * 2 * i / 10 - Math.PI / 2
                    val r = if (i % 2 == 0) 9.0 else 4.0
                    val px = 12 + Math.cos(a) * r
                    val py = 12.6 + Math.sin(a) * r
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                closePath()
            }
            "more" -> for (x in listOf(5.5, 12.0, 18.5)) circle(g, x, 12, 1.4, true)
            "more-vert" -> for (y in listOf(5.5, 12.0, 18.5)) circle(g, 12, y, 1.4, true)
            "bookmark", "bookmark-filled" -> path(g, fill = name == "bookmark-filled") { moveTo(6.5, 3.5); lineTo(17.5, 3.5); lineTo(17.5, 20.5); lineTo(12.0, 16.5); lineTo(6.5, 20.5); closePath() }
            "text" -> {
                path(g) { moveTo(3.0, 19.0); lineTo(8.0, 6.0); lineTo(13.0, 19.0) }
                line(g, 4.8, 14.5, 11.2, 14.5)
                path(g) { moveTo(15.0, 12.5); quadTo(16.5, 10.8, 18.5, 11.0); quadTo(21.0, 11.4, 21.0, 14.0); lineTo(21.0, 19.0) }
                path(g) { moveTo(21.0, 15.3); quadTo(15.0, 14.8, 15.0, 17.2); quadTo(15.2, 19.3, 17.6, 19.2); quadTo(20.2, 19.0, 21.0, 16.8) }
            }
            "sun", "light" -> {
                circle(g, 12, 12, 4)
                for (i in 0 until 8) {
                    val a = Math.PI * 2 * i / 8
                    line(g, 12 + Math.cos(a) * 7, 12 + Math.sin(a) * 7, 12 + Math.cos(a) * 9.5, 12 + Math.sin(a) * 9.5)
                }
            }
            "moon" -> path(g) { moveTo(19.5, 14.5); curveTo(15.0, 16.5, 9.0, 13.0, 9.5, 7.5); curveTo(9.7, 6.0, 10.2, 4.8, 11.0, 4.0); curveTo(6.5, 4.8, 3.5, 9.0, 4.5, 13.5); curveTo(5.5, 18.5, 11.0, 21.5, 16.0, 19.2); curveTo(17.5, 18.4, 18.7, 17.0, 19.5, 14.5) }
            "palette", "theme" -> {
                path(g) { moveTo(12.0, 3.5); curveTo(6.5, 3.5, 3.0, 7.5, 3.5, 12.5); curveTo(4.0, 17.0, 8.0, 20.5, 12.0, 20.5); curveTo(13.5, 20.5, 14.0, 19.5, 13.5, 18.0); curveTo(13.0, 16.0, 14.5, 15.0, 16.5, 15.0); curveTo(19.0, 15.0, 20.5, 13.5, 20.5, 11.0); curveTo(20.5, 6.8, 16.8, 3.5, 12.0, 3.5); closePath() }
                circle(g, 8, 10, 1.2, true)
                circle(g, 12, 7.2, 1.2, true)
                circle(g, 16, 9.5, 1.2, true)
            }
            "play" -> path(g, fill = true) { moveTo(7.5, 4.5); lineTo(19.0, 12.0); lineTo(7.5, 19.5); closePath() }
            "pause" -> {
                rr(g, 6.5, 5, 3.5, 14, 1, true)
                rr(g, 14, 5, 3.5, 14, 1, true)
            }
            "stop" -> rr(g, 6, 6, 12, 12, 2, true)
            "speak", "listen" -> {
                path(g) { moveTo(4.0, 9.5); lineTo(7.5, 9.5); lineTo(12.0, 5.5); lineTo(12.0, 18.5); lineTo(7.5, 14.5); lineTo(4.0, 14.5); closePath() }
                g.draw(Arc2D.Double(10.0, 8.0, 7.0, 8.0, -60.0, 120.0, Arc2D.OPEN))
                g.draw(Arc2D.Double(9.0, 5.0, 12.0, 14.0, -60.0, 120.0, Arc2D.OPEN))
            }
            "timer", "auto" -> {
                circle(g, 12, 13, 7.5)
                line(g, 12, 13, 12, 9)
                line(g, 12, 13, 15, 15)
                line(g, 9.5, 3, 14.5, 3)
            }
            "fullscreen" -> {
                path(g) { moveTo(4.0, 9.0); lineTo(4.0, 4.0); lineTo(9.0, 4.0) }
                path(g) { moveTo(15.0, 4.0); lineTo(20.0, 4.0); lineTo(20.0, 9.0) }
                path(g) { moveTo(20.0, 15.0); lineTo(20.0, 20.0); lineTo(15.0, 20.0) }
                path(g) { moveTo(9.0, 20.0); lineTo(4.0, 20.0); lineTo(4.0, 15.0) }
            }
            "fullscreen-exit" -> {
                path(g) { moveTo(9.0, 4.0); lineTo(9.0, 9.0); lineTo(4.0, 9.0) }
                path(g) { moveTo(15.0, 4.0); lineTo(15.0, 9.0); lineTo(20.0, 9.0) }
                path(g) { moveTo(20.0, 15.0); lineTo(15.0, 15.0); lineTo(15.0, 20.0) }
                path(g) { moveTo(4.0, 15.0); lineTo(9.0, 15.0); lineTo(9.0, 20.0) }
            }
            "trash", "delete" -> {
                line(g, 4, 6.5, 20, 6.5)
                path(g) { moveTo(9.5, 6.5); lineTo(9.5, 4.0); lineTo(14.5, 4.0); lineTo(14.5, 6.5) }
                path(g) { moveTo(6.0, 6.5); lineTo(7.0, 20.0); lineTo(17.0, 20.0); lineTo(18.0, 6.5) }
                line(g, 10, 10, 10.3, 16.5)
                line(g, 14, 10, 13.7, 16.5)
            }
            "export", "share" -> {
                line(g, 12, 3.5, 12, 14.5)
                path(g) { moveTo(7.5, 8.0); lineTo(12.0, 3.5); lineTo(16.5, 8.0) }
                path(g) { moveTo(5.0, 12.0); lineTo(5.0, 20.0); lineTo(19.0, 20.0); lineTo(19.0, 12.0) }
            }
            "copy" -> {
                rr(g, 8.5, 8.5, 12, 12, 2)
                path(g) { moveTo(15.5, 8.5); lineTo(15.5, 5.0); quadTo(15.5, 3.5, 14.0, 3.5); lineTo(5.0, 3.5); quadTo(3.5, 3.5, 3.5, 5.0); lineTo(3.5, 14.0); quadTo(3.5, 15.5, 5.0, 15.5); lineTo(8.5, 15.5) }
            }
            "info" -> {
                circle(g, 12, 12, 9)
                line(g, 12, 11, 12, 16.5)
                circle(g, 12, 7.8, 1.1, true)
            }
            "refresh" -> {
                g.draw(Arc2D.Double(4.5, 4.5, 15.0, 15.0, 60.0, 280.0, Arc2D.OPEN))
                path(g) { moveTo(15.5, 4.2); lineTo(16.3, 7.2); lineTo(13.2, 8.0) }
            }
            "eye" -> {
                path(g) { moveTo(2.5, 12.0); curveTo(5.5, 6.5, 18.5, 6.5, 21.5, 12.0); curveTo(18.5, 17.5, 5.5, 17.5, 2.5, 12.0); closePath() }
                circle(g, 12, 12, 3)
            }
            "external" -> {
                path(g) { moveTo(13.0, 4.0); lineTo(20.0, 4.0); lineTo(20.0, 11.0) }
                line(g, 20, 4, 11, 13)
                path(g) { moveTo(17.0, 14.5); lineTo(17.0, 19.5); lineTo(4.5, 19.5); lineTo(4.5, 7.0); lineTo(9.5, 7.0) }
            }
            "reveal" -> {
                path(g) { moveTo(3.5, 6.5); lineTo(3.5, 18.5); lineTo(20.5, 18.5); lineTo(20.5, 8.5); lineTo(11.5, 8.5); lineTo(9.5, 6.0); lineTo(3.5, 6.0) }
                circle(g, 12, 13.5, 2.5)
            }
            "clock" -> {
                circle(g, 12, 12, 8.5)
                path(g) { moveTo(12.0, 7.0); lineTo(12.0, 12.0); lineTo(15.5, 14.0) }
            }
            "flame" -> path(g) { moveTo(12.0, 3.0); curveTo(13.5, 7.0, 18.5, 9.5, 18.5, 14.5); curveTo(18.5, 18.5, 15.5, 21.0, 12.0, 21.0); curveTo(8.5, 21.0, 5.5, 18.5, 5.5, 14.5); curveTo(5.5, 11.5, 7.5, 9.5, 8.5, 8.0); curveTo(9.0, 10.5, 10.0, 11.5, 11.0, 12.0); curveTo(11.0, 8.5, 10.5, 6.0, 12.0, 3.0); closePath() }
            "trophy" -> {
                path(g) { moveTo(7.0, 4.0); lineTo(17.0, 4.0); lineTo(17.0, 10.0); curveTo(17.0, 13.0, 14.8, 15.0, 12.0, 15.0); curveTo(9.2, 15.0, 7.0, 13.0, 7.0, 10.0); closePath() }
                path(g) { moveTo(7.0, 6.0); lineTo(4.0, 6.0); curveTo(4.0, 9.5, 5.5, 11.0, 7.3, 11.3) }
                path(g) { moveTo(17.0, 6.0); lineTo(20.0, 6.0); curveTo(20.0, 9.5, 18.5, 11.0, 16.7, 11.3) }
                line(g, 12, 15, 12, 18.5)
                line(g, 8, 20, 16, 20)
            }
            "pages" -> {
                rr(g, 6, 3.5, 12, 17, 1.5)
                line(g, 9, 8, 15, 8)
                line(g, 9, 11.5, 15, 11.5)
                line(g, 9, 15, 13, 15)
            }
            "target" -> {
                circle(g, 12, 12, 8.5)
                circle(g, 12, 12, 4.5)
                circle(g, 12, 12, 1.2, true)
            }
            "language" -> {
                circle(g, 12, 12, 8.5)
                line(g, 3.5, 12, 20.5, 12)
                path(g) { moveTo(12.0, 3.5); curveTo(8.5, 7.5, 8.5, 16.5, 12.0, 20.5) }
                path(g) { moveTo(12.0, 3.5); curveTo(15.5, 7.5, 15.5, 16.5, 12.0, 20.5) }
            }
            "lock", "privacy" -> {
                rr(g, 5, 10.5, 14, 10, 2)
                path(g) { moveTo(8.0, 10.5); lineTo(8.0, 7.5); curveTo(8.0, 4.8, 9.8, 3.5, 12.0, 3.5); curveTo(14.2, 3.5, 16.0, 4.8, 16.0, 7.5); lineTo(16.0, 10.5) }
                line(g, 12, 14.5, 12, 16.5)
            }
            "storage" -> {
                g.draw(Ellipse2D.Double(4.5, 3.5, 15.0, 5.0))
                path(g) { moveTo(4.5, 6.0); lineTo(4.5, 18.0); curveTo(4.5, 21.3, 19.5, 21.3, 19.5, 18.0); lineTo(19.5, 6.0) }
                path(g) { moveTo(4.5, 12.0); curveTo(4.5, 15.3, 19.5, 15.3, 19.5, 12.0) }
            }
            "upload" -> {
                line(g, 12, 15, 12, 4)
                path(g) { moveTo(7.5, 8.5); lineTo(12.0, 4.0); lineTo(16.5, 8.5) }
                line(g, 4.5, 20, 19.5, 20)
            }
            "zoom-in" -> {
                circle(g, 10.5, 10.5, 6)
                line(g, 15, 15, 20, 20)
                line(g, 10.5, 8, 10.5, 13)
                line(g, 8, 10.5, 13, 10.5)
            }
            "zoom-out" -> {
                circle(g, 10.5, 10.5, 6)
                line(g, 15, 15, 20, 20)
                line(g, 8, 10.5, 13, 10.5)
            }
            "columns" -> {
                rr(g, 3.5, 4.5, 17, 15, 2)
                line(g, 12, 4.5, 12, 19.5)
            }
            "undo" -> {
                path(g) { moveTo(8.5, 5.5); lineTo(4.0, 10.0); lineTo(8.5, 14.5) }
                path(g) { moveTo(4.0, 10.0); lineTo(15.0, 10.0); curveTo(18.3, 10.0, 20.0, 12.0, 20.0, 15.0); curveTo(20.0, 18.0, 18.3, 19.5, 15.0, 19.5); lineTo(11.0, 19.5) }
            }
            "logo" -> drawLogo(g)
            else -> circle(g, 12, 12, 8)
        }
    }

    /** The app's open-book mark, as on the Android launcher icon. */
    fun drawLogo(g: Graphics2D) {
        val old = g.paint
        g.color = Color(0xE9D5B4)
        path(g, fill = true) { moveTo(12.0, 7.0); curveTo(9.6, 5.5, 6.5, 5.5, 4.0, 6.1); lineTo(4.0, 18.4); curveTo(6.5, 17.8, 9.6, 18.1, 12.0, 19.6); curveTo(14.4, 18.1, 17.5, 17.8, 20.0, 18.4); lineTo(20.0, 6.1); curveTo(17.5, 5.5, 14.4, 5.5, 12.0, 7.0); closePath() }
        g.color = Color(0xFFF8EC)
        path(g, fill = true) { moveTo(12.0, 6.2); curveTo(9.9, 5.0, 7.5, 4.7, 5.0, 5.3); lineTo(5.0, 17.2); curveTo(7.5, 16.6, 9.9, 16.9, 12.0, 18.4); closePath() }
        g.color = Color(0xFBEFDD)
        path(g, fill = true) { moveTo(12.0, 6.2); curveTo(14.1, 5.0, 16.5, 4.7, 19.0, 5.3); lineTo(19.0, 17.2); curveTo(16.5, 16.6, 14.1, 16.9, 12.0, 18.4); closePath() }
        g.color = Color(0xE05A47)
        path(g, fill = true) { moveTo(15.3, 5.0); lineTo(16.8, 4.9); lineTo(16.8, 9.3); lineTo(16.05, 8.6); lineTo(15.3, 9.3); closePath() }
        g.paint = old
    }
}

/** The app icon at any size: the open book on the warm brown square of the launcher icon. */
object AppIcon {
    fun image(size: Int): java.awt.image.BufferedImage {
        val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val s = size.toFloat()
        val inset = s * 0.06f
        val shape = RoundRectangle2D.Float(inset, inset, s - inset * 2, s - inset * 2, s * 0.44f, s * 0.44f)
        g.paint = java.awt.GradientPaint(0f, 0f, Color(0xB0703F), s, s, Color(0x5E331C))
        g.fill(shape)
        g.paint = java.awt.RadialGradientPaint(s * 0.37f, s * 0.31f, s * 0.55f, floatArrayOf(0f, 1f), arrayOf(Color(255, 255, 255, 0x33), Color(255, 255, 255, 0)))
        g.fill(shape)
        val k = (s - inset * 2) / 24.0 * 0.92
        g.translate((s - 24 * k) / 2.0, (s - 24 * k) / 2.0 + s * 0.01)
        g.scale(k, k)
        Icons.drawLogo(g)
        g.dispose()
        return img
    }
}
