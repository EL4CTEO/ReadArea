package com.readarea.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.readarea.core.format.BookPostProcessor
import com.readarea.core.format.EpubParser
import com.readarea.core.format.FileZipAccess
import com.readarea.core.format.TestBooks
import com.readarea.data.ReaderSettings
import com.readarea.reader.engine.Decorations
import com.readarea.reader.engine.PagePos
import com.readarea.reader.engine.PageSetup
import com.readarea.reader.engine.TextEngine
import com.readarea.reader.view.FlipMode
import com.readarea.reader.view.PageFlipView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class RenderTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun engine(settings: ReaderSettings = ReaderSettings(), w: Int = 1080, h: Int = 2340, columns: Int = 1): TextEngine {
        val file = TestBooks.tempFile(TestBooks.epub(chapters = 4, paragraphs = 14), "epub")
        val book = BookPostProcessor.process(EpubParser(FileZipAccess(file)).parse(), false)
        val e = TextEngine(book, 16 shl 20)
        val theme = ReadingThemes.resolve(settings)
        e.configure(PageSetup(w, h, 2.75f, 1f, settings, 80, 60, columns), theme)
        for (c in 0 until e.chapterCount) e.ensure(c)
        return e
    }

    private fun save(bmp: Bitmap, name: String) {
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun paginationCoversAllTextWithoutGaps() {
        val e = engine()
        for (c in 0 until e.chapterCount) {
            val pages = e.pages(c)!!
            val text = e.plainText(c)
            assertEquals(text.length, pages.text.length)
            assertEquals(0, pages.startOffset(0))
            for (p in 0 until pages.pageCount - 1) {
                assertEquals(pages.endOffset(p), pages.startOffset(p + 1))
                assertTrue(pages.endOffset(p) > pages.startOffset(p))
                assertEquals(p, pages.pageOf(pages.startOffset(p)))
                assertTrue(pages.bottom(p) - pages.top(p) <= e.setup!!.contentHeight)
            }
            assertEquals(text.length, pages.endOffset(pages.pageCount - 1))
        }
        assertTrue((e.totalPages() ?: 0) > e.chapterCount)
        val (c, o) = e.locate(0.5f)
        assertEquals(0.5f, e.progress(c, o), 0.01f)
    }

    @Test
    fun rendersPagesAndThemes() {
        for (theme in listOf("paper", "night", "sepia")) {
            val e = engine(ReaderSettings(theme = theme))
            val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            val deco = Decorations().apply {
                clock = "21:45"
                bookTitle = "The Test Book"
                highlights = listOf(com.readarea.reader.engine.HighlightRange(0, 120, 220, ReadingThemes.highlightColors[0]))
            }
            e.drawPage(Canvas(bmp), PagePos(0, 0), deco)
            save(bmp, "page_${theme}_0.png")
            e.drawPage(Canvas(bmp), PagePos(1, 1), deco)
            save(bmp, "page_${theme}_c1p1.png")
        }
    }

    @Test
    fun rendersTwoPageSpread() {
        val e = engine(w = 2176, h = 1812, columns = 2)
        val bmp = Bitmap.createBitmap(2176, 1812, Bitmap.Config.ARGB_8888)
        e.drawPage(Canvas(bmp), PagePos(1, 0), Decorations().apply { bookTitle = "The Test Book"; clock = "9:30" })
        save(bmp, "spread.png")
        assertEquals(PagePos(1, 2), e.next(PagePos(1, 0)))
    }

    private fun flipView(e: TextEngine, w: Int, h: Int, spread: Boolean): PageFlipView {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        var pos = PagePos(0, 0)
        val deco = Decorations().apply { bookTitle = "The Test Book"; clock = "9:30" }
        val v = PageFlipView(ctx)
        v.mode = FlipMode.CURL
        v.spread = spread
        v.pageBackground = ReadingThemes.all[1].background
        v.callback = object : PageFlipView.Callback {
            override fun canFlip(forward: Boolean) = (if (forward) e.next(pos) else e.prev(pos)) != null
            override fun drawPage(offset: Int, canvas: Canvas): Boolean {
                val p = when (offset) {
                    0 -> pos
                    1 -> e.next(pos)
                    else -> e.prev(pos)
                } ?: return false
                e.drawPage(canvas, p, deco)
                return true
            }
            override fun onFlipped(forward: Boolean) {
                pos = (if (forward) e.next(pos) else e.prev(pos))!!
            }
            override fun onTap(x: Float, y: Float) {}
            override fun onLongPress(x: Float, y: Float) {}
            override fun onSelectionDrag(handle: Int, x: Float, y: Float) {}
            override fun onSelectionDragEnd() {}
            override fun onBrightnessDrag(delta: Float, done: Boolean) {}
            override fun onUserActivity() {}
            override fun onFlipBlocked(forward: Boolean) {}
        }
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun touch(v: View, action: Int, x: Float, y: Float, t: Long) {
        val ev = MotionEvent.obtain(t, t, action, x, y, 0)
        v.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun snapshot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        save(bmp, name)
    }

    @Test
    fun curlFollowsFinger() {
        val w = 1080
        val h = 2340
        val e = engine(w = w, h = h)
        val v = flipView(e, w, h, false)
        snapshot(v, "curl_0_rest.png")
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, w - 40f, h - 60f, t)
        touch(v, MotionEvent.ACTION_MOVE, w - 120f, h - 140f, t + 16)
        touch(v, MotionEvent.ACTION_MOVE, w * 0.72f, h * 0.86f, t + 32)
        snapshot(v, "curl_1_corner.png")
        touch(v, MotionEvent.ACTION_MOVE, w * 0.35f, h * 0.78f, t + 48)
        snapshot(v, "curl_2_mid.png")
        touch(v, MotionEvent.ACTION_MOVE, -w * 0.1f, h * 0.9f, t + 64)
        snapshot(v, "curl_3_late.png")
        touch(v, MotionEvent.ACTION_CANCEL, -w * 0.1f, h * 0.9f, t + 80)
        val t2 = t + 2000
        touch(v, MotionEvent.ACTION_DOWN, w * 0.8f, h * 0.5f, t2)
        touch(v, MotionEvent.ACTION_MOVE, w * 0.7f, h * 0.5f, t2 + 16)
        touch(v, MotionEvent.ACTION_MOVE, w * 0.3f, h * 0.52f, t2 + 32)
        snapshot(v, "curl_4_flat_middle.png")
        touch(v, MotionEvent.ACTION_CANCEL, w * 0.3f, h * 0.52f, t2 + 48)
        val t3 = t + 4000
        touch(v, MotionEvent.ACTION_DOWN, w - 60f, 90f, t3)
        touch(v, MotionEvent.ACTION_MOVE, w - 120f, 150f, t3 + 16)
        touch(v, MotionEvent.ACTION_MOVE, w * 0.45f, h * 0.2f, t3 + 32)
        snapshot(v, "curl_5_top_corner.png")
    }

    @Test
    fun spreadCurlTurnsRightSheet() {
        val w = 2176
        val h = 1812
        val e = engine(w = w, h = h, columns = 2)
        val v = flipView(e, w, h, true)
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, w - 40f, h - 50f, t)
        touch(v, MotionEvent.ACTION_MOVE, w - 120f, h - 120f, t + 16)
        touch(v, MotionEvent.ACTION_MOVE, w * 0.62f, h * 0.8f, t + 32)
        snapshot(v, "spread_curl_1.png")
        touch(v, MotionEvent.ACTION_MOVE, w * 0.2f, h * 0.75f, t + 48)
        snapshot(v, "spread_curl_2.png")
    }
}
