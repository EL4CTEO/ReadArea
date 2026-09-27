package com.readarea.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

object BookStatus {
    const val NEW = 0
    const val READING = 1
    const val FINISHED = 2
    const val WANT = 3
}

@Entity(tableName = "books", indices = [Index(value = ["uri"], unique = true)])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val fileName: String,
    val folderUri: String? = null,
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
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val offset: Int,
    val progress: Float,
    val snippet: String,
    val chapterTitle: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "book_collections",
    primaryKeys = ["bookId", "collectionId"],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("collectionId")],
)
data class BookCollectionEntity(val bookId: Long, val collectionId: Long)

@Entity(tableName = "sessions", indices = [Index("bookId"), Index("day")])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val start: Long,
    val durationMs: Long,
    val pages: Int,
    val day: Long,
)

data class CollectionWithCount(
    @Embedded val collection: CollectionEntity,
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "coverPath") val coverPath: String?,
)

data class HighlightWithBook(
    @Embedded val highlight: HighlightEntity,
    @ColumnInfo(name = "bookTitle") val bookTitle: String,
    @ColumnInfo(name = "bookAuthor") val bookAuthor: String,
)

data class BookmarkWithBook(
    @Embedded val bookmark: BookmarkEntity,
    @ColumnInfo(name = "bookTitle") val bookTitle: String,
)

data class DayStat(
    @ColumnInfo(name = "day") val day: Long,
    @ColumnInfo(name = "ms") val ms: Long,
    @ColumnInfo(name = "pages") val pages: Int,
)

data class BookTime(
    @ColumnInfo(name = "bookId") val bookId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "ms") val ms: Long,
)
