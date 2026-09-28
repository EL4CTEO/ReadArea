package com.readarea.desktop.reader.engine

import com.readarea.core.format.ResourceProvider
import com.readarea.desktop.book.Images
import java.awt.image.BufferedImage

/** Book illustrations: sizes remembered, pixels decoded at display size and evicted by memory use. */
class ImageCache(private val resources: ResourceProvider, private val maxBytes: Long) {
    private val sizes = HashMap<String, Images.Size?>()
    private val images = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {}
    private var bytes = 0L

    fun size(path: String): Images.Size? = synchronized(sizes) {
        if (sizes.containsKey(path)) return sizes[path]
        val s = runCatching { Images.size(resources.read(path)) }.getOrNull()
        sizes[path] = s
        s
    }

    fun get(path: String, w: Int, h: Int): BufferedImage? {
        val key = "$path@${w}x$h"
        synchronized(images) { images[key]?.let { return it } }
        val data = runCatching { resources.read(path) }.getOrNull() ?: return null
        val decoded = Images.decode(data, w, h) ?: return null
        val img = if (decoded.width > w * 1.5 || decoded.height > h * 1.5) Images.scale(decoded, w, h) else decoded
        synchronized(images) {
            images[key] = img
            bytes += img.width.toLong() * img.height * 4
            val it = images.entries.iterator()
            while (bytes > maxBytes && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                bytes -= e.value.width.toLong() * e.value.height * 4
                it.remove()
            }
        }
        return img
    }

    fun clear() = synchronized(images) {
        images.clear()
        bytes = 0
    }
}
