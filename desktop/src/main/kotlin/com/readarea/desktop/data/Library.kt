package com.readarea.desktop.data

import com.readarea.core.book.BookOpener
import com.readarea.core.book.FormatSniffer
import com.readarea.core.format.BookFormat
import com.readarea.core.library.BookStatus
import com.readarea.core.library.ReadingStats
import com.readarea.desktop.book.CbzSource
import com.readarea.desktop.book.Images
import com.readarea.desktop.book.PdfSource
import com.readarea.desktop.platform.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors

enum class ScanPhase { IDLE, FOLDERS, DETAILS }

data class ScanState(val running: Boolean = false, val found: Int = 0, val processed: Int = 0, val total: Int = 0, val phase: ScanPhase = ScanPhase.IDLE)

/**
 * The reader's library: which books exist, what the app knows about them and everything they did with
 * them. Database work runs on one background thread; the UI observes [books] and [version].
 */
class Library(private val db: Database, private val settings: SettingsStore, private val dirs: AppDirs) : AutoCloseable {
    private val dbThread = Executors.newSingleThreadExecutor { r -> Thread(r, "library-db").apply { isDaemon = true } }
    val io = dbThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val metaMutex = Mutex()

    private val _books = MutableStateFlow<List<Book>?>(null)
    val books: StateFlow<List<Book>?> = _books.asStateFlow()

    /** Bumps on every change, so screens showing notes, shelves or stats know to reload. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    @Volatile private var lastScan = 0L

    init {
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        val list = withContext(io) { db.books() }
        _books.value = list
        _version.value++
    }

    private suspend fun <T> write(block: Database.() -> T): T {
        val r = withContext(io) { db.block() }
        refresh()
        return r
    }

    suspend fun <T> read(block: Database.() -> T): T = withContext(io) { db.block() }

    fun launch(block: suspend Library.() -> Unit) {
        scope.launch { block() }
    }

    // Folders

    fun addFolder(dir: File) {
        val path = dir.absoluteFile.normalize().path
        settings.updateApp { s -> if (s.folders.contains(path)) s else s.copy(folders = s.folders + path, onboardingDone = true) }
        scope.launch { scanAll() }
    }

    fun removeFolder(path: String, removeBooks: Boolean) {
        settings.updateApp { s -> s.copy(folders = s.folders - path) }
        if (removeBooks) scope.launch {
            // Their cover images go too: nothing else would ever clean those up.
            val covers = read { books(onlyPresent = false).filter { it.folder == path }.mapNotNull { it.coverPath } }
            write { deleteFolderBooks(path) }
            withContext(Dispatchers.IO) { covers.forEach { p -> File(p).takeIf { AppDirs.isInside(dirs.covers, it) }?.delete() } }
        }
    }

    fun rescan(force: Boolean = true) {
        if (!force && System.currentTimeMillis() - lastScan < 120_000) return
        scope.launch { scanAll() }
    }

    suspend fun scanAll() {
        if (scanMutex.isLocked) return
        scanMutex.withLock {
            lastScan = System.currentTimeMillis()
            val app = settings.app.value
            val ignored = app.ignored.toHashSet()
            _scan.value = ScanState(running = true, phase = ScanPhase.FOLDERS)
            var found = 0
            for (folder in app.folders) {
                val dir = File(folder)
                if (!dir.isDirectory) continue
                val files = withContext(Dispatchers.IO) { walk(dir) { n -> _scan.value = _scan.value.copy(found = found + n) } }
                found += files.size
                write {
                    transaction {
                        val existing = books(onlyPresent = false).filter { it.folder == folder }.associateBy { it.path }
                        val seen = HashSet<String>()
                        for (f in files) {
                            val path = f.path
                            seen.add(path)
                            if (path in ignored) continue
                            val old = existing[path] ?: bookByPath(path)
                            if (old == null) {
                                val format = BookFormat.fromFileName(f.name) ?: continue
                                insertBook(Book(path = path, fileName = f.name, folder = folder, format = format.name, size = f.length(), title = BookOpener.titleFromFileName(f.name), fileModified = f.lastModified()))
                            } else if (old.missing) {
                                setMissing(listOf(old.id), false)
                            }
                        }
                        val gone = existing.values.filter { it.path !in seen && !it.missing && !File(it.path).isFile }.map { it.id }
                        if (gone.isNotEmpty()) setMissing(gone, true)
                    }
                }
            }
            // Books added one by one: notice when their files disappear or come back.
            write {
                val loose = books(onlyPresent = false).filter { it.folder == null }
                setMissing(loose.filter { !it.missing && !File(it.path).isFile }.map { it.id }, true)
                setMissing(loose.filter { it.missing && File(it.path).isFile }.map { it.id }, false)
            }
            _scan.value = ScanState(running = true, found = found, phase = ScanPhase.DETAILS)
            metaMutex.withLock { loadPendingMetadata() }
            _scan.value = ScanState(running = false, found = found)
        }
    }

    /** Book files under [root], skipping hidden folders and not following links, so loops can't trap the scan. */
    private fun walk(root: File, progress: (Int) -> Unit): List<File> {
        val out = ArrayList<File>()
        runCatching {
            Files.walkFileTree(root.toPath(), emptySet(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    if (dir != root.toPath() && (name.startsWith(".") || name in SKIP_DIRS || dir in systemDirs)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName.toString()
                    if (name.startsWith(".") || attrs.size() < 64) return FileVisitResult.CONTINUE
                    if (BookFormat.fromFileName(name) == null) return FileVisitResult.CONTINUE
                    out.add(file.toFile())
                    if (out.size % 50 == 0) progress(out.size)
                    return if (out.size >= MAX_FILES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        progress(out.size)
        return out
    }

    // Single files

    /**
     * Adds files the reader chose, dropped or opened from elsewhere. The format comes from the contents,
     * so misnamed files still work and files that aren't books are skipped. Returns the new book ids.
     */
    suspend fun importFiles(files: List<File>): List<Long> {
        val ids = ArrayList<Long>()
        for (f in files.take(MAX_IMPORT)) {
            val file = runCatching { f.canonicalFile }.getOrNull() ?: continue
            if (!file.isFile) continue
            val format = withContext(Dispatchers.IO) { FormatSniffer.detect(file) } ?: continue
            val id = write {
                bookByPath(file.path)?.let { existing ->
                    if (existing.missing) setMissing(listOf(existing.id), false)
                    return@write existing.id
                }
                val inserted = insertBook(Book(path = file.path, fileName = file.name, format = format.name, size = file.length(), title = BookOpener.titleFromFileName(file.name), fileModified = file.lastModified()))
                if (inserted > 0) inserted else bookByPath(file.path)?.id ?: -1
            }
            if (id > 0) ids.add(id)
            settings.updateApp { s -> if (file.path in s.ignored) s.copy(ignored = s.ignored - file.path) else s }
        }
        scope.launch { metaMutex.withLock { loadPendingMetadata() } }
        return ids
    }

    /** Adds one file and reads its details right away, for opening it straight into the reader. */
    suspend fun openExternal(file: File): Long? {
        val id = importFiles(listOf(file)).firstOrNull() ?: return null
        get(id)?.let { if (!it.metaLoaded) metaMutex.withLock { loadMetadata(it) } }
        return id
    }

    // Metadata and covers

    /** Reads titles, authors and covers for new books, a few at a time. */
    private suspend fun loadPendingMetadata() = kotlinx.coroutines.coroutineScope {
        val pending = read { pendingMeta() }
        if (pending.isEmpty()) return@coroutineScope
        _scan.value = _scan.value.copy(total = pending.size, processed = 0)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val gate = kotlinx.coroutines.sync.Semaphore(META_PARALLELISM)
        pending.map { b ->
            launch {
                gate.acquire()
                try {
                    loadMetadata(b)
                } finally {
                    gate.release()
                }
                val n = done.incrementAndGet()
                _scan.update { it.copy(processed = n) }
            }
        }.forEach { it.join() }
    }

    suspend fun loadMetadata(book: Book) {
        val file = File(book.path)
        val updated = withContext(Dispatchers.IO) {
            // A file that sends a parser into a loop must not hold up every book after it: past the time
            // limit the scan moves on. (A Java thread can't be killed; the stuck one is left to finish.)
            val task = metaExecutor.submit<Book> { readDetails(book, file) }
            runCatching { task.get(META_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrElse {
                task.cancel(true)
                book.copy(metaLoaded = true)
            }
        }
        write {
            val current = book(book.id) ?: return@write
            updateBook(
                current.copy(
                    title = updated.title, author = updated.author, description = updated.description, language = updated.language,
                    series = updated.series, seriesIndex = updated.seriesIndex, publisher = updated.publisher,
                    coverPath = updated.coverPath ?: current.coverPath, metaLoaded = true, pageCount = updated.pageCount,
                    size = file.length().takeIf { it > 0 } ?: current.size, fileModified = file.lastModified(),
                ),
            )
        }
    }

    private fun readDetails(book: Book, file: File): Book {
        val format = book.bookFormat
        var cover: java.awt.image.BufferedImage? = null
        var pages = book.pageCount
        var title = book.title
        var author = book.author
        var meta: com.readarea.core.format.BookMeta? = null
        try {
            when (format) {
                BookFormat.PDF -> PdfSource.open(file).use { src ->
                    pages = src.pageCount
                    val (t, a) = src.documentInfo()
                    val info = com.readarea.core.format.BookMeta(title = t.orEmpty(), author = a.orEmpty()).sanitized()
                    info.title.takeIf { it.isNotEmpty() }?.let { title = it }
                    info.author.takeIf { it.isNotEmpty() }?.let { author = it }
                    val aspect = src.pageAspect(0).coerceIn(0.4f, 1.6f)
                    cover = src.render(0, COVER_W, (COVER_W / aspect).toInt())
                }
                else -> {
                    val details = BookOpener.details(file, format, book.title)
                    meta = details.meta
                    cover = Images.decode(details.cover, COVER_W, COVER_H)?.let { Images.fit(it, COVER_W * 2, COVER_H * 2) }
                    if (format == BookFormat.CBZ) pages = CbzSource(file).use { it.pageCount }
                }
            }
        } catch (e: OutOfMemoryError) {
            return book.copy(metaLoaded = true)
        }
        val m = meta
        return book.copy(
            title = (m?.title?.ifBlank { null } ?: title).take(300),
            author = (m?.author ?: author).take(200),
            description = m?.description?.take(4000),
            language = m?.language?.take(35),
            series = m?.series?.take(200),
            seriesIndex = m?.seriesIndex,
            publisher = m?.publisher?.take(200),
            coverPath = cover?.let { saveCover(book.id, it, book.coverPath) },
            metaLoaded = true,
            pageCount = pages,
        )
    }

    private fun saveCover(id: Long, img: java.awt.image.BufferedImage, previous: String?): String? {
        val dir = dirs.covers
        // Replace the book's old cover by its recorded path; listing the folder for every book would make
        // scanning a large library quadratic.
        previous?.let { File(it) }?.takeIf { AppDirs.isInside(dir, it) }?.delete()
        val file = File(dir, "cover_${id}_${System.currentTimeMillis() % 1_000_000}.jpg")
        return runCatching {
            Images.writeJpeg(Images.fit(img, COVER_W, COVER_H), file)
            AppDirs.makePrivate(file)
            file.absolutePath
        }.getOrNull()
    }

    // Everyday edits

    suspend fun get(id: Long): Book? = read { book(id) }
    suspend fun setFavorite(ids: List<Long>, fav: Boolean) = write { setFavorite(ids, fav) }
    suspend fun setStatus(ids: List<Long>, status: Int) = write { setStatus(ids, status, System.currentTimeMillis()) }
    suspend fun resetProgress(ids: List<Long>) = write { resetProgress(ids) }
    suspend fun setRating(id: Long, rating: Int) = write { setRating(id, rating) }
    suspend fun updateBook(book: Book) = write { updateBook(book) }
    suspend fun updatePosition(id: Long, chapter: Int, offset: Int, progress: Float) = write { updatePosition(id, chapter, offset, progress, System.currentTimeMillis()) }
    suspend fun touch(id: Long) = write { touch(id, System.currentTimeMillis()) }
    suspend fun setPageCount(id: Long, count: Int) = write { setPageCount(id, count) }

    /**
     * Removes books from the library. Their files stay where they are unless [moveFilesToTrash], and
     * even then go to the system trash rather than being deleted. Books from a library folder are
     * remembered as removed so the next scan doesn't bring them back.
     */
    suspend fun removeBooks(ids: List<Long>, moveFilesToTrash: Boolean) {
        val removed = read { ids.mapNotNull { book(it) } }
        val keepOut = ArrayList<String>()
        withContext(Dispatchers.IO) {
            for (b in removed) {
                b.coverPath?.let { p -> File(p).takeIf { AppDirs.isInside(dirs.covers, it) }?.delete() }
                if (moveFilesToTrash && canTrash) runCatching { Desktop.getDesktop().moveToTrash(File(b.path)) }
                else if (b.folder != null) keepOut.add(b.path)
            }
        }
        write { deleteBooks(ids) }
        if (keepOut.isNotEmpty()) settings.updateApp { s -> s.copy(ignored = (s.ignored + keepOut).distinct()) }
    }

    val canTrash: Boolean by lazy { runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) }.getOrDefault(false) }

    suspend fun createShelf(name: String, books: List<Long> = emptyList()): Long = write {
        val id = createShelf(name.trim().take(80), 0)
        if (books.isNotEmpty()) addToShelf(id, books)
        id
    }

    suspend fun renameShelf(id: Long, name: String) = write { renameShelf(id, name.trim().take(80)) }
    suspend fun deleteShelf(id: Long) = write { deleteShelf(id) }
    suspend fun addToShelf(id: Long, books: List<Long>) = write { addToShelf(id, books) }
    suspend fun removeFromShelf(id: Long, books: List<Long>) = write { removeFromShelf(id, books) }

    suspend fun addHighlight(h: Highlight) = write { insertHighlight(h) }
    suspend fun updateHighlight(h: Highlight) = write { updateHighlight(h) }
    suspend fun deleteHighlight(id: Long) = write { deleteHighlight(id) }
    suspend fun addBookmark(b: Bookmark) = write { insertBookmark(b) }
    suspend fun deleteBookmark(id: Long) = write { deleteBookmark(id) }

    suspend fun recordSession(bookId: Long, start: Long, durationMs: Long, pages: Int) {
        if (durationMs < ReadingStats.MIN_SESSION_MS) return
        write {
            transaction {
                insertSession(bookId, start, durationMs.coerceAtMost(12 * 3_600_000L), pages, ReadingStats.dayOf(start))
                addReadingTime(bookId, durationMs.coerceAtMost(12 * 3_600_000L))
            }
        }
    }

    /** Whether the reader has finished the book, for the end-of-book prompt. */
    suspend fun markFinished(id: Long) = setStatus(listOf(id), BookStatus.FINISHED)

    override fun close() {
        scope.cancel()
        dbThread.shutdown()
        runCatching { dbThread.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
    }

    companion object {
        const val COVER_W = 400
        const val COVER_H = 600
        private const val META_TIMEOUT_MS = 20_000L
        private val META_PARALLELISM = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        private val metaExecutor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "book-details").apply { isDaemon = true } }
        private const val MAX_DEPTH = 16
        private const val MAX_FILES = 200_000
        private const val MAX_IMPORT = 5_000
        private val SKIP_DIRS = setOf("node_modules", "\$RECYCLE.BIN", "System Volume Information", "__MACOSX")

        /** App and system data under the home folder, never worth scanning when the reader adds their home. */
        private val systemDirs: Set<Path> = System.getProperty("user.home").let { home ->
            listOf("Library", "AppData", ".cache", ".local").map { File(home, it).toPath() }.toSet()
        }
    }
}
