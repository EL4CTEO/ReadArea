package com.readarea.core.security

import com.readarea.core.format.CoverImages
import com.readarea.core.format.EpubParser
import com.readarea.core.format.MarkdownParser
import com.readarea.core.format.Stylesheet
import com.readarea.core.format.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostileInputTest {
    private fun within(ms: Long, name: String, block: () -> Unit) {
        var error: Throwable? = null
        val t = Thread { try { block() } catch (e: Throwable) { error = e } }
        t.isDaemon = true
        t.start()
        t.join(ms)
        assertTrue("$name must finish within $ms ms", !t.isAlive)
        error?.let { throw it }
    }

    @Test
    fun markdownPatternsStayFastOnHostileText() {
        within(3000, "heading padded with spaces") { MarkdownParser("# a" + " ".repeat(200_000) + "b\n", "t").parse() }
        within(3000, "unclosed links") { MarkdownParser("[a](".repeat(50_000) + "\n", "t").parse() }
        within(3000, "unclosed images") { MarkdownParser("![a](".repeat(50_000) + "\n", "t").parse() }
        within(3000, "unclosed autolinks") { MarkdownParser("<https://".repeat(50_000) + "\n", "t").parse() }
        within(3000, "emphasis runs") { MarkdownParser("**a".repeat(30_000) + "\n" + "_a".repeat(30_000), "t").parse() }
    }

    @Test
    fun markdownStillParsesNormalDocuments() {
        val html = MarkdownParser.toHtml("# Title ##\n\n## C#\n\nSee [the *site*](https://example.com/a_b_c \"x\") and ![map](img/map.png) or <https://a.b/c_d_e>.")
        assertTrue(html, html.contains("<h1>Title</h1>"))
        assertTrue(html, html.contains("<h2>C#</h2>"))
        assertTrue(html, html.contains("<a href=\"https://example.com/a_b_c\">the <i>site</i></a>"))
        assertTrue(html, html.contains("<img src=\"img/map.png\" alt=\"map\"/>"))
        assertTrue(html, html.contains("<a href=\"https://a.b/c_d_e\">https://a.b/c_d_e</a>"))
        assertTrue(html, MarkdownParser.toHtml("[x](a\"onclick=1)").contains("href=\"a&quot;onclick=1\""))
    }

    @Test
    fun markupScannersStayLinear() {
        within(3000, "docx blip tags") { CoverImages.firstTagAttr("<a:blip ".repeat(300_000), "a:blip", "r:embed") }
        within(3000, "docx text runs") { CoverImages.docxTextBefore("<w:t ".repeat(300_000), 1_500_000) }
        within(3000, "odt images") { CoverImages.firstTagAttr("<draw:image ".repeat(300_000), "draw:image", "xlink:href") }
        within(3000, "tag stripping") { EpubParser.stripTags("<".repeat(1_000_000)) }
        within(3000, "text length") { CoverImages.textLength("<".repeat(1_000_000)) }
        within(3000, "css comments") { Stylesheet().add("/*".repeat(300_000)) }
        within(3000, "aozora notes") { TxtParser("［＃「".repeat(100_000) + "\n" + "［＃".repeat(100_000), "t").parse() }
    }

    @Test
    fun markupScannersFindTheRightThings() {
        val xml = "<w:p><w:r><w:t>Hi</w:t></w:r><w:tab/><w:r><w:t xml:space=\"preserve\"> there </w:t></w:r><a:blip r:link=\"x\" r:embed=\"rId7\"/></w:p>"
        val m = CoverImages.firstTagAttr(xml, "a:blip", "r:embed")!!
        assertEquals("rId7", m.value)
        assertEquals(xml.indexOf("<a:blip"), m.start)
        assertEquals(7, CoverImages.docxTextBefore(xml, m.start))
        assertEquals("rId2", CoverImages.firstTagAttr("<a:blipFill r:embed=\"no\"/><a:blip\n r:embed=\"rId2\">", "a:blip", "r:embed")!!.value)
        assertEquals("Pictures/a.png", CoverImages.firstTagAttr("<draw:image xlink:type=\"simple\" xlink:href=\"Pictures/a.png\"/>", "draw:image", "xlink:href")!!.value)
        assertEquals("A b <> c", EpubParser.stripTags("A <i>b</i> <> c"))
        assertEquals(" x  y ", CoverImages.stripTags("<p>x</p><p>y</p>"))
        assertEquals(3, CoverImages.textLength("<p>a&amp;b</p>"))
    }
}
