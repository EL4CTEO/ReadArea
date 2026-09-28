package com.readarea.core.book

import com.readarea.core.format.BookFormat
import com.readarea.core.format.TestBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FormatSnifferTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun detect(name: String, bytes: ByteArray) = FormatSniffer.detect(File(tmp.root, name).apply { writeBytes(bytes) })

    @Test
    fun contentWinsOverExtension() {
        assertEquals(BookFormat.EPUB, detect("book.pdf", TestBooks.epub()))
        assertEquals(BookFormat.EPUB, detect("noextension", TestBooks.epub()))
        assertEquals(BookFormat.PDF, detect("paper.epub", "%PDF-1.7\n...".toByteArray()))
        assertEquals(BookFormat.DOCX, detect("x.zip", TestBooks.docx("T", "A")))
        assertEquals(BookFormat.ODT, detect("x.bin", TestBooks.odt("T", "A")))
        assertEquals(BookFormat.MOBI, detect("x.dat", TestBooks.mobi("T", "A", "<p>x</p>")))
        assertEquals(BookFormat.RTF, detect("x.txt", "{\\rtf1 hi}".toByteArray()))
        assertEquals(BookFormat.FB2, detect("x.xml", "<?xml version='1.0'?><FictionBook></FictionBook>".toByteArray()))
        assertEquals(BookFormat.HTML, detect("page", "<!DOCTYPE html><html></html>".toByteArray()))
        assertEquals(BookFormat.CBZ, detect("comic.zip", TestBooks.zip(mapOf("01.jpg" to byteArrayOf(1)))))
        assertEquals(BookFormat.FB2, detect("b.zip", TestBooks.zip(mapOf("b.fb2" to "<FictionBook/>".toByteArray()))))
    }

    @Test
    fun plainTextNeedsATextExtensionAndNoBinary() {
        assertEquals(BookFormat.TXT, detect("a.txt", "just words".toByteArray()))
        assertEquals(BookFormat.MD, detect("a.md", "# heading".toByteArray()))
        assertNull(detect("a.txt", byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0, 1)))
        assertNull(detect("random.bin", "words".toByteArray()))
        assertNull(detect("junk.zip", TestBooks.zip(mapOf("a.exe" to byteArrayOf(0)))))
        assertEquals(BookFormat.TXT, detect("utf16.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0)))
    }

    @Test
    fun missingFileIsNull() {
        assertNull(FormatSniffer.detect(File(tmp.root, "nope.epub")))
    }
}
