package com.readarea.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE missing = 0")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE metaLoaded = 0 AND missing = 0")
    suspend fun pendingMeta(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET chapter = :chapter, `offset` = :offset, progress = :progress, lastOpenedAt = :time, status = CASE WHEN status = 2 AND :progress < 0.99 THEN 1 WHEN status = 2 THEN 2 WHEN :progress >= 0.999 THEN 2 ELSE 1 END, finishedAt = CASE WHEN :progress >= 0.999 AND finishedAt = 0 THEN :time ELSE finishedAt END WHERE id = :id")
    suspend fun updatePosition(id: Long, chapter: Int, offset: Int, progress: Float, time: Long)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("UPDATE books SET readingMs = readingMs + :ms WHERE id = :id")
    suspend fun addReadingTime(id: Long, ms: Long)

    @Query("UPDATE books SET favorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, favorite: Boolean)

    @Query("UPDATE books SET status = :status, finishedAt = CASE WHEN :status = 2 THEN :time ELSE 0 END WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<Long>, status: Int, time: Long)

    @Query("UPDATE books SET progress = 0, chapter = 0, `offset` = 0, status = 0, finishedAt = 0 WHERE id IN (:ids)")
    suspend fun resetProgress(ids: List<Long>)

    @Query("UPDATE books SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    @Query("UPDATE books SET pageCount = :count WHERE id = :id")
    suspend fun setPageCount(id: Long, count: Int)

    @Query("UPDATE books SET missing = :missing WHERE id IN (:ids)")
    suspend fun setMissing(ids: List<Long>, missing: Boolean)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM books WHERE folderUri = :folder")
    suspend fun deleteFolder(folder: String)
}

@Dao
interface CollectionDao {
    @Query("SELECT c.*, (SELECT COUNT(*) FROM book_collections bc WHERE bc.collectionId = c.id) AS count, (SELECT b.coverPath FROM books b INNER JOIN book_collections bc2 ON bc2.bookId = b.id WHERE bc2.collectionId = c.id AND b.coverPath IS NOT NULL ORDER BY b.lastOpenedAt DESC LIMIT 1) AS coverPath FROM collections c ORDER BY c.name COLLATE NOCASE")
    fun observe(): Flow<List<CollectionWithCount>>

    @Query("SELECT b.* FROM books b INNER JOIN book_collections bc ON b.id = bc.bookId WHERE bc.collectionId = :id AND b.missing = 0")
    fun books(id: Long): Flow<List<BookEntity>>

    @Query("SELECT collectionId FROM book_collections WHERE bookId = :bookId")
    fun collectionsFor(bookId: Long): Flow<List<Long>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeOne(id: Long): Flow<CollectionEntity?>

    @Insert
    suspend fun insert(c: CollectionEntity): Long

    @Update
    suspend fun update(c: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBooks(items: List<BookCollectionEntity>)

    @Query("DELETE FROM book_collections WHERE collectionId = :collectionId AND bookId IN (:bookIds)")
    suspend fun removeBooks(collectionId: Long, bookIds: List<Long>)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapter, start")
    fun highlights(bookId: Long): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapter, `offset`")
    fun bookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT h.*, b.title AS bookTitle, b.author AS bookAuthor FROM highlights h INNER JOIN books b ON b.id = h.bookId ORDER BY h.createdAt DESC")
    fun allHighlights(): Flow<List<HighlightWithBook>>

    @Query("SELECT m.*, b.title AS bookTitle FROM bookmarks m INNER JOIN books b ON b.id = m.bookId ORDER BY m.createdAt DESC")
    fun allBookmarks(): Flow<List<BookmarkWithBook>>

    @Insert
    suspend fun insertHighlight(h: HighlightEntity): Long

    @Update
    suspend fun updateHighlight(h: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Insert
    suspend fun insertBookmark(b: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(s: ReadingSessionEntity)

    @Query("SELECT day, SUM(durationMs) AS ms, SUM(pages) AS pages FROM sessions GROUP BY day ORDER BY day DESC LIMIT 400")
    fun days(): Flow<List<DayStat>>

    @Query("SELECT s.bookId AS bookId, b.title AS title, SUM(s.durationMs) AS ms FROM sessions s INNER JOIN books b ON b.id = s.bookId GROUP BY s.bookId ORDER BY ms DESC LIMIT 8")
    fun topBooks(): Flow<List<BookTime>>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM sessions")
    fun totalMs(): Flow<Long>

    @Query("SELECT COALESCE(SUM(pages), 0) FROM sessions")
    fun totalPages(): Flow<Long>
}
