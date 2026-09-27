package com.readarea.reader.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.Magnifier
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

enum class FlipMode { CURL, SLIDE, COVER, FADE, NONE }

class PageFlipView(context: Context) : View(context) {

    interface Callback {
        fun canFlip(forward: Boolean): Boolean
        fun drawPage(offset: Int, canvas: Canvas)
        fun onFlipped(forward: Boolean)
        fun onTap(x: Float, y: Float)
        fun onLongPress(x: Float, y: Float)
        fun onSelectionDrag(handle: Int, x: Float, y: Float)
        fun onSelectionDragEnd()
        fun onBrightnessDrag(delta: Float, done: Boolean)
        fun onUserActivity()
        fun onFlipBlocked(forward: Boolean)
    }

    var callback: Callback? = null
    var mode: FlipMode = FlipMode.CURL
    var animSpeed: Float = 1f
    var brightnessGesture: Boolean = true
    var pageBackground: Int = Color.WHITE
    var selectionColor: Int = 0x552F6FDB
    var handleColor: Int = 0xFF2F6FDB.toInt()
    var spread: Boolean = false

    private var bmPrev: Bitmap? = null
    private var bmCur: Bitmap? = null
    private var bmNext: Bitmap? = null
    private var validPrev = false
    private var validCur = false
    private var validNext = false

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 2f
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private enum class State { IDLE, PRESSED, DRAG, ANIM, BRIGHTNESS, SELECTING, HANDLE, IGNORE }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var forward = true
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private var dragHandle = -1
    private var longPressed = false
    private var magnifier: Magnifier? = null

    private var selectionPath: Path? = null
    private var handleStart: PointF? = null
    private var handleEnd: PointF? = null
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePath = Path()

    private val curl = Curl()
    private var offsetX = 0f
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val longPress = Runnable {
        if (state == State.PRESSED) {
            longPressed = true
            state = State.SELECTING
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            callback?.onLongPress(downX, downY)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        recycle()
        bmPrev = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmCur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmNext = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        invalidatePages()
    }

    private fun recycle() {
        bmPrev?.recycle()
        bmCur?.recycle()
        bmNext?.recycle()
        bmPrev = null
        bmCur = null
        bmNext = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        magnifier?.dismiss()
        recycle()
    }

    fun invalidatePages() {
        validPrev = false
        validCur = false
        validNext = false
        invalidate()
    }

    fun invalidateNeighbors() {
        validPrev = false
        validNext = false
    }

    fun refreshCurrent() {
        validCur = false
        invalidate()
    }

    private fun ensure(offset: Int): Bitmap? {
        val cb = callback ?: return null
        return when (offset) {
            0 -> bmCur?.also { if (!validCur) { cb.drawPage(0, Canvas(it)); validCur = true } }
            1 -> bmNext?.also { if (!validNext) { cb.drawPage(1, Canvas(it)); validNext = true } }
            else -> bmPrev?.also { if (!validPrev) { cb.drawPage(-1, Canvas(it)); validPrev = true } }
        }
    }

    fun setSelection(path: Path?, start: PointF?, end: PointF?) {
        selectionPath = path
        handleStart = start
        handleEnd = end
        invalidate()
    }

    val hasSelection: Boolean get() = selectionPath != null

    val isBusy: Boolean get() = state == State.ANIM || state == State.DRAG

    override fun onDraw(canvas: Canvas) {
        val cur = ensure(0) ?: return
        when (state) {
            State.DRAG, State.ANIM -> drawTransition(canvas, cur)
            else -> canvas.drawBitmap(cur, 0f, 0f, null)
        }
        selectionPath?.let { p ->
            selPaint.color = selectionColor
            canvas.drawPath(p, selPaint)
            handlePaint.color = handleColor
            handleStart?.let { drawHandle(canvas, it, true) }
            handleEnd?.let { drawHandle(canvas, it, false) }
        }
    }

    private fun drawHandle(canvas: Canvas, p: PointF, start: Boolean) {
        val r = 10 * density
        handlePath.reset()
        if (start) {
            handlePath.addCircle(p.x - r, p.y + r, r, Path.Direction.CW)
            handlePath.addRect(p.x - r, p.y, p.x, p.y + r, Path.Direction.CW)
        } else {
            handlePath.addCircle(p.x + r, p.y + r, r, Path.Direction.CW)
            handlePath.addRect(p.x, p.y, p.x + r, p.y + r, Path.Direction.CW)
        }
        canvas.drawPath(handlePath, handlePaint)
    }

    private fun drawTransition(canvas: Canvas, cur: Bitmap) {
        val other = if (forward) ensure(1) else ensure(-1)
        if (other == null) {
            canvas.drawBitmap(cur, 0f, 0f, null)
            return
        }
        val w = width.toFloat()
        when (mode) {
            FlipMode.CURL -> {
                if (forward) curl.draw(canvas, cur, other) else curl.draw(canvas, other, cur)
            }
            FlipMode.SLIDE -> {
                if (forward) {
                    canvas.drawBitmap(cur, offsetX, 0f, null)
                    canvas.drawBitmap(other, offsetX + w, 0f, null)
                } else {
                    canvas.drawBitmap(other, offsetX - w, 0f, null)
                    canvas.drawBitmap(cur, offsetX, 0f, null)
                }
            }
            FlipMode.COVER -> {
                if (forward) {
                    canvas.drawBitmap(other, 0f, 0f, null)
                    canvas.drawBitmap(cur, offsetX, 0f, null)
                    drawEdgeShadow(canvas, offsetX + w)
                } else {
                    canvas.drawBitmap(cur, 0f, 0f, null)
                    canvas.drawBitmap(other, offsetX - w, 0f, null)
                    drawEdgeShadow(canvas, offsetX)
                }
            }
            FlipMode.FADE, FlipMode.NONE -> {
                val p = (abs(offsetX) / w).coerceIn(0f, 1f)
                canvas.drawBitmap(other, 0f, 0f, null)
                fadePaint.alpha = ((1f - p) * 255).toInt()
                canvas.drawBitmap(cur, 0f, 0f, fadePaint)
            }
        }
    }

    private fun drawEdgeShadow(canvas: Canvas, x: Float) {
        val sw = 18 * density
        shadowPaint.shader = LinearGradient(x, 0f, x + sw, 0f, 0x44000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(x, 0f, x + sw, height.toFloat(), shadowPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cb = callback ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cb.onUserActivity()
                if (state == State.ANIM) animator?.end()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x
                downY = e.y
                lastY = e.y
                longPressed = false
                dragHandle = if (selectionPath != null) hitHandle(e.x, e.y) else -1
                state = if (dragHandle >= 0) State.HANDLE else State.PRESSED
                if (state == State.PRESSED) postDelayed(longPress, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                when (state) {
                    State.PRESSED -> {
                        if (hypot(dx, dy) > touchSlop) {
                            removeCallbacks(longPress)
                            if (selectionPath != null) {
                                state = State.IGNORE
                            } else if (brightnessGesture && downX < width * 0.12f && abs(dy) > abs(dx) * 1.4f) {
                                state = State.BRIGHTNESS
                                lastY = e.y
                            } else if (abs(dx) > abs(dy) * 0.6f) {
                                val fwd = dx < 0
                                if (cb.canFlip(fwd)) {
                                    startDrag(fwd, e.x, e.y)
                                } else {
                                    cb.onFlipBlocked(fwd)
                                    state = State.IGNORE
                                }
                            } else {
                                state = State.IGNORE
                            }
                        }
                    }
                    State.DRAG -> updateDrag(e.x, e.y)
                    State.BRIGHTNESS -> {
                        cb.onBrightnessDrag(-(e.y - lastY) / (height * 0.7f), false)
                        lastY = e.y
                    }
                    State.SELECTING -> {
                        cb.onSelectionDrag(1, e.x, e.y)
                        showMagnifier(e.x, e.y)
                    }
                    State.HANDLE -> {
                        cb.onSelectionDrag(dragHandle, e.x, e.y - 18 * density)
                        showMagnifier(e.x, e.y - 18 * density)
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                magnifier?.dismiss()
                when (state) {
                    State.PRESSED -> {
                        state = State.IDLE
                        if (e.actionMasked == MotionEvent.ACTION_UP) cb.onTap(e.x, e.y)
                    }
                    State.DRAG -> release(e.x, e.y, vx, e.actionMasked == MotionEvent.ACTION_CANCEL)
                    State.BRIGHTNESS -> {
                        state = State.IDLE
                        cb.onBrightnessDrag(0f, true)
                    }
                    State.SELECTING, State.HANDLE -> {
                        state = State.IDLE
                        cb.onSelectionDragEnd()
                    }
                    else -> state = State.IDLE
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 29) return
        val m = magnifier ?: Magnifier.Builder(this).build().also { magnifier = it }
        m.show(x, y)
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val r = 10 * density
        val tol = 30 * density
        handleStart?.let { if (hypot(x - (it.x - r), y - (it.y + r)) < tol) return 0 }
        handleEnd?.let { if (hypot(x - (it.x + r), y - (it.y + r)) < tol) return 1 }
        return -1
    }

    private fun startDrag(fwd: Boolean, x: Float, y: Float) {
        forward = fwd
        state = State.DRAG
        val h = height.toFloat()
        if (mode == FlipMode.CURL) {
            curl.cornerX = sheetWidth()
            curl.cornerY = if (downY < h * 0.33f && fwd) 0f else h
            curl.flat = downY in h * 0.33f..h * 0.67f
        }
        ensure(if (fwd) 1 else -1)
        updateDrag(x, y)
    }

    private fun sheetWidth(): Float = if (spread) width / 2f else width.toFloat()

    private fun updateDrag(x: Float, y: Float) {
        val h = height.toFloat()
        val dx = x - downX
        if (mode == FlipMode.CURL) {
            val w = sheetWidth()
            if (forward) {
                curl.touchX = (w + dx).coerceIn(-w, w - 0.5f)
                curl.touchY = if (curl.flat) h - 0.5f else (curl.cornerY + (y - downY)).coerceIn(-h * 0.2f, h * 1.2f)
            } else {
                curl.touchX = (-w + dx * 2f).coerceIn(-w, w - 0.5f)
                curl.touchY = if (curl.flat) h - 0.5f else (curl.cornerY + (y - downY) * 0.5f).coerceIn(-h * 0.2f, h * 1.2f)
            }
            if (curl.cornerY == 0f && curl.touchY < 0.5f && !curl.flat) curl.touchY = 0.5f
            if (curl.cornerY == h && curl.touchY > h - 0.5f) curl.touchY = h - 0.5f
        } else {
            val w = width.toFloat()
            offsetX = if (forward) dx.coerceIn(-w, 0f) else dx.coerceIn(0f, w)
        }
        invalidate()
    }

    private fun release(x: Float, y: Float, vx: Float, cancelled: Boolean) {
        val w = width.toFloat()
        val dx = x - downX
        val complete = !cancelled && if (forward) {
            vx < -minFling || (vx <= minFling && dx < -w * 0.12f)
        } else {
            vx > minFling || (vx >= -minFling && dx > w * 0.12f)
        }
        animateTo(complete)
    }

    fun flip(fwd: Boolean): Boolean {
        val cb = callback ?: return false
        if (state == State.ANIM) animator?.end()
        if (state != State.IDLE && state != State.ANIM) return false
        if (!cb.canFlip(fwd)) {
            cb.onFlipBlocked(fwd)
            return false
        }
        cb.onUserActivity()
        forward = fwd
        ensure(if (fwd) 1 else -1)
        if (mode == FlipMode.NONE) {
            finish(true)
            return true
        }
        val w = sheetWidth()
        val h = height.toFloat()
        state = State.DRAG
        if (mode == FlipMode.CURL) {
            curl.cornerX = w
            curl.cornerY = h
            curl.flat = false
            if (fwd) {
                curl.touchX = w - 0.5f
                curl.touchY = h * 0.96f
            } else {
                curl.touchX = -w
                curl.touchY = h * 0.96f
            }
        } else {
            offsetX = 0f
        }
        animateTo(true)
        return true
    }

    private fun animateTo(complete: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        state = State.ANIM
        animator?.cancel()
        if (mode == FlipMode.NONE) {
            finish(complete)
            return
        }
        val speed = animSpeed.coerceIn(0.3f, 3f)
        if (mode == FlipMode.CURL) {
            val sw = sheetWidth()
            val sx = curl.touchX
            val sy = curl.touchY
            val tx: Float
            val ty: Float
            if (forward == complete) {
                tx = -sw * 1.05f
                ty = if (curl.flat) h - 0.5f else curl.cornerY + (if (curl.cornerY == 0f) 0.5f else -0.5f)
            } else {
                tx = sw - 0.5f
                ty = if (curl.flat) h - 0.5f else curl.cornerY + (if (curl.cornerY == 0f) 0.5f else -0.5f)
            }
            val dist = abs(tx - sx) / (2 * sw)
            val duration = ((260 + 420 * dist) / speed).toLong()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    val t = it.animatedValue as Float
                    curl.touchX = sx + (tx - sx) * t
                    curl.touchY = sy + (ty - sy) * t
                    invalidate()
                }
                addListener(endListener(complete))
                start()
            }
        } else {
            val start = offsetX
            val end = if (complete) (if (forward) -w else w) else 0f
            val duration = ((180 + 260 * abs(end - start) / w) / speed).toLong()
            animator = ValueAnimator.ofFloat(start, end).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    offsetX = it.animatedValue as Float
                    invalidate()
                }
                addListener(endListener(complete))
                start()
            }
        }
    }

    private fun endListener(complete: Boolean) = object : AnimatorListenerAdapter() {
        private var done = false
        override fun onAnimationEnd(animation: Animator) {
            if (done) return
            done = true
            finish(complete)
        }

        override fun onAnimationCancel(animation: Animator) {
            if (done) return
            done = true
            finish(complete)
        }
    }

    private fun finish(complete: Boolean) {
        state = State.IDLE
        offsetX = 0f
        if (complete) {
            if (forward) {
                val t = bmPrev
                bmPrev = bmCur
                bmCur = bmNext
                bmNext = t
                validPrev = validCur
                validCur = validNext
                validNext = false
            } else {
                val t = bmNext
                bmNext = bmCur
                bmCur = bmPrev
                bmPrev = t
                validNext = validCur
                validCur = validPrev
                validPrev = false
            }
            callback?.onFlipped(forward)
        }
        invalidate()
        post { if (state == State.IDLE) { ensure(1); ensure(-1) } }
    }

    private inner class Curl {
        var cornerX = 0f
        var cornerY = 0f
        var touchX = 0f
        var touchY = 0f
        var flat = false
        private var tx = 0f
        private var ty = 0f
        private var mx = 0f
        private var my = 0f
        private var c1x = 0f
        private var c1y = 0f
        private var c2x = 0f
        private var c2y = 0f
        private var s1x = 0f
        private var s1y = 0f
        private var s2x = 0f
        private var s2y = 0f
        private var e1x = 0f
        private var e1y = 0f
        private var e2x = 0f
        private var e2y = 0f
        private var v1x = 0f
        private var v1y = 0f
        private var v2x = 0f
        private var v2y = 0f
        private var dist = 0f
        private val path0 = Path()
        private val path1 = Path()
        private val matrix = Matrix()
        private val values = FloatArray(9)
        private val backPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 38 }
        private val folderShadowRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0x00333333, 0xB0333333.toInt()))
        private val folderShadowLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00333333, 0xB0333333.toInt()))
        private val backShadowRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0xAA111111.toInt(), 0x00111111))
        private val backShadowLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xAA111111.toInt(), 0x00111111))
        private val frontShadowVLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x70111111, 0x00111111))
        private val frontShadowVRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0x70111111, 0x00111111))
        private val frontShadowHTB = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x70111111, 0x00111111))
        private val frontShadowHBT = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x70111111, 0x00111111))

        private fun base() {
            mx = (tx + cornerX) / 2f
            my = (ty + cornerY) / 2f
            var dxm = cornerX - mx
            if (abs(dxm) < 0.01f) dxm = 0.01f
            val dym = cornerY - my
            c1x = mx - dym * dym / dxm
            c1y = cornerY
            c2x = cornerX
            c2y = if (abs(dym) < 0.01f) my - dxm * dxm / 0.1f else my - dxm * dxm / dym
            s1x = c1x - (cornerX - c1x) / 2f
            s1y = cornerY
        }

        private fun compute(): Boolean {
            tx = touchX
            ty = touchY
            if (hypot(tx - cornerX, ty - cornerY) < 1f) return false
            val w = sheetWidth()
            base()
            if (tx > 0 && tx < w && (s1x < 0 || s1x > w)) {
                if (s1x < 0) s1x = w - s1x
                val f1 = abs(cornerX - tx)
                val f2 = w * f1 / s1x
                tx = abs(cornerX - f2)
                val f3 = abs(cornerX - tx) * abs(cornerY - ty) / f1
                ty = abs(cornerY - f3)
                base()
            }
            s2x = cornerX
            s2y = c2y - (cornerY - c2y) / 2f
            dist = hypot(tx - cornerX, ty - cornerY)
            intersect(tx, ty, c1x, c1y, s1x, s1y, s2x, s2y).let { e1x = it.x; e1y = it.y }
            intersect(tx, ty, c2x, c2y, s1x, s1y, s2x, s2y).let { e2x = it.x; e2y = it.y }
            v1x = (s1x + 2 * c1x + e1x) / 4f
            v1y = (2 * c1y + s1y + e1y) / 4f
            v2x = (s2x + 2 * c2x + e2x) / 4f
            v2y = (2 * c2y + s2y + e2y) / 4f
            return listOf(c1x, c2y, s1x, s2y, e1x, e1y, e2x, e2y).all { it.isFinite() }
        }

        private val tmp = PointF()

        private fun intersect(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float): PointF {
            val d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
            if (abs(d) < 1e-4f) {
                tmp.set(x2, y2)
                return tmp
            }
            val a = x1 * y2 - y1 * x2
            val b = x3 * y4 - y3 * x4
            tmp.set((a * (x3 - x4) - (x1 - x2) * b) / d, (a * (y3 - y4) - (y1 - y2) * b) / d)
            return tmp
        }

        fun draw(canvas: Canvas, top: Bitmap, bottom: Bitmap) {
            val ox = if (spread) width / 2f else 0f
            val h = height.toFloat()
            if (spread) {
                canvas.save()
                canvas.clipRect(0f, 0f, ox, h)
                canvas.drawBitmap(top, 0f, 0f, null)
                canvas.restore()
            }
            canvas.save()
            canvas.translate(ox, 0f)
            drawSheet(canvas, top, bottom, ox)
            canvas.restore()
            if (spread) {
                spinePaint.shader = LinearGradient(ox - 14 * density, 0f, ox + 14 * density, 0f, intArrayOf(0x00000000, 0x22000000, 0x00000000), null, Shader.TileMode.CLAMP)
                canvas.drawRect(ox - 14 * density, 0f, ox + 14 * density, h, spinePaint)
            }
        }

        private val spinePaint = Paint()
        private val sheetPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        private fun drawSheet(canvas: Canvas, top: Bitmap, bottom: Bitmap, ox: Float) {
            val w = sheetWidth()
            val h = height.toFloat()
            if (!compute()) {
                canvas.save()
                canvas.clipRect(0f, 0f, w, h)
                canvas.drawBitmap(top, -ox, 0f, null)
                canvas.restore()
                return
            }
            val maxLen = hypot(w, h)
            val rtlb = cornerY == 0f
            val sh = 10f * density

            path0.reset()
            path0.moveTo(s1x, s1y)
            path0.quadTo(c1x, c1y, e1x, e1y)
            path0.lineTo(tx, ty)
            path0.lineTo(e2x, e2y)
            path0.quadTo(c2x, c2y, s2x, s2y)
            path0.lineTo(cornerX, cornerY)
            path0.close()

            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.clipOutPath(path0)
            canvas.drawBitmap(top, -ox, 0f, null)
            canvas.restore()

            path1.reset()
            path1.moveTo(s1x, s1y)
            path1.lineTo(v1x, v1y)
            path1.lineTo(v2x, v2y)
            path1.lineTo(s2x, s2y)
            path1.lineTo(cornerX, cornerY)
            path1.close()
            val deg = Math.toDegrees(atan2((c1x - cornerX).toDouble(), (c2y - cornerY).toDouble())).toFloat()
            canvas.save()
            canvas.clipPath(path0)
            canvas.clipPath(path1)
            canvas.clipRect(0f, 0f, w, h)
            canvas.drawBitmap(bottom, -ox, 0f, null)
            canvas.rotate(deg, s1x, s1y)
            val back = if (rtlb) backShadowLR else backShadowRL
            if (rtlb) back.setBounds(s1x.toInt(), s1y.toInt(), (s1x + dist / 4).toInt(), (maxLen + s1y).toInt())
            else back.setBounds((s1x - dist / 4).toInt(), s1y.toInt(), s1x.toInt(), (maxLen + s1y).toInt())
            back.draw(canvas)
            canvas.restore()

            val deg2 = if (rtlb) Math.PI / 4 - atan2((c1y - ty).toDouble(), (tx - c1x).toDouble()) else Math.PI / 4 - atan2((ty - c1y).toDouble(), (tx - c1x).toDouble())
            val d1 = (sh * 1.414 * cos(deg2)).toFloat()
            val d2 = (sh * 1.414 * sin(deg2)).toFloat()
            val px = tx + d1
            val py = if (rtlb) ty + d2 else ty - d2
            path1.reset()
            path1.moveTo(px, py)
            path1.lineTo(tx, ty)
            path1.lineTo(c1x, c1y)
            path1.lineTo(s1x, s1y)
            path1.close()
            canvas.save()
            canvas.clipOutPath(path0)
            canvas.clipPath(path1)
            val fv = if (rtlb) frontShadowVLR else frontShadowVRL
            canvas.rotate(Math.toDegrees(atan2((tx - c1x).toDouble(), (c1y - ty).toDouble())).toFloat(), c1x, c1y)
            if (rtlb) fv.setBounds(c1x.toInt(), (c1y - maxLen).toInt(), (c1x + sh).toInt(), c1y.toInt())
            else fv.setBounds((c1x - sh).toInt(), (c1y - maxLen).toInt(), (c1x + 1).toInt(), c1y.toInt())
            fv.draw(canvas)
            canvas.restore()

            path1.reset()
            path1.moveTo(px, py)
            path1.lineTo(tx, ty)
            path1.lineTo(c2x, c2y)
            path1.lineTo(s2x, s2y)
            path1.close()
            canvas.save()
            canvas.clipOutPath(path0)
            canvas.clipPath(path1)
            val fh = if (rtlb) frontShadowHTB else frontShadowHBT
            canvas.rotate(Math.toDegrees(atan2((c2y - ty).toDouble(), (c2x - tx).toDouble())).toFloat(), c2x, c2y)
            val temp = if (c2y < 0) c2y - h else c2y
            val hmg = hypot(c2x, temp)
            val ly = if (rtlb) c2y else c2y - sh
            val ry = if (rtlb) c2y + sh else c2y + 1
            if (hmg > maxLen) fh.setBounds((c2x - sh - hmg).toInt(), ly.toInt(), (c2x + maxLen - hmg).toInt(), ry.toInt())
            else fh.setBounds((c2x - maxLen).toInt(), ly.toInt(), c2x.toInt(), ry.toInt())
            fh.draw(canvas)
            canvas.restore()

            val f1 = abs((s1x + c1x) / 2 - c1x)
            val f2 = abs((s2y + c2y) / 2 - c2y)
            val f3 = min(f1, f2)
            path1.reset()
            path1.moveTo(v2x, v2y)
            path1.lineTo(v1x, v1y)
            path1.lineTo(e1x, e1y)
            path1.lineTo(tx, ty)
            path1.lineTo(e2x, e2y)
            path1.close()
            canvas.save()
            canvas.clipPath(path0)
            canvas.clipPath(path1)
            canvas.drawColor(pageBackground)
            val dis = hypot(cornerX - c1x, c2y - cornerY)
            if (dis > 0.1f) {
                val f8 = (cornerX - c1x) / dis
                val f9 = (c2y - cornerY) / dis
                values[0] = 1 - 2 * f9 * f9
                values[1] = 2 * f8 * f9
                values[2] = 0f
                values[3] = values[1]
                values[4] = 1 - 2 * f8 * f8
                values[5] = 0f
                values[6] = 0f
                values[7] = 0f
                values[8] = 1f
                matrix.reset()
                matrix.setValues(values)
                matrix.preTranslate(-c1x, -c1y)
                matrix.postTranslate(c1x, c1y)
                if (spread) {
                    matrix.preScale(-1f, 1f)
                    matrix.preTranslate(-ox, 0f)
                    canvas.drawBitmap(bottom, matrix, sheetPaint)
                } else {
                    canvas.drawBitmap(top, matrix, backPaint)
                }
            }
            canvas.rotate(deg, s1x, s1y)
            val fold = if (rtlb) folderShadowLR else folderShadowRL
            if (rtlb) fold.setBounds((s1x - 1).toInt(), s1y.toInt(), (s1x + f3 + 1).toInt(), (s1y + maxLen).toInt())
            else fold.setBounds((s1x - f3 - 1).toInt(), s1y.toInt(), (s1x + 1).toInt(), (s1y + maxLen).toInt())
            fold.draw(canvas)
            canvas.restore()
        }
    }
}
