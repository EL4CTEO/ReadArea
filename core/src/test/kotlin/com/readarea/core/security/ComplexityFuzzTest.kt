package com.readarea.core.security

import com.readarea.core.book.BookOpener
import com.readarea.core.format.BookFormat
import com.readarea.core.format.BookParseException
import com.readarea.core.format.TestBooks
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Algorithmic-complexity fuzzing. Mutating small books (see [FuzzTest]) can't find work that grows faster than
 * the input, because that only shows on large inputs with the right repetition: thousands of openers with no
 * closer, brackets inside brackets, runs of headings or ampersands. So this repeats short random patterns of
 * each format's syntax into a small and a four-times-larger book and times opening both. A linear parser takes
 * about four times as long on the larger one; quadratic work takes about sixteen.
 */
class ComplexityFuzzTest {
    private val small = 50_000
    private val large = 200_000

    private val markdown = listOf("**", "*", "_", "__", "~~", "`", "```", "[", "]", "(", ")", "![", "](", "<", ">", "<https://", "#", "# ", "-", "- ", "1. ", " ", "  ", "\n", "\n\n", "a", "b", "\"", "\\", "&", "|", "> ", "  \n", "---", "===", "\t")
    private val html = listOf("<", ">", "</", "/>", "<p>", "</p>", "<div>", "</div>", "<b>", "</b>", "<i>", "<span>", "<table>", "<tr>", "<td>", "<li>", "<ul>", "<ol>", "<ruby>", "<rt>", "<h1>", "<pre>", "<br>", "<hr>", "<sup>", "<a href=\"#x\">", "</a>", "<img src=\"i.png\">", "<p id=x>", "&amp;", "&#", "&#x", "&", ";", "\"", "'", "=", "a", " ", "\n", "<!--", "-->", "<![CDATA[", "]]>", "<script>", "</script>", "<style>", "</style>", "{", "}", "/*", "*/", "@media screen", ".c", "#i", ",", ":", "font-weight:bold", "<p class=\"c\">", "<p style=\"", "<svg>", "<math>", "<?", "?>", "<!DOCTYPE")
    private val text = listOf("Chapter 1", "CHAPTER", "\n", "\n\n", "\r\n", " ", "a", "Word", "［＃", "「", "」", "］", "は大見出し］", "《", "》", "｜", "*", "* * *", "-", "---", "第一章", "...", "1.", "IV", "\t", "　", "\u0000", "\uFEFF")
    private val rtf = listOf("{", "}", "\\", "\\par", "\\pard", "\\b", "\\b0", "\\i", "\\'e9", "\\'", "\\u1234?", "\\u", "\\bin3 ", "\\bin", "\\*", "{\\*\\x ", "{\\pict ", "\\pngblip ", "89504e47", "{\\fonttbl", "{\\f0 A;}", "\\f0", "\\ansicpg1252", "{\\info", "{\\title ", "a", " ", ";", "\\~", "\\-", "\\line", "\\tab", "\\page", "\\sect", "\\cell", "\\row", "\\uc2", "\n")
    private val fb2 = listOf("<section>", "</section>", "<p>", "</p>", "<title>", "</title>", "<emphasis>", "</emphasis>", "<strong>", "<a l:href=\"#n1\">", "</a>", "<image l:href=\"#c\"/>", "<epigraph>", "<poem>", "<stanza>", "<v>", "<cite>", "<empty-line/>", "<", ">", "&", "&#", "&amp;", "<!--", "-->", "<![CDATA[", "]]>", "a", " ", "\"", "=", "\n", "<table>", "<tr>", "<td>", "<subtitle>")
    private val docx = listOf("<w:p>", "</w:p>", "<w:r>", "</w:r>", "<w:t>", "</w:t>", "<w:t xml:space=\"preserve\">", "<w:pPr>", "</w:pPr>", "<w:pStyle w:val=\"Heading1\"/>", "<w:pStyle w:val=\"Title\"/>", "<w:numPr>", "<w:ilvl w:val=\"1\"/>", "<w:b/>", "<w:i/>", "<w:br/>", "<w:br w:type=\"page\"/>", "<w:tab/>", "<w:tbl>", "<w:tr>", "<w:tc>", "</w:tc>", "<w:hyperlink r:id=\"rId1\">", "</w:hyperlink>", "<w:bookmarkStart w:name=\"b\"/>", "<w:footnoteReference w:id=\"1\"/>", "<a:blip r:embed=\"rId7\"/>", "<w:drawing>", "<", ">", "&", "&#", "&amp;", "a", " ", "\"", "<!--", "<![CDATA[")
    private val odt = listOf("<text:p>", "</text:p>", "<text:h text:outline-level=\"1\">", "<text:h text:outline-level=\"2\">", "</text:h>", "<text:span>", "</text:span>", "<text:span text:style-name=\"B\">", "<text:list>", "<text:list-item>", "</text:list-item>", "<text:line-break/>", "<text:tab/>", "<text:s text:c=\"3\"/>", "<text:s text:c=\"99999999\"/>", "<text:a xlink:href=\"#x\">", "</text:a>", "<text:bookmark text:name=\"x\"/>", "<text:note>", "<text:note-body>", "<draw:frame>", "<draw:image xlink:href=\"a.png\"/>", "<table:table>", "<table:table-row>", "<table:table-cell>", "<", ">", "&", "&#", "a", " ", "\"", "<!--")
    private val mobi = listOf("<p>", "</p>", "<mbp:pagebreak/>", "<mbp:pagebreak", "filepos=", "filepos=00012", "<a filepos=12>", "</a>", "<h1>", "<b>", "<", ">", "&", "a", " ", "\"", "<img recindex=\"1\">", "\n")

    private class Pattern(val prefix: String, val unit: String) {
        fun expand(size: Int): String {
            val body = StringBuilder(size + 64).append(prefix)
            while (body.length < size) body.append(unit)
            return body.toString()
        }
    }

    private fun pattern(r: Random, tokens: List<String>): Pattern {
        val unit = StringBuilder()
        repeat(1 + r.nextInt(4)) { unit.append(tokens.random(r)) }
        // Sometimes a prefix before the repetition, which is where a lone opener or a header usually goes.
        val prefix = if (r.nextBoolean()) (1..r.nextInt(1, 4)).joinToString("") { tokens.random(r) } else ""
        return Pattern(prefix, unit.toString())
    }

    private fun bytes(format: BookFormat, content: String): ByteArray = when (format) {
        BookFormat.FB2 -> """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>T</book-title></title-info></description><body><section>$content</section></body></FictionBook>""".toByteArray()
        BookFormat.RTF -> "{\\rtf1\\ansi $content}".toByteArray()
        BookFormat.DOCX -> TestBooks.zip(
            linkedMapOf(
                "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".toByteArray(),
                "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com" TargetMode="External"/></Relationships>""".toByteArray(),
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body><w:p><w:r><w:t>$content</w:t></w:r></w:p></w:body></w:document>""".toByteArray(),
            ),
        )
        BookFormat.ODT -> TestBooks.zip(
            linkedMapOf(
                "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
                "content.xml" to """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:xlink="http://www.w3.org/1999/xlink"><office:body><office:text><text:p>$content</text:p></office:text></office:body></office:document-content>""".toByteArray(),
            ),
        )
        BookFormat.MOBI -> TestBooks.mobi("T", "A", "<html><body>$content</body></html>")
        BookFormat.EPUB -> TestBooks.zip(
            linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
                "c.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/><item id="s" href="s.css" media-type="text/css"/></manifest><spine><itemref idref="c"/></spine></package>""".toByteArray(),
                // The same text as the chapter's body and as its stylesheet, so both parsers see it.
                "c.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="s.css"/></head><body>$content</body></html>""".toByteArray(),
                "s.css" to content.toByteArray(),
            ),
        )
        else -> content.toByteArray()
    }

    private val ext = mapOf(BookFormat.MD to "md", BookFormat.HTML to "html", BookFormat.TXT to "txt", BookFormat.RTF to "rtf", BookFormat.FB2 to "fb2", BookFormat.MOBI to "mobi", BookFormat.EPUB to "epub", BookFormat.DOCX to "docx", BookFormat.ODT to "odt")

    /** Milliseconds to open [content] as [format], or null if it didn't finish within [limitMs]. */
    private fun time(format: BookFormat, content: String, limitMs: Long): Long? {
        val file = File.createTempFile("complexity", "." + ext[format])
        try {
            file.writeBytes(bytes(format, content))
            var error: Throwable? = null
            val t = Thread(null, {
                try {
                    runCatching { BookOpener.open(file, format).close() }.exceptionOrNull()?.let { if (it !is BookParseException && it !is IOException) throw it }
                } catch (e: Throwable) {
                    error = e
                }
            }, "complexity", 1 shl 20)
            t.isDaemon = true
            val start = System.nanoTime()
            t.start()
            t.join(limitMs)
            val ms = (System.nanoTime() - start) / 1_000_000
            if (t.isAlive) {
                t.interrupt()
                return null
            }
            error?.let { throw AssertionError("escaped with $it", it) }
            return ms
        } finally {
            file.delete()
        }
    }

    @Test
    fun largeRepetitiveBooksOpenInLinearTime() {
        val limit = 5000L
        val r = Random(20260928)
        val problems = ArrayList<String>()
        val grammars = listOf(BookFormat.MD to markdown, BookFormat.HTML to html, BookFormat.EPUB to html, BookFormat.TXT to text, BookFormat.RTF to rtf, BookFormat.FB2 to fb2, BookFormat.MOBI to mobi, BookFormat.DOCX to docx, BookFormat.ODT to odt)
        for ((format, tokens) in grammars) {
            repeat(if (format in setOf(BookFormat.EPUB, BookFormat.DOCX, BookFormat.ODT)) 30 else 120) { i ->
                val p = pattern(r, tokens)
                val label = "$format #$i '${(p.prefix + p.unit.repeat(3)).take(40).replace("\n", "\\n")}...'"
                val (smallText, largeText) = p.expand(small) to p.expand(large)
                try {
                    fun grows(): Boolean {
                        val a = time(format, smallText, limit) ?: return true
                        val b = time(format, largeText, limit) ?: return true
                        // Small times are mostly noise; only a slow large book that grew far past 4x counts.
                        return b > 400 && b > 9 * maxOf(a, 25)
                    }
                    // Measured again before it counts, in case a collection or the JIT landed in the wrong run.
                    if (grows() && grows()) problems.add("$label grows faster than its input")
                } catch (e: AssertionError) {
                    problems.add("$label ${e.message}")
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
