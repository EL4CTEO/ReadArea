package com.readarea.core.book

import com.readarea.core.format.BookFormat
import com.readarea.core.format.BookMeta
import com.readarea.core.format.BookParseException
import com.readarea.core.format.BookPostProcessor
import com.readarea.core.format.Chapter
import com.readarea.core.format.CoverImages
import com.readarea.core.format.DocxParser
import com.readarea.core.format.EpubParser
import com.readarea.core.format.Fb2Parser
import com.readarea.core.format.FileZipAccess
import com.readarea.core.format.HtmlConverter
import com.readarea.core.format.Limits
import com.readarea.core.format.MarkdownParser
import com.readarea.core.format.MobiParser
import com.readarea.core.format.OdtParser
import com.readarea.core.format.ParseError
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.PathUtil
import com.readarea.core.format.ResourceProvider
import com.readarea.core.format.RtfParser
import com.readarea.core.format.TextDecoder
import com.readarea.core.format.TxtParser
import com.readarea.core.format.ZipAccess
import com.readarea.core.format.readCapped
import java.io.Closeable
import java.io.File
import java.util.zip.ZipInputStream

/** A reflowable book opened for reading. Closing it releases the file it reads resources from. */
class OpenBook(val book: ParsedBook, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() = onClose()
}

/** What the library shows for a book before it is opened: its metadata and cover image bytes. */
class BookDetails(val meta: BookMeta, val cover: ByteArray?)

/**
 * Opens books from files on disk. Used by the desktop app; the Android app streams from content URIs
 * with its own loader but hands the same parsers the same inputs.
 */
object BookOpener {

    fun titleFromFileName(name: String): String {
        var t = name.substringBeforeLast('.')
        if (t.endsWith(".fb2", true)) t = t.dropLast(4)
        return t.replace('_', ' ').replace(WS, " ").trim().ifEmpty { name }
    }

    /**
     * Opens a reflowable book. [format] must not be fixed layout (PDF and comics are rendered page by
     * page by the platform).
     */
    fun open(file: File, format: BookFormat, title: String = titleFromFileName(file.name)): OpenBook {
        require(!format.fixedLayout) { "$format is rendered as pages" }
        checkSize(file)
        return guarded { openParsed(file, format, title) }
    }

    private fun openParsed(file: File, format: BookFormat, title: String): OpenBook {
        return when (format) {
            BookFormat.EPUB -> zipBook(file, split = false) { EpubParser(it).parse().let { p -> if (p.meta.title.isBlank()) p.withTitle(title) else p } }
            BookFormat.DOCX -> zipBook(file, split = true) { DocxParser(it, title).parse() }
            BookFormat.ODT -> zipBook(file, split = true) { OdtParser(it, title).parse() }
            BookFormat.FB2 -> {
                val parsed = Fb2Parser(TextDecoder.decode(fb2Bytes(file))).parse()
                OpenBook(BookPostProcessor.process(if (parsed.meta.title.isBlank()) parsed.withTitle(title) else parsed, false))
            }
            BookFormat.MOBI -> OpenBook(BookPostProcessor.process(MobiParser(readBytes(file), title).parse(), true))
            BookFormat.TXT -> OpenBook(BookPostProcessor.process(TxtParser(TextDecoder.decode(readBytes(file)), title).parse(), false))
            BookFormat.MD -> {
                val parsed = MarkdownParser(TextDecoder.decode(readBytes(file), "UTF-8"), title).parse()
                OpenBook(BookPostProcessor.process(parsed.withResources(LocalImages(file.parentFile, "index.md")), true))
            }
            BookFormat.RTF -> OpenBook(BookPostProcessor.process(RtfParser(readBytes(file), title).parse(), true))
            BookFormat.HTML -> {
                val conv = HtmlConverter("index.html")
                val blocks = conv.convert(TextDecoder.decode(readBytes(file)))
                val t = conv.docTitle?.takeIf { it.isNotBlank() } ?: title
                val parsed = ParsedBook(BookMeta(title = t), listOf(Chapter(t, "index.html", blocks)), emptyList(), LocalImages(file.parentFile, "index.html"))
                OpenBook(BookPostProcessor.process(parsed, true))
            }
            BookFormat.PDF, BookFormat.CBZ -> error("unreachable")
        }
    }

    /** Title, author, cover and the rest, read without parsing the whole book where the format allows. */
    fun details(file: File, format: BookFormat, title: String = titleFromFileName(file.name)): BookDetails {
        checkSize(file)
        return guarded { readDetails(file, format, title) }
    }

    private fun readDetails(file: File, format: BookFormat, title: String): BookDetails {
        return when (format) {
            BookFormat.EPUB -> FileZipAccess(file).useZip { zip ->
                val parser = EpubParser(zip)
                val meta = parser.parse(metadataOnly = true).meta
                val coverRef = meta.coverRef ?: parser.firstSpineDocument()?.let { parser.firstImageIn(it) }
                BookDetails(meta.copy(title = meta.title.ifBlank { title }, coverRef = coverRef), coverRef?.let { zip.read(it) })
            }
            BookFormat.DOCX -> FileZipAccess(file).useZip { zip -> DocxParser(zip, title).parse(true).let { BookDetails(it.meta, it.coverBytes()) } }
            BookFormat.ODT -> FileZipAccess(file).useZip { zip -> OdtParser(zip, title).parse(true).let { BookDetails(it.meta, it.coverBytes()) } }
            BookFormat.CBZ -> FileZipAccess(file).useZip { zip ->
                val first = ComicPages.of(zip.entries).firstOrNull()
                BookDetails(BookMeta(title = title, coverRef = first), first?.let { zip.read(it) })
            }
            BookFormat.FB2 -> Fb2Parser(TextDecoder.decode(fb2Bytes(file))).parse(true).let { p -> BookDetails(p.meta.copy(title = p.meta.title.ifBlank { title }), p.coverBytes()) }
            BookFormat.MOBI -> MobiParser(readBytes(file), title).parse(true).let { BookDetails(it.meta, it.coverBytes()) }
            BookFormat.PDF, BookFormat.TXT -> BookDetails(BookMeta(title = title), null)
            BookFormat.MD, BookFormat.RTF, BookFormat.HTML -> open(file, format, title).use { BookDetails(it.book.meta, it.book.coverBytes()) }
        }
    }

    /**
     * The boundary between untrusted files and the app: whatever a malformed or hostile file makes a
     * parser do, callers only ever see a [BookParseException] (or an I/O error reading the file).
     */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: BookParseException) {
        throw e
    } catch (e: java.util.zip.ZipException) {
        throw BookParseException(ParseError.INVALID, "This file is damaged or isn't a valid book", e)
    } catch (e: java.io.IOException) {
        throw e
    } catch (e: RuntimeException) {
        throw BookParseException(ParseError.INVALID, "This file is damaged or isn't a valid book", e)
    } catch (e: StackOverflowError) {
        throw BookParseException(ParseError.INVALID, "This file is damaged or isn't a valid book", e)
    } catch (e: OutOfMemoryError) {
        throw BookParseException(ParseError.TOO_LARGE, "This book is too large to open", e)
    }

    private fun checkSize(file: File) {
        if (!file.isFile) throw java.io.FileNotFoundException(file.name)
        if (file.length() > Limits.FILE * 8L) throw BookParseException(ParseError.TOO_LARGE, "File is too large")
    }

    fun readBytes(file: File): ByteArray = file.inputStream().use { it.readCapped(Limits.FILE) }

    private fun fb2Bytes(file: File): ByteArray {
        val bytes = readBytes(file)
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return bytes
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".fb2", true)) return zin.readCapped(Limits.ENTRY)
            }
        }
        throw BookParseException(ParseError.INVALID, "No FB2 file found in archive")
    }

    private inline fun <T> FileZipAccess.useZip(block: (FileZipAccess) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun zipBook(file: File, split: Boolean, parse: (ZipAccess) -> ParsedBook): OpenBook {
        val zip = try {
            FileZipAccess(file)
        } catch (e: java.util.zip.ZipException) {
            throw BookParseException(ParseError.INVALID, "Not a valid archive")
        }
        return try {
            OpenBook(BookPostProcessor.process(parse(zip), split)) { zip.close() }
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }

    private fun ParsedBook.withTitle(t: String) = ParsedBook(meta.copy(title = t), chapters, toc, resources)

    private fun ParsedBook.withResources(r: ResourceProvider) = ParsedBook(meta, chapters, toc, r)

    private val WS = Regex("\\s+")
}

/**
 * Images next to a local HTML or Markdown file. Only raster images inside the file's own folder can be
 * read, so a document can't pull in arbitrary files through `../` or symlinks.
 */
class LocalImages(dir: File?, private val base: String) : ResourceProvider {
    private val root: File? = dir?.let { runCatching { it.canonicalFile }.getOrNull() }

    override fun read(path: String): ByteArray? {
        val r = root ?: return null
        if (path.contains(':') || !CoverImages.isRaster(path)) return null
        val rel = PathUtil.normalize(path)
        if (rel.isEmpty()) return null
        val f = runCatching { File(r, rel).canonicalFile }.getOrNull() ?: return null
        if (!f.path.startsWith(r.path + File.separator) || !f.isFile || f.length() > Limits.ENTRY) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    override fun toString(): String = "LocalImages($base)"
}

/** The page images of a comic archive, in reading order. */
object ComicPages {
    private val PARTS = Regex("\\d+|\\D+")

    fun isImage(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp")
    }

    fun of(entries: List<String>): List<String> = entries
        .filter { isImage(it) && !it.lowercase().startsWith("__macosx") && !it.substringAfterLast('/').startsWith(".") }
        .sortedWith { a, b -> naturalCompare(a, b) }

    fun naturalCompare(a: String, b: String): Int {
        val ra = PARTS.findAll(a.lowercase()).map { it.value }.toList()
        val rb = PARTS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) compareNumbers(x, y) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        if (a.length != b.length) return a.length - b.length
        return a.compareTo(b)
    }
}
