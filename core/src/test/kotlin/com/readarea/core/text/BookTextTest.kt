package com.readarea.core.text

import com.readarea.core.format.Anchor
import com.readarea.core.format.Block
import com.readarea.core.format.BlockKind
import com.readarea.core.format.BookMeta
import com.readarea.core.format.Chapter
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.ResourceProvider
import com.readarea.core.format.Run
import com.readarea.core.format.TocItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTextTest {
    private fun para(text: String, vararg anchors: Anchor) = Block(BlockKind.PARAGRAPH, listOf(Run(text)), anchors = anchors.toList())

    private val book = ParsedBook(
        BookMeta("Title", language = "en"),
        listOf(
            Chapter("One", "a.xhtml", listOf(para("Café au lait. Second sentence here!"), Block(BlockKind.IMAGE, image = "x.png"), para("Tail", Anchor("t", 0)))),
            Chapter("", "b.xhtml", listOf(para("It’s a “quoted” co­operation.", Anchor("top", 0)), para("Note text", Anchor("n1", 0)), para("After", Anchor("n2", 0)))),
        ),
        listOf(TocItem("One", 0, null, 0), TocItem("Two", 1, null, 0), TocItem("Notes", 1, "n1", 1)),
        ResourceProvider { null },
    )
    private val text = BookText(book)

    @Test
    fun plainTextJoinsBlocksAndReplacesImages() {
        assertEquals("Café au lait. Second sentence here!\n￼\nTail\n", text.plainText(0))
        assertEquals(mapOf("t" to 38), text.anchors(0))
        assertEquals("", text.plainText(5))
    }

    @Test
    fun progressAndLocateRoundTrip() {
        assertEquals(0f, text.progress(0, 0), 0f)
        assertEquals(1f, text.progress(1, text.chapterLength(1)), 0f)
        for (p in listOf(0f, 0.25f, 0.5f, 0.99f)) {
            val (c, o) = text.locate(p)
            assertEquals(p, text.progress(c, o), 0.01f)
        }
    }

    @Test
    fun searchFoldsAccentsQuotesAndSoftHyphens() {
        assertEquals(1, text.search("cafe").size)
        val quoted = text.search("\"quoted\"")
        assertEquals(1, quoted.size)
        assertEquals(1, quoted[0].chapter)
        assertEquals(1, text.search("it's").size)
        val hit = text.search("cooperation").single()
        assertEquals("co­operation", text.plainText(1).substring(hit.start, hit.end))
        assertTrue(text.search("x").isEmpty())
        assertTrue(hit.snippet.substring(hit.matchStart, hit.matchEnd).equals("cooperation", true))
    }

    @Test
    fun sentencesSkipImagesAndStartAtOffset() {
        val s = text.sentences(0, 0).map { text.plainText(0).substring(it.first, it.last + 1) }
        assertEquals(listOf("Café au lait.", "Second sentence here!", "Tail"), s)
        val from = text.sentences(0, 14).map { text.plainText(0).substring(it.first, it.last + 1) }
        assertEquals("Second sentence here!", from.first())
    }

    @Test
    fun longSentencesAreSplit() {
        val long = (1..200).joinToString(" ") { "word$it" }
        val t = BookText(ParsedBook(BookMeta("t"), listOf(Chapter("c", "c", listOf(para(long)))), emptyList(), ResourceProvider { null }))
        val parts = t.sentences(0, 0)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.last - it.first < 420 })
    }

    @Test
    fun linksResolveAnchorsAndIgnoreExternal() {
        assertEquals(1 to text.anchors(1).getValue("n1"), text.resolveLink("b.xhtml#n1", 0))
        assertEquals(1 to 0, text.resolveLink("b.xhtml", 0))
        assertEquals(0 to 38, text.resolveLink("#t", 1))
        assertNull(text.resolveLink("https://example.com/#n1", 0))
        assertNull(text.resolveLink("javascript:alert(1)", 0))
        assertNull(text.resolveLink("missing.xhtml", 0))
    }

    @Test
    fun footnoteStopsAtNextAnchor() {
        val (c, o) = text.resolveLink("b.xhtml#n1", 0)!!
        assertEquals("Note text", text.footnote(c, o))
    }

    @Test
    fun tocLookupAndChapterStep() {
        assertEquals("One", text.sectionTitleAt(0, 5))
        assertEquals("Two", text.sectionTitleAt(1, 0))
        assertEquals("Notes", text.sectionTitleAt(1, text.anchors(1).getValue("n1")))
        assertEquals(1 to 0, text.chapterStep(0, 0, 10, forward = true))
        assertEquals(1 to 0, text.chapterStep(1, 5, 20, forward = false))
        assertEquals(0 to 0, text.chapterStep(1, 0, 20, forward = false))
        assertEquals("One", text.chapterTitle(0))
        assertEquals("Notes", text.chapterTitle(1))
    }

    @Test
    fun wordAtFindsWordsNotImages() {
        val w = text.wordAt(0, 9)!!
        assertEquals("lait", text.plainText(0).substring(w.first, w.last + 1))
        assertNull(text.wordAt(0, text.plainText(0).indexOf('￼')))
    }

    @Test
    fun textStripsObjectsAndClampsRanges() {
        assertEquals("Tail", text.text(0, 36, 1000))
        assertEquals("", text.text(0, 50, 10))
    }
}
