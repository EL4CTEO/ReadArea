package com.readarea.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.readarea.core.format.BookFormat
import com.readarea.core.format.BookMeta
import com.readarea.core.format.BookParseException
import com.readarea.core.format.ParseError
import com.readarea.core.format.BookPostProcessor
import com.readarea.core.format.DocxParser
import com.readarea.core.format.EpubParser
import com.readarea.core.format.Fb2Parser
import com.readarea.core.format.FileZipAccess
import com.readarea.core.format.MemoryZipAccess
import com.readarea.core.format.PathUtil
import com.readarea.core.format.HtmlConverter
import com.readarea.core.format.MarkdownParser
import com.readarea.core.format.MobiParser
import com.readarea.core.format.OdtParser
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.ResourceProvider
import com.readarea.core.format.RtfParser
import com.readarea.core.format.TextDecoder
import com.readarea.core.format.TxtParser
import com.readarea.core.format.ZipAccess
import com.readarea.core.format.Chapter
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

sealed interface OpenedBook {
    val meta: BookMeta
    fun close()
}

class ReflowableBook(val book: ParsedBook, private val onClose: () -> Unit = {}) : OpenedBook {
    override val meta: BookMeta get() = book.meta
    override fun close() = onClose()
}

class FixedBook(override val meta: BookMeta, val source: FixedSource) : OpenedBook {
    override fun close() = source.close()
}

interface FixedSource {
    val pageCount: Int
    fun pageAspect(index: Int): Float
    fun render(index: Int, target: Bitmap, crop: RectF?)
    fun contentBounds(index: Int): RectF? = null
    fun close()
}

class PdfSource(private val pfd: ParcelFileDescriptor) : FixedSource {
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    private val aspects = HashMap<Int, Float>()
    override val pageCount: Int = renderer.pageCount

    override fun pageAspect(index: Int): Float = synchronized(lock) {
        aspects.getOrPut(index) {
            renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
        }
    }

    override fun render(index: Int, target: Bitmap, crop: RectF?) {
        synchronized(lock) {
            if (index !in 0 until pageCount) return
            target.eraseColor(Color.WHITE)
            renderer.openPage(index).use { page ->
                val src = crop ?: RectF(0f, 0f, 1f, 1f)
                val pw = page.width * src.width()
                val ph = page.height * src.height()
                val scale = minOf(target.width / pw, target.height / ph)
                val dx = (target.width - pw * scale) / 2f
                val dy = (target.height - ph * scale) / 2f
                val m = Matrix()
                m.postTranslate(-src.left * page.width, -src.top * page.height)
                m.postScale(scale, scale)
                m.postTranslate(dx, dy)
                page.render(target, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun contentBounds(index: Int): RectF? {
        val bmp = Bitmap.createBitmap(200, (200 / pageAspect(index)).toInt().coerceIn(50, 600), Bitmap.Config.ARGB_8888)
        render(index, bmp, null)
        return ImageUtil.contentBounds(bmp).also { bmp.recycle() }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

class CbzSource(file: File) : FixedSource {
    private val zip = FileZipAccess(file)
    private val pages = zip.entries.filter { name ->
        val l = name.lowercase()
        !l.startsWith("__macosx") && (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp"))
    }.sortedWith { a, b -> naturalCompare(a, b) }
    private val aspects = HashMap<Int, Float>()
    override val pageCount: Int = pages.size

    fun bytes(index: Int): ByteArray? = pages.getOrNull(index)?.let { zip.read(it) }

    override fun pageAspect(index: Int): Float = synchronized(aspects) {
        aspects.getOrPut(index) {
            val b = bytes(index) ?: return@getOrPut 0.7f
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0.7f
        }
    }

    override fun render(index: Int, target: Bitmap, crop: RectF?) {
        target.eraseColor(Color.WHITE)
        val b = bytes(index) ?: return
        val bmp = ImageUtil.decodeSampled(b, target.width, target.height) ?: return
        val src = crop ?: RectF(0f, 0f, 1f, 1f)
        val sw = bmp.width * src.width()
        val sh = bmp.height * src.height()
        val scale = minOf(target.width / sw, target.height / sh)
        val dx = (target.width - sw * scale) / 2f
        val dy = (target.height - sh * scale) / 2f
        val m = Matrix()
        m.postTranslate(-src.left * bmp.width, -src.top * bmp.height)
        m.postScale(scale, scale)
        m.postTranslate(dx, dy)
        Canvas(target).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        bmp.recycle()
    }

    override fun close() = zip.close()

    companion object {
        fun naturalCompare(a: String, b: String): Int {
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
                if (c != 0) return c
            }
            return ra.size - rb.size
        }
    }
}

object ImageUtil {
    fun decodeSampled(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    fun contentBounds(bmp: Bitmap): RectF? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun dark(c: Int): Boolean {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return r + g + b < 690
        }
        var top = -1
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            var rowHas = false
            for (x in 0 until w) {
                if (dark(px[y * w + x])) {
                    rowHas = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (rowHas) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0 || right <= left) return null
        val pad = 0.02f
        return RectF(
            (left.toFloat() / w - pad).coerceAtLeast(0f),
            (top.toFloat() / h - pad).coerceAtLeast(0f),
            (right.toFloat() / w + pad).coerceAtMost(1f),
            (bottom.toFloat() / h + pad).coerceAtMost(1f),
        )
    }
}

object BookLoader {

    private fun metaZip(context: Context, uri: Uri, keep: (String, Long) -> Boolean): MemoryZipAccess {
        val input = if (uri.scheme == "file") File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Unable to open file")
        return input.use { MemoryZipAccess(it, keep) }
    }

    private fun isImage(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp")
    }

    private fun withCover(context: Context, uri: Uri, zip: ZipAccess, parsed: ParsedBook): ReflowableBook {
        val ref = parsed.meta.coverRef
        val bytes = ref?.let { r -> zip.read(r) ?: metaZip(context, uri) { name, _ -> name.equals(r, true) || PathUtil.decode(name).equals(r, true) }.read(r) }
        return ReflowableBook(ParsedBook(parsed.meta, emptyList(), emptyList(), ResourceProvider { p -> if (p == ref) bytes else null }))
    }

    private fun openMetadata(context: Context, uri: Uri, format: BookFormat, title: String): OpenedBook? = when (format) {
        BookFormat.EPUB -> {
            val zip = metaZip(context, uri) { name, _ ->
                val l = name.lowercase()
                l.endsWith(".opf") || l.endsWith(".xml") || l.endsWith(".ncx") || ((l.endsWith("html") || l.endsWith(".htm")) && (l.contains("cover") || l.contains("title")))
            }
            val parser = EpubParser(zip)
            val parsed = parser.parse(true)
            val coverRef = parsed.meta.coverRef ?: parser.firstSpineDocument()?.let { doc ->
                val docZip = metaZip(context, uri) { name, _ -> name.equals(doc, true) || PathUtil.decode(name).equals(doc, true) }
                EpubParser(docZip).firstImageIn(doc)
            }
            val coverBytes = coverRef?.let { ref -> zip.read(ref) ?: metaZip(context, uri) { name, _ -> name.equals(ref, true) || PathUtil.decode(name).equals(ref, true) }.read(ref) }
            val meta = (if (parsed.meta.title.isBlank()) parsed.meta.copy(title = title) else parsed.meta).copy(coverRef = coverRef)
            ReflowableBook(ParsedBook(meta, emptyList(), emptyList(), ResourceProvider { p -> if (p == coverRef) coverBytes else null }))
        }
        BookFormat.DOCX -> {
            val zip = metaZip(context, uri) { name, _ -> name == "docProps/core.xml" || name == "word/document.xml" || name == "word/_rels/document.xml.rels" || name.startsWith("docProps/thumbnail") }
            withCover(context, uri, zip, DocxParser(zip, title).parse(true))
        }
        BookFormat.ODT -> {
            val zip = metaZip(context, uri) { name, _ -> name == "meta.xml" || name == "content.xml" || name.equals("Thumbnails/thumbnail.png", true) }
            withCover(context, uri, zip, OdtParser(zip, title).parse(true))
        }
        BookFormat.CBZ -> {
            val names = metaZip(context, uri) { _, _ -> false }.entries
            val best = names.filter { isImage(it) && !it.lowercase().startsWith("__macosx") }.minWithOrNull { a, b -> CbzSource.naturalCompare(a, b) }
            val cover = best?.let { b -> metaZip(context, uri) { name, _ -> name == b }.read(b) }
            ReflowableBook(ParsedBook(BookMeta(title = title, coverRef = best), emptyList(), emptyList(), ResourceProvider { p -> if (p == best) cover else null }))
        }
        else -> null
    }

    fun open(context: Context, uriString: String, format: BookFormat, fileName: String, cacheKey: String, metadataOnly: Boolean = false): OpenedBook {
        val uri = uriString.toUri()
        val title = fileName.substringBeforeLast('.').let { if (it.endsWith(".fb2", true)) it.dropLast(4) else it }.replace('_', ' ').trim()
        if (metadataOnly) openMetadata(context, uri, format, title)?.let { return it }
        return when (format) {
            BookFormat.PDF -> {
                val pfd = openPfd(context, uri)
                FixedBook(BookMeta(title = title), PdfSource(pfd))
            }
            BookFormat.CBZ -> {
                val file = localCopy(context, uri, cacheKey, "cbz")
                FixedBook(BookMeta(title = title), CbzSource(file))
            }
            BookFormat.EPUB -> {
                val zip = FileZipAccess(localCopy(context, uri, cacheKey, "epub"))
                try {
                    val parsed = EpubParser(zip).parse(metadataOnly)
                    val fixed = if (parsed.meta.title.isBlank()) parsed.withTitle(title) else parsed
                    ReflowableBook(if (metadataOnly) fixed else BookPostProcessor.process(fixed, false)) { zip.close() }
                } catch (e: Exception) {
                    zip.close()
                    throw e
                }
            }
            BookFormat.DOCX -> zipBook(context, uri, cacheKey, "docx", metadataOnly) { DocxParser(it, title).parse(metadataOnly) }
            BookFormat.ODT -> zipBook(context, uri, cacheKey, "odt", metadataOnly) { OdtParser(it, title).parse(metadataOnly) }
            BookFormat.FB2 -> {
                val bytes = readBytes(context, uri).let { if (isZip(it)) unzipFirst(it, ".fb2") ?: throw BookParseException(ParseError.INVALID, "No FB2 file found in archive") else it }
                val parsed = Fb2Parser(TextDecoder.decode(bytes)).parse(metadataOnly)
                val fixed = if (parsed.meta.title.isBlank()) parsed.withTitle(title) else parsed
                ReflowableBook(if (metadataOnly) fixed else BookPostProcessor.process(fixed, false))
            }
            BookFormat.MOBI -> {
                val parsed = MobiParser(readBytes(context, uri), title).parse(metadataOnly)
                ReflowableBook(if (metadataOnly) parsed else BookPostProcessor.process(parsed, true))
            }
            BookFormat.TXT -> {
                if (metadataOnly) return ReflowableBook(ParsedBook(BookMeta(title), emptyList(), emptyList(), ResourceProvider { null }))
                ReflowableBook(BookPostProcessor.process(TxtParser(TextDecoder.decode(readBytes(context, uri)), title).parse(), false))
            }
            BookFormat.MD -> {
                val parsed = MarkdownParser(TextDecoder.decode(readBytes(context, uri), "UTF-8"), title).parse()
                ReflowableBook(if (metadataOnly) parsed else BookPostProcessor.process(parsed, true))
            }
            BookFormat.RTF -> {
                val parsed = RtfParser(readBytes(context, uri), title).parse()
                ReflowableBook(if (metadataOnly) parsed else BookPostProcessor.process(parsed, true))
            }
            BookFormat.HTML -> {
                val conv = HtmlConverter("index.html")
                val blocks = conv.convert(TextDecoder.decode(readBytes(context, uri)))
                val t = conv.docTitle ?: title
                val parsed = ParsedBook(BookMeta(title = t), listOf(Chapter(t, "index.html", blocks)), emptyList(), ResourceProvider { null })
                ReflowableBook(if (metadataOnly) parsed else BookPostProcessor.process(parsed, true))
            }
        }
    }

    private fun ParsedBook.withTitle(t: String) = ParsedBook(meta.copy(title = t), chapters, toc, resources)

    private fun zipBook(context: Context, uri: Uri, key: String, ext: String, metadataOnly: Boolean, parse: (FileZipAccess) -> ParsedBook): OpenedBook {
        val zip = FileZipAccess(localCopy(context, uri, key, ext))
        return try {
            val parsed = parse(zip)
            ReflowableBook(if (metadataOnly) parsed else BookPostProcessor.process(parsed, true)) { zip.close() }
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    fun openPfd(context: Context, uri: Uri): ParcelFileDescriptor {
        if (uri.scheme == "file") return ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        return context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Unable to open file")
    }

    fun readBytes(context: Context, uri: Uri): ByteArray {
        if (uri.scheme == "file") return File(uri.path!!).readBytes()
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException("Unable to open file")
    }

    fun localCopy(context: Context, uri: Uri, key: String, ext: String): File {
        if (uri.scheme == "file") return File(uri.path!!)
        val dir = File(context.cacheDir, "books").apply { mkdirs() }
        val target = File(dir, "$key.$ext")
        val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        if (target.exists() && (size <= 0 || target.length() == size)) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val tmp = File(dir, "$key.$ext.tmp")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: throw FileNotFoundException("Unable to open file")
        tmp.renameTo(target)
        trimCache(dir)
        return target
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > 400L * 1024 * 1024) f.delete()
        }
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun unzipFirst(bytes: ByteArray, suffix: String): ByteArray? {
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(suffix, true)) return zin.readBytes()
            }
        }
        return null
    }
}
