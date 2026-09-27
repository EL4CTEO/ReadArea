package com.readarea.qa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.readarea.ReadAreaApp
import com.readarea.data.AppSettings
import com.readarea.data.ReaderSettings
import com.readarea.data.db.BookEntity
import com.readarea.reader.ReaderActivity
import com.readarea.reader.engine.PagePos
import com.readarea.reader.engine.TextEngine
import com.readarea.reader.view.PageFlipView
import com.readarea.reader.view.ScrollPageView
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

@OptIn(ExperimentalTestApi::class)
abstract class ReaderQa {
    @get:Rule
    val compose = createEmptyComposeRule()
    protected val app: ReadAreaApp get() = ApplicationProvider.getApplicationContext()
    protected val out = File("build/screens/qa").apply { mkdirs() }
    protected var bookId = 0L

    @Before
    fun prepareReader() {
        com.readarea.TestIsolation.reset()
        runBlocking {
            app.settings.updateApp { AppSettings(askedDeviceScan = true) }
            app.settings.updateReader { ReaderSettings() }
        }
        val png = QaBooks.image(40, 30, Color.RED)
        BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inJustDecodeBounds = true })
        BitmapFactory.decodeByteArray(png, 0, png.size)
        bookId = addBook("harbour.epub", QaBooks.realisticEpub(), "EPUB", "The Harbour Light", "Mara Quill")
    }

    protected fun addBook(name: String, bytes: ByteArray, format: String, title: String, author: String = ""): Long = runBlocking {
        val f = File(app.filesDir, name).apply { writeBytes(bytes) }
        app.database.books().insert(BookEntity(uri = Uri.fromFile(f).toString(), fileName = f.name, format = format, size = f.length(), title = title, author = author, metaLoaded = true))
    }

    protected fun idle(ms: Long = 600) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    protected fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        idle(400)
    }

    protected fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    protected fun <T> ActivityScenario<ReaderActivity>.get(block: (ReaderActivity) -> T): T {
        var v: T? = null
        onActivity { v = block(it) }
        @Suppress("UNCHECKED_CAST")
        return v as T
    }

    protected fun ActivityScenario<ReaderActivity>.waitFor(timeout: Long = 20_000, what: String = "", cond: (ReaderActivity) -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
            if (get(cond)) return
            Thread.sleep(40)
        }
        throw AssertionError("$what: condition not met in time: " + get { a -> a.vm.ui.value.let { u -> "loading=${u.loading} error=${u.error} laidOut=${u.laidOut} panel=${u.panel} menu=${u.menu} pos=${a.vm.pos}" } })
    }

    protected fun waitUntil(timeout: Long = 10_000, what: String = "", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
            if (cond()) return
            Thread.sleep(40)
        }
        throw AssertionError("$what: condition not met in time")
    }

    protected fun open(id: Long = bookId): ActivityScenario<ReaderActivity> {
        val sc = ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id))
        sc.waitFor(what = "book laid out") { it.vm.ui.value.laidOut && !it.vm.ui.value.loading }
        idle()
        return sc
    }

    protected fun shot(name: String): Bitmap {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    protected fun pageView(sc: ActivityScenario<ReaderActivity>): View = sc.get { a ->
        fun find(v: View): View? {
            if (v is PageFlipView || v is ScrollPageView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(a.window.decorView)!!
    }

    protected fun pageOrigin(sc: ActivityScenario<ReaderActivity>): Pair<Int, Int> {
        val v = pageView(sc)
        val loc = IntArray(2)
        sc.onActivity { v.getLocationInWindow(loc) }
        return loc[0] to loc[1]
    }

    private fun event(v: View, action: Int, down: Long, t: Long, x: Float, y: Float): Boolean {
        val e = MotionEvent.obtain(down, t, action, x, y, 0)
        val r = v.dispatchTouchEvent(e)
        e.recycle()
        return r
    }

    protected fun tap(sc: ActivityScenario<ReaderActivity>, x: Float, y: Float) {
        val v = pageView(sc)
        val t = SystemClock.uptimeMillis()
        sc.onActivity {
            event(v, MotionEvent.ACTION_DOWN, t, t, x, y)
            event(v, MotionEvent.ACTION_UP, t, t + 60, x, y)
        }
        advance(900)
        idle(200)
    }

    protected fun swipe(sc: ActivityScenario<ReaderActivity>, x0: Float, x1: Float, y: Float, y1: Float = y, steps: Int = 10, ms: Long = 220) {
        val v = pageView(sc)
        val t = SystemClock.uptimeMillis()
        sc.onActivity {
            event(v, MotionEvent.ACTION_DOWN, t, t, x0, y)
            for (i in 1..steps) {
                val f = i.toFloat() / steps
                event(v, MotionEvent.ACTION_MOVE, t, t + ms * i / steps, x0 + (x1 - x0) * f, y + (y1 - y) * f)
            }
            event(v, MotionEvent.ACTION_UP, t, t + ms + 10, x1, y1)
        }
        advance(1500)
        idle(200)
    }

    protected fun longPress(sc: ActivityScenario<ReaderActivity>, x: Float, y: Float, dragTo: Pair<Float, Float>? = null) {
        val v = pageView(sc)
        val t = SystemClock.uptimeMillis()
        sc.onActivity { event(v, MotionEvent.ACTION_DOWN, t, t, x, y) }
        advance(ViewConfiguration.getLongPressTimeout() + 150L)
        val t2 = SystemClock.uptimeMillis()
        sc.onActivity {
            dragTo?.let { (dx, dy) ->
                for (i in 1..8) event(v, MotionEvent.ACTION_MOVE, t, t2 + i * 20L, x + (dx - x) * i / 8f, y + (dy - y) * i / 8f)
            }
            event(v, MotionEvent.ACTION_UP, t, t2 + 200, dragTo?.first ?: x, dragTo?.second ?: y)
        }
        advance(400)
        idle(200)
    }

    protected fun drag(sc: ActivityScenario<ReaderActivity>, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 12, ms: Long = 300) {
        val v = pageView(sc)
        val t = SystemClock.uptimeMillis()
        sc.onActivity {
            event(v, MotionEvent.ACTION_DOWN, t, t, x0, y0)
            for (i in 1..steps) event(v, MotionEvent.ACTION_MOVE, t, t + ms * i / steps, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
            event(v, MotionEvent.ACTION_UP, t, t + ms + 10, x1, y1)
        }
        advance(600)
        idle(200)
    }

    protected fun size(sc: ActivityScenario<ReaderActivity>): Pair<Float, Float> {
        val v = pageView(sc)
        return v.width.toFloat() to v.height.toFloat()
    }

    protected fun engine(sc: ActivityScenario<ReaderActivity>): TextEngine = sc.get { it.vm.engine as TextEngine }

    protected fun pos(sc: ActivityScenario<ReaderActivity>): PagePos = sc.get { it.vm.pos }

    protected fun viewPixels(sc: ActivityScenario<ReaderActivity>): Bitmap {
        val v = pageView(sc)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        sc.onActivity { v.draw(Canvas(bmp)) }
        return bmp
    }

    protected fun expectedPixels(sc: ActivityScenario<ReaderActivity>, p: PagePos): Bitmap {
        val v = pageView(sc)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        sc.onActivity { it.vm.engine!!.drawPage(Canvas(bmp), p, it.vm.deco) }
        return bmp
    }

    protected fun difference(a: Bitmap, b: Bitmap): Float {
        var diff = 0
        var total = 0
        for (y in 0 until minOf(a.height, b.height) step 3) for (x in 0 until minOf(a.width, b.width) step 3) {
            total++
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            if (kotlin.math.abs(Color.red(p) - Color.red(q)) + kotlin.math.abs(Color.green(p) - Color.green(q)) + kotlin.math.abs(Color.blue(p) - Color.blue(q)) > 30) diff++
        }
        return diff.toFloat() / total
    }

    protected fun assertShowing(sc: ActivityScenario<ReaderActivity>, what: String) {
        val d = difference(viewPixels(sc), expectedPixels(sc, pos(sc)))
        if (d > 0.01f) throw AssertionError("$what: the screen does not show page ${pos(sc)} (${(d * 100).toInt()}% of pixels differ)")
    }

    protected fun wordOnPage(sc: ActivityScenario<ReaderActivity>, predicate: (String, Int, Int) -> Boolean = { _, _, _ -> true }): Triple<String, Int, Int> {
        val e = engine(sc)
        val p = pos(sc)
        val text = e.plainText(p.chapter)
        val from = e.offsetOf(p)
        val to = e.endOffsetOf(p)
        val re = Regex("[\\p{L}]{5,}")
        return re.findAll(text.substring(from, to)).map { Triple(it.value, from + it.range.first, from + it.range.last + 1) }.first { (w, a, b) -> predicate(w, a, b) }
    }

    protected fun inkOnScreen(sc: ActivityScenario<ReaderActivity>, a: Int, b: Int): RectF {
        val e = engine(sc)
        val p = pos(sc)
        val cp = e.pages(p.chapter)!!
        val ink = Ink.wordInk(cp, a, b)!!
        val layoutBox = Ink.pathBounds(cp, a, b)
        val path = Path()
        check(e.selectionPath(p, a, b, path))
        val pageBox = RectF().also { path.computeBounds(it, true) }
        val dx = pageBox.left - layoutBox.left
        val dy = pageBox.top - layoutBox.top
        return RectF(ink.left + dx, ink.top + dy, ink.right + dx, ink.bottom + dy)
    }
}
