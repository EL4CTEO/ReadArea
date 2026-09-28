package com.readarea.desktop.book

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream

/**
 * Decodes untrusted images from books. The declared size is checked before any pixels are allocated,
 * and large images are subsampled while decoding, so a small file claiming to be enormous can't exhaust
 * memory. Only the first frame of animated images is read.
 */
object Images {
    /** Largest side accepted at all; covers and illustrations are far smaller. */
    const val MAX_SIDE = 16_384

    /** Largest pixel count decoded at once; bigger images are subsampled down to this. */
    const val MAX_PIXELS = 24_000_000L

    init {
        ImageIO.setUseCache(false)
    }

    data class Size(val width: Int, val height: Int)

    private inline fun <T> withReader(bytes: ByteArray, block: (ImageReader, ImageInputStream) -> T): T? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            while (readers.hasNext()) {
                val reader = readers.next()
                try {
                    stream.seek(0)
                    reader.setInput(stream, true, true)
                    return block(reader, stream)
                } catch (_: Exception) {
                    // Try the next reader that claims the format.
                } finally {
                    reader.dispose()
                }
            }
        }
        return null
    }

    fun size(bytes: ByteArray?): Size? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            withReader(bytes) { r, _ ->
                val w = r.getWidth(0)
                val h = r.getHeight(0)
                if (w in 1..MAX_SIDE && h in 1..MAX_SIDE) Size(w, h) else null
            }
        }.getOrNull()
    }

    /**
     * Decodes [bytes] so that the result is at least [reqW] x [reqH] where the image allows, using the
     * largest power-of-two subsampling that keeps it so. Returns null for anything that isn't a valid,
     * sanely sized image.
     */
    fun decode(bytes: ByteArray?, reqW: Int = Int.MAX_VALUE, reqH: Int = Int.MAX_VALUE): BufferedImage? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            withReader(bytes) { r, _ ->
                val w = r.getWidth(0)
                val h = r.getHeight(0)
                if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) return@withReader null
                var sample = 1
                while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
                while ((w.toLong() / sample) * (h.toLong() / sample) > MAX_PIXELS) sample *= 2
                val param: ImageReadParam = r.defaultReadParam
                if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
                r.read(0, param)
            }?.let { toRgb(it) }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Converts to a plain (A)RGB image the screen draws quickly, keeping transparency if present. */
    fun toRgb(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_RGB || img.type == BufferedImage.TYPE_INT_ARGB || img.type == BufferedImage.TYPE_INT_ARGB_PRE) return img
        val out = BufferedImage(img.width, img.height, if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    fun scale(img: BufferedImage, w: Int, h: Int, background: Color? = null): BufferedImage {
        val out = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), if (background == null && img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        if (background != null) {
            g.color = background
            g.fillRect(0, 0, out.width, out.height)
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // Downscaling in halving steps keeps thumbnails sharp instead of aliased.
        var src = img
        var cw = img.width
        var ch = img.height
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2
            ch /= 2
            val step = BufferedImage(cw, ch, if (src.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
            val sg = step.createGraphics()
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            sg.composite = AlphaComposite.Src
            sg.drawImage(src, 0, 0, cw, ch, null)
            sg.dispose()
            src = step
        }
        g.drawImage(src, 0, 0, out.width, out.height, null)
        g.dispose()
        return out
    }

    /** Scales to fit inside [maxW] x [maxH] keeping the aspect ratio; never scales up. */
    fun fit(img: BufferedImage, maxW: Int, maxH: Int): BufferedImage {
        val k = minOf(maxW.toDouble() / img.width, maxH.toDouble() / img.height, 1.0)
        if (k >= 1.0) return img
        return scale(img, (img.width * k).toInt().coerceAtLeast(1), (img.height * k).toInt().coerceAtLeast(1))
    }

    fun writeJpeg(img: BufferedImage, file: File, quality: Float = 0.88f) {
        val rgb = if (img.colorModel.hasAlpha()) BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also {
            val g = it.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, it.width, it.height)
            g.drawImage(img, 0, 0, null)
            g.dispose()
        } else img
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val bos = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(bos).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(rgb, null, null), param)
            }
            file.writeBytes(bos.toByteArray())
        } finally {
            writer.dispose()
        }
    }

    /** Bounds of the non-blank area of a page, as fractions, with a little padding; null if blank. */
    fun contentBounds(img: BufferedImage): java.awt.geom.Rectangle2D.Float? {
        val w = img.width
        val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        var top = -1
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            var row = false
            val base = y * w
            for (x in 0 until w) {
                val c = px[base + x]
                if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) < 690) {
                    row = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (row) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0 || right <= left) return null
        val pad = 0.02f
        val l = (left.toFloat() / w - pad).coerceAtLeast(0f)
        val t = (top.toFloat() / h - pad).coerceAtLeast(0f)
        val r = (right.toFloat() / w + pad).coerceAtMost(1f)
        val b = (bottom.toFloat() / h + pad).coerceAtMost(1f)
        return java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t)
    }
}
