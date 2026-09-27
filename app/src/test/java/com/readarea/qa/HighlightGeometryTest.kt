package com.readarea.qa

import com.readarea.core.format.Block
import com.readarea.core.format.BlockKind
import com.readarea.core.format.BookMeta
import com.readarea.core.format.BookPostProcessor
import com.readarea.core.format.Chapter
import com.readarea.core.format.EpubParser
import com.readarea.core.format.FileZipAccess
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.ResourceProvider
import com.readarea.core.format.Run
import com.readarea.core.format.TestBooks
import com.readarea.data.ReaderSettings
import com.readarea.reader.ReadingThemes
import com.readarea.reader.engine.PagePos
import com.readarea.reader.engine.PageSetup
import com.readarea.reader.engine.TextEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HighlightGeometryTest {
    private fun engine(book: ParsedBook, s: ReaderSettings): TextEngine {
        val e = TextEngine(book, 16 shl 20)
        e.configure(PageSetup(1080, 2340, 2.75f, 1f, s, 80, 60), ReadingThemes.resolve(s))
        for (c in 0 until e.chapterCount) e.ensure(c)
        return e
    }

    private fun check(e: TextEngine, chapter: Int, maxWords: Int, label: String) {
        val cp = e.pages(chapter)!!
        val t = cp.text
        var checked = 0
        var worst = 0f
        var i = 0
        var n = 0
        while (i < t.length && checked < maxWords) {
            if (t[i].isLetter() && (i == 0 || !t[i - 1].isLetter())) {
                var j = i
                while (j < t.length && (t[j].isLetter() || t[j] == '­')) j++
                val sized = cp.layout.paint.letterSpacing != 0f && (t as android.text.Spanned).getSpans(i, j, android.text.style.RelativeSizeSpan::class.java).isNotEmpty()
                if (n++ % 5 == 0 && !sized && cp.layout.getLineForOffset(i) == cp.layout.getLineForOffset(j - 1) && j - i >= 3) {
                    val ink = Ink.wordInk(cp, i, j)
                    if (ink != null) {
                        val hl = Ink.pathBounds(cp, i, j)
                        val err = maxOf(abs(hl.left - ink.left), abs(hl.right - ink.right))
                        worst = maxOf(worst, err)
                        assertTrue("$label: highlight of '${t.substring(i, j)}' at ${hl.left}-${hl.right} but ink at ${ink.left}-${ink.right}", err < 9f)
                        checked++
                    }
                }
                i = j
                continue
            }
            i++
        }
        assertTrue("$label: checked only $checked words", checked >= minOf(maxWords, 20))
        println("GEOMETRY $label words=$checked worst=$worst")
    }

    @Test
    fun highlightsSitOnTheWordsInJustifiedText() {
        val book = BookPostProcessor.process(EpubParser(FileZipAccess(TestBooks.tempFile(QaBooks.realisticEpub(), "epub"))).parse(), false)
        check(engine(book, ReaderSettings(theme = "day", justify = true)), 1, 80, "justified")
        check(engine(book, ReaderSettings(theme = "day", justify = false)), 1, 40, "ragged")
        check(engine(book, ReaderSettings(theme = "day", justify = true, fontSize = 26f, letterSpacing = 0.05f)), 2, 40, "large")
    }

    @Test
    fun highlightsSitOnTheWordsInRightToLeftText() {
        val ar = "كان يا ما كان في قديم الزمان وسالف العصر والأوان كان هناك قارئ يحب الكتب كثيرا ويقرأ كل ليلة تحت ضوء القمر حتى يغلبه النعاس فيضع الكتاب جانبا وينام بهدوء"
        val blocks = (0 until 6).map { Block(BlockKind.PARAGRAPH, listOf(Run(ar))) }
        val book = BookPostProcessor.process(ParsedBook(BookMeta("كتاب", language = "ar"), listOf(Chapter("", "a", blocks)), emptyList(), ResourceProvider { null }), false)
        check(engine(book, ReaderSettings(theme = "day", justify = true)), 0, 40, "rtl")
    }

    @Test
    fun searchFindsWordsSplitBySoftHyphensAndCurlyApostrophes() {
        val book = BookPostProcessor.process(EpubParser(FileZipAccess(TestBooks.tempFile(QaBooks.realisticEpub(), "epub"))).parse(), false)
        val e = engine(book, ReaderSettings())
        val hits = e.search("fisherman's cottage")
        assertTrue("expected hits, got ${hits.size}", hits.size >= 5)
        for (h in hits) assertTrue(e.plainText(h.chapter).substring(h.start, h.end).replace("­", "").replace('’', '\'') == "fisherman's cottage")
        assertTrue(e.search("CAFE").isNotEmpty())
        val h = hits[1]
        val pos = PagePos(h.chapter, e.pageOf(h.chapter, h.start))
        assertTrue(e.offsetOf(pos) <= h.start && h.end <= e.endOffsetOf(pos))
        assertTrue(hits.all { it.snippet.substring(it.matchStart, it.matchEnd).replace('’', '\'').lowercase() == "fisherman's cottage" })
    }
}
