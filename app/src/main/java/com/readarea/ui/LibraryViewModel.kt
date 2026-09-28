package com.readarea.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readarea.app
import com.readarea.data.AppSettings
import com.readarea.data.ScanState
import com.readarea.data.db.BookEntity
import com.readarea.data.db.BookStatus
import com.readarea.data.db.BookTime
import com.readarea.data.db.BookmarkWithBook
import com.readarea.data.db.CollectionEntity
import com.readarea.data.db.CollectionWithCount
import com.readarea.data.db.DayStat
import com.readarea.data.db.HighlightWithBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.readarea.R

enum class LibraryFilter(val label: Int) { ALL(R.string.filter_all), READING(R.string.status_reading), WANT(R.string.status_want), FINISHED(R.string.status_finished), FAVORITES(R.string.filter_favorites), NEW(R.string.status_new) }

data class LibraryQuery(val text: String = "", val filter: LibraryFilter = LibraryFilter.ALL, val format: String? = null)

data class StatsUi(
    val todayMs: Long = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val totalMs: Long = 0,
    val totalPages: Long = 0,
    val finished: Int = 0,
    val finishedThisYear: Int = 0,
    val week: List<Pair<LocalDate, Long>> = emptyList(),
    val days: Map<Long, Long> = emptyMap(),
    val topBooks: List<BookTime> = emptyList(),
    val goalMinutes: Int = 30,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.app.library
    private val settingsRepo = application.app.settings

    val settings: StateFlow<AppSettings> = settingsRepo.app.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val allBooks: StateFlow<List<BookEntity>?> = repo.books.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val collections: StateFlow<List<CollectionWithCount>> = repo.collections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val highlights: StateFlow<List<HighlightWithBook>> = repo.highlights.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks: StateFlow<List<BookmarkWithBook>> = repo.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scan: StateFlow<ScanState> = repo.scan

    val query = MutableStateFlow(LibraryQuery())

    val library: StateFlow<List<BookEntity>> = combine(repo.books, query, settingsRepo.app) { books, q, s -> filterSort(books, q, s.sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<StatsUi> = combine(repo.days, repo.topBooks, repo.totalMs, repo.totalPages, combine(repo.books, settingsRepo.app) { b, s -> b to s }) { days, top, total, pages, (books, s) ->
        buildStats(days, top, total, pages, books, s.dailyGoalMinutes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUi())

    init {
        viewModelScope.launch {
            val app = settingsRepo.appNow()
            if (app.folders.isNotEmpty() || app.deviceScan) repo.scanAll()
        }
    }

    private fun filterSort(books: List<BookEntity>, q: LibraryQuery, sort: String): List<BookEntity> {
        val text = q.text.trim().lowercase()
        val filtered = books.filter { b ->
            (text.isEmpty() || b.title.lowercase().contains(text) || b.author.lowercase().contains(text) || b.series?.lowercase()?.contains(text) == true || b.fileName.lowercase().contains(text)) &&
                (q.format == null || b.format == q.format) &&
                when (q.filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.READING -> b.status == BookStatus.READING
                    LibraryFilter.WANT -> b.status == BookStatus.WANT
                    LibraryFilter.FINISHED -> b.status == BookStatus.FINISHED
                    LibraryFilter.FAVORITES -> b.favorite
                    LibraryFilter.NEW -> b.status == BookStatus.NEW
                }
        }
        return when (sort) {
            "title" -> filtered.sortedBy { it.title.lowercase() }
            "author" -> filtered.sortedWith(compareBy({ it.author.lowercase().ifEmpty { "￿" } }, { it.series ?: "" }, { it.seriesIndex ?: 0f }, { it.title.lowercase() }))
            "added" -> filtered.sortedByDescending { it.addedAt }
            "progress" -> filtered.sortedByDescending { it.progress }
            "size" -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt }.thenByDescending { it.addedAt })
        }
    }

    private fun buildStats(days: List<DayStat>, top: List<BookTime>, total: Long, pages: Long, books: List<BookEntity>, goal: Int): StatsUi {
        val today = LocalDate.now().toEpochDay()
        val map = days.associate { it.day to it.ms }
        val active = days.filter { it.ms >= 60_000 }.map { it.day }.toSortedSet()
        var streak = 0
        var d = if (active.contains(today)) today else today - 1
        while (active.contains(d)) {
            streak++
            d--
        }
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        for (day in active) {
            run = if (day == prev + 1) run + 1 else 1
            best = maxOf(best, run)
            prev = day
        }
        val week = (6 downTo 0).map { off -> LocalDate.ofEpochDay(today - off) to (map[today - off] ?: 0L) }
        val year = LocalDate.now().year
        val finished = books.filter { it.status == BookStatus.FINISHED }
        return StatsUi(
            todayMs = map[today] ?: 0L,
            streak = streak,
            bestStreak = maxOf(best, streak),
            totalMs = total,
            totalPages = pages,
            finished = finished.size,
            finishedThisYear = finished.count { it.finishedAt > 0 && java.time.Instant.ofEpochMilli(it.finishedAt).atZone(java.time.ZoneId.systemDefault()).year == year },
            week = week,
            days = map,
            topBooks = top,
            goalMinutes = goal,
        )
    }

    fun setQuery(q: LibraryQuery) {
        query.value = q
    }

    fun book(id: Long): Flow<BookEntity?> = repo.book(id)
    fun collectionBooks(id: Long) = repo.collectionBooks(id)
    fun collection(id: Long) = repo.collection(id)
    fun collectionsFor(bookId: Long) = repo.collectionsFor(bookId)

    fun addFolder(uri: Uri) = repo.addFolder(uri)
    fun removeFolder(uri: String, removeBooks: Boolean) = repo.removeFolder(uri, removeBooks)
    fun rescan() = repo.rescan()

    fun setDeviceScan(enabled: Boolean, removeBooks: Boolean = false) = repo.setDeviceScan(enabled, removeBooks)

    fun markDeviceScanAsked() = repo.markDeviceScanAsked()

    fun adoptGrantedAccess() {
        viewModelScope.launch { repo.adoptGrantedAccess() }
    }

    suspend fun resumeTarget(): Long? = repo.resumeTarget()

    /** Whether to offer finding books on the device: the first time, or when it's on but Android took back the access it needs. */
    suspend fun shouldOfferDeviceScan(hasAccess: Boolean): Boolean {
        val s = settingsRepo.appNow()
        return (!s.askedDeviceScan && !s.deviceScan) || (s.deviceScan && !hasAccess)
    }

    val lostFolders = repo.lostFolders

    fun importFiles(uris: List<Uri>, onDone: (List<Long>) -> Unit = {}) {
        viewModelScope.launch { onDone(repo.importFiles(uris)) }
    }

    fun setFavorite(ids: List<Long>, fav: Boolean) = viewModelScope.launch { repo.setFavorite(ids, fav) }
    fun setStatus(ids: List<Long>, status: Int) = viewModelScope.launch { repo.setStatus(ids, status) }
    fun resetProgress(ids: List<Long>) = viewModelScope.launch { repo.resetProgress(ids) }
    fun setRating(id: Long, rating: Int) = viewModelScope.launch { repo.setRating(id, rating) }
    fun remove(ids: List<Long>, deleteFiles: Boolean) = viewModelScope.launch { repo.removeBooks(ids, deleteFiles) }
    fun updateBook(book: BookEntity) = viewModelScope.launch { repo.updateBook(book) }

    fun createCollection(name: String, bookIds: List<Long> = emptyList()) = viewModelScope.launch {
        val id = repo.createCollection(name)
        if (bookIds.isNotEmpty()) repo.addToCollection(id, bookIds)
    }

    fun renameCollection(c: CollectionEntity, name: String) = viewModelScope.launch { repo.renameCollection(c, name) }
    fun deleteCollection(id: Long) = viewModelScope.launch { repo.deleteCollection(id) }
    fun addToCollection(id: Long, bookIds: List<Long>) = viewModelScope.launch { repo.addToCollection(id, bookIds) }
    fun removeFromCollection(id: Long, bookIds: List<Long>) = viewModelScope.launch { repo.removeFromCollection(id, bookIds) }

    fun updateSettings(block: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.updateApp(block) }

    fun deleteHighlight(id: Long) = viewModelScope.launch { getApplication<Application>().app.database.notes().deleteHighlight(id) }
    fun deleteBookmark(id: Long) = viewModelScope.launch { getApplication<Application>().app.database.notes().deleteBookmark(id) }

    fun clearCache() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        java.io.File(getApplication<Application>().cacheDir, "books").deleteRecursively()
    }

    fun cacheSize(): Long = java.io.File(getApplication<Application>().cacheDir, "books").walk().filter { it.isFile }.sumOf { it.length() }

    fun booksMap(): Map<Long, BookEntity> = allBooks.value.orEmpty().associateBy { it.id }

    val formats: StateFlow<List<String>> = repo.books.map { list -> list.map { it.format }.distinct().sorted() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
