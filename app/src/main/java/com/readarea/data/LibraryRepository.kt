package com.readarea.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.readarea.core.BookLoader
import com.readarea.core.FixedBook
import com.readarea.core.ImageUtil
import com.readarea.core.ReflowableBook
import com.readarea.core.format.BookFormat
import com.readarea.data.db.AppDatabase
import com.readarea.data.db.BookCollectionEntity
import com.readarea.data.db.BookEntity
import com.readarea.data.db.CollectionEntity
import com.readarea.data.db.ReadingSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

enum class ScanPhase { IDLE, FOLDERS, DETAILS }

data class ScanState(val running: Boolean = false, val found: Int = 0, val processed: Int = 0, val total: Int = 0, val phase: ScanPhase = ScanPhase.IDLE)

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val metaMutex = Mutex()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _lostFolders = MutableStateFlow<List<String>>(emptyList())

    /** Library folders Android no longer lets ReadArea read, whose books are set aside until it's granted again. */
    val lostFolders: StateFlow<List<String>> = _lostFolders.asStateFlow()

    val books = db.books().observeAll()
    val collections = db.collections().observe()
    val highlights = db.notes().allHighlights()
    val bookmarks = db.notes().allBookmarks()
    val days = db.stats().days()
    val topBooks = db.stats().topBooks()
    val totalMs = db.stats().totalMs()
    val totalPages = db.stats().totalPages()

    fun book(id: Long) = db.books().observe(id)
    fun collectionBooks(id: Long) = db.collections().books(id)
    fun collection(id: Long) = db.collections().observeOne(id)
    fun collectionsFor(bookId: Long) = db.collections().collectionsFor(bookId)

    private val coverDir get() = File(context.filesDir, "covers").apply { mkdirs() }

    fun addFolder(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        scope.launch {
            settings.updateApp { s -> if (s.folders.contains(tree.toString())) s else s.copy(folders = s.folders + tree.toString()) }
            scanAll()
        }
    }

    fun removeFolder(tree: String, removeBooks: Boolean) {
        scope.launch {
            settings.updateApp { s -> s.copy(folders = s.folders - tree) }
            runCatching { context.contentResolver.releasePersistableUriPermission(tree.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (removeBooks) removeFolderBooks(tree)
        }
    }

    /** Deletes the books of one library folder along with their cover images, which nothing else would ever clean up. */
    private suspend fun removeFolderBooks(folder: String) {
        val covers = db.books().all().filter { it.folderUri == folder }.mapNotNull { it.coverPath }
        db.books().deleteFolder(folder)
        covers.forEach { path -> File(path).takeIf { SafeFiles.inside(coverDir, it) }?.delete() }
    }

    fun rescan() {
        scope.launch { scanAll() }
    }

    fun setDeviceScan(enabled: Boolean, removeBooks: Boolean = false) {
        scope.launch {
            settings.updateApp { it.copy(deviceScan = enabled, askedDeviceScan = true, deviceScanOff = !enabled) }
            if (enabled) scanAll() else if (removeBooks) removeFolderBooks(DeviceStorage.DEVICE)
        }
    }

    fun setResumeBook(id: Long) {
        scope.launch { settings.updateApp { if (it.resumeBookId == id) it else it.copy(resumeBookId = id) } }
    }

    suspend fun resumeTarget(): Long? {
        val s = settings.appNow()
        if (!s.reopenLastBook || s.resumeBookId <= 0) return null
        val b = db.books().get(s.resumeBookId) ?: return null
        // Starting on an error page instead of the library helps nobody: only reopen a book that can be read.
        return if (b.missing || !canRead(b.uri)) null else b.id
    }

    /** Whether a book's file can be opened right now, checked without reading it. */
    private suspend fun canRead(uri: String): Boolean = withContext(Dispatchers.IO) {
        val u = uri.toUri()
        runCatching {
            if (u.scheme == "file") File(u.path!!).canRead()
            else context.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    suspend fun adoptGrantedAccess(): Boolean {
        val s = settings.appNow()
        if (s.deviceScan || s.deviceScanOff || !DeviceStorage.hasAccess(context)) return false
        settings.updateApp { it.copy(deviceScan = true, askedDeviceScan = true) }
        scope.launch { scanAll() }
        return true
    }

    fun markDeviceScanAsked() {
        scope.launch { settings.updateApp { it.copy(askedDeviceScan = true) } }
    }

    suspend fun scanAll() {
        if (scanMutex.isLocked) return
        scanMutex.withLock {
            val app = settings.appNow()
            val folders = app.folders
            val ignored = app.ignored.toHashSet()
            _scan.value = ScanState(running = true, phase = ScanPhase.FOLDERS)
            var found = 0
            val lost = ArrayList<String>()
            for (folder in folders) {
                val tree = folder.toUri()
                val walked = runCatching { walkTree(tree) }
                if (walked.exceptionOrNull() is SecurityException) {
                    // Android took back access to this folder (a reinstall restored from backup, or access withdrawn):
                    // its books can't be opened, so they are set aside until the folder is chosen again.
                    lost.add(folder)
                    val stranded = db.books().all().filter { it.folderUri == folder && !it.missing }.map { it.id }
                    setMissing(stranded, true)
                    continue
                }
                val files = walked.getOrElse { emptyList() }
                found += files.size
                _scan.value = _scan.value.copy(found = found)
                val all = db.books().all()
                val existing = all.filter { it.folderUri == folder }.associateBy { it.uri }
                val deviceUris = all.filter { it.folderUri == DeviceStorage.DEVICE }.map { it.uri }.toHashSet()
                val seen = HashSet<String>()
                for (f in files) {
                    seen.add(f.uri)
                    if (f.uri in ignored) continue
                    val old = existing[f.uri]
                    if (old == null) {
                        val real = DeviceStorage.realPath(f.uri)
                        if (real != null && DeviceStorage.fileUri(File(real)) in deviceUris) continue
                        db.books().insert(
                            BookEntity(
                                uri = f.uri, fileName = f.name, folderUri = folder, format = f.format.name, size = f.size,
                                title = cleanTitle(f.name), fileModified = f.modified,
                            ),
                        )
                    } else if (old.missing) {
                        db.books().setMissing(listOf(old.id), false)
                    }
                }
                val gone = existing.values.filter { it.uri !in seen && !it.missing }.map { it.id }
                if (gone.isNotEmpty() && files.isNotEmpty()) db.books().setMissing(gone, true)
            }
            _lostFolders.value = lost
            if (DeviceStorage.hasAccess(context)) {
                if (app.deviceScan) found += scanDevice(found, ignored)
            } else {
                // Books found on the device can only be read with access to all files; without it they'd fail when opened.
                val stranded = db.books().all().filter { it.folderUri == DeviceStorage.DEVICE && !it.missing }.map { it.id }
                setMissing(stranded, true)
            }
            checkImportedAccess()
            forgetLostCovers()
            _scan.value = ScanState(running = true, found = found, phase = ScanPhase.DETAILS)
            metaMutex.withLock { loadPendingMetadata() }
            _scan.value = ScanState(running = false, found = found)
        }
    }

    private suspend fun scanDevice(base: Int, ignored: Set<String>): Int {
        val roots = DeviceStorage.roots(context)
        if (roots.isEmpty()) return 0
        val found = ArrayList<DeviceStorage.Found>()
        DeviceStorage.walk(roots) { f ->
            found.add(f)
            if (found.size % 20 == 0) _scan.value = _scan.value.copy(found = base + found.size)
        }
        _scan.value = _scan.value.copy(found = base + found.size)
        val all = db.books().all()
        val existing = all.filter { it.folderUri == DeviceStorage.DEVICE }.associateBy { it.uri }
        val otherPaths = all.filter { it.folderUri != DeviceStorage.DEVICE }.mapNotNull { DeviceStorage.realPath(it.uri) }.toHashSet()
        val seen = HashSet<String>()
        for (f in found) {
            val uri = DeviceStorage.fileUri(f.file)
            seen.add(uri)
            if (uri in ignored || f.file.absolutePath in otherPaths) continue
            val old = existing[uri]
            if (old == null) {
                db.books().insert(
                    BookEntity(
                        uri = uri, fileName = f.file.name, folderUri = DeviceStorage.DEVICE, format = f.format.name, size = f.file.length(),
                        title = cleanTitle(f.file.name), fileModified = f.file.lastModified(),
                    ),
                )
            } else if (old.missing) {
                db.books().setMissing(listOf(old.id), false)
            }
        }
        val gone = existing.values.filter { it.uri !in seen && !it.missing }.map { it.id }
        if (gone.isNotEmpty()) db.books().setMissing(gone, true)
        return found.size
    }

    /**
     * Books added one by one keep a permission per file, which Android can take back (a reinstall, or its limit on
     * how many it keeps). Those that can't be opened are set aside, and come back once they can be.
     */
    private suspend fun checkImportedAccess() = withContext(Dispatchers.IO) {
        val held = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toHashSet()
        val imported = db.books().all().filter { it.folderUri == null && it.uri.toUri().scheme == "content" }
        val lost = ArrayList<Long>()
        val back = ArrayList<Long>()
        for (b in imported) {
            val readable = b.uri in held || canRead(b.uri)
            if (!readable && !b.missing) lost.add(b.id) else if (readable && b.missing) back.add(b.id)
        }
        setMissing(lost, true)
        setMissing(back, false)
    }

    /** Covers live in app storage, which a restore from backup doesn't bring back: read the ones that are gone again. */
    private suspend fun forgetLostCovers() = withContext(Dispatchers.IO) {
        val gone = db.books().all().filter { b -> b.coverPath?.let { !File(it).exists() } == true }.map { it.id }
        gone.chunked(500).forEach { db.books().forgetCovers(it) }
    }

    /** Android's SQLite takes at most 999 values in one statement, and these lists can be a whole library. */
    private suspend fun setMissing(ids: List<Long>, missing: Boolean) = ids.chunked(500).forEach { db.books().setMissing(it, missing) }

    private data class FoundFile(val uri: String, val name: String, val size: Long, val modified: Long, val format: BookFormat)

    private fun walkTree(tree: Uri): List<FoundFile> {
        val out = ArrayList<FoundFile>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        val resolver: ContentResolver = context.contentResolver
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 5000) {
            val docId = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.add(id)
                        continue
                    }
                    val format = BookFormat.fromFileName(name) ?: continue
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (size in 1..63) continue
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    out.add(FoundFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, size, modified, format))
                }
            }
        }
        return out
    }

    suspend fun importFiles(uris: List<Uri>): List<Long> = withContext(Dispatchers.IO) {
        val ids = ArrayList<Long>()
        for (uri in uris) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            addSingle(uri, copy = false)?.let { ids.add(it) }
        }
        scope.launch { metaMutex.withLock { loadPendingMetadata() } }
        ids
    }

    suspend fun openExternal(uri: Uri): Long? = withContext(Dispatchers.IO) {
        if (SafeFiles.isPrivate(context, uri)) return@withContext null
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        val id = addSingle(uri, copy = !persisted && uri.scheme != "file") ?: return@withContext null
        db.books().get(id)?.let { if (!it.metaLoaded) loadMetadata(it) }
        id
    }

    private suspend fun addSingle(uri: Uri, copy: Boolean): Long? {
        val (rawName, size) = queryName(uri)
        val name = SafeFiles.fileName(rawName)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val format = BookFormat.fromFileName(name) ?: BookFormat.fromMime(mime) ?: return null
        val fileName = if (BookFormat.fromFileName(name) == null) "$name.${format.extensions.first()}" else name
        var target = uri
        if (copy) {
            val dir = File(context.filesDir, "imported").apply { mkdirs() }
            val out = uniqueFile(dir, fileName)
            if (!SafeFiles.inside(dir, out)) return null
            val existing = dir.listFiles()?.firstOrNull { it.name == fileName && it.length() == size && size > 0 }
            if (existing != null) {
                target = Uri.fromFile(existing)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
                target = Uri.fromFile(out)
            }
        }
        db.books().byUri(target.toString())?.let {
            if (it.missing) db.books().setMissing(listOf(it.id), false)
            return it.id
        }
        val id = db.books().insert(BookEntity(uri = target.toString(), fileName = fileName, format = format.name, size = size, title = cleanTitle(fileName)))
        return if (id > 0) id else db.books().byUri(target.toString())?.id
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.'))
            i++
        }
        return f
    }

    private fun queryName(uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return f.name to f.length()
        }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Book"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    private suspend fun loadPendingMetadata() {
        val pending = db.books().pendingMeta()
        _scan.value = _scan.value.copy(total = pending.size, processed = 0)
        pending.forEachIndexed { i, b ->
            loadMetadata(b)
            _scan.value = _scan.value.copy(processed = i + 1)
        }
    }

    suspend fun loadMetadata(book: BookEntity) {
        val format = BookFormat.byName(book.format)
        val updated = runCatching {
            val opened = BookLoader.open(context, book.uri, format, book.fileName, "b${book.id}", metadataOnly = true)
            try {
                val meta = opened.meta
                var coverPath: String? = null
                var pages = 0
                when (opened) {
                    is ReflowableBook -> {
                        if (format == BookFormat.CBZ) pages = 0
                        opened.book.coverBytes()?.let { bytes ->
                            ImageUtil.decodeSampled(bytes, 360, 540)?.let { bmp -> coverPath = saveCover(book.id, bmp, book.coverPath) }
                        }
                    }
                    is FixedBook -> {
                        pages = opened.source.pageCount
                        if (pages > 0) {
                            val aspect = opened.source.pageAspect(0).coerceIn(0.4f, 1.6f)
                            val w = 360
                            val bmp = Bitmap.createBitmap(w, (w / aspect).toInt(), Bitmap.Config.ARGB_8888)
                            opened.source.render(0, bmp, null)
                            coverPath = saveCover(book.id, bmp, book.coverPath)
                        }
                    }
                }
                book.copy(
                    title = meta.title.ifBlank { book.title }.take(300),
                    author = meta.author.take(200),
                    description = meta.description?.take(4000),
                    language = meta.language,
                    series = meta.series,
                    seriesIndex = meta.seriesIndex,
                    publisher = meta.publisher,
                    coverPath = coverPath,
                    metaLoaded = true,
                    pageCount = if (pages > 0) pages else book.pageCount,
                )
            } finally {
                opened.close()
            }
        }
        // A file that can't be opened right now (not reachable, or access not granted yet) is tried again on the next
        // scan; only one that opened but couldn't be read is settled without details.
        val error = updated.exceptionOrNull()
        if (error is java.io.FileNotFoundException || error is SecurityException) return
        val details = updated.getOrElse { book.copy(metaLoaded = true) }
        val current = db.books().get(book.id) ?: return
        db.books().update(
            current.copy(
                title = details.title, author = details.author, description = details.description, language = details.language,
                series = details.series, seriesIndex = details.seriesIndex, publisher = details.publisher, coverPath = details.coverPath,
                metaLoaded = true, pageCount = details.pageCount,
            ),
        )
    }

    private fun saveCover(id: Long, bmp: Bitmap, previous: String?): String? {
        // Replace the book's old cover by its recorded path; listing the folder for every book would make scanning
        // a large library quadratic.
        previous?.let { File(it) }?.takeIf { SafeFiles.inside(coverDir, it) }?.delete()
        val file = File(coverDir, "cover_${id}_${System.currentTimeMillis() % 100000}.jpg")
        return runCatching {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            file.absolutePath
        }.getOrNull()
    }

    suspend fun setFavorite(ids: List<Long>, fav: Boolean) = db.books().setFavorite(ids, fav)
    suspend fun setStatus(ids: List<Long>, status: Int) = db.books().setStatus(ids, status, System.currentTimeMillis())
    suspend fun resetProgress(ids: List<Long>) = db.books().resetProgress(ids)
    suspend fun setRating(id: Long, rating: Int) = db.books().setRating(id, rating)
    suspend fun updateBook(book: BookEntity) = db.books().update(book)
    suspend fun get(id: Long) = db.books().get(id)

    suspend fun removeBooks(ids: List<Long>, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val keepOut = ArrayList<String>()
        val imported = File(context.filesDir, "imported").canonicalPath + File.separator
        for (id in ids) {
            val b = db.books().get(id) ?: continue
            b.coverPath?.let { File(it).delete() }
            val uri = b.uri.toUri()
            val ownCopy = uri.scheme == "file" && runCatching { File(uri.path!!).canonicalPath.startsWith(imported) }.getOrDefault(false)
            if (deleteFiles || ownCopy) {
                runCatching {
                    if (uri.scheme == "file") File(uri.path!!).delete() else DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
            } else if (b.folderUri != null) {
                keepOut.add(b.uri)
            }
            File(File(context.cacheDir, "books"), "b$id.${BookFormat.byName(b.format).extensions.first()}").delete()
        }
        db.books().delete(ids)
        if (keepOut.isNotEmpty()) settings.updateApp { s -> s.copy(ignored = (s.ignored + keepOut).distinct().takeLast(5000)) }
    }

    suspend fun createCollection(name: String, color: Int = 0): Long = db.collections().insert(CollectionEntity(name = name.trim(), color = color))
    suspend fun renameCollection(c: CollectionEntity, name: String) = db.collections().update(c.copy(name = name.trim()))
    suspend fun deleteCollection(id: Long) = db.collections().delete(id)
    suspend fun addToCollection(collectionId: Long, bookIds: List<Long>) = db.collections().addBooks(bookIds.map { BookCollectionEntity(it, collectionId) })
    suspend fun removeFromCollection(collectionId: Long, bookIds: List<Long>) = db.collections().removeBooks(collectionId, bookIds)

    suspend fun recordSession(bookId: Long, start: Long, durationMs: Long, pages: Int) {
        if (durationMs < 5000) return
        val day = java.time.Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        db.stats().insert(ReadingSessionEntity(bookId = bookId, start = start, durationMs = durationMs, pages = pages, day = day))
        db.books().addReadingTime(bookId, durationMs)
    }

    fun today(): Long = LocalDate.now().toEpochDay()

    companion object {
        fun cleanTitle(fileName: String): String {
            var t = fileName.substringBeforeLast('.')
            if (t.endsWith(".fb2", true)) t = t.dropLast(4)
            return t.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        }
    }
}
