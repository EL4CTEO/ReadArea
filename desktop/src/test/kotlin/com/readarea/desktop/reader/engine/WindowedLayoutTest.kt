package com.readarea.desktop.reader.engine

import com.readarea.core.format.Align
import com.readarea.core.format.Block
import com.readarea.core.format.BlockKind
import com.readarea.core.format.Chapter
import com.readarea.core.format.ResourceProvider
import com.readarea.core.format.Ruby
import com.readarea.core.format.Run
import com.readarea.core.format.RunStyle
import com.readarea.desktop.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Paragraphs styled in many stretches are laid out a line at a time from windows of their text, for speed.
 * That must not show: every line has to come out exactly as laying out the whole paragraph would make it.
 */
class WindowedLayoutTest {
    private val words = listOf(
        "the", "quiet", "reader", "turned", "another", "page,", "and", "then", "extraordinarily", "long-winded",
        "sentences", "­soft", "hy­phen­a­tion", "1984", "3.14", "(aside)", "\"quoted\"", "—", "…",
        "שלום", "עולם", "ספר", "مرحبا", "بالعالم", "كتاب", "漢字", "かな", "読書", "中文字符", "é", "é", "ǟ",
        "x\ty", "a", "I", "ok.",
    )

    private val styles = listOf(0, 0, 0, RunStyle.BOLD, RunStyle.ITALIC, RunStyle.SUP, RunStyle.SUB, RunStyle.MONO, RunStyle.UNDERLINE, RunStyle.STRIKE, RunStyle.MARK, RunStyle.EMPHASIS, RunStyle.BOLD or RunStyle.ITALIC)

    private fun paragraph(r: Random): Block {
        val runs = ArrayList<Run>()
        repeat(r.nextInt(8, 260)) {
            val text = (1..r.nextInt(1, 6)).joinToString(" ") { words.random(r) } + if (r.nextInt(4) > 0) " " else ""
            val link = if (r.nextInt(12) == 0) "#note${r.nextInt(9)}" else null
            val scale = if (r.nextInt(15) == 0) listOf(0.8f, 1.2f, 1.5f).random(r) else 1f
            runs.add(Run(text, styles.random(r), link, scale))
        }
        val length = runs.sumOf { it.text.length }
        val ruby = if (r.nextInt(3) == 0) {
            (0 until r.nextInt(1, 30)).map { r.nextInt(length) }.sorted().distinct().map { s -> Ruby(s, minOf(length, s + r.nextInt(1, 4)), listOf("かん", "じ", "よみ").random(r)) }
        } else {
            emptyList()
        }
        val kind = listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.QUOTE, BlockKind.LIST_ITEM, BlockKind.PRE).random(r)
        val align = listOf(null, null, Align.JUSTIFY, Align.CENTER, Align.END, Align.START).random(r)
        return Block(kind, runs, align, ruby = ruby)
    }

    private fun builder(settings: ReaderSettings, manyRuns: Int): TextLayoutBuilder {
        val setup = PageSetup(900, 1100, settings, scale = 2f)
        return TextLayoutBuilder(setup, ReaderFonts.base(settings.fontFamily, null), ReaderFonts.monoBase(), SpanColors(), ImageCache(ResourceProvider { null }, 1L shl 20), Locale.ENGLISH, manyRuns)
    }

    private fun same(a: Float, b: Float) = abs(a - b) < 0.01f || (a.isNaN() && b.isNaN())

    @Test
    fun windowedLinesMatchWholeParagraphLines() {
        val r = Random(20260928)
        var windowedLines = 0
        for (round in 0 until 24) {
            val settings = ReaderSettings(justify = round % 2 == 0, hyphenation = round % 3 != 0, fontSize = listOf(14f, 19f, 26f)[round % 3])
            val chapter = Chapter("t", "c", List(6) { paragraph(r) })
            val plain = chapter.blocks.joinToString("\n") { it.text } + "\n"
            val whole = builder(settings, Int.MAX_VALUE).build(chapter, plain).lines
            val windowed = builder(settings, 0).build(chapter, plain).lines
            assertEquals("round $round: line count", whole.size, windowed.size)
            for (i in whole.indices) {
                val a = whole[i]
                val b = windowed[i]
                val where = "round $round, line $i ('${plain.substring(a.start, minOf(a.end, plain.length)).take(40)}')"
                assertEquals("$where: range", a.start to a.end, b.start to b.end)
                assertEquals("$where: hyphen", a.hyphen, b.hyphen)
                assertTrue("$where: x ${a.x} vs ${b.x}", same(a.x, b.x))
                assertTrue("$where: baseline ${a.baseline} vs ${b.baseline}", same(a.baseline, b.baseline))
                assertTrue("$where: bottom", same(a.bottom, b.bottom))
                val la = a.layout
                val lb = b.layout
                assertEquals("$where: has layout", la != null, lb != null)
                if (la != null && lb != null) {
                    assertTrue("$where: advance ${la.advance} vs ${lb.advance}", same(la.advance, lb.advance))
                    assertEquals("$where: direction", la.isLeftToRight, lb.isLeftToRight)
                    assertEquals("$where: characters", la.characterCount, lb.characterCount)
                    windowedLines++
                }
            }
        }
        assertTrue("enough text lines compared: $windowedLines", windowedLines > 500)
    }

    @Test
    fun paragraphsWithThousandsOfStretchesLayOutInLinearTime() {
        // One paragraph of 120,000 alternating runs, and one of 100,000 ruby annotations: minutes each before.
        val marks = Block(BlockKind.PARAGRAPH, List(120_000) { if (it % 2 == 0) Run("a") else Run("1", RunStyle.SUP) })
        val rubyText = "漢".repeat(100_000)
        val ruby = Block(BlockKind.PARAGRAPH, listOf(Run(rubyText)), ruby = List(100_000) { Ruby(it, it + 1, "かん") })
        for ((name, block) in listOf("superscripts" to marks, "ruby" to ruby)) {
            val chapter = Chapter("t", "c", listOf(block))
            val start = System.nanoTime()
            var error: Throwable? = null
            val t = Thread {
                try {
                    builder(ReaderSettings(), TextLayoutBuilder.MANY_RUNS).build(chapter, block.text + "\n")
                } catch (e: Throwable) {
                    error = e
                }
            }
            t.isDaemon = true
            t.start()
            t.join(20_000)
            assertTrue("$name must lay out within 20 s", !t.isAlive)
            error?.let { throw it }
            println("$name: ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }
}
