package com.readarea.core.format

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestBooks {
    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun tempFile(bytes: ByteArray, ext: String): File = File.createTempFile("book", ".$ext").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    private val loremWords = "the quick brown fox jumps over a lazy dog while reading an old book by candle light and thinking about distant seas".split(' ')

    fun lorem(words: Int, seed: Int = 1): String {
        val r = java.util.Random(seed.toLong())
        val sb = StringBuilder()
        for (i in 0 until words) {
            if (i > 0) sb.append(' ')
            var w = loremWords[r.nextInt(loremWords.size)]
            if (i == 0) w = w.replaceFirstChar { it.uppercase() }
            sb.append(w)
            if (i % 13 == 12) sb.append(',')
        }
        sb.append('.')
        return sb.toString()
    }

    fun epub(chapters: Int = 3, paragraphs: Int = 8, withCover: Boolean = true, epub3: Boolean = true, title: String = "The Test Book", author: String = "Ada Writer", coverImage: ByteArray? = null): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        files["mimetype"] = "application/epub+zip".toByteArray()
        files["META-INF/container.xml"] = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray()
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val nav = StringBuilder()
        val ncx = StringBuilder()
        for (i in 1..chapters) {
            manifest.append("""<item id="c$i" href="text/ch$i.xhtml" media-type="application/xhtml+xml"/>""")
            spine.append("""<itemref idref="c$i"/>""")
            nav.append("""<li><a href="text/ch$i.xhtml">Chapter $i</a>${if (i == 2) "<ol><li><a href=\"text/ch2.xhtml#sec2\">Section 2.1</a></li></ol>" else ""}</li>""")
            ncx.append("""<navPoint id="n$i"><navLabel><text>Chapter $i</text></navLabel><content src="text/ch$i.xhtml"/></navPoint>""")
            val body = StringBuilder("<h1>Chapter $i</h1>")
            for (p in 1..paragraphs) {
                if (i == 2 && p == 3) body.append("<h2 id=\"sec2\">Section 2.1</h2>")
                body.append("<p class=\"").append(if (p == 1) "first" else "body").append("\">")
                body.append(lorem(40 + p * 7, i * 100 + p))
                if (p == 2) body.append(" <em>emphasis</em> and <strong>strong</strong> text<a href=\"notes.xhtml#n$i\"><sup>$i</sup></a>")
                body.append("</p>")
            }
            if (i == 1 && withCover) body.append("<p><img src=\"../images/pic.png\" alt=\"pic\"/></p>")
            files["OEBPS/text/ch$i.xhtml"] = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Chapter $i</title><link rel="stylesheet" type="text/css" href="../styles/book.css"/></head>
<body>$body</body></html>""".toByteArray()
        }
        manifest.append("""<item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>""")
        spine.append("""<itemref idref="notes" linear="no"/>""")
        files["OEBPS/text/notes.xhtml"] = """<html><body><aside id="n1" epub:type="footnote"><p>First footnote text.</p></aside><aside id="n2"><p>Second note.</p></aside><aside id="n3"><p>Third note.</p></aside></body></html>""".toByteArray()
        manifest.append("""<item id="css" href="styles/book.css" media-type="text/css"/>""")
        files["OEBPS/styles/book.css"] = """p.first { text-indent: 0; } h1 { text-align: center; } .hidden { display:none }""".toByteArray()
        if (withCover) {
            manifest.append("""<item id="cover-img" href="images/pic.png" media-type="image/png" ${if (epub3) "properties=\"cover-image\"" else ""}/>""")
            files["OEBPS/images/pic.png"] = coverImage ?: PNG_1x1
        }
        if (epub3) {
            manifest.append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            files["OEBPS/nav.xhtml"] = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>""".toByteArray()
        } else {
            manifest.append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            files["OEBPS/toc.ncx"] = """<ncx><navMap>$ncx</navMap></ncx>""".toByteArray()
        }
        files["OEBPS/content.opf"] = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="${if (epub3) "3.0" else "2.0"}">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>$title</dc:title>
    <dc:creator opf:role="aut">$author</dc:creator>
    <dc:language>en</dc:language>
    <dc:description>&lt;p&gt;A &lt;b&gt;test&lt;/b&gt; description.&lt;/p&gt;</dc:description>
    <meta name="calibre:series" content="Tests"/>
    <meta name="calibre:series_index" content="2"/>
    ${if (!epub3 && withCover) "<meta name=\"cover\" content=\"cover-img\"/>" else ""}
  </metadata>
  <manifest>$manifest</manifest>
  <spine${if (!epub3) " toc=\"ncx\"" else ""}>$spine</spine>
</package>""".toByteArray()
        return zip(files)
    }

    fun docx(title: String, author: String, thumbnail: ByteArray? = null, leadingImage: ByteArray? = null): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        files["[Content_Types].xml"] = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".toByteArray()
        files["docProps/core.xml"] = """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator></cp:coreProperties>""".toByteArray()
        if (thumbnail != null) files["docProps/thumbnail.jpeg"] = thumbnail
        val rels = StringBuilder()
        val body = StringBuilder()
        if (leadingImage != null) {
            files["word/media/image1.png"] = leadingImage
            rels.append("""<Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>""")
            body.append("""<w:p><w:r><w:drawing><wp:inline><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="rId7"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
        }
        body.append("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>$title</w:t></w:r></w:p>""")
        repeat(6) { body.append("<w:p><w:r><w:t>").append(lorem(60, it + 11)).append("</w:t></w:r></w:p>") }
        files["word/_rels/document.xml.rels"] = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""".toByteArray()
        files["word/document.xml"] = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body>$body</w:body></w:document>""".toByteArray()
        return zip(files)
    }

    fun odt(title: String, author: String, thumbnail: ByteArray? = null): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        files["mimetype"] = "application/vnd.oasis.opendocument.text".toByteArray()
        files["meta.xml"] = """<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"><office:meta><dc:title>$title</dc:title><meta:initial-creator>$author</meta:initial-creator></office:meta></office:document-meta>""".toByteArray()
        val body = StringBuilder("<text:h text:outline-level=\"1\">$title</text:h>")
        repeat(6) { body.append("<text:p>").append(lorem(60, it + 21)).append("</text:p>") }
        files["content.xml"] = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>$body</office:text></office:body></office:document-content>""".toByteArray()
        if (thumbnail != null) files["Thumbnails/thumbnail.png"] = thumbnail
        return zip(files)
    }

    val PNG_1x1: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

    fun mobi(title: String, author: String, html: String): ByteArray {
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
