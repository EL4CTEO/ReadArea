package com.readarea.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class FormatParsersTest {

    @Test
    fun entitiesDecode() {
        assertEquals("a & b A  c &unknown; <", Entities.decode("a &amp; b &#x41; &nbsp;c &unknown; &lt"))
        assertEquals("—“q”", Entities.decode("&mdash;&ldquo;q&rdquo;"))
        assertEquals("😀", Entities.decode("&#128512;"))
    }

    @Test
    fun tokenizerHandlesCommentsCdataAndRawText() {
        val events = ArrayList<String>()
        HtmlTokenizer("<p class='x'>Hi<!-- no --><![CDATA[<b>]]><br/><style>p{color:red}</style><svg:image xlink:href=\"a.png\"/></p>").parse(object : HtmlHandler {
            override fun startTag(name: String, attrs: Attributes, selfClosing: Boolean) { events.add("<$name${if (selfClosing) "/" else ""}:${attrs.all}") }
            override fun endTag(name: String) { events.add("</$name>") }
            override fun text(text: String) { events.add("T:$text") }
            override fun rawText(tag: String, content: String) { events.add("R:$content") }
        })
        assertEquals(listOf("<p:{class=x}", "T:Hi", "T:<b>", "<br/:{}", "<style:{}", "R:p{color:red}", "</style>", "<image/:{xlink:href=a.png}", "</p>"), events)
    }

    @Test
    fun cssComputesClassAndInlineStyles() {
        val css = Stylesheet()
        css.add("/* c */ p { text-indent: 1em } p.first, .noindent { text-indent: 0 } div > .c { text-align: center } @media print { p { color: red } } a:hover { color: blue }")
        assertEquals("0", css.compute("p", "first", null, null)["text-indent"])
        assertEquals("1em", css.compute("p", null, null, null)["text-indent"])
        assertEquals("center", css.compute("span", "c", null, null)["text-align"])
        assertEquals("bold", css.compute("p", null, null, "font-weight: bold !important")["font-weight"])
        assertNull(css.compute("p", null, null, null)["color"])
        assertEquals(1.5f, Stylesheet.fontScale("150%")!!, 0.001f)
    }

    @Test
    fun htmlConverterBuildsBlocks() {
        val html = """
            <html><head><title>Doc</title><style>.c{text-align:center}.gone{display:none}</style></head>
            <body>
              <h1 id="top">Heading <i>one</i></h1>
              <p>First   paragraph with <b>bold</b>
                 and <a href="#top">link</a>.</p>
              <p class="c">Centered</p>
              <div class="gone"><p>Hidden</p></div>
              <ul><li>One</li><li>Two</li></ul>
              <ol start="3"><li>Three</li></ol>
              <blockquote><p>Quote</p></blockquote>
              <pre>  code
line</pre>
              <p>Line<br/>break</p>
              <hr/>
              <p><img src="img/a.png"/></p>
              <p><span id="end"></span></p>
            </body></html>
        """.trimIndent()
        val conv = HtmlConverter("text/ch.xhtml")
        val blocks = conv.convert(html)
        assertEquals("Doc", conv.docTitle)
        assertEquals("Heading one", conv.firstHeading)
        val kinds = blocks.map { it.kind }
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.LIST_ITEM, BlockKind.LIST_ITEM, BlockKind.LIST_ITEM, BlockKind.QUOTE, BlockKind.PRE, BlockKind.PARAGRAPH, BlockKind.RULE, BlockKind.IMAGE),
            kinds,
        )
        assertEquals("top", blocks[0].anchors.single().id)
        assertEquals("First paragraph with bold and link.", blocks[1].text)
        assertTrue(blocks[1].runs.any { it.style and RunStyle.BOLD != 0 && it.text == "bold" })
        assertEquals("text/ch.xhtml#top", blocks[1].runs.first { it.text == "link" }.link)
        assertEquals(Align.CENTER, blocks[2].align)
        assertEquals("• One", blocks[3].text)
        assertEquals("3. Three", blocks[5].text)
        assertEquals("  code\nline", blocks[7].text)
        assertEquals("Line\nbreak", blocks[8].text)
        assertTrue(blocks[8].noIndent)
        assertEquals("text/img/a.png", blocks[10].image)
        assertTrue(blocks.last().anchors.any { it.id == "end" })
        assertFalse(blocks.any { it.text.contains("Hidden") })
    }

    @Test
    fun epub3ParsesMetadataTocAndChapters() {
        val file = TestBooks.tempFile(TestBooks.epub(chapters = 3), "epub")
        val zip = FileZipAccess(file)
        val book = EpubParser(zip).parse()
        assertEquals("The Test Book", book.meta.title)
        assertEquals("Ada Writer", book.meta.author)
        assertEquals("en", book.meta.language)
        assertEquals("A test description.", book.meta.description)
        assertEquals("Tests", book.meta.series)
        assertEquals(2f, book.meta.seriesIndex!!, 0.01f)
        assertEquals("OEBPS/images/pic.png", book.meta.coverRef)
        assertNotNull(book.coverBytes())
        assertEquals(4, book.chapters.size)
        assertEquals("Chapter 1", book.chapters[0].title)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Section 2.1", "Chapter 3"), book.toc.map { it.title })
        assertEquals(1, book.toc[2].chapter)
        assertEquals("sec2", book.toc[2].anchor)
        assertEquals(1, book.toc[2].depth)
        val first = book.chapters[0].blocks
        assertEquals(BlockKind.HEADING, first[0].kind)
        assertTrue(first[1].noIndent)
        assertFalse(first[2].noIndent)
        assertTrue(first.any { it.kind == BlockKind.IMAGE && it.image == "OEBPS/images/pic.png" })
        val link = first.flatMap { it.runs }.first { it.link != null }.link
        assertEquals("OEBPS/text/notes.xhtml#n1", link)
        zip.close()
    }

    @Test
    fun epub2UsesNcxAndMetaCover() {
        val file = TestBooks.tempFile(TestBooks.epub(chapters = 2, epub3 = false), "epub")
        val book = EpubParser(FileZipAccess(file)).parse()
        assertEquals(listOf("Chapter 1", "Chapter 2"), book.toc.map { it.title })
        assertEquals("OEBPS/images/pic.png", book.meta.coverRef)
    }

    @Test
    fun postProcessorSplitsLargeChaptersAndRemapsToc() {
        val big = (1..400).map { Block(BlockKind.PARAGRAPH, listOf(Run(TestBooks.lorem(60, it)))) }
        val withAnchor = big.toMutableList().also { it[300] = it[300].copy(anchors = listOf(Anchor("deep", 0))) }
        val book = ParsedBook(
            BookMeta("Big"),
            listOf(Chapter("Intro", "a.html", listOf(Block(BlockKind.PARAGRAPH, listOf(Run("Hi"))))), Chapter("Long", "b.html", withAnchor)),
            listOf(TocItem("Intro", 0, null, 0), TocItem("Long", 1, null, 0), TocItem("Deep", 1, "deep", 1)),
            ResourceProvider { null },
        )
        val out = BookPostProcessor.process(book, false)
        assertTrue(out.chapters.size >= 3)
        assertTrue(out.chapters.all { it.textLength <= 70_000 })
        assertEquals(1, out.toc[1].chapter)
        val deep = out.toc[2]
        assertTrue(out.chapters[deep.chapter].blocks.any { b -> b.anchors.any { it.id == "deep" } })
        assertEquals(big.sumOf { it.length }, out.chapters.drop(1).sumOf { c -> c.blocks.sumOf { it.length } })
    }

    @Test
    fun postProcessorSplitsSingleDocumentByHeadings() {
        val blocks = ArrayList<Block>()
        for (i in 1..3) {
            blocks.add(Block(BlockKind.HEADING, listOf(Run("Part $i")), level = 2))
            repeat(3) { blocks.add(Block(BlockKind.PARAGRAPH, listOf(Run(TestBooks.lorem(20, it))))) }
        }
        val out = BookPostProcessor.process(ParsedBook(BookMeta("Doc"), listOf(Chapter("Doc", "d", blocks)), emptyList(), ResourceProvider { null }), true)
        assertEquals(3, out.chapters.size)
        assertEquals(listOf("Part 1", "Part 2", "Part 3"), out.toc.map { it.title })
    }

    @Test
    fun fb2ParsesSectionsNotesAndCover() {
        val cover = java.util.Base64.getEncoder().encodeToString(TestBooks.PNG_1x1)
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
 <description><title-info>
   <genre>sf</genre><author><first-name>Ivan</first-name><last-name>Petrov</last-name></author>
   <book-title>Stars &amp; Ships</book-title>
   <annotation><p>About the book.</p></annotation>
   <coverpage><image l:href="#cover.png"/></coverpage>
   <lang>ru</lang><sequence name="Saga" number="3"/>
 </title-info></description>
 <body>
  <title><p>Stars &amp; Ships</p></title>
  <section id="s1"><title><p>Chapter One</p></title>
    <epigraph><p>Per aspera</p><text-author>Seneca</text-author></epigraph>
    <p>Text with <emphasis>style</emphasis> and a note<a l:href="#n1" type="note">1</a>.</p>
    <empty-line/>
    <poem><stanza><v>Line one</v><v>Line two</v></stanza></poem>
    <section><title><p>Part A</p></title><p>Nested.</p></section>
  </section>
  <section><title><p>Chapter Two</p></title><p>More text.</p><image l:href="#cover.png"/></section>
 </body>
 <body name="notes"><title><p>Notes</p></title><section id="n1"><title><p>1</p></title><p>The note.</p></section></body>
 <binary id="cover.png" content-type="image/png">$cover</binary>
</FictionBook>"""
        val book = Fb2Parser(xml).parse()
        assertEquals("Stars & Ships", book.meta.title)
        assertEquals("Ivan Petrov", book.meta.author)
        assertEquals("Saga", book.meta.series)
        assertEquals("cover.png", book.meta.coverRef)
        assertNotNull(book.coverBytes())
        assertEquals(3, book.chapters.size)
        assertEquals(listOf("Chapter One", "Part A", "Chapter Two", "Notes"), book.toc.map { it.title })
        val c1 = book.chapters[0].blocks
        assertTrue(c1.any { it.kind == BlockKind.QUOTE && it.text == "Per aspera" })
        assertTrue(c1.any { it.kind == BlockKind.VERSE && it.text == "Line one" })
        assertTrue(c1.any { it.kind == BlockKind.HEADING && it.text == "Part A" })
        assertTrue(c1.flatMap { it.runs }.any { it.link?.endsWith("#n1") == true && it.style and RunStyle.SUP != 0 })
        assertTrue(book.chapters[2].blocks.any { b -> b.anchors.any { it.id == "n1" } })
        assertTrue(book.chapters[1].blocks.any { it.kind == BlockKind.IMAGE && it.image == "cover.png" })
    }

    @Test
    fun txtDetectsChaptersAndJoinsHardWrappedLines() {
        val wrapped = buildString {
            append("CHAPTER 1\n\n")
            repeat(6) {
                val words = TestBooks.lorem(60, it).split(' ')
                var line = StringBuilder()
                for (w in words) {
                    if (line.length + w.length + 1 > 70) {
                        append(line.toString().trim()).append('\n')
                        line = StringBuilder()
                    }
                    line.append(w).append(' ')
                }
                append(line.toString().trim()).append("\n\n")
            }
            append("Chapter 2: The Return\n\n")
            append(TestBooks.lorem(50, 9)).append("\n")
        }
        val book = TxtParser(wrapped, "Story").parse()
        assertEquals(listOf("CHAPTER 1", "Chapter 2: The Return"), book.toc.map { it.title })
        val paras = book.chapters[0].blocks.filter { it.kind == BlockKind.PARAGRAPH }
        assertEquals(6, paras.size)
        assertTrue(paras.all { !it.text.contains('\n') && it.text.length > 200 })
    }

    @Test
    fun txtWithoutChaptersIsOneChapter() {
        val book = TxtParser("Line one\nLine two\n\n\nLine three", "Plain").parse()
        assertEquals(1, book.chapters.size)
        assertEquals(listOf("Line one", "Line two", " ", "Line three"), book.chapters[0].blocks.map { it.text })
    }

    @Test
    fun markdownConvertsBlocksAndInline() {
        val md = "# Title\n\nSome **bold**, *italic* and `code` with [link](http://x.y).\n\n- one\n- two\n  - nested\n\n1. first\n\n> quoted\n\n```\nraw <b>\n```\n\n---\n\nSetext\n======\n"
        val book = MarkdownParser(md, "f").parse()
        val b = book.chapters[0].blocks
        assertEquals("Title", book.meta.title)
        assertEquals(BlockKind.HEADING, b[0].kind)
        assertTrue(b[1].runs.any { it.text == "bold" && it.style and RunStyle.BOLD != 0 })
        assertTrue(b[1].runs.any { it.text == "italic" && it.style and RunStyle.ITALIC != 0 })
        assertTrue(b[1].runs.any { it.text == "code" && it.style and RunStyle.MONO != 0 })
        assertTrue(b[1].runs.any { it.link == "http://x.y" })
        assertEquals(listOf("• one", "• two", "◦ nested", "1. first"), b.filter { it.kind == BlockKind.LIST_ITEM }.map { it.text })
        assertTrue(b.any { it.kind == BlockKind.QUOTE && it.text == "quoted" })
        assertTrue(b.any { it.kind == BlockKind.PRE && it.text == "raw <b>" })
        assertTrue(b.any { it.kind == BlockKind.RULE })
        assertTrue(b.any { it.kind == BlockKind.HEADING && it.text == "Setext" })
    }

    @Test
    fun rtfParsesFormattingAndEncodings() {
        val rtf = "{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f0 Times;}}{\\info{\\title My Doc}{\\author Jane}}\\pard\\qc\\b Title\\b0\\par\\pard Caf\\'e9 costs \\u8364? 5 \\i italic\\i0 .\\line next\\par{\\*\\generator Word;}}"
        val book = RtfParser(rtf.toByteArray(Charsets.ISO_8859_1), "f").parse()
        assertEquals("My Doc", book.meta.title)
        assertEquals("Jane", book.meta.author)
        val b = book.chapters[0].blocks
        assertEquals("Title", b[0].text)
        assertEquals(Align.CENTER, b[0].align)
        assertTrue(b[0].runs.all { it.style and RunStyle.BOLD != 0 })
        assertEquals("Café costs € 5 italic.\nnext", b[1].text)
        assertFalse(b.any { it.text.contains("Word") || it.text.contains("Times") })
    }

    @Test
    fun docxParsesHeadingsRunsAndImages() {
        val doc = """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="w" xmlns:r="r" xmlns:a="a"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Intro</w:t></w:r></w:p>
<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">Bold </w:t></w:r><w:r><w:rPr><w:i/><w:b w:val="0"/></w:rPr><w:t>italic</w:t></w:r><w:r><w:tab/><w:t>tab</w:t></w:r></w:p>
<w:p><w:r><w:drawing><a:blip r:embed="rId5"/></w:drawing></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Second</w:t></w:r></w:p>
<w:p><w:r><w:t>Body</w:t></w:r><w:r><w:instrText>HYPERLINK x</w:instrText></w:r><w:del><w:r><w:delText>gone</w:delText></w:r></w:del></w:p>
</w:body></w:document>"""
        val styles = """<w:styles xmlns:w="w"><w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val rels = """<Relationships><Relationship Id="rId5" Target="media/image1.png"/></Relationships>"""
        val core = """<cp:coreProperties xmlns:dc="dc"><dc:title>Word Doc</dc:title><dc:creator>Sam</dc:creator></cp:coreProperties>"""
        val file = TestBooks.tempFile(TestBooks.zip(mapOf("word/document.xml" to doc.toByteArray(), "word/styles.xml" to styles.toByteArray(), "word/_rels/document.xml.rels" to rels.toByteArray(), "docProps/core.xml" to core.toByteArray(), "word/media/image1.png" to TestBooks.PNG_1x1)), "docx")
        val book = BookPostProcessor.process(DocxParser(FileZipAccess(file), "f").parse(), true)
        assertEquals("Word Doc", book.meta.title)
        assertEquals("Sam", book.meta.author)
        assertEquals(listOf("Intro", "Second"), book.toc.map { it.title })
        val b = book.chapters[0].blocks
        assertEquals(BlockKind.HEADING, b[0].kind)
        assertEquals("Bold italic tab", b[1].text)
        assertEquals(Align.CENTER, b[1].align)
        assertTrue(b[1].runs.first().style and RunStyle.BOLD != 0)
        assertTrue(b[1].runs[1].style and RunStyle.ITALIC != 0 && b[1].runs[1].style and RunStyle.BOLD == 0)
        assertEquals("word/media/image1.png", b[2].image)
        assertEquals("Body", book.chapters[1].blocks.last().text)
    }

    @Test
    fun odtParsesStylesAndLists() {
        val content = """<office:document-content xmlns:office="o" xmlns:style="s" xmlns:text="t" xmlns:fo="f">
<office:automatic-styles>
 <style:style style:name="T1" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
 <style:style style:name="P1" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
</office:automatic-styles>
<office:body><office:text>
 <text:h text:outline-level="1">Head</text:h>
 <text:p text:style-name="P1">Mid <text:span text:style-name="T1">bold</text:span><text:s text:c="2"/>end<text:line-break/>next</text:p>
 <text:list><text:list-item><text:p>Item</text:p></text:list-item></text:list>
</office:text></office:body></office:document-content>"""
        val meta = """<office:document-meta><office:meta><dc:title>Odt Title</dc:title><meta:initial-creator>Kim</meta:initial-creator></office:meta></office:document-meta>"""
        val file = TestBooks.tempFile(TestBooks.zip(mapOf("content.xml" to content.toByteArray(), "meta.xml" to meta.toByteArray())), "odt")
        val book = OdtParser(FileZipAccess(file), "f").parse()
        assertEquals("Odt Title", book.meta.title)
        assertEquals("Kim", book.meta.author)
        val b = book.chapters[0].blocks
        assertEquals(BlockKind.HEADING, b[0].kind)
        assertEquals("Mid bold  end\nnext", b[1].text)
        assertEquals(Align.CENTER, b[1].align)
        assertTrue(b[1].runs.any { it.text == "bold" && it.style and RunStyle.BOLD != 0 })
        assertEquals("• Item", b[2].text)
    }

    @Test
    fun palmDocDecompression() {
        val input = byteArrayOf(0x61, 0x62, 0x63, 0x80.toByte(), 0x18, 0xE1.toByte(), 0x02, 0x7A, 0x7A)
        assertEquals("abcabc azz", String(MobiParser.palmDocDecompress(input), Charsets.ISO_8859_1))
        val data = byteArrayOf(1, 2, 3, 4, 0x81.toByte())
        assertEquals(1, MobiParser.trailingSize(data, 0x2))
    }

    @Test
    fun mobiParsesHeaderExthAndText() {
        val html = "<html><body><h1>Chapter One</h1><p>Hello <b>mobi</b> world.</p><mbp:pagebreak/><h1>Chapter Two</h1><p>Second part, see <a filepos=0000000010>start</a>.</p></body></html>"
        val book = MobiParser(buildMobi("Mobi Title", "Mo Author", html), "f").parse()
        assertEquals("Mobi Title", book.meta.title)
        assertEquals("Mo Author", book.meta.author)
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter One", book.chapters[0].title)
        assertTrue(book.chapters[0].blocks.any { it.text == "Hello mobi world." })
        assertTrue(book.chapters.flatMap { it.blocks }.flatMap { it.runs }.any { it.link?.endsWith("#filepos10") == true })
        assertTrue(book.chapters.flatMap { it.blocks }.any { b -> b.anchors.any { it.id == "filepos10" } })
    }

    @Test
    fun textDecoderDetectsEncodings() {
        assertEquals("héllo", TextDecoder.decode("héllo".toByteArray(Charsets.UTF_8)))
        val cyr = "Привет мир, это проверка кодировки текста для чтения книг".repeat(5)
        assertEquals(cyr, TextDecoder.decode(cyr.toByteArray(charset("windows-1251"))))
        assertEquals("café", TextDecoder.decode("café".toByteArray(charset("windows-1252"))))
        assertEquals("bom", TextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bom".toByteArray()))
        assertEquals("déjà", TextDecoder.decode("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>déjà".toByteArray(Charsets.ISO_8859_1)).substringAfter("?>"))
    }

    @Test
    fun pathUtilResolvesRelativePaths() {
        assertEquals("OEBPS/images/a.png", PathUtil.resolve("OEBPS/text/ch1.xhtml", "../images/a.png"))
        assertEquals("OEBPS/text/My File.xhtml", PathUtil.resolve("OEBPS/text/ch1.xhtml", "My%20File.xhtml"))
        assertEquals("a.png", PathUtil.resolve("ch.xhtml", "./a.png"))
        assertEquals("root/x", PathUtil.resolve("a/b/c.html", "/root/x"))
    }

    private fun buildMobi(title: String, author: String, html: String): ByteArray {
        val text = html.toByteArray(Charsets.UTF_8)
        val exthRecords = ByteArrayOutputStream()
        val authorBytes = author.toByteArray()
        exthRecords.write(ByteBuffer.allocate(8).putInt(100).putInt(authorBytes.size + 8).array())
        exthRecords.write(authorBytes)
        val exthBody = exthRecords.toByteArray()
        val exth = ByteBuffer.allocate(12 + exthBody.size).put("EXTH".toByteArray()).putInt(12 + exthBody.size).putInt(1).put(exthBody).array()
        val mobiHeaderLen = 0xE8
        val titleBytes = title.toByteArray()
        val r0 = ByteBuffer.allocate(16 + mobiHeaderLen + exth.size + titleBytes.size + 4)
        r0.putShort(0, 1)
        r0.putInt(4, text.size)
        r0.putShort(8, 1)
        r0.putShort(10, 4096)
        r0.putShort(12, 0)
        r0.position(16)
        r0.put("MOBI".toByteArray())
        r0.putInt(20, mobiHeaderLen)
        r0.putInt(24, 2)
        r0.putInt(28, 65001)
        val nameOff = 16 + mobiHeaderLen + exth.size
        r0.putInt(84, nameOff)
        r0.putInt(88, titleBytes.size)
        r0.putInt(108, -1)
        r0.putInt(128, 0x40)
        r0.putShort(0xF2, 0)
        r0.position(16 + mobiHeaderLen)
        r0.put(exth)
        r0.put(titleBytes)
        val record0 = r0.array()
        val headerSize = 78 + 2 * 8 + 2
        val out = ByteBuffer.allocate(headerSize + record0.size + text.size)
        val name = "Mobi_Title".toByteArray()
        out.put(name)
        out.position(60)
        out.put("BOOKMOBI".toByteArray())
        out.putShort(76, 2)
        out.putInt(78, headerSize)
        out.putInt(86, headerSize + record0.size)
        out.position(headerSize)
        out.put(record0)
        out.put(text)
        return out.array()
    }
}
