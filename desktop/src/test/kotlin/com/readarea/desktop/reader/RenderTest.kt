package com.readarea.desktop.reader

import com.readarea.core.format.Anchor
import com.readarea.core.format.Block
import com.readarea.core.format.BlockKind
import com.readarea.core.format.BookMeta
import com.readarea.core.format.Chapter
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.ResourceProvider
import com.readarea.core.format.Ruby
import com.readarea.core.format.Run
import com.readarea.core.format.RunStyle
import com.readarea.core.format.TestBooks
import com.readarea.core.text.BookText
import com.readarea.core.theme.ReadingThemes
import com.readarea.desktop.data.ReaderSettings
import com.readarea.desktop.reader.engine.Decorations
import com.readarea.desktop.reader.engine.HighlightRange
import com.readarea.desktop.reader.engine.PageChrome
import com.readarea.desktop.reader.engine.PagePos
import com.readarea.desktop.reader.engine.PageSetup
import com.readarea.desktop.reader.engine.TextEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class RenderTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun engineFor(book: ParsedBook, settings: ReaderSettings = ReaderSettings(), w: Int = 720, h: Int = 960, columns: Int = 1, vertical: Boolean = false, rtl: Boolean = false): TextEngine {
        val e = TextEngine(book, BookText(book), null, 32L shl 20)
        e.configure(PageSetup(w, h, settings, columns = columns, vertical = vertical, rtl = rtl), ReadingThemes.resolve(settings.theme, settings.nightTheme, false, false, settings.customBg, settings.customFg, settings.texture))
        for (c in 0 until e.chapterCount) e.ensure(c)
        return e
    }

    private fun render(e: TextEngine, pos: PagePos, name: String, deco: Decorations = Decorations()): BufferedImage {
        val s = e.setup!!
        val img = BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        PageChrome.setupQuality(g)
        e.drawPage(g, pos, deco)
        g.dispose()
        ImageIO.write(img, "png", File(out, "$name.png"))
        return img
    }

    private fun inkRatio(img: BufferedImage, bg: Int): Double {
        var ink = 0
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            val c = img.getRGB(x, y) and 0xFFFFFF
            val d = Math.abs(((c shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) + Math.abs(((c shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) + Math.abs((c and 0xFF) - (bg and 0xFF))
            if (d > 90) ink++
        }
        return ink / ((img.width / 2.0) * (img.height / 2.0))
    }

    private fun para(t: String, style: Int = 0) = Block(BlockKind.PARAGRAPH, listOf(Run(t, style)))

    @Test
    fun paginatesEpubAndDrawsText() {
        val zip = com.readarea.core.format.MemoryZipAccess(TestBooks.epub(chapters = 3, paragraphs = 30).inputStream()) { _, _ -> true }
        val book = com.readarea.core.format.BookPostProcessor.process(com.readarea.core.format.EpubParser(zip).parse(), false)
        val e = engineFor(book)
        assertTrue(e.allReady())
        val total = e.totalPages()!!
        assertTrue("pages: $total", total > 6)
        // Pages cover the chapter text exactly, in order, with no gaps.
        for (c in 0 until e.chapterCount) {
            var expected = 0
            for (p in 0 until e.pageCount(c)) {
                assertEquals(expected, e.offsetOf(PagePos(c, p)))
                expected = e.endOffsetOf(PagePos(c, p))
            }
            assertEquals(e.text.plainText(c).length, expected)
        }
        val deco = Decorations().apply {
            bookTitle = book.meta.title
            highlights = listOf(HighlightRange(0, 40, 160, ReadingThemes.highlightColors[0]))
        }
        val img = render(e, PagePos(0, 0), "epub_page", deco)
        assertTrue(inkRatio(img, ReadingThemes.all[1].background) > 0.01)
        // Every offset maps back to the page that shows it.
        val off = e.offsetOf(PagePos(1, 1)) + 5
        assertEquals(1, e.pageOf(1, off))
    }

    @Test
    fun hitTestingFindsWordsAndLinks() {
        val book = ParsedBook(
            BookMeta("Links", language = "en"),
            listOf(Chapter("One", "a", listOf(para("Hello brave new world, see the note."), Block(BlockKind.PARAGRAPH, listOf(Run("Go "), Run("here", link = "#n1"), Run(" now.")))))),
            emptyList(),
            ResourceProvider { null },
        )
        val e = engineFor(book)
        val s = e.setup!!
        val shape = e.rangeShape(PagePos(0, 0), 6, 11)
        assertNotNull(shape)
        val b = shape!!.bounds2D
        val word = e.wordAt(PagePos(0, 0), b.centerX.toFloat(), b.centerY.toFloat())
        assertEquals("brave", e.text.plainText(0).substring(word!!.first, word.last + 1))
        val linkStart = e.text.plainText(0).indexOf("here")
        val lb = e.rangeShape(PagePos(0, 0), linkStart, linkStart + 4)!!.bounds2D
        assertEquals("#n1", e.linkAt(PagePos(0, 0), lb.centerX.toFloat(), lb.centerY.toFloat()))
        assertEquals(null, e.linkAt(PagePos(0, 0), s.contentLeft + 2f, (s.contentTop + s.contentHeight - 4f)))
    }

    @Test
    fun softHyphensBreakWithAVisibleHyphen() {
        val word = "in­com­pre­hen­si­bil­i­ties"
        val text = (1..40).joinToString(" ") { if (it % 3 == 0) word else "text" }
        val book = ParsedBook(BookMeta("H", language = "en"), listOf(Chapter("c", "c", listOf(para(text)))), emptyList(), ResourceProvider { null })
        val e = engineFor(book, ReaderSettings(fontSize = 22f, marginH = 20), w = 360, h = 640)
        render(e, PagePos(0, 0), "hyphenation")
        val off = engineFor(book, ReaderSettings(fontSize = 22f, marginH = 20, hyphenation = false), w = 360, h = 640)
        render(off, PagePos(0, 0), "hyphenation_off")
    }

    @Test
    fun drawsRightToLeftArabic() {
        val ar = "هذا نص عربي تجريبي لاختبار الاتجاه من اليمين إلى اليسار في القارئ. " .repeat(12)
        val book = ParsedBook(BookMeta("عربي", language = "ar", rtl = true), listOf(Chapter("الفصل", "c", listOf(Block(BlockKind.HEADING, listOf(Run("الفصل الأول")), level = 1), para(ar), para(ar)))), emptyList(), ResourceProvider { null })
        val e = engineFor(book, rtl = true)
        val img = render(e, PagePos(0, 0), "rtl_arabic")
        assertTrue(inkRatio(img, ReadingThemes.all[1].background) > 0.01)
    }

    @Test
    fun drawsVerticalJapaneseWithRuby() {
        val jp = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。"
        val block = Block(BlockKind.PARAGRAPH, listOf(Run(jp.repeat(4))), ruby = listOf(Ruby(0, 2, "わがはい"), Ruby(3, 4, "ねこ")))
        val book = ParsedBook(BookMeta("猫", language = "ja", vertical = true), listOf(Chapter("一", "c", listOf(Block(BlockKind.HEADING, listOf(Run("第一章")), level = 1), block, para("二〇二六年、「東京」へ！？"))),), emptyList(), ResourceProvider { null })
        val e = engineFor(book, ReaderSettings(theme = "sepia"), vertical = true, rtl = true)
        val img = render(e, PagePos(0, 0), "vertical_japanese")
        assertTrue(inkRatio(img, ReadingThemes.all[2].background) > 0.01)
        val horizontal = engineFor(book, ReaderSettings(theme = "sepia"))
        render(horizontal, PagePos(0, 0), "horizontal_ruby")
    }

    @Test
    fun twoPageSpreadAndDarkTheme() {
        val paras = (1..40).map { para(TestBooks.lorem(60, it), if (it == 3) RunStyle.ITALIC else 0) }
        val book = ParsedBook(BookMeta("Spread"), listOf(Chapter("Chapter One", "c", listOf(Block(BlockKind.HEADING, listOf(Run("Chapter One")), level = 1)) + paras)), emptyList(), ResourceProvider { null })
        val e = engineFor(book, ReaderSettings(theme = "night", texture = false), w = 1400, h = 900, columns = 2)
        render(e, PagePos(0, 0), "spread_night", Decorations().apply { bookTitle = "Spread" })
        assertEquals(0, e.align(1))
        assertEquals(PagePos(0, 2), e.next(PagePos(0, 0)))
    }

    @Test
    fun headingsStayWithTheirText() {
        val blocks = ArrayList<Block>()
        repeat(30) { i ->
            blocks.add(Block(BlockKind.HEADING, listOf(Run("Section ${i + 1}")), level = 2, anchors = listOf(Anchor("s$i", 0))))
            blocks.add(para(TestBooks.lorem(35 + i * 3, i)))
        }
        val book = ParsedBook(BookMeta("Keep"), listOf(Chapter("c", "c", blocks)), emptyList(), ResourceProvider { null })
        val e = engineFor(book, w = 600, h = 700)
        val plain = e.text.plainText(0)
        for (p in 0 until e.pageCount(0)) {
            val end = e.endOffsetOf(PagePos(0, p))
            if (end >= plain.length) continue
            // The last line on a page is never a heading.
            val lastLineStart = plain.lastIndexOf('\n', end - 2) + 1
            assertTrue("page $p ends with a heading", !plain.substring(lastLineStart, end).trim().startsWith("Section"))
        }
    }

    @Test
    fun imagesThatNearlyFitShrinkIntoThePage() {
        val png = java.io.ByteArrayOutputStream().also { ImageIO.write(BufferedImage(800, 1000, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val blocks = listOf(para(TestBooks.lorem(40, 1)), Block(BlockKind.IMAGE, emptyList(), image = "big.png"), para(TestBooks.lorem(40, 2)))
        val book = ParsedBook(BookMeta("Img"), listOf(Chapter("c", "c", blocks)), emptyList(), ResourceProvider { if (it == "big.png") png else null })
        val e = engineFor(book, w = 600, h = 800)
        val imageStart = e.text.plainText(0).indexOf(BookText.OBJ)
        // The image stays on the first page, after the paragraph, instead of leaving most of it blank.
        assertTrue("image on page 0", e.endOffsetOf(PagePos(0, 0)) > imageStart)
        render(e, PagePos(0, 0), "image_shrink")
    }
}
