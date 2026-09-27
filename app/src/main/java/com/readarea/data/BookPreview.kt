package com.readarea.data

import android.content.Context
import com.readarea.core.BookLoader
import com.readarea.core.ReflowableBook
import com.readarea.core.format.BlockKind
import com.readarea.core.format.BookFormat
import com.readarea.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BookPreview {
    private val cache = object : android.util.LruCache<Long, String>(24) {}
    private val textKinds = setOf(BlockKind.PARAGRAPH, BlockKind.QUOTE, BlockKind.VERSE, BlockKind.HEADING, BlockKind.LIST_ITEM)

    suspend fun excerpt(context: Context, book: BookEntity, maxChars: Int = 1400): String? {
        cache.get(book.id)?.let { return it }
        val format = BookFormat.byName(book.format)
        if (format.fixedLayout || book.size > 40L * 1024 * 1024) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val opened = BookLoader.open(context, book.uri, format, book.fileName, "b${book.id}") as? ReflowableBook ?: return@runCatching null
                try {
                    val chapters = opened.book.chapters
                    val start = chapters.indexOfFirst { c -> c.blocks.filter { it.kind == BlockKind.PARAGRAPH }.sumOf { it.length } > 400 }.coerceAtLeast(0)
                    val sb = StringBuilder()
                    loop@ for (c in chapters.drop(start)) {
                        for (b in c.blocks) {
                            if (b.kind !in textKinds) continue
                            val t = b.text.replace(' ', ' ').trim()
                            if (t.isEmpty()) continue
                            if (sb.isNotEmpty()) sb.append("\n\n")
                            sb.append(t)
                            if (sb.length >= maxChars) break@loop
                        }
                    }
                    val text = sb.toString().let { if (it.length > maxChars) it.take(maxChars).substringBeforeLast(' ') + "…" else it }
                    text.ifBlank { null }?.also { cache.put(book.id, it) }
                } finally {
                    opened.close()
                }
            }.getOrNull()
        }
    }
}
