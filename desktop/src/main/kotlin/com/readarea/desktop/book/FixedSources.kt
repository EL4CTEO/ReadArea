package com.readarea.desktop.book

import com.readarea.core.book.ComicPages
import com.readarea.core.format.BookParseException
import com.readarea.core.format.FileZipAccess
import com.readarea.core.format.ParseError
import com.readarea.core.format.SafeText
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File

data class FixedTocEntry(val title: String, val page: Int, val depth: Int)

/** A book made of pages drawn as images: PDFs and comics. */
interface FixedSource : Closeable {
    val pageCount: Int

    /** PDFs can be recolored to the reading theme; comic art shouldn't be. */
    val tintable: Boolean

    fun pageAspect(index: Int): Float

    /**
     * Renders page [index] (or the [crop] region of it, as fractions) to fit inside [width] x [height].
     * The image has the page's aspect ratio, so it may be smaller than the box in one dimension.
     */
    fun render(index: Int, width: Int, height: Int, crop: Rectangle2D.Float? = null): BufferedImage

    fun contentBounds(index: Int): Rectangle2D.Float? {
        val aspect = pageAspect(index)
        val img = render(index, 200, (200 / aspect).toInt().coerceIn(50, 600))
        return Images.contentBounds(img)
    }

    fun outline(): List<FixedTocEntry> = emptyList()

    fun pageText(index: Int): String? = null

    /** Title and author the file declares, if any. */
    fun documentInfo(): Pair<String?, String?> = null to null
}

class PasswordRequiredException : Exception("This PDF is protected by a password")

fun fitBox(pw: Float, ph: Float, width: Int, height: Int): Pair<Int, Int> {
    val aspect = pw / ph.coerceAtLeast(0.001f)
    var w = width.coerceAtLeast(1)
    var h = (w / aspect).toInt()
    if (h > height) {
        h = height
        w = (h * aspect).toInt()
    }
    return w.coerceAtLeast(1) to h.coerceAtLeast(1)
}

/**
 * PDF pages rendered with PDFBox. PDFBox documents aren't thread-safe, so every call locks the document;
 * the reader renders on one background thread anyway.
 */
class PdfSource private constructor(private val doc: PDDocument) : FixedSource {
    private val renderer = PDFRenderer(doc).apply { isSubsamplingAllowed = true }
    private val lock = Any()
    private val aspects = HashMap<Int, Float>()
    override val pageCount: Int = doc.numberOfPages
    override val tintable = true

    private fun pageSize(index: Int): Pair<Float, Float> {
        val page = doc.getPage(index)
        val box = page.cropBox
        val rotated = (page.rotation % 180) != 0
        val w = box.width.coerceAtLeast(1f)
        val h = box.height.coerceAtLeast(1f)
        return if (rotated) h to w else w to h
    }

    override fun pageAspect(index: Int): Float = synchronized(lock) {
        if (index !in 0 until pageCount) return 0.7f
        aspects.getOrPut(index) { runCatching { pageSize(index).let { (w, h) -> (w / h).coerceIn(0.1f, 10f) } }.getOrDefault(0.7f) }
    }

    override fun render(index: Int, width: Int, height: Int, crop: Rectangle2D.Float?): BufferedImage = synchronized(lock) {
        val c = crop ?: FULL
        val (pw, ph) = runCatching { pageSize(index) }.getOrDefault(612f to 792f)
        val (w, h) = fitBox(pw * c.width, ph * c.height, width, height)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color.WHITE
            // PDFBox clears the page with the background color before drawing it.
            g.background = Color.WHITE
            g.fillRect(0, 0, w, h)
            if (index !in 0 until pageCount) return img
            val k = w / (pw * c.width)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.clipRect(0, 0, w, h)
            g.scale(k.toDouble(), k.toDouble())
            g.translate(-(c.x * pw).toDouble(), -(c.y * ph).toDouble())
            renderer.renderPageToGraphics(index, g, 1f)
        } catch (_: OutOfMemoryError) {
            // A page too complex to draw stays blank rather than taking the app down.
        } catch (_: Exception) {
        } catch (_: StackOverflowError) {
        } finally {
            g.dispose()
        }
        img
    }

    override fun outline(): List<FixedTocEntry> = synchronized(lock) {
        val out = ArrayList<FixedTocEntry>()
        val root = runCatching { doc.documentCatalog.documentOutline }.getOrNull() ?: return out
        val seen = HashSet<PDOutlineItem>()
        fun walk(node: PDOutlineNode, depth: Int) {
            var item: PDOutlineItem? = node.firstChild
            while (item != null && out.size < 5000 && seen.add(item)) {
                val page = runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull() ?: -1
                val title = SafeText.line(item.title.orEmpty(), 200)
                if (page >= 0 && title.isNotEmpty()) out.add(FixedTocEntry(title, page, depth))
                if (depth < 8) walk(item, depth + 1)
                item = item.nextSibling
            }
        }
        runCatching { walk(root, 0) }
        out
    }

    override fun pageText(index: Int): String? = synchronized(lock) {
        if (index !in 0 until pageCount) return null
        runCatching {
            PDFTextStripper().apply {
                startPage = index + 1
                endPage = index + 1
                sortByPosition = true
            }.getText(doc)
        }.getOrNull()
    }

    override fun documentInfo(): Pair<String?, String?> = synchronized(lock) {
        val info = runCatching { doc.documentInformation }.getOrNull()
        info?.title?.trim()?.takeIf { it.isNotEmpty() && it.length < 300 } to info?.author?.trim()?.takeIf { it.isNotEmpty() && it.length < 200 }
    }

    override fun close() = synchronized(lock) { runCatching { doc.close() }; Unit }

    companion object {
        private val FULL = Rectangle2D.Float(0f, 0f, 1f, 1f)

        fun open(file: File, password: String = ""): PdfSource {
            val doc = try {
                Loader.loadPDF(RandomAccessReadBufferedFile(file), password, null, null, IOUtils.createMemoryOnlyStreamCache())
            } catch (e: InvalidPasswordException) {
                throw PasswordRequiredException()
            } catch (e: java.io.IOException) {
                if (e is java.io.FileNotFoundException) throw e
                throw BookParseException(ParseError.INVALID, "Not a valid PDF")
            } catch (e: RuntimeException) {
                // Malformed files can trip PDFBox's own checks in ways it doesn't declare.
                throw BookParseException(ParseError.INVALID, "Not a valid PDF", e)
            } catch (e: StackOverflowError) {
                throw BookParseException(ParseError.INVALID, "Not a valid PDF", e)
            }
            if (runCatching { doc.numberOfPages }.getOrDefault(0) <= 0) {
                doc.close()
                throw BookParseException(ParseError.EMPTY, "This PDF has no pages")
            }
            return PdfSource(doc)
        }
    }
}

/** Comic book archives: one image per page, in natural file-name order. */
class CbzSource(file: File) : FixedSource {
    private val zip = FileZipAccess(file)
    private val pages = ComicPages.of(zip.entries)
    private val sizes = HashMap<Int, Images.Size?>()
    override val pageCount: Int = pages.size
    override val tintable = false

    init {
        if (pages.isEmpty()) {
            zip.close()
            throw BookParseException(ParseError.EMPTY, "This comic has no pages")
        }
    }

    private fun bytes(index: Int): ByteArray? = pages.getOrNull(index)?.let { runCatching { zip.read(it) }.getOrNull() }

    override fun pageAspect(index: Int): Float {
        val s = synchronized(sizes) { if (sizes.containsKey(index)) sizes[index] else null } ?: Images.size(bytes(index)).also { synchronized(sizes) { sizes[index] = it } }
        return s?.let { (it.width.toFloat() / it.height).coerceIn(0.1f, 10f) } ?: 0.7f
    }

    override fun render(index: Int, width: Int, height: Int, crop: Rectangle2D.Float?): BufferedImage {
        val c = crop ?: Rectangle2D.Float(0f, 0f, 1f, 1f)
        val src = Images.decode(bytes(index), (width / c.width).toInt(), (height / c.height).toInt())
        if (src == null) {
            val (w, h) = fitBox(0.7f, 1f, width, height)
            return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img -> img.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, w, h); dispose() } }
        }
        val sx = (c.x * src.width).toInt().coerceIn(0, src.width - 1)
        val sy = (c.y * src.height).toInt().coerceIn(0, src.height - 1)
        val sw = (c.width * src.width).toInt().coerceIn(1, src.width - sx)
        val sh = (c.height * src.height).toInt().coerceIn(1, src.height - sy)
        val region = if (sx == 0 && sy == 0 && sw == src.width && sh == src.height) src else src.getSubimage(sx, sy, sw, sh)
        val (w, h) = fitBox(sw.toFloat(), sh.toFloat(), width, height)
        return Images.scale(region, w, h, Color.WHITE)
    }

    override fun close() = zip.close()
}
