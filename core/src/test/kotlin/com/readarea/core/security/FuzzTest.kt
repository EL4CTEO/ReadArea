package com.readarea.core.security

import com.readarea.core.book.BookOpener
import com.readarea.core.format.BookFormat
import com.readarea.core.format.BookParseException
import com.readarea.core.format.TestBooks
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.random.Random

/**
 * Mutation fuzzing: thousands of damaged variants of valid books in every format, from flipped bytes and
 * truncation to mangled markup inside archives. Opening each must either work or fail with the app's own
 * error, quickly: never another exception, a stack overflow, a hang or runaway memory.
 */
class FuzzTest {
    private val seeds: Map<BookFormat, ByteArray> = mapOf(
        BookFormat.EPUB to TestBooks.epub(chapters = 3, paragraphs = 4),
        BookFormat.DOCX to TestBooks.docx("Report", "Sam"),
        BookFormat.ODT to TestBooks.odt("Notes", "Kim"),
        BookFormat.MOBI to TestBooks.mobi("Tale", "Ann", "<html><body><h1>One</h1><p>First <b>page</b>.</p><mbp:pagebreak/><h1>Two</h1><p>Second <a filepos=12>link</a>.</p></body></html>"),
        BookFormat.FB2 to """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><author><first-name>A</first-name><last-name>B</last-name></author><book-title>T</book-title><coverpage><image l:href="#c.png"/></coverpage></title-info></description><body><section><title><p>One</p></title><p>Text <emphasis>here</emphasis> and <a l:href="#n1">note</a>.</p><image l:href="#c.png"/></section></body><body name="notes"><section id="n1"><p>A note.</p></section></body><binary id="c.png" content-type="image/png">iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==</binary></FictionBook>""".toByteArray(),
        BookFormat.RTF to """{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0 Times;}}{\info{\title T}{\author A}}\pard\b Heading\b0\par Some \i text\i0 with 舒? dash and {\*\ignored stuff} a \'e9 accent.\par{\pict\pngblip 89504e47}\par\page Second page \bin4 abcd done.\par}""".toByteArray(),
        BookFormat.HTML to "<html><head><title>T</title><style>p{margin:1em} .x{font-weight:bold}</style></head><body><h1 id=a>One</h1><p class=x>Text &amp; <a href=\"#a\">link</a><br>more<img src=\"i.png\" alt=i></p><table><tr><td>cell</td></tr></table><ruby>漢<rt>かん</rt></ruby></body></html>".toByteArray(),
        BookFormat.MD to "# Title\n\nSome *text* with a [link](https://example.com) and ![img](a.png).\n\n- one\n- two\n\n> quote\n\n```\ncode\n```\n".toByteArray(),
        BookFormat.TXT to "Chapter 1\n\nIt was a dark night.\n\nChapter 2\n\nThe end.\n".toByteArray(),
    )

    private val ext = mapOf(BookFormat.EPUB to "epub", BookFormat.DOCX to "docx", BookFormat.ODT to "odt", BookFormat.MOBI to "mobi", BookFormat.FB2 to "fb2", BookFormat.RTF to "rtf", BookFormat.HTML to "html", BookFormat.MD to "md", BookFormat.TXT to "txt")

    private fun mutateBytes(r: Random, input: ByteArray): ByteArray {
        var b = input.copyOf()
        when (r.nextInt(5)) {
            0 -> repeat(1 + r.nextInt(20)) { if (b.isNotEmpty()) b[r.nextInt(b.size)] = r.nextInt(256).toByte() }
            1 -> b = b.copyOf(r.nextInt(b.size + 1))
            2 -> {
                val at = r.nextInt(b.size + 1)
                b = b.copyOfRange(0, at) + ByteArray(1 + r.nextInt(64)) { r.nextInt(256).toByte() } + b.copyOfRange(at, b.size)
            }
            3 -> if (b.size > 2) {
                val from = r.nextInt(b.size - 1)
                val to = from + r.nextInt(b.size - from)
                val at = r.nextInt(b.size + 1)
                b = b.copyOfRange(0, at) + b.copyOfRange(from, to) + b.copyOfRange(at, b.size)
            }
            else -> repeat(1 + r.nextInt(5)) {
                // Markup-shaped damage: stray brackets, quotes, entities and control words.
                val junk = listOf("<", ">", "</", "/>", "\"", "'", "&", "&#", "&#x", "{", "}", "\\", "\\u", "<![CDATA[", "<!--", "]]>", "\u0000", "￿").random(r).toByteArray()
                val at = r.nextInt(b.size + 1)
                b = b.copyOfRange(0, at) + junk + b.copyOfRange(at, b.size)
            }
        }
        return b
    }

    /** Damages the XML inside an archive while keeping the archive itself valid, to reach the parsers. */
    private fun mutateArchive(r: Random, input: ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(input)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        val markup = entries.keys.filter { it.endsWith(".xml") || it.endsWith(".opf") || it.endsWith("html") || it.endsWith(".ncx") }
        repeat(1 + r.nextInt(3)) {
            val name = markup.random(r)
            entries[name] = mutateBytes(r, entries[name]!!)
        }
        return TestBooks.zip(entries)
    }

    private val failures = ArrayList<String>()

    /** Parser bugs the boundary had to catch: not failures, but worth knowing about. */
    private val caught = java.util.TreeMap<String, Int>()

    private fun note(e: Throwable?) {
        val cause = (e as? BookParseException)?.cause ?: return
        if (cause is java.util.zip.ZipException) return
        val at = cause.stackTrace.firstOrNull { it.className.startsWith("com.readarea") }
        val key = "${cause::class.java.simpleName} at ${at?.className?.substringAfterLast('.')}.${at?.methodName}:${at?.lineNumber}"
        synchronized(caught) { caught[key] = (caught[key] ?: 0) + 1 }
    }

    private fun check(format: BookFormat, bytes: ByteArray, label: String) {
        val file = File.createTempFile("fuzz", "." + ext[format])
        try {
            file.writeBytes(bytes)
            var error: Throwable? = null
            val t = Thread(null, {
                try {
                    runCatching { BookOpener.details(file, format) }.exceptionOrNull()?.let { note(it); if (it !is BookParseException && it !is IOException) throw it }
                    runCatching { BookOpener.open(file, format).close() }.exceptionOrNull()?.let { note(it); if (it !is BookParseException && it !is IOException) throw it }
                } catch (e: Throwable) {
                    error = e
                }
            }, "fuzz", 1 shl 20)
            t.isDaemon = true
            t.start()
            t.join(5000)
            if (t.isAlive) failures.add("$label didn't finish within 5 s")
            error?.let { failures.add("$label escaped with ${it::class.java.name}: ${it.message}") }
        } finally {
            file.delete()
        }
    }

    @Test
    fun damagedBooksFailCleanly() {
        val r = Random(20260928)
        for ((format, seed) in seeds) {
            check(format, seed, "$format seed")
            repeat(250) { i -> check(format, mutateBytes(r, seed), "$format byte mutation $i") }
            if (format in setOf(BookFormat.EPUB, BookFormat.DOCX, BookFormat.ODT)) {
                repeat(250) { i -> check(format, mutateArchive(r, seed), "$format markup mutation $i") }
            }
        }
        caught.forEach { (k, n) -> println("caught at the boundary: $k (x$n)") }
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }
}
