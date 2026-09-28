package com.readarea.desktop.data

import com.readarea.core.format.BookFormat
import com.readarea.core.library.BookStatus

data class Book(
    val id: Long = 0,
    val path: String,
    val fileName: String,
    val folder: String? = null,
    val format: String,
    val size: Long = 0,
    val title: String,
    val author: String = "",
    val description: String? = null,
    val language: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val publisher: String? = null,
    val coverPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    val progress: Float = 0f,
    val chapter: Int = 0,
    val offset: Int = 0,
    val status: Int = BookStatus.NEW,
    val favorite: Boolean = false,
    val readingMs: Long = 0,
    val missing: Boolean = false,
    val metaLoaded: Boolean = false,
    val pageCount: Int = 0,
    val finishedAt: Long = 0,
    val rating: Int = 0,
    val fileModified: Long = 0,
) {
    val bookFormat: BookFormat get() = BookFormat.byName(format)
}

data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val offset: Int,
    val progress: Float,
    val snippet: String,
    val chapterTitle: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class Highlight(
    val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val note: String? = null,
    val chapterTitle: String,
    val progress: Float,
    val createdAt: Long = System.currentTimeMillis(),
)

data class Shelf(val id: Long = 0, val name: String, val color: Int = 0, val createdAt: Long = System.currentTimeMillis())

data class ShelfSummary(val shelf: Shelf, val count: Int, val coverPath: String?, val bookIds: List<Long>)

data class HighlightWithBook(val highlight: Highlight, val bookTitle: String, val bookAuthor: String)

data class BookmarkWithBook(val bookmark: Bookmark, val bookTitle: String)

data class BookTime(val bookId: Long, val title: String, val ms: Long)
