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
        // Openers with no closer: every one used to be retried against the rest of the paragraph.
        within(3000, "bold with no closers") { MarkdownParser("**a ".repeat(50_000), "t").parse() }
        within(3000, "italics with no closers") { MarkdownParser("*a ".repeat(50_000) + "\n\n" + "_a ".repeat(50_000), "t").parse() }
        within(3000, "long rule") { MarkdownParser("-" + " -".repeat(100_000) + "\n\n" + "-" + " -".repeat(100_000) + " x", "t").parse() }
        within(3000, "links nested in brackets") { MarkdownParser(("[".repeat(400) + "a](" + "x".repeat(1999) + " ").repeat(100), "t").parse() }
    }

    @Test
    fun emphasisPairsExactlyAsTheRegexDid() {
        // The scanner replaced these regexes for speed; it must pair delimiters the same way on any input.
        val regexes = listOf(
            Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1") to "b",
            Regex("(\\*|_)(?=\\S)(.+?)(?<=\\S)\\1") to "i",
            Regex("(~~)(.+?)~~") to "s",
        )
        val delims = mapOf("b" to listOf("**", "__"), "i" to listOf("*", "_"), "s" to listOf("~~"))
        val r = kotlin.random.Random(7)
        repeat(20_000) {
            val s = String(CharArray(1 + r.nextInt(24)) { "**__~~ ab".random(r) })
            for ((regex, tag) in regexes) {
                val expected = regex.replace(s) { m -> "<$tag>${m.groupValues[2]}</$tag>" }
                assertEquals("'$s' as $tag", expected, MarkdownParser.emphasis(s, delims.getValue(tag), tag, flanking = tag != "s"))
            }
        }
        val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
        repeat(20_000) {
            val line = String(CharArray(r.nextInt(12)) { " \t-*_a".random(r) })
            assertEquals("'$line' as a rule", rule.matches(line), MarkdownParser.isRule(line))
        }
        assertEquals("<p><b>bold</b>, <i>it</i>, <b>u</b>, <i>v</i> and <s>gone</s></p>\n", MarkdownParser.toHtml("**bold**, *it*, __u__, _v_ and ~~gone~~"))
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
        within(3000, "unclosed css comments") { Stylesheet().add("/* ".repeat(300_000)) }
        within(3000, "nested media queries") { Stylesheet().add("@media screen{".repeat(50_000) + "p{font-weight:bold}" + "}".repeat(50_000)) }
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
