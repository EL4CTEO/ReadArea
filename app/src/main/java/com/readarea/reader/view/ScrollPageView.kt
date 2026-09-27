package com.readarea.reader.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.readarea.reader.engine.PagePos
import kotlin.math.abs
import kotlin.math.hypot

class ScrollPageView(context: Context) : View(context) {

    interface Callback {
        fun next(pos: PagePos): PagePos?
        fun prev(pos: PagePos): PagePos?
        fun pageHeight(pos: PagePos): Float
        fun drawBackground(canvas: Canvas)
        fun drawSlice(canvas: Canvas, pos: PagePos)
        fun drawOverlay(canvas: Canvas)
        fun onPosition(pos: PagePos, fraction: Float)
        fun onTap(x: Float, y: Float)
        fun onUserActivity()
        fun onBrightnessDrag(delta: Float, done: Boolean)
        fun renderDetail(pos: PagePos, region: RectF, w: Int, h: Int, done: (Bitmap?) -> Unit) {}
        fun onReachedEnd() {}
    }

    var callback: Callback? = null
    var zoomEnabled = false
    var brightnessGesture = true
    var topPadding = 0f

    private var anchor = PagePos(0, 0)
    private var offset = 0f
    private var scale = 1f
    private var panX = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var brightness = false
    private var scaling = false
    private var lastScrollY = 0
    private var autoSpeed = 0f
    private var lastFrame = 0L
    private val detailPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var details: List<Pair<RectF, Bitmap>> = emptyList()
    private var detailToken = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!zoomEnabled) return false
            scaling = true
            clearDetails()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val s1 = scale
            val s2 = (scale * detector.scaleFactor).coerceIn(1f, 4f)
            val fx = detector.focusX
            val fy = detector.focusY
            val uy = offset + fy / s1
            val ux = (fx - panX) / s1
            scale = s2
            offset = uy - fy / s2
            panX = fx - ux * s2
            clampPan()
            normalize()
            invalidate()
            return true
        }
    })

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (autoSpeed <= 0f) return
            val now = frameTimeNanos / 1_000_000
            if (lastFrame != 0L) {
                val dt = (now - lastFrame).coerceAtMost(64)
                if (!dragging && !scaling) {
                    val moved = scrollContent(autoSpeed * dt / 1000f)
                    if (moved == 0f) {
                        autoSpeed = 0f
                        callback?.onReachedEnd()
                        return
                    }
                }
            }
            lastFrame = now
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setAutoScroll(pxPerSecond: Float) {
        autoSpeed = pxPerSecond
        lastFrame = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (pxPerSecond > 0f) Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun smoothScrollBy(dy: Float) {
        scroller.forceFinished(true)
        lastScrollY = 0
        scroller.startScroll(0, 0, 0, (dy * scale).toInt(), 380)
        postInvalidateOnAnimation()
    }

    fun setDetailFilter(filter: android.graphics.ColorFilter?) {
        detailPaint.colorFilter = filter
        invalidate()
    }

    fun setPosition(pos: PagePos, fraction: Float) {
        anchor = pos
        val h = callback?.pageHeight(pos) ?: 0f
        offset = (h * fraction).coerceAtLeast(0f)
        normalize()
        clearDetails()
        invalidate()
    }

    fun resetZoom() {
        scale = 1f
        panX = 0f
        clearDetails()
        invalidate()
    }

    val position: Pair<PagePos, Float>
        get() {
            val h = callback?.pageHeight(anchor) ?: 1f
            return anchor to if (h > 0) (offset / h).coerceIn(0f, 1f) else 0f
        }

    private fun clampPan() {
        val minPan = width - width * scale
        panX = panX.coerceIn(minPan, 0f)
    }

    private fun normalize() {
        val cb = callback ?: return
        var guard = 0
        while (offset < 0 && guard++ < 500) {
            val p = cb.prev(anchor)
            if (p == null) {
                offset = 0f
                break
            }
            anchor = p
            offset += cb.pageHeight(p)
        }
        guard = 0
        while (guard++ < 500) {
            val h = cb.pageHeight(anchor)
            if (offset < h) break
            val n = cb.next(anchor) ?: break
            offset -= h
            anchor = n
        }
    }

    private fun remainingBelow(limit: Float): Float {
        val cb = callback ?: return 0f
        var total = cb.pageHeight(anchor) - offset
        var p: PagePos? = anchor
        val viewport = height / scale
        while (total < viewport + limit) {
            p = p?.let { cb.next(it) } ?: break
            total += cb.pageHeight(p)
        }
        return (total - viewport).coerceAtLeast(0f)
    }

    private fun scrollContent(dy: Float): Float {
        var d = dy
        if (d > 0) d = minOf(d, remainingBelow(d))
        if (d == 0f) return 0f
        val before = anchor to offset
        offset += d
        normalize()
        if (anchor == before.first && offset == before.second) return 0f
        report()
        invalidate()
        return d
    }

    private fun report() {
        val cb = callback ?: return
        val h = cb.pageHeight(anchor)
        cb.onPosition(anchor, if (h > 0) (offset / h).coerceIn(0f, 1f) else 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val cb = callback ?: return
        cb.drawBackground(canvas)
        canvas.save()
        canvas.translate(panX, 0f)
        canvas.scale(scale, scale)
        var y = -offset + topPadding / scale
        var pos: PagePos? = anchor
        val limit = height / scale
        var guard = 0
        while (pos != null && y < limit && guard++ < 60) {
            val h = cb.pageHeight(pos)
            if (y + h > 0) {
                canvas.save()
                canvas.translate(0f, y)
                cb.drawSlice(canvas, pos)
                canvas.restore()
            }
            y += h
            pos = cb.next(pos)
        }
        canvas.restore()
        for ((r, b) in details) canvas.drawBitmap(b, null, r, detailPaint)
        cb.drawOverlay(canvas)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            val dy = y - lastScrollY
            lastScrollY = y
            val moved = scrollContent(dy / scale)
            if (moved == 0f && dy != 0) scroller.forceFinished(true)
            postInvalidateOnAnimation()
        } else if (!dragging && scale > 1.05f && details.isEmpty()) {
            scheduleDetails()
        }
    }

    private fun clearDetails() {
        detailToken++
        details.forEach { it.second.recycle() }
        details = emptyList()
    }

    private fun scheduleDetails() {
        val cb = callback ?: return
        if (!zoomEnabled || width == 0) return
        val token = ++detailToken
        postDelayed({
            if (token != detailToken || dragging || scaling) return@postDelayed
            var y = -offset + topPadding / scale
            var pos: PagePos? = anchor
            val limit = height / scale
            val out = ArrayList<Pair<RectF, Bitmap>>()
            var pending = 0
            val jobs = ArrayList<Triple<PagePos, RectF, RectF>>()
            while (pos != null && y < limit) {
                val h = cb.pageHeight(pos)
                val pageTopScreen = y * scale
                val pageBottomScreen = (y + h) * scale
                val visTop = maxOf(0f, pageTopScreen)
                val visBottom = minOf(height.toFloat(), pageBottomScreen)
                if (visBottom > visTop) {
                    val pageW = width * scale
                    val left = maxOf(0f, panX)
                    val right = minOf(width.toFloat(), panX + pageW)
                    val region = RectF((left - panX) / pageW, (visTop - pageTopScreen) / (h * scale), (right - panX) / pageW, (visBottom - pageTopScreen) / (h * scale))
                    jobs.add(Triple(pos, region, RectF(left, visTop, right, visBottom)))
                }
                y += h
                pos = cb.next(pos)
            }
            pending = jobs.size
            for ((p, region, screen) in jobs) {
                cb.renderDetail(p, region, screen.width().toInt(), screen.height().toInt()) { bmp ->
                    post {
                        if (token == detailToken && bmp != null) {
                            out.add(screen to bmp)
                            pending--
                            if (pending <= 0) {
                                details = out
                                invalidate()
                            }
                        } else bmp?.recycle()
                    }
                }
            }
        }, 180)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cb = callback ?: return false
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cb.onUserActivity()
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                dragging = false
                brightness = false
                scaling = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = true
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (scaling || e.pointerCount > 1) {
                    lastX = e.x
                    lastY = e.y
                    return true
                }
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (!dragging && !brightness && hypot(e.x - downX, e.y - downY) > touchSlop) {
                    if (brightnessGesture && downX < width * 0.12f && abs(e.y - downY) > abs(e.x - downX) && scale <= 1.01f && autoSpeed == 0f) brightness = true else {
                        dragging = true
                        clearDetails()
                    }
                }
                if (brightness) cb.onBrightnessDrag(-dy / (height * 0.7f), false)
                if (dragging) {
                    if (scale > 1f) {
                        panX += dx
                        clampPan()
                    }
                    scrollContent(-dy / scale)
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000, maxFling)
                val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                when {
                    brightness -> cb.onBrightnessDrag(0f, true)
                    dragging && !scaling -> {
                        lastScrollY = 0
                        scroller.fling(0, 0, 0, (-vy).toInt(), 0, 0, -1_000_000, 1_000_000)
                        postInvalidateOnAnimation()
                    }
                    !scaling && e.actionMasked == MotionEvent.ACTION_UP -> cb.onTap(e.x, e.y)
                }
                if (scale > 1.05f) scheduleDetails()
                dragging = false
                brightness = false
                scaling = false
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setAutoScroll(0f)
        clearDetails()
    }
}
