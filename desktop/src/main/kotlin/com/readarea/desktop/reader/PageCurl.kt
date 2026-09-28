package com.readarea.desktop.reader

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.hypot

/**
 * Draws a page being turned: the sheet folds along the line halfway between its corner and the pointer,
 * the folded part (its back) lies over the page, the next page shows through, with shadow and shading
 * along the fold.
 *
 * Coordinates are logical pixels; images are the full view's page buffers at device scale.
 */
object PageCurl {
    /**
     * @param sheet the rectangle of the sheet being turned (the whole view, or one half of a spread)
     * @param corner the sheet corner being lifted
     * @param pointer where that corner is now
     * @param front the image the sheet shows now (its visible face is the part of [sheet] in it)
     * @param under the image revealed beneath the sheet
     * @param back draws the sheet's back face through the given reflection transform, clipped already
     * @param paper the page color, for the back of a single sheet
     */
    fun draw(
        g: Graphics2D,
        viewW: Int,
        viewH: Int,
        sheet: Rectangle2D.Float,
        corner: Point2D.Float,
        pointer: Point2D.Float,
        front: BufferedImage,
        under: BufferedImage,
        back: (Graphics2D, AffineTransform) -> Unit,
    ) {
        val dx = pointer.x - corner.x
        val dy = pointer.y - corner.y
        val len = hypot(dx, dy)
        val full = Rectangle2D.Float(0f, 0f, viewW.toFloat(), viewH.toFloat())
        if (len < 0.5f) {
            g.drawImage(front, 0, 0, viewW, viewH, null)
            return
        }
        val nx = dx / len
        val ny = dy / len
        val mx = (corner.x + pointer.x) / 2f
        val my = (corner.y + pointer.y) / 2f
        val big = (viewW + viewH) * 4f
        // Half-plane on the corner's side of the fold line: the part of the sheet that is lifted.
        val liftedSide = halfPlane(mx, my, -nx, -ny, big)
        val lifted = Area(sheet).apply { intersect(Area(liftedSide)) }
        val flat = Area(full).apply { subtract(lifted) }

        // The revealed page under the lifted part, and the untouched front everywhere else.
        val saved = g.clip
        g.clip(lifted)
        g.drawImage(under, 0, 0, viewW, viewH, null)
        shadeAlongFold(g, mx, my, -nx, -ny, minOf(len * 0.5f, 60f), 0x55)
        g.clip = saved
        g.clip(flat)
        g.drawImage(front, 0, 0, viewW, viewH, null)
        g.clip = saved

        // The flap: the lifted part mirrored across the fold line.
        val reflect = reflection(mx, my, nx, ny)
        val flap = Area(reflect.createTransformedShape(lifted))
        // A soft shadow the flap casts on the page beside it.
        val shadowShift = AffineTransform.getTranslateInstance(nx * 6.0, ny * 6.0)
        g.clip(Area(full).apply { subtract(flap) })
        val oldComposite = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f)
        g.color = Color.BLACK
        g.fill(shadowShift.createTransformedShape(flap))
        g.composite = oldComposite
        g.clip = saved

        g.clip(flap)
        back(g, reflect)
        // Curvature: brighter at the fold, a little darker towards the flap's edge.
        val depth = minOf(len * 0.5f, 140f)
        g.paint = LinearGradientPaint(
            mx, my, mx + nx * depth, my + ny * depth, floatArrayOf(0f, 0.25f, 1f),
            arrayOf(Color(0, 0, 0, 0x30), Color(255, 255, 255, 0x28), Color(0, 0, 0, 0x14)), MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fill(flap)
        g.clip = saved
    }

    /** A darkening strip on the revealed page along the fold line. */
    private fun shadeAlongFold(g: Graphics2D, mx: Float, my: Float, nx: Float, ny: Float, width: Float, alpha: Int) {
        if (width < 1f) return
        g.paint = LinearGradientPaint(mx, my, mx + nx * width, my + ny * width, floatArrayOf(0f, 1f), arrayOf(Color(0, 0, 0, alpha), Color(0, 0, 0, 0)))
        g.fill(g.clip ?: return)
    }

    /** The half-plane of points X with (X - m) · n >= 0, as a large polygon. */
    private fun halfPlane(mx: Float, my: Float, nx: Float, ny: Float, big: Float): Shape {
        val tx = -ny
        val ty = nx
        return Path2D.Float().apply {
            moveTo(mx + tx * big, my + ty * big)
            lineTo(mx - tx * big, my - ty * big)
            lineTo(mx - tx * big + nx * big, my - ty * big + ny * big)
            lineTo(mx + tx * big + nx * big, my + ty * big + ny * big)
            closePath()
        }
    }

    /** Reflection across the line through (mx, my) with unit normal (nx, ny). */
    fun reflection(mx: Float, my: Float, nx: Float, ny: Float): AffineTransform {
        val a = 1 - 2 * nx * nx
        val b = -2 * nx * ny
        val d = 1 - 2 * ny * ny
        val m = AffineTransform(a.toDouble(), b.toDouble(), b.toDouble(), d.toDouble(), 0.0, 0.0)
        val t = AffineTransform.getTranslateInstance(mx.toDouble(), my.toDouble())
        t.concatenate(m)
        t.concatenate(AffineTransform.getTranslateInstance(-mx.toDouble(), -my.toDouble()))
        return t
    }

    /** Mirror across the vertical line x = [x]. */
    fun mirrorX(x: Float): AffineTransform = AffineTransform(-1.0, 0.0, 0.0, 1.0, 2.0 * x, 0.0)
}
