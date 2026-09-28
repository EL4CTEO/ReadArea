package com.readarea.desktop.data

import com.readarea.core.library.DayTotal
import com.readarea.desktop.platform.AppDirs
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The library database: books, notes, shelves and reading sessions in one SQLite file.
 *
 * All statements are prepared with bound parameters. Access is serialized on the connection, and
 * callers run it off the UI thread (see [Library]).
 */
class Database(file: File) : AutoCloseable {
    private val conn: Connection

    init {
        file.parentFile?.let { AppDirs.ensurePrivate(it) }
        val props = java.util.Properties().apply {
            setProperty("foreign_keys", "true")
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("busy_timeout", "5000")
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.absolutePath, props)
        for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).takeIf { it.exists() }?.let { AppDirs.makePrivate(it) }
        migrate()
    }

    private fun migrate() = synchronized(conn) {
        val version = conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 } }
        if (version > SCHEMA) throw IllegalStateException("The library was created by a newer version of ReadArea")
        if (version < 1) transaction {
            conn.createStatement().use { st ->
                for (sql in SCHEMA_V1) st.execute(sql)
                st.execute("PRAGMA user_version = $SCHEMA")
            }
        }
    }

    fun <T> transaction(block: () -> T): T = synchronized(conn) {
        val auto = conn.autoCommit
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            r
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = auto
        }
    }

    private fun <T> stmt(sql: String, args: List<Any?>, block: (PreparedStatement) -> T): T = synchronized(conn) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> bind(ps, i + 1, a) }
            block(ps)
        }
    }

    private fun bind(ps: PreparedStatement, i: Int, a: Any?) {
        when (a) {
            null -> ps.setNull(i, Types.NULL)
            is String -> ps.setString(i, a)
            is Int -> ps.setInt(i, a)
            is Long -> ps.setLong(i, a)
            is Float -> ps.setDouble(i, a.toDouble())
            is Double -> ps.setDouble(i, a)
            is Boolean -> ps.setInt(i, if (a) 1 else 0)
            else -> throw IllegalArgumentException("Unsupported parameter type ${a::class}")
        }
    }

    fun update(sql: String, vararg args: Any?): Int = stmt(sql, args.toList()) { it.executeUpdate() }

    fun insert(sql: String, vararg args: Any?): Long = synchronized(conn) {
        stmt(sql, args.toList()) { ps ->
            val n = ps.executeUpdate()
            if (n == 0) -1L else conn.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { if (it.next()) it.getLong(1) else -1L } }
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = stmt(sql, args.toList()) { ps ->
        ps.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out.add(map(rs))
            out
        }
    }

    fun long(sql: String, vararg args: Any?): Long = query(sql, *args) { it.getLong(1) }.firstOrNull() ?: 0L

    /** `IN (?, ?, …)` for [n] parameters. */
    fun placeholders(n: Int): String = List(n.coerceAtLeast(1)) { "?" }.joinToString(",", "(", ")")

    // Books

    fun books(onlyPresent: Boolean = true): List<Book> = query(if (onlyPresent) "SELECT * FROM books WHERE missing = 0" else "SELECT * FROM books") { it.book() }

    fun book(id: Long): Book? = query("SELECT * FROM books WHERE id = ?", id) { it.book() }.firstOrNull()

    fun bookByPath(path: String): Book? = query("SELECT * FROM books WHERE path = ?", path) { it.book() }.firstOrNull()

    fun pendingMeta(): List<Book> = query("SELECT * FROM books WHERE metaLoaded = 0 AND missing = 0 ORDER BY addedAt DESC") { it.book() }

    fun insertBook(b: Book): Long = insert(
        "INSERT OR IGNORE INTO books (path, fileName, folder, format, size, title, author, description, language, series, seriesIndex, publisher, coverPath, addedAt, lastOpenedAt, progress, chapter, `offset`, status, favorite, readingMs, missing, metaLoaded, pageCount, finishedAt, rating, fileModified) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        b.path, b.fileName, b.folder, b.format, b.size, b.title, b.author, b.description, b.language, b.series, b.seriesIndex, b.publisher, b.coverPath,
        b.addedAt, b.lastOpenedAt, b.progress, b.chapter, b.offset, b.status, b.favorite, b.readingMs, b.missing, b.metaLoaded, b.pageCount, b.finishedAt, b.rating, b.fileModified,
    )

    fun updateBook(b: Book) {
        update(
            "UPDATE books SET path=?, fileName=?, folder=?, format=?, size=?, title=?, author=?, description=?, language=?, series=?, seriesIndex=?, publisher=?, coverPath=?, lastOpenedAt=?, progress=?, chapter=?, `offset`=?, status=?, favorite=?, readingMs=?, missing=?, metaLoaded=?, pageCount=?, finishedAt=?, rating=?, fileModified=? WHERE id=?",
            b.path, b.fileName, b.folder, b.format, b.size, b.title, b.author, b.description, b.language, b.series, b.seriesIndex, b.publisher, b.coverPath,
            b.lastOpenedAt, b.progress, b.chapter, b.offset, b.status, b.favorite, b.readingMs, b.missing, b.metaLoaded, b.pageCount, b.finishedAt, b.rating, b.fileModified, b.id,
        )
    }

    /** Saves a reading position and moves the book between Reading and Finished as the reader goes. */
    fun updatePosition(id: Long, chapter: Int, offset: Int, progress: Float, time: Long) {
        update(
            "UPDATE books SET chapter = ?, `offset` = ?, progress = ?, lastOpenedAt = ?, " +
                "status = CASE WHEN status = 2 AND ? < 0.99 THEN 1 WHEN status = 2 THEN 2 WHEN ? >= 0.999 THEN 2 ELSE 1 END, " +
                "finishedAt = CASE WHEN ? >= 0.999 AND finishedAt = 0 THEN ? ELSE finishedAt END WHERE id = ?",
            chapter, offset, progress, time, progress, progress, progress, time, id,
        )
    }

    fun touch(id: Long, time: Long) = update("UPDATE books SET lastOpenedAt = ? WHERE id = ?", time, id)

    fun setFavorite(ids: List<Long>, favorite: Boolean) = update("UPDATE books SET favorite = ? WHERE id IN ${placeholders(ids.size)}", favorite, *ids.toTypedArray())

    fun setStatus(ids: List<Long>, status: Int, time: Long) =
        update("UPDATE books SET status = ?, finishedAt = CASE WHEN ? = 2 THEN ? ELSE 0 END WHERE id IN ${placeholders(ids.size)}", status, status, time, *ids.toTypedArray())

    fun resetProgress(ids: List<Long>) =
        update("UPDATE books SET progress = 0, chapter = 0, `offset` = 0, status = 0, finishedAt = 0 WHERE id IN ${placeholders(ids.size)}", *ids.toTypedArray())

    fun setRating(id: Long, rating: Int) = update("UPDATE books SET rating = ? WHERE id = ?", rating.coerceIn(0, 5), id)

    fun setPageCount(id: Long, count: Int) = update("UPDATE books SET pageCount = ? WHERE id = ?", count, id)

    fun setMissing(ids: List<Long>, missing: Boolean) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { chunk -> update("UPDATE books SET missing = ? WHERE id IN ${placeholders(chunk.size)}", missing, *chunk.toTypedArray()) }
    }

    fun deleteBooks(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { chunk -> update("DELETE FROM books WHERE id IN ${placeholders(chunk.size)}", *chunk.toTypedArray()) }
    }

    fun deleteFolderBooks(folder: String) = update("DELETE FROM books WHERE folder = ?", folder)

    fun addReadingTime(id: Long, ms: Long) = update("UPDATE books SET readingMs = readingMs + ? WHERE id = ?", ms, id)

    // Shelves

    fun shelves(): List<ShelfSummary> {
        val members = query("SELECT bc.collectionId, bc.bookId FROM book_collections bc INNER JOIN books b ON b.id = bc.bookId WHERE b.missing = 0") { it.getLong(1) to it.getLong(2) }
            .groupBy({ it.first }, { it.second })
        return query(
            "SELECT c.*, (SELECT b.coverPath FROM books b INNER JOIN book_collections bc2 ON bc2.bookId = b.id WHERE bc2.collectionId = c.id AND b.coverPath IS NOT NULL AND b.missing = 0 ORDER BY b.lastOpenedAt DESC LIMIT 1) AS cover FROM collections c ORDER BY c.name COLLATE NOCASE",
        ) { rs ->
            val shelf = Shelf(rs.getLong("id"), rs.getString("name"), rs.getInt("color"), rs.getLong("createdAt"))
            val ids = members[shelf.id].orEmpty()
            ShelfSummary(shelf, ids.size, rs.getString("cover"), ids)
        }
    }

    fun shelvesFor(bookId: Long): List<Long> = query("SELECT collectionId FROM book_collections WHERE bookId = ?", bookId) { it.getLong(1) }

    fun createShelf(name: String, color: Int): Long = insert("INSERT INTO collections (name, color, createdAt) VALUES (?, ?, ?)", name, color, System.currentTimeMillis())

    fun renameShelf(id: Long, name: String) = update("UPDATE collections SET name = ? WHERE id = ?", name, id)

    fun deleteShelf(id: Long) = update("DELETE FROM collections WHERE id = ?", id)

    fun addToShelf(shelf: Long, books: List<Long>) = transaction {
        for (b in books) update("INSERT OR IGNORE INTO book_collections (bookId, collectionId) VALUES (?, ?)", b, shelf)
    }

    fun removeFromShelf(shelf: Long, books: List<Long>) =
        update("DELETE FROM book_collections WHERE collectionId = ? AND bookId IN ${placeholders(books.size)}", shelf, *books.toTypedArray())

    // Notes

    fun highlights(bookId: Long): List<Highlight> = query("SELECT * FROM highlights WHERE bookId = ? ORDER BY chapter, start", bookId) { it.highlight() }

    fun bookmarks(bookId: Long): List<Bookmark> = query("SELECT * FROM bookmarks WHERE bookId = ? ORDER BY chapter, `offset`", bookId) { it.bookmark() }

    fun allHighlights(): List<HighlightWithBook> =
        query("SELECT h.*, b.title AS bookTitle, b.author AS bookAuthor FROM highlights h INNER JOIN books b ON b.id = h.bookId ORDER BY h.createdAt DESC") {
            HighlightWithBook(it.highlight(), it.getString("bookTitle"), it.getString("bookAuthor"))
        }

    fun allBookmarks(): List<BookmarkWithBook> =
        query("SELECT m.*, b.title AS bookTitle FROM bookmarks m INNER JOIN books b ON b.id = m.bookId ORDER BY m.createdAt DESC") {
            BookmarkWithBook(it.bookmark(), it.getString("bookTitle"))
        }

    fun insertHighlight(h: Highlight): Long = insert(
        "INSERT INTO highlights (bookId, chapter, start, `end`, text, color, note, chapterTitle, progress, createdAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
        h.bookId, h.chapter, h.start, h.end, h.text, h.color, h.note, h.chapterTitle, h.progress, h.createdAt,
    )

    fun updateHighlight(h: Highlight) = update("UPDATE highlights SET color = ?, note = ? WHERE id = ?", h.color, h.note, h.id)

    fun deleteHighlight(id: Long) = update("DELETE FROM highlights WHERE id = ?", id)

    fun insertBookmark(b: Bookmark): Long = insert(
        "INSERT INTO bookmarks (bookId, chapter, `offset`, progress, snippet, chapterTitle, createdAt) VALUES (?,?,?,?,?,?,?)",
        b.bookId, b.chapter, b.offset, b.progress, b.snippet, b.chapterTitle, b.createdAt,
    )

    fun deleteBookmark(id: Long) = update("DELETE FROM bookmarks WHERE id = ?", id)

    // Reading sessions

    fun insertSession(bookId: Long, start: Long, durationMs: Long, pages: Int, day: Long) =
        insert("INSERT INTO sessions (bookId, start, durationMs, pages, day) VALUES (?,?,?,?,?)", bookId, start, durationMs, pages, day)

    fun days(limit: Int = 400): List<DayTotal> =
        query("SELECT day, SUM(durationMs), SUM(pages) FROM sessions GROUP BY day ORDER BY day DESC LIMIT ?", limit) { DayTotal(it.getLong(1), it.getLong(2), it.getInt(3)) }

    fun topBooks(limit: Int = 8): List<BookTime> =
        query("SELECT s.bookId, b.title, SUM(s.durationMs) AS ms FROM sessions s INNER JOIN books b ON b.id = s.bookId GROUP BY s.bookId ORDER BY ms DESC LIMIT ?", limit) {
            BookTime(it.getLong(1), it.getString(2), it.getLong(3))
        }

    fun totalMs(): Long = long("SELECT COALESCE(SUM(durationMs), 0) FROM sessions")

    fun totalPages(): Long = long("SELECT COALESCE(SUM(pages), 0) FROM sessions")

    override fun close() {
        synchronized(conn) { runCatching { conn.close() } }
    }

    private fun ResultSet.floatOrNull(col: String): Float? = getDouble(col).let { if (wasNull()) null else it.toFloat() }

    private fun ResultSet.book() = Book(
        id = getLong("id"), path = getString("path"), fileName = getString("fileName"), folder = getString("folder"), format = getString("format"),
        size = getLong("size"), title = getString("title"), author = getString("author") ?: "", description = getString("description"),
        language = getString("language"), series = getString("series"), seriesIndex = floatOrNull("seriesIndex"), publisher = getString("publisher"),
        coverPath = getString("coverPath"), addedAt = getLong("addedAt"), lastOpenedAt = getLong("lastOpenedAt"), progress = getDouble("progress").toFloat(),
        chapter = getInt("chapter"), offset = getInt("offset"), status = getInt("status"), favorite = getInt("favorite") != 0, readingMs = getLong("readingMs"),
        missing = getInt("missing") != 0, metaLoaded = getInt("metaLoaded") != 0, pageCount = getInt("pageCount"), finishedAt = getLong("finishedAt"),
        rating = getInt("rating"), fileModified = getLong("fileModified"),
    )

    private fun ResultSet.highlight() = Highlight(
        id = getLong("id"), bookId = getLong("bookId"), chapter = getInt("chapter"), start = getInt("start"), end = getInt("end"), text = getString("text"),
        color = getInt("color"), note = getString("note"), chapterTitle = getString("chapterTitle") ?: "", progress = getDouble("progress").toFloat(), createdAt = getLong("createdAt"),
    )

    private fun ResultSet.bookmark() = Bookmark(
        id = getLong("id"), bookId = getLong("bookId"), chapter = getInt("chapter"), offset = getInt("offset"), progress = getDouble("progress").toFloat(),
        snippet = getString("snippet") ?: "", chapterTitle = getString("chapterTitle") ?: "", createdAt = getLong("createdAt"),
    )

    companion object {
        const val SCHEMA = 1

        private val SCHEMA_V1 = listOf(
            """CREATE TABLE books (
                id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, fileName TEXT NOT NULL, folder TEXT, format TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL, author TEXT NOT NULL DEFAULT '', description TEXT, language TEXT,
                series TEXT, seriesIndex REAL, publisher TEXT, coverPath TEXT, addedAt INTEGER NOT NULL, lastOpenedAt INTEGER NOT NULL DEFAULT 0,
                progress REAL NOT NULL DEFAULT 0, chapter INTEGER NOT NULL DEFAULT 0, `offset` INTEGER NOT NULL DEFAULT 0, status INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0, readingMs INTEGER NOT NULL DEFAULT 0, missing INTEGER NOT NULL DEFAULT 0, metaLoaded INTEGER NOT NULL DEFAULT 0,
                pageCount INTEGER NOT NULL DEFAULT 0, finishedAt INTEGER NOT NULL DEFAULT 0, rating INTEGER NOT NULL DEFAULT 0, fileModified INTEGER NOT NULL DEFAULT 0)""",
            "CREATE UNIQUE INDEX index_books_path ON books(path)",
            "CREATE INDEX index_books_folder ON books(folder)",
            """CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE, chapter INTEGER NOT NULL,
                `offset` INTEGER NOT NULL, progress REAL NOT NULL, snippet TEXT NOT NULL, chapterTitle TEXT NOT NULL, createdAt INTEGER NOT NULL)""",
            "CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)",
            """CREATE TABLE highlights (
                id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE, chapter INTEGER NOT NULL,
                start INTEGER NOT NULL, `end` INTEGER NOT NULL, text TEXT NOT NULL, color INTEGER NOT NULL, note TEXT, chapterTitle TEXT NOT NULL,
                progress REAL NOT NULL, createdAt INTEGER NOT NULL)""",
            "CREATE INDEX index_highlights_bookId ON highlights(bookId)",
            "CREATE TABLE collections (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, color INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)",
            """CREATE TABLE book_collections (
                bookId INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE, collectionId INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                PRIMARY KEY (bookId, collectionId))""",
            "CREATE INDEX index_book_collections_collectionId ON book_collections(collectionId)",
            "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER NOT NULL, start INTEGER NOT NULL, durationMs INTEGER NOT NULL, pages INTEGER NOT NULL, day INTEGER NOT NULL)",
            "CREATE INDEX index_sessions_bookId ON sessions(bookId)",
            "CREATE INDEX index_sessions_day ON sessions(day)",
        )
    }
}
