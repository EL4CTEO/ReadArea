package com.readarea.desktop.security

import com.readarea.core.format.BookParseException
import com.readarea.desktop.book.CbzSource
import com.readarea.desktop.book.PasswordRequiredException
import com.readarea.desktop.book.PdfSource
import com.readarea.desktop.qa.SampleLibrary
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Damaged PDFs and comics through everything the reader does with them: open, count pages, render,
 * read the outline and text. Each must work or fail with the app's own errors, within a time limit.
 */
class PdfFuzzTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun mutate(r: Random, input: ByteArray): ByteArray {
        var b = input.copyOf()
        when (r.nextInt(4)) {
            0 -> repeat(1 + r.nextInt(30)) { b[r.nextInt(b.size)] = r.nextInt(256).toByte() }
            1 -> b = b.copyOf(r.nextInt(b.size))
            2 -> {
                // PDF syntax damage: stray delimiters, huge numbers, broken references.
                val junk = listOf("<<", ">>", "[", "]", "(", ")", "/", "R", " 999999999 0 R", "obj", "endobj", "stream", "endstream", "-1", "9999999999999", "%%EOF", "xref").random(r)
                val at = r.nextInt(b.size)
                b = b.copyOfRange(0, at) + junk.toByteArray() + b.copyOfRange(at, b.size)
            }
            else -> {
                val from = r.nextInt(b.size - 1)
                val to = from + r.nextInt(minOf(4096, b.size - from))
                val at = r.nextInt(b.size)
                b = b.copyOfRange(0, at) + b.copyOfRange(from, to) + b.copyOfRange(at, b.size)
            }
        }
        return b
    }

    private fun exercise(file: File, pdf: Boolean) {
        val src = try {
            if (pdf) PdfSource.open(file) else CbzSource(file)
        } catch (e: BookParseException) {
            return
        } catch (e: PasswordRequiredException) {
            return
        } catch (e: IOException) {
            return
        }
        src.use {
            for (i in 0 until minOf(it.pageCount, 3)) {
                it.pageAspect(i)
                it.render(i, 200, 280)
                it.pageText(i)
            }
            it.outline()
            it.documentInfo()
        }
    }

    @Test
    fun damagedPdfsAndComicsFailCleanly() {
        val dir = SampleLibrary.create(tmp.newFolder("books"))
        val seeds = listOf(File(dir, "Atlas of Clouds.pdf").readBytes() to true, File(dir, "Moonlit Harbor.cbz").readBytes() to false)
        val r = Random(7)
        val failures = ArrayList<String>()
        for ((seed, pdf) in seeds) repeat(150) { i ->
            val f = File(tmp.root, "fuzz$i." + if (pdf) "pdf" else "cbz")
            f.writeBytes(mutate(r, seed))
            var error: Throwable? = null
            val t = Thread(null, { try { exercise(f, pdf) } catch (e: Throwable) { error = e } }, "pdf-fuzz", 1 shl 20).apply { isDaemon = true; start() }
            t.join(10_000)
            if (t.isAlive) failures.add("${f.name} didn't finish within 10 s")
            error?.let { failures.add("${f.name}: ${it::class.java.name}: ${it.message}") }
        }
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }
}
