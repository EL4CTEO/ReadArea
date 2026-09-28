package com.readarea.desktop.reader

import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.reader.engine.PageChrome
import com.readarea.desktop.reader.engine.PagePos
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the current page (or spread) and animates turning it: a curl that follows the mouse, slide, fade
 * or instant. Pages are drawn once into buffers at the screen's pixel density and only redrawn when
 * something on them changes, so idle reading costs nothing.
 */
class PageView(private val controller: ReaderController) : Widget() {
    private var buffers = HashMap<PagePos, BufferedImage>()
    private var bufferScale = 1f
    private var bufferSize = 0 to 0
    private var dirty = true

    private enum class Kind { CURL, SLIDE, FADE }

    private inner class Anim(val kind: Kind, val forward: Boolean, val target: PagePos, val from: PagePos) {
        var t = 0f
        var dragging = false
        var pointer = Point2D.Float()
        var corner = Point2D.Float()
        var start = System.nanoTime()
        var startT = 0f
        var endT = 1f
        var duration = 0.4f
        var complete = true
        var pointerFrom = Point2D.Float()
        var pointerTo = Point2D.Float()
    }

    private var anim: Anim? = null
    private val timer = Timer(8) { step() }
    private var pressX = 0
    private var pressY = 0
    private var pressTime = 0L
    private var mode = Drag.NONE
    private var lastWheel = 0L

    private enum class Drag { NONE, PENDING_CURL, CURL, PENDING_SELECT, SELECT }

    var onActivity: (() -> Unit)? = null

    init {
        isOpaque = true
        isFocusable = true
        val m = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = press(e)
            override fun mouseDragged(e: MouseEvent) = drag(e)
            override fun mouseReleased(e: MouseEvent) = release(e)
            override fun mouseMoved(e: MouseEvent) = hover(e)
            override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent) = wheel(e)
        }
        addMouseListener(m)
        addMouseMotionListener(m)
        addMouseWheelListener(m)
        getAccessibleContext().accessibleName = "Page"
    }

    /** Throws away the page buffers so the next paint redraws them. */
    fun refresh() {
        dirty = true
        repaint()
    }

    private fun deviceScale(g: Graphics2D): Float = g.transform.scaleX.toFloat().coerceIn(1f, 4f)

    private fun buffer(pos: PagePos, g: Graphics2D): BufferedImage? {
        val scale = deviceScale(g)
        val size = width to height
        if (dirty || scale != bufferScale || size != bufferSize) {
            buffers.clear()
            dirty = false
            bufferScale = scale
            bufferSize = size
        }
        buffers[pos]?.let { return it }
        val e = controller.engine ?: return null
        if (width <= 0 || height <= 0) return null
        val img = g.deviceConfiguration.createCompatibleImage((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Transparency.OPAQUE)
        val bg = img.createGraphics()
        PageChrome.setupQuality(bg)
        bg.scale(scale.toDouble(), scale.toDouble())
        runCatching { e.drawPage(bg, pos, controller.deco) }
        bg.dispose()
        if (buffers.size > 4) buffers.keys.filter { it != controller.pos }.take(buffers.size - 3).forEach { buffers.remove(it) }
        buffers[pos] = img
        return img
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val theme = controller.engine?.theme
        g.color = theme?.let { PageChrome.color(it.background, 255) } ?: background ?: Color.WHITE
        g.fillRect(0, 0, width, height)
        val cur = controller.pageAt(0)
        val a = anim
        if (cur == null) {
            g.dispose()
            return
        }
        val front = buffer(if (a != null) a.from else cur, g)
        if (a == null || front == null) {
            front?.let { g.drawImage(it, 0, 0, width, height, null) }
        } else {
            val other = buffer(a.target, g)
            if (other == null) g.drawImage(front, 0, 0, width, height, null) else drawAnim(g, a, front, other)
        }
        val s = controller.settings.value
        PageChrome.drawNightOverlay(g, width, height, s.dim, s.warmth)
        g.dispose()
    }

    private fun drawAnim(g: Graphics2D, a: Anim, front: BufferedImage, other: BufferedImage) {
        val w = width.toFloat()
        val t = a.t.coerceIn(0f, 1f)
        val rtl = controller.ui.value.rtl
        when (a.kind) {
            Kind.FADE -> {
                g.drawImage(other, 0, 0, width, height, null)
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f - t)
                g.drawImage(front, 0, 0, width, height, null)
                g.composite = AlphaComposite.SrcOver
            }
            Kind.SLIDE -> {
                val dir = (if (a.forward) -1 else 1) * (if (rtl) -1 else 1)
                val off = w * t * dir
                g.drawImage(front, off.toInt(), 0, width, height, null)
                g.drawImage(other, (off - dir * w).toInt(), 0, width, height, null)
                val edge = if (dir < 0) off + w else off
                g.paint = GradientPaint(edge, 0f, Color(0, 0, 0, 60), edge + 18f * -dir, 0f, Color(0, 0, 0, 0))
                g.fillRect(min(edge, edge - 18f * dir).toInt(), 0, 18, height)
            }
            Kind.CURL -> drawCurl(g, a, front, other, rtl)
        }
    }

    /** The sheet being turned: the whole page, or the outer half of a spread. */
    private fun sheet(forward: Boolean, rtl: Boolean): Rectangle2D.Float {
        val w = width.toFloat()
        val h = height.toFloat()
        if (controller.ui.value.columns < 2) return Rectangle2D.Float(0f, 0f, w, h)
        val right = forward != rtl
        return if (right) Rectangle2D.Float(w / 2, 0f, w / 2, h) else Rectangle2D.Float(0f, 0f, w / 2, h)
    }

    private fun drawCurl(g: Graphics2D, a: Anim, front: BufferedImage, other: BufferedImage, rtl: Boolean) {
        // Turning back is turning forward in reverse: the previous page lies on top and unfolds.
        val forwardSheet = a.forward
        val top = if (forwardSheet) front else other
        val bottom = if (forwardSheet) other else front
        val sheet = sheet(true, rtl)
        val spread = controller.ui.value.columns > 1
        val theme = controller.engine?.theme
        val paper = theme?.let { PageChrome.color(it.background, 255) } ?: Color.WHITE
        val back: (Graphics2D, AffineTransform) -> Unit = { bg, reflect ->
            val saved = bg.transform
            if (spread) {
                // The back of the right-hand sheet is the next left page; seen through the fold it reads normally.
                val spine = width / 2f
                bg.transform(reflect)
                bg.transform(PageCurl.mirrorX(spine))
                bg.drawImage(bottom, 0, 0, width, height, null)
            } else {
                bg.transform(reflect)
                bg.drawImage(top, 0, 0, width, height, null)
                bg.transform = saved
                bg.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.86f)
                bg.color = paper
                bg.fill(bg.clip)
                bg.composite = AlphaComposite.SrcOver
            }
            bg.transform = saved
        }
        if (spread) {
            // Static halves: the left of the current spread and the right of the next one sit still.
            val left = Rectangle2D.Float(if (rtl) width / 2f else 0f, 0f, width / 2f, height.toFloat())
            val saved = g.clip
            g.clip(left)
            g.drawImage(top, 0, 0, width, height, null)
            g.clip = saved
            PageCurl.draw(g, width, height, sheet, a.corner, a.pointer, top, bottom, back)
        } else {
            PageCurl.draw(g, width, height, sheet, a.corner, a.pointer, top, bottom, back)
        }
    }

    // Starting and running animations

    fun flip(forward: Boolean) {
        if (anim != null) finishNow()
        if (!controller.canFlip(forward)) return
        val cur = controller.pageAt(0) ?: return
        val target = controller.pageAt(if (forward) 1 else -1) ?: return
        val s = controller.settings.value
        val kind = when (s.pageAnim) {
            "instant", "scroll" -> null
            "fade" -> Kind.FADE
            "slide" -> Kind.SLIDE
            else -> Kind.CURL
        }
        if (kind == null) {
            controller.onFlipped(forward)
            refreshSoon()
            return
        }
        val a = Anim(kind, forward, target, cur)
        a.duration = (if (kind == Kind.CURL) 0.46f else 0.3f) / s.animSpeed.coerceIn(0.25f, 3f)
        if (kind == Kind.CURL) setupAutoCurl(a)
        anim = a
        a.start = System.nanoTime()
        timer.start()
    }

    private fun cornerFor(fromTop: Boolean): Point2D.Float {
        val rtl = controller.ui.value.rtl
        val sheet = sheet(true, rtl)
        val x = if (rtl) sheet.x else sheet.x + sheet.width
        return Point2D.Float(x, if (fromTop) 0f else height.toFloat())
    }

    /** A click or key turn: the corner sweeps across along a gentle arc. */
    private fun setupAutoCurl(a: Anim) {
        val rtl = controller.ui.value.rtl
        val sheet = sheet(true, rtl)
        a.corner = cornerFor(false)
        val far = if (rtl) sheet.x + sheet.width * 2 else sheet.x - sheet.width
        val folded = Point2D.Float(far, height.toFloat())
        val lifted = Point2D.Float(a.corner.x + (if (rtl) 1 else -1) * 1f, height - 1f)
        if (a.forward) {
            a.pointerFrom = lifted
            a.pointerTo = folded
        } else {
            a.pointerFrom = folded
            a.pointerTo = Point2D.Float(a.corner.x, a.corner.y)
        }
        a.pointer = Point2D.Float(a.pointerFrom.x, a.pointerFrom.y)
    }

    private fun step() {
        val a = anim ?: return timer.stop()
        if (a.dragging) return
        val elapsed = (System.nanoTime() - a.start) / 1e9f
        val raw = (elapsed / a.duration).coerceIn(0f, 1f)
        val eased = 1f - (1f - raw) * (1f - raw) * (1f - raw)
        a.t = a.startT + (a.endT - a.startT) * eased
        if (a.kind == Kind.CURL) {
            val k = eased
            val lift = kotlin.math.sin(k * Math.PI).toFloat() * height * 0.18f
            a.pointer = Point2D.Float(a.pointerFrom.x + (a.pointerTo.x - a.pointerFrom.x) * k, a.pointerFrom.y + (a.pointerTo.y - a.pointerFrom.y) * k - lift)
        }
        repaint()
        if (raw >= 1f) {
            timer.stop()
            anim = null
            if (a.complete) controller.onFlipped(a.forward)
            refreshSoon()
        }
    }

    private fun finishNow() {
        val a = anim ?: return
        timer.stop()
        anim = null
        if (a.complete && !a.dragging) controller.onFlipped(a.forward)
        repaint()
    }

    private fun refreshSoon() {
        repaint()
    }

    // Mouse

    private fun edgeZone(x: Int): Int {
        val w = width
        val rtl = controller.ui.value.rtl
        val right = x > w - max(60, w / 9)
        val left = x < max(60, w / 9)
        return when {
            right -> if (rtl) -1 else 1
            left -> if (rtl) 1 else -1
            else -> 0
        }
    }

    private fun press(e: MouseEvent) {
        requestFocusInWindow()
        onActivity?.invoke()
        if (e.button != MouseEvent.BUTTON1) return
        pressX = e.x
        pressY = e.y
        pressTime = System.currentTimeMillis()
        if (anim != null) finishNow()
        if (e.clickCount == 2) {
            controller.selectWord(e.x.toFloat(), e.y.toFloat())
            mode = Drag.NONE
            return
        }
        if (e.clickCount >= 3) {
            controller.selectParagraph(e.x.toFloat(), e.y.toFloat())
            mode = Drag.NONE
            return
        }
        val zone = edgeZone(e.x)
        mode = if (zone != 0 && controller.settings.value.pageAnim == "curl") Drag.PENDING_CURL else Drag.PENDING_SELECT
    }

    private fun drag(e: MouseEvent) {
        val dist = abs(e.x - pressX) + abs(e.y - pressY)
        when (mode) {
            Drag.PENDING_CURL -> if (dist > 6) startCurlDrag(e)
            Drag.CURL -> anim?.let { a ->
                a.pointer = clampPointer(e.x.toFloat(), e.y.toFloat())
                repaint()
            }
            Drag.PENDING_SELECT -> if (dist > 4 && controller.beginSelection(pressX.toFloat(), pressY.toFloat())) {
                mode = Drag.SELECT
                controller.extendSelection(e.x.toFloat(), e.y.toFloat())
            }
            Drag.SELECT -> controller.extendSelection(e.x.toFloat(), e.y.toFloat())
            Drag.NONE -> {}
        }
    }

    private fun clampPointer(x: Float, y: Float): Point2D.Float = Point2D.Float(x.coerceIn(-width.toFloat(), width * 2f), y.coerceIn(-height * 0.2f, height * 1.2f))

    private fun startCurlDrag(e: MouseEvent) {
        val zone = edgeZone(pressX)
        val forward = zone > 0
        if (!controller.canFlip(forward)) {
            mode = Drag.NONE
            return
        }
        val cur = controller.pageAt(0) ?: return
        val target = controller.pageAt(if (forward) 1 else -1) ?: return
        val a = Anim(Kind.CURL, forward, target, cur)
        a.dragging = true
        // Turning back works like forward in reverse: the corner of the previous page follows the mouse from
        // the far side, so it unfolds as the mouse moves towards the outer edge.
        a.corner = cornerFor(pressY < height / 2)
        a.pointer = clampPointer(e.x.toFloat(), e.y.toFloat())
        anim = a
        mode = Drag.CURL
        repaint()
    }

    private fun release(e: MouseEvent) {
        val m = mode
        mode = Drag.NONE
        when (m) {
            Drag.CURL -> {
                val a = anim ?: return
                a.dragging = false
                val rtl = controller.ui.value.rtl
                val sheet = sheet(true, rtl)
                val mid = sheet.x + sheet.width / 2
                val pastMiddle = if (rtl) a.pointer.x > mid else a.pointer.x < mid
                val far = if (rtl) sheet.x + sheet.width * 2 else sheet.x - sheet.width
                a.complete = if (a.forward) pastMiddle else !pastMiddle
                a.pointerFrom = Point2D.Float(a.pointer.x, a.pointer.y)
                // Forward: folded away means done. Backward: the previous page unfolds onto the page.
                val done = if (a.forward) a.complete else !a.complete
                a.pointerTo = if (done) Point2D.Float(far, a.corner.y) else Point2D.Float(a.corner.x, a.corner.y)
                a.duration = 0.28f / controller.settings.value.animSpeed.coerceIn(0.25f, 3f)
                a.start = System.nanoTime()
                timer.start()
            }
            Drag.SELECT -> controller.endSelection()
            Drag.PENDING_CURL, Drag.PENDING_SELECT -> if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                when (controller.onClick(e.x.toFloat(), e.y.toFloat(), width)) {
                    1 -> flip(true)
                    -1 -> flip(false)
                }
            }
            Drag.NONE -> {}
        }
    }

    private fun hover(e: MouseEvent) {
        onActivity?.invoke()
        val link = controller.linkUnder(e.x.toFloat(), e.y.toFloat())
        cursor = when {
            link != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            edgeZone(e.x) != 0 && controller.settings.value.clickToTurn -> Cursor.getDefaultCursor()
            controller.engine is com.readarea.desktop.reader.engine.TextEngine -> Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            else -> Cursor.getDefaultCursor()
        }
        toolTipText = link?.takeIf { it.contains("://") || it.startsWith("mailto:") }
    }

    private fun wheel(e: java.awt.event.MouseWheelEvent) {
        if (e.isControlDown || e.isMetaDown) {
            val s = controller.settings.value
            controller.updateSettings { it.copy(fontSize = (s.fontSize - e.preciseWheelRotation.toFloat()).coerceIn(10f, 48f)) }
            return
        }
        if (!controller.settings.value.wheelTurnsPages) return
        val now = System.currentTimeMillis()
        if (now - lastWheel < 260 || abs(e.preciseWheelRotation) < 0.3) return
        lastWheel = now
        flip(e.preciseWheelRotation > 0)
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }
}
