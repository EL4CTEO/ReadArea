package com.readarea.core.book

import com.readarea.core.format.BookFormat
import com.readarea.core.format.BookParseException
import com.readarea.core.format.ParseError
import com.readarea.core.format.TestBooks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookOpenerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply { writeBytes(bytes) }

    @Test
    fun opensEpubWithChaptersAndToc() {
        val f = file("book.epub", TestBooks.epub(chapters = 3))
        BookOpener.open(f, BookFormat.EPUB).use { opened ->
            assertEquals("The Test Book", opened.book.meta.title)
            assertEquals("Ada Writer", opened.book.meta.author)
            assertEquals(4, opened.book.chapters.size)
            assertTrue(opened.book.toc.any { it.title == "Section 2.1" })
        }
    }

    @Test
    fun detailsReadCoverWithoutParsingChapters() {
        val f = file("book.epub", TestBooks.epub(coverImage = png))
        val d = BookOpener.details(f, BookFormat.EPUB)
        assertEquals("The Test Book", d.meta.title)
        assertArrayEquals(png, d.cover)
    }

    @Test
    fun detailsFallBackToFileNameTitle() {
        val f = file("My_Great_Notes.txt", "Hello there".toByteArray())
        assertEquals("My Great Notes", BookOpener.details(f, BookFormat.TXT).meta.title)
        assertEquals("Tale", BookOpener.titleFromFileName("Tale.fb2.zip"))
    }

    @Test
    fun opensEveryTextFormat() {
        val cases = mapOf(
            "a.txt" to "Chapter 1\n\nSome text here.\n\nChapter 2\n\nMore text.",
            "a.md" to "# Title\n\nSome *text*.\n\n## Two\n\nMore.",
            "a.html" to "<html><head><title>Doc</title></head><body><h1>Hi</h1><p>Para</p></body></html>",
            "a.rtf" to "{\\rtf1\\ansi {\\b Bold} text\\par Next\\par}",
            "a.fb2" to "<?xml version=\"1.0\"?><FictionBook><description><title-info><book-title>FB</book-title></title-info></description><body><section><p>Text</p></section></body></FictionBook>",
        )
        for ((name, content) in cases) {
            val f = file(name, content.toByteArray())
            val format = BookFormat.fromFileName(name)!!
            BookOpener.open(f, format).use { assertTrue(name, it.book.chapters.isNotEmpty()) }
        }
    }

    @Test
    fun opensZippedFb2() {
        val fb2 = "<?xml version=\"1.0\"?><FictionBook><description><title-info><book-title>Zipped</book-title></title-info></description><body><section><p>Text</p></section></body></FictionBook>"
        val f = file("z.fb2.zip", TestBooks.zip(mapOf("book.fb2" to fb2.toByteArray())))
        BookOpener.open(f, BookFormat.FB2).use { assertEquals("Zipped", it.book.meta.title) }
    }

    @Test
    fun opensDocxOdtAndMobi() {
        BookOpener.open(file("d.docx", TestBooks.docx("Doc", "Author")), BookFormat.DOCX).use { assertEquals("Doc", it.book.meta.title) }
        BookOpener.open(file("o.odt", TestBooks.odt("Odt", "Author")), BookFormat.ODT).use { assertEquals("Odt", it.book.meta.title) }
        BookOpener.open(file("m.mobi", TestBooks.mobi("Mobi", "Author", "<p>Hello world</p>")), BookFormat.MOBI).use { assertTrue(it.book.chapters.isNotEmpty()) }
    }

    @Test
    fun comicPagesAreNaturallySortedAndSkipJunk() {
        val pages = ComicPages.of(listOf("p10.jpg", "p2.jpg", "p1.png", "__MACOSX/p1.png", "notes.txt", "dir/.hidden.jpg", "p02.jpg"))
        assertEquals(listOf("p1.png", "p2.jpg", "p02.jpg", "p10.jpg"), pages)
        val cbz = file("c.cbz", TestBooks.zip(mapOf("2.png" to png, "1.png" to png)))
        assertEquals("1.png", BookOpener.details(cbz, BookFormat.CBZ).meta.coverRef)
        val huge = "9".repeat(5000)
        assertTrue(ComicPages.naturalCompare("a$huge", "a1") > 0)
    }

    @Test
    fun localImagesStayInsideTheFolder() {
        val dir = File(tmp.root, "doc").apply { mkdirs() }
        File(dir, "img").mkdirs()
        File(dir, "img/a.png").writeBytes(png)
        file("secret.png", png)
        val md = File(dir, "notes.md").apply { writeText("# T\n\n![a](img/a.png)\n\n![b](../secret.png)\n\n![c](/etc/passwd)") }
        BookOpener.open(md, BookFormat.MD).use { opened ->
            val r = opened.book.resources
            assertNotNull(r.read("img/a.png"))
            assertNull(r.read("../secret.png"))
            assertNull(r.read("secret.png"))
            assertNull(r.read("etc/passwd"))
            assertNull(r.read("file:///etc/passwd"))
        }
    }

    @Test
    fun localImagesRejectSymlinkEscapes() {
        val dir = File(tmp.root, "doc2").apply { mkdirs() }
        val outside = file("outside.png", png)
        val link = File(dir, "link.png")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }.onFailure { return }
        assertNull(LocalImages(dir, "index.md").read("link.png"))
    }

    @Test
    fun notAnArchiveIsInvalid() {
        val f = file("fake.epub", "not a zip".toByteArray())
        try {
            BookOpener.open(f, BookFormat.EPUB)
            fail()
        } catch (e: BookParseException) {
            assertEquals(ParseError.INVALID, e.reason)
        }
    }

    @Test
    fun encryptedEpubIsReportedAsDrm() {
        val epub = TestBooks.epub()
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(epub.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        entries["META-INF/encryption.xml"] = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
            <enc:CipherData><enc:CipherReference URI="OEBPS/text/ch1.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>""".toByteArray()
        val f = file("drm.epub", TestBooks.zip(entries))
        try {
            BookOpener.open(f, BookFormat.EPUB)
            fail()
        } catch (e: BookParseException) {
            assertEquals(ParseError.DRM, e.reason)
        }
        assertEquals("The Test Book", BookOpener.details(f, BookFormat.EPUB).meta.title)
    }

    @Test
    fun obfuscatedFontsAreNotDrm() {
        val epub = TestBooks.epub()
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(epub.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        entries["META-INF/encryption.xml"] = """<encryption xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><enc:CipherData><enc:CipherReference URI="OEBPS/fonts/a.otf"/></enc:CipherData></enc:EncryptedData></encryption>""".toByteArray()
        BookOpener.open(file("fonts.epub", TestBooks.zip(entries)), BookFormat.EPUB).use { assertEquals(4, it.book.chapters.size) }
    }
}
