package com.readarea.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.readarea.core.format.BlockKind
import com.readarea.core.format.BookPostProcessor
import com.readarea.core.format.EpubParser
import com.readarea.core.format.FileZipAccess
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.RunStyle
import com.readarea.core.format.TestBooks
import com.readarea.core.format.TextDecoder
import com.readarea.core.format.TxtParser
import com.readarea.data.ReaderSettings
import com.readarea.reader.engine.Decorations
import com.readarea.reader.engine.HighlightRange
import com.readarea.reader.engine.PagePos
import com.readarea.reader.engine.PageSetup
import com.readarea.reader.engine.TextEngine
import com.readarea.reader.view.FlipMode
import com.readarea.reader.view.PageFlipView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class VerticalTextTest {
    private val out = File("build/screens").apply { mkdirs() }

    companion object {
        private val paragraphs = listOf(
            "海辺の小さな町に、ひとりの<ruby>灯台守<rt>とうだいもり</rt></ruby>が住んでいた。毎朝、日が昇る前に長い<ruby>螺旋<rp>（</rp><rt>らせん</rt><rp>）</rp></ruby>階段をのぼり、ガラスの曇りをていねいに拭きとる。それが彼の三十年来の習慣だった。",
            "「灯台守の朝は早いんだ。」と老人は笑った。「ちょっと早すぎるくらいさ。」",
            "ある冬の夜、嵐がやってきた。波は<ruby>岸壁<rt>がんぺき</rt></ruby>を叩き、風は窓をゆらした。「今夜はきっと、誰かが光を必要としている」と、彼はつぶやいた。……そうだ、火を絶やしてはならない。",
            "町の子どもたちは、灯台を<span class=\"em-sesame\">ひかりの塔</span>と呼んでいた。夏になると、彼らはおにぎりとコーヒーの水筒をもって丘をのぼり、遠くの船を数えて遊んだ。2024年の夏には、12隻もの船が見えたという。本当に!?",
            "灯台のなかには古い本棚があり、<ruby>航海日誌<rt>こうかいにっし</rt></ruby>や詩集、そして一冊の English Dictionary が並んでいた。老人はときどきノートをひらき、――あの夏の日のことを書きとめた。",
        )

        fun jaEpub(chapters: Int = 2, repeat: Int = 4): ByteArray {
            val files = LinkedHashMap<String, ByteArray>()
            files["mimetype"] = "application/epub+zip".toByteArray()
            files["META-INF/container.xml"] = "<container><rootfiles><rootfile full-path=\"item/standard.opf\"/></rootfiles></container>".toByteArray()
            val manifest = StringBuilder("<item id=\"css\" href=\"style/book.css\" media-type=\"text/css\"/>")
            val spine = StringBuilder()
            for (c in 1..chapters) {
                manifest.append("<item id=\"p$c\" href=\"xhtml/p-$c.xhtml\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"p$c\"/>")
                val body = StringBuilder("<h1>第${"一二三四"[c - 1]}章　ひかりの塔</h1>")
                repeat(repeat) { r -> paragraphs.forEachIndexed { i, p -> body.append("<p>").append(if (r > 0 && i == 0) p.replace("海辺", "港") else p).append("</p>") } }
                files["item/xhtml/p-$c.xhtml"] = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ja" class="vrtl"><head><title>第${c}章</title><link rel="stylesheet" type="text/css" href="../style/book.css"/></head>
<body class="p-text">$body</body></html>""".toByteArray()
            }
            files["item/style/book.css"] = "html.vrtl { writing-mode: vertical-rl; -epub-writing-mode: vertical-rl; } .em-sesame { -epub-text-emphasis-style: sesame; text-emphasis-style: sesame; }".toByteArray()
            files["item/standard.opf"] = """<package version="3.0"><metadata><dc:title>ひかりの塔</dc:title><dc:creator>海野 灯</dc:creator><dc:language>ja</dc:language></metadata>
<manifest>$manifest</manifest><spine page-progression-direction="rtl">$spine</spine></package>""".toByteArray()
            return TestBooks.zip(files)
        }
    }

    private fun book(): ParsedBook = BookPostProcessor.process(EpubParser(FileZipAccess(TestBooks.tempFile(jaEpub(), "epub"))).parse(), false)

    private fun engine(settings: ReaderSettings = ReaderSettings(), w: Int = 1080, h: Int = 2340, columns: Int = 1, vertical: Boolean = true): TextEngine {
        val e = TextEngine(book(), 16 shl 20)
        e.configure(PageSetup(w, h, 2.75f, 1f, settings, 80, 60, columns, rtl = vertical, vertical = vertical), ReadingThemes.resolve(settings))
        for (c in 0 until e.chapterCount) e.ensure(c)
        return e
    }

    private fun save(bmp: Bitmap, name: String) {
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun epubRubyEmphasisAndWritingModeAreParsed() {
        val b = book()
        assertTrue(b.meta.vertical)
        assertTrue(b.meta.rtl)
        assertEquals("ja", b.meta.language)
        val blocks = b.chapters[0].blocks
        val first = blocks.first { it.kind == BlockKind.PARAGRAPH }
        assertFalse(first.text.contains("とうだいもり"))
        assertFalse(first.text.contains("（"))
        val ruby = first.ruby
        assertEquals(listOf("とうだいもり", "らせん"), ruby.map { it.text })
        assertEquals("灯台守", first.text.substring(ruby[0].start, ruby[0].end))
        assertEquals("螺旋", first.text.substring(ruby[1].start, ruby[1].end))
        val em = blocks.first { it.text.startsWith("町の子ども") }.runs.first { it.style and RunStyle.EMPHASIS != 0 }
        assertEquals("ひかりの塔", em.text)
    }

    @Test
    fun aozoraTextIsCleanedWithRubyAndHeadings() {
        val src = """ひかりの塔
海野灯

-------------------------------------------------------
【テキスト中に現れる記号について】

《》：ルビ
（例）灯台守《とうだいもり》
-------------------------------------------------------

［＃３字下げ］一［＃「一」は中見出し］

　海辺の町に｜ひとりの《ひとりの》灯台守《とうだいもり》が住んでいた。※［＃「魚＋師」、第3水準1-94-41］
　毎朝、螺旋《らせん》階段をのぼる。

［＃改ページ］
［＃３字下げ］二［＃「二」は中見出し］

　嵐の夜、岸壁《がんぺき》に波が打ちよせた。
"""
        val book = TxtParser(src, "fallback").parse()
        assertTrue(book.meta.vertical)
        assertEquals("ja", book.meta.language)
        assertEquals(listOf("一", "二"), book.toc.map { it.title })
        val all = book.chapters.flatMap { it.blocks }
        val text = all.joinToString("\n") { it.text }
        assertFalse(text.contains("［＃"))
        assertFalse(text.contains("《"))
        assertFalse(text.contains("テキスト中に現れる記号"))
        val p = all.first { it.text.startsWith("海辺") }
        assertEquals(listOf("ひとりの", "とうだいもり"), p.ruby.map { it.text })
        assertEquals("灯台守", p.text.substring(p.ruby[1].start, p.ruby[1].end))
        val q = all.first { it.text.startsWith("毎朝") }
        assertEquals("螺旋", q.text.substring(q.ruby[0].start, q.ruby[0].end))
        val chinese = TxtParser("第一章 开始\n他读了《红楼梦》这本书，很喜欢。他读了《红楼梦》这本书，很喜欢。他读了《红楼梦》这本书，很喜欢。他读了《红楼梦》这本书。\n第二章 结束\n书读完了。", "zh").parse()
        assertTrue(chinese.chapters.flatMap { it.blocks }.all { it.ruby.isEmpty() })
        assertTrue(chinese.chapters.flatMap { it.blocks }.any { it.text.contains("《红楼梦》") })
    }

    @Test
    fun legacyCjkEncodingsAreDetected() {
        val ja = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。".repeat(3)
        val zh = "我们在这个城市里生活了很多年，他说这是一个美丽的地方，大家都很喜欢这里的人和风景。".repeat(3)
        val zhHant = "我們在這個城市裡生活了很多年，他說這是一個美麗的地方，大家都很喜歡這裡的人和風景。".repeat(3)
        val ko = "우리는 이 도시에서 오랫동안 살았습니다. 그는 이곳이 아름다운 곳이라고 말했고 모두가 이곳의 사람들을 좋아합니다.".repeat(3)
        val ru = "Мы долго жили в этом городе, и он сказал, что это красивое место, и всем нравятся здешние люди.".repeat(3)
        assertEquals(ja, TextDecoder.decode(ja.toByteArray(charset("Shift_JIS"))))
        assertEquals(ja, TextDecoder.decode(ja.toByteArray(charset("EUC-JP"))))
        assertEquals(zh, TextDecoder.decode(zh.toByteArray(charset("GB18030"))))
        assertEquals(zhHant, TextDecoder.decode(zhHant.toByteArray(charset("Big5"))))
        assertEquals(ko, TextDecoder.decode(ko.toByteArray(charset("x-windows-949"))))
        assertEquals(ru, TextDecoder.decode(ru.toByteArray(charset("windows-1251"))))
    }

    @Test
    fun verticalPaginationCoversAllText() {
        val e = engine()
        for (c in 0 until e.chapterCount) {
            val vp = e.vpages(c)!!
            assertEquals(e.plainText(c), vp.text)
            assertEquals(0, vp.startOffset(0))
            assertTrue(vp.pageCount >= 2)
            for (p in 0 until vp.pageCount - 1) {
                assertTrue(vp.endOffset(p) > vp.startOffset(p))
                assertEquals(vp.endOffset(p), vp.startOffset(p + 1))
                assertEquals(p, vp.pageOf(vp.startOffset(p)))
            }
            assertEquals(vp.text.length, vp.endOffset(vp.pageCount - 1))
        }
        val (c, o) = e.locate(0.5f)
        assertEquals(0.5f, e.progress(c, o), 0.01f)
    }

    @Test
    fun kinsokuKeepsPunctuationOffColumnTops() {
        for (size in listOf(16f, 19f, 23f, 28f)) {
            val e = engine(ReaderSettings(fontSize = size))
            for (c in 0 until e.chapterCount) {
                val vp = e.vpages(c)!!
                for (p in 0 until vp.pageCount) {
                    for (r in vp.columnRanges(p)) {
                        val first = vp.text[r.first]
                        assertFalse("column starts with '$first' at size $size", first in "、。」』）ー")
                        val last = vp.text[r.last]
                        assertFalse("column ends with '$last' at size $size", last in "「『（")
                    }
                }
            }
        }
    }

    @Test
    fun selectionHitTestingRoundTrips() {
        val e = engine()
        val pos = PagePos(0, 1)
        val s = e.setup!!
        val start = e.offsetOf(pos) + 12
        val end = start + 9
        val path = Path()
        assertTrue(e.selectionPath(pos, start, end, path))
        val b = RectF()
        path.computeBounds(b, true)
        assertTrue(b.width() > 0 && b.height() > 0)
        assertTrue(b.right <= s.width && b.left >= 0)
        val hp = e.handlePoint(pos, start, false)!!
        val back = e.offsetAt(pos, hp.x + s.fontPx * 0.62f, hp.y + 1f)!!
        assertEquals(start, back)
        val word = e.wordAt(pos, hp.x + s.fontPx * 0.62f, hp.y + s.fontPx * 0.5f)
        assertNotNull(word)
        assertTrue(start in word!!)
        val right = e.offsetAt(pos, s.width - s.marginH - s.fontPx, s.contentTop + 2f)!!
        assertEquals(e.offsetOf(pos), right)
    }

    @Test
    fun rendersVerticalPages() {
        val deco = Decorations().apply {
            bookTitle = "ひかりの塔"
        }
        val e = engine(ReaderSettings(theme = "paper"))
        val vp = e.vpages(0)!!
        val hl = vp.text.indexOf("毎朝")
        deco.highlights = listOf(HighlightRange(0, hl, hl + 14, ReadingThemes.highlightColors[0]))
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        e.drawPage(Canvas(bmp), PagePos(0, 0), deco)
        save(bmp, "vertical_p0.png")
        e.drawPage(Canvas(bmp), PagePos(0, 1), deco)
        save(bmp, "vertical_p1.png")
        val night = engine(ReaderSettings(theme = "night", fontSize = 22f))
        night.drawPage(Canvas(bmp), PagePos(1, 0), deco)
        save(bmp, "vertical_night.png")
        val sw = 2176
        val sh = 1812
        val spread = engine(ReaderSettings(theme = "sepia"), w = sw, h = sh, columns = 2)
        val sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        spread.drawPage(Canvas(sb), PagePos(0, 0), deco)
        save(sb, "vertical_spread.png")
        val horizontal = engine(ReaderSettings(theme = "paper"), vertical = false)
        horizontal.drawPage(Canvas(bmp), PagePos(0, 0), deco)
        save(bmp, "horizontal_ruby.png")
    }

    @Test
    fun verticalCurlTurnsFromTheLeft() {
        val w = 1080
        val h = 2340
        val e = engine()
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        var pos = PagePos(0, 0)
        val deco = Decorations().apply { bookTitle = "ひかりの塔" }
        val v = PageFlipView(ctx)
        v.mode = FlipMode.CURL
        v.rtl = true
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
        val t = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, y: Float, at: Long) {
            val ev = MotionEvent.obtain(t, at, action, x, y, 0)
            v.dispatchTouchEvent(ev)
            ev.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 40f, h - 60f, t)
        touch(MotionEvent.ACTION_MOVE, 120f, h - 140f, t + 16)
        touch(MotionEvent.ACTION_MOVE, w * 0.55f, h * 0.8f, t + 32)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        save(bmp, "vertical_curl.png")
    }
}
