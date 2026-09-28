package com.readarea.desktop.reader

import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.reader.engine.PageChrome
import com.readarea.desktop.reader.engine.PagePos
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.abs

/**
 * Continuous scrolling: the book's pages laid end to end without breaks, scrolled by wheel, trackpad,
 * keyboard or by dragging. Reports the reading position as the page at the top and how far into it.
 */
class ScrollView(private val controller: ReaderController) : Widget() {
    private var anchor = PagePos(0, 0)
    private var offsetY = 0f
    private var autoSpeed = 0f
    private var lastTick = 0L
    private var dragY = -1
    private var pressX = 0
    private var pressY = 0
    private var moved = false
    private var velocity = 0f
    private var lastDragTime = 0L
    private val timer = Timer(12) { tick() }
    var onActivity: (() -> Unit)? = null

    init {
        isOpaque = true
        isFocusable = true
        val m = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                onActivity?.invoke()
                dragY = e.y
                pressX = e.x
                pressY = e.y
                moved = false
                velocity = 0f
                lastDragTime = System.nanoTime()
            }

            override fun mouseDragged(e: MouseEvent) {
                if (dragY < 0) return
                val dy = (dragY - e.y).toFloat()
                if (abs(e.y - pressY) + abs(e.x - pressX) > 4) moved = true
                if (moved) {
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    val now = System.nanoTime()
                    val dt = ((now - lastDragTime) / 1e9f).coerceAtLeast(0.001f)
                    velocity = velocity * 0.6f + (dy / dt) * 0.4f
                    lastDragTime = now
                    scrollBy(dy)
                }
                dragY = e.y
            }

            override fun mouseReleased(e: MouseEvent) {
                cursor = Cursor.getDefaultCursor()
                dragY = -1
                if (!moved && e.button == MouseEvent.BUTTON1) controller.onClick(e.x.toFloat(), e.y.toFloat(), width)
                else if (abs(velocity) > 400f) startTimer()
            }

            override fun mouseMoved(e: MouseEvent) {
                onActivity?.invoke()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                onActivity?.invoke()
                if (e.isControlDown || e.isMetaDown) {
                    val s = controller.settings.value
                    controller.updateSettings { it.copy(fontSize = (s.fontSize - e.preciseWheelRotation.toFloat()).coerceIn(10f, 48f)) }
                    return
                }
                scrollBy((e.preciseWheelRotation * 60).toFloat())
            }
        }
        addMouseListener(m)
        addMouseMotionListener(m)
        addMouseWheelListener(m)
    }

    private fun height(p: PagePos): Float = controller.engine?.scrollHeight(p)?.coerceAtLeast(1f) ?: height.toFloat()

    fun scrollTo(pos: PagePos, fraction: Float) {
        anchor = pos
        offsetY = height(pos) * fraction
        normalize()
        repaint()
    }

    fun scrollBy(dy: Float) {
        offsetY += dy
        normalize()
        repaint()
        report()
    }

    fun autoScroll(pxPerSecond: Float) {
        autoSpeed = pxPerSecond
        if (pxPerSecond > 0f) startTimer()
    }

    private fun startTimer() {
        lastTick = System.nanoTime()
        timer.start()
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = ((now - lastTick) / 1e9f).coerceIn(0f, 0.1f)
        lastTick = now
        var dy = autoSpeed * dt
        if (abs(velocity) > 20f && dragY < 0) {
            dy += velocity * dt
            velocity *= 0.94f
        } else if (dragY < 0) velocity = 0f
        if (dy != 0f) scrollBy(dy)
        if (autoSpeed <= 0f && velocity == 0f) timer.stop()
    }

    private fun normalize() {
        val e = controller.engine ?: return
        var guard = 0
        while (offsetY >= height(anchor) && guard++ < 500) {
            val next = e.next(anchor)
            if (next == null) {
                e.neighborPending(anchor, true)?.let { e.ensure(it) }
                val retry = e.next(anchor)
                if (retry == null) {
                    offsetY = (height(anchor) - height * 0.5f).coerceAtLeast(0f).coerceAtMost(offsetY)
                    if (autoSpeed > 0f) controller.onReachedEnd()
                    return
                }
                offsetY -= height(anchor)
                anchor = retry
                continue
            }
            offsetY -= height(anchor)
            anchor = next
        }
        guard = 0
        while (offsetY < 0f && guard++ < 500) {
            val prev = e.prev(anchor) ?: run {
                e.neighborPending(anchor, false)?.let { e.ensure(it) }
                e.prev(anchor)
            }
            if (prev == null) {
                offsetY = 0f
                return
            }
            anchor = prev
            offsetY += height(prev)
        }
    }

    private fun report() {
        val h = height(anchor)
        controller.onScrollPosition(anchor, (offsetY / h).coerceIn(0f, 0.999f))
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        PageChrome.setupQuality(g)
        val e = controller.engine
        val th = e?.theme
        val s = e?.setup
        if (e == null || th == null || s == null) {
            g.color = background ?: Color.WHITE
            g.fillRect(0, 0, width, height)
            g.dispose()
            return
        }
        if (e.fixed) {
            g.color = if (th.dark) Color(0x101010) else Color(0xE9E9E9)
            g.fillRect(0, 0, width, height)
        } else PageChrome.drawBackground(g, th, width, height)
        var y = -offsetY
        var p: PagePos? = anchor
        var guard = 0
        while (p != null && y < height && guard++ < 200) {
            val h = height(p)
            if (y + h > 0) {
                val saved = g.transform
                g.translate(0.0, y.toDouble())
                runCatching { e.drawScrollSlice(g, p, controller.deco) }
                g.transform = saved
            }
            y += h
            p = e.next(p)
        }
        val st = controller.settings.value
        PageChrome.drawNightOverlay(g, width, height, st.dim, st.warmth)
        g.dispose()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }
}
