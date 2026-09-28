package com.readarea.desktop.reader

import com.readarea.core.book.BookOpener
import com.readarea.core.format.BookFormat
import com.readarea.core.format.BookParseException
import com.readarea.core.format.ParseError
import com.readarea.core.format.TextDirection
import com.readarea.core.text.BookText
import com.readarea.core.text.SearchHit
import com.readarea.core.theme.ReadingTheme
import com.readarea.core.theme.ReadingThemes
import com.readarea.desktop.App
import com.readarea.desktop.book.CbzSource
import com.readarea.desktop.book.PasswordRequiredException
import com.readarea.desktop.book.PdfSource
import com.readarea.desktop.data.Book
import com.readarea.desktop.data.Bookmark
import com.readarea.desktop.data.Highlight
import com.readarea.desktop.data.ReaderSettings
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.platform.SystemIntegration
import com.readarea.desktop.reader.engine.Decorations
import com.readarea.desktop.reader.engine.FixedEngine
import com.readarea.desktop.reader.engine.HighlightRange
import com.readarea.desktop.reader.engine.PageEngine
import com.readarea.desktop.reader.engine.PagePos
import com.readarea.desktop.reader.engine.PageSetup
import com.readarea.desktop.reader.engine.TextEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Shape
import java.io.File

enum class Panel { NONE, CONTENTS, SEARCH, TEXT, THEME }

data class TocEntry(val title: String, val chapter: Int, val anchor: String?, val page: Int, val depth: Int)

data class SelectionUi(val chapter: Int, val start: Int, val end: Int, val text: String, val bounds: java.awt.Rectangle, val highlightId: Long? = null, val color: Int = -1, val note: String? = null)

data class FootnoteUi(val text: String, val chapter: Int, val offset: Int, val bounds: java.awt.Rectangle?)

data class ReaderUi(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val author: String = "",
    val format: BookFormat = BookFormat.EPUB,
    val fixed: Boolean = false,
    val chapter: Int = 0,
    val chapterCount: Int = 1,
    val chapterTitle: String = "",
    val progress: Float = 0f,
    val pageLabel: String = "",
    val pagesLeftInChapter: Int = 0,
    val menu: Boolean = false,
    val panel: Panel = Panel.NONE,
    val bookmarked: Boolean = false,
    val toc: List<TocEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchHit> = emptyList(),
    val selection: SelectionUi? = null,
    val footnote: FootnoteUi? = null,
    val speaking: Boolean = false,
    val ttsPaused: Boolean = false,
    val autoTurn: Boolean = false,
    val jumpBack: Pair<Int, Int>? = null,
    val message: String? = null,
    val laidOut: Boolean = false,
    val endReached: Boolean = false,
    val ttsAvailable: Boolean = false,
    val rtl: Boolean = false,
    val bookRtl: Boolean = false,
    val vertical: Boolean = false,
    val bookVertical: Boolean = false,
    val bookCjk: Boolean = false,
    val scrollMode: Boolean = false,
    val columns: Int = 1,
)

/** What the controller asks of the window that shows the book. */
interface ReaderHost {
    fun refresh()
    fun flip(forward: Boolean)
    fun scrollTo(pos: PagePos, fraction: Float)
    fun scrollBy(dy: Float)
    fun autoScroll(pxPerSecond: Float)
    fun askPassword(retry: Boolean): String?
}

data class Viewport(val width: Int, val height: Int, val scale: Float)

/**
 * The reading logic, independent of Swing widgets: which page is showing, layout on settings changes,
 * notes, search, read-aloud and progress. It runs on the UI thread and lays books out in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderController(private val app: App, val bookId: Long, private val initialAnchor: Pair<Int, Int>?) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val repo = app.library
    val settings: StateFlow<ReaderSettings> = app.settings.reader
    private val _ui = MutableStateFlow(ReaderUi(ttsAvailable = app.speech != null))
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()
    var host: ReaderHost? = null

    var engine: PageEngine? = null
        private set
    var text: BookText? = null
        private set
    val deco = Decorations()
    var pos = PagePos(0, 0)
        private set
    private var book: Book? = null
    private var viewport: Viewport? = null
    @Volatile var systemDark = app.systemDark
    private var laidOutSettings: ReaderSettings? = null
    private val layoutDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var layoutJob: Job? = null
    private var saveJob: Job? = null
    private var autoJob: Job? = null
    private var searchJob: Job? = null
    @Volatile var layoutBusy = true
        private set
    private var pendingAnchor: Pair<Int, Int>? = null
    private var readingAnchor: Pair<Int, Int>? = null
    private var scrollFraction = 0f
    private var sessionStart = 0L
    private var pagesTurned = 0
    private var selChapter = 0
    private var selStart = 0
    private var selEnd = 0
    private var ttsSentences: List<IntRange> = emptyList()
    private var ttsChapter = 0
    private var ttsIndex = 0

    val scrollMode: Boolean get() = isScroll(settings.value)

    private fun isScroll(s: ReaderSettings): Boolean = s.pageAnim == "scroll" && !verticalFor(s)

    private fun verticalFor(s: ReaderSettings): Boolean {
        if (engine !is TextEngine) return false
        return when (s.writingMode) {
            "vertical" -> true
            "horizontal" -> false
            else -> _ui.value.bookVertical
        }
    }

    private fun rtlFor(s: ReaderSettings): Boolean = when (s.pageDirection) {
        "rtl" -> true
        "ltr" -> false
        else -> _ui.value.bookRtl || verticalFor(s)
    }

    private fun columnsFor(v: Viewport, s: ReaderSettings): Int {
        if (isScroll(s)) return 1
        return when (s.spread) {
            "single" -> 1
            "double" -> 2
            else -> if (v.width >= 1100 && v.width > v.height * 1.2f) 2 else 1
        }
    }

    private fun errorText(e: Throwable): String = when {
        e is BookParseException && e.reason == ParseError.DRM -> tr("error_drm")
        e is BookParseException && e.reason == ParseError.UNSUPPORTED -> tr("error_unsupported")
        e is BookParseException && e.reason == ParseError.EMPTY -> tr("error_empty")
        e is BookParseException && e.reason == ParseError.TOO_LARGE -> tr("error_too_large")
        e is OutOfMemoryError -> tr("error_too_large")
        e is BookParseException -> tr("error_invalid")
        e is PasswordRequiredException -> tr("error_password")
        e is java.io.FileNotFoundException || e is SecurityException || e is java.nio.file.NoSuchFileException -> tr("error_missing_file")
        else -> tr("error_generic")
    }

    fun open() {
        scope.launch {
            val b = repo.get(bookId)
            if (b == null) {
                _ui.update { it.copy(loading = false, error = tr("error_not_in_library")) }
                return@launch
            }
            book = b
            deco.bookTitle = b.title
            _ui.update { it.copy(title = b.title, author = b.author, format = b.bookFormat) }
            val result = openEngine(b)
            result.onFailure { e -> _ui.update { it.copy(loading = false, error = errorText(e)) } }
            result.onSuccess { e -> onEngine(b, e) }
        }
    }

    private suspend fun openEngine(b: Book): Result<PageEngine> {
        val file = File(b.path)
        val format = b.bookFormat
        var password = ""
        var retry = false
        while (true) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (format) {
                        BookFormat.PDF -> FixedEngine(PdfSource.open(file, password), b.title, fixedCacheBytes())
                        BookFormat.CBZ -> FixedEngine(CbzSource(file), b.title, fixedCacheBytes())
                        else -> {
                            val opened = BookOpener.open(file, format, b.title)
                            val maxImg = (Runtime.getRuntime().maxMemory() / 10).coerceIn(16L shl 20, 96L shl 20)
                            TextEngine(opened.book, BookText(opened.book), app.dirs.fonts, maxImg).also { closer = { opened.close() } }
                        }
                    }
                }
            }
            val err = r.exceptionOrNull()
            if (err is PasswordRequiredException) {
                password = host?.askPassword(retry) ?: return r
                retry = true
                continue
            }
            return r
        }
    }

    private var closer: (() -> Unit)? = null

    private fun fixedCacheBytes() = (Runtime.getRuntime().maxMemory() / 5).coerceIn(48L shl 20, 320L shl 20)

    private fun onEngine(b: Book, e: PageEngine) {
        engine = e
        text = (e as? TextEngine)?.text
        if (e is FixedEngine) e.onPageRendered = { javax.swing.SwingUtilities.invokeLater { host?.refresh() } }
        pendingAnchor = initialAnchor?.let { it.first.coerceIn(0, e.chapterCount - 1) to it.second } ?: (b.chapter.coerceIn(0, e.chapterCount - 1) to b.offset)
        val meta = (e as? TextEngine)?.book?.meta
        val bookRtl = meta?.rtl ?: false
        val bookVertical = meta?.vertical ?: false
        val bookCjk = meta != null && (TextDirection.isCjkLanguage(meta.language) || bookVertical)
        val toc = when (e) {
            is TextEngine -> e.book.toc.map { TocEntry(it.title, it.chapter, it.anchor, -1, it.depth) }
            is FixedEngine -> e.outline.map { TocEntry(it.title, 0, null, it.page, it.depth) }
            else -> emptyList()
        }
        _ui.update { it.copy(toc = toc, chapterCount = e.chapterCount, fixed = e.fixed, ttsAvailable = e is TextEngine && app.speech != null, bookRtl = bookRtl, bookVertical = bookVertical, bookCjk = bookCjk) }
        _ui.update { it.copy(rtl = rtlFor(settings.value), vertical = verticalFor(settings.value)) }
        scope.launch { app.library.version.collect { reloadNotes() } }
        scope.launch { settings.drop(1).collect { s -> onSettings(s) } }
        if (!b.metaLoaded) scope.launch { repo.loadMetadata(b) }
        scope.launch { repo.touch(b.id) }
        relayout()
    }

    private suspend fun reloadNotes() {
        val marks = repo.read { bookmarks(bookId) }
        val hls = repo.read { highlights(bookId) }
        if (marks != _ui.value.bookmarks) {
            _ui.update { it.copy(bookmarks = marks) }
            rebuildBookmarkPages()
        }
        if (hls != _ui.value.highlights) {
            _ui.update { it.copy(highlights = hls) }
            deco.highlights = hls.map { HighlightRange(it.chapter, it.start, it.end, ReadingThemes.highlightColor(it.color)) }
            host?.refresh()
        }
    }

    fun currentTheme(): ReadingTheme {
        val s = settings.value
        return ReadingThemes.resolve(s.theme, s.nightTheme, s.autoNight, systemDark, s.customBg, s.customFg, s.texture)
    }

    fun onSystemTheme(dark: Boolean) {
        if (systemDark == dark) return
        systemDark = dark
        engine?.applyTheme(currentTheme())
        host?.refresh()
    }

    private fun onSettings(s: ReaderSettings) {
        val prev = laidOutSettings ?: return
        _ui.update { it.copy(rtl = rtlFor(s), vertical = verticalFor(s)) }
        val v = viewport ?: return
        val setupChanged = prev.layoutKey() != s.layoutKey() || isScroll(prev) != isScroll(s) || verticalFor(prev) != verticalFor(s) || columnsFor(v, prev) != columnsFor(v, s)
        if (setupChanged) relayout() else {
            engine?.configure(buildSetup(v, s), currentTheme())
            laidOutSettings = s
            host?.refresh()
        }
    }

    fun onViewport(v: Viewport) {
        if (v == viewport) return
        viewport = v
        relayout()
    }

    private fun buildSetup(v: Viewport, s: ReaderSettings): PageSetup {
        val scroll = isScroll(s)
        return PageSetup(v.width, v.height, s, columnsFor(v, s), scroll, rtlFor(s), verticalFor(s), v.scale)
    }

    private fun anchor(): Pair<Int, Int> {
        pendingAnchor?.let { return it }
        readingAnchor?.let { return it }
        val e = engine ?: return 0 to 0
        if (!e.isReady(pos.chapter)) return pos.chapter to 0
        val start = e.offsetOf(pos)
        if (scrollMode && !e.fixed && scrollFraction > 0f) {
            val end = e.endOffsetOf(pos)
            return pos.chapter to (start + ((end - start) * scrollFraction).toInt())
        }
        return pos.chapter to start
    }

    /** Lays the book out again for the current window and settings, staying on the same text. */
    private fun relayout() {
        val e = engine ?: return
        val v = viewport ?: return
        val s = settings.value
        val a = anchor()
        pendingAnchor = a
        layoutBusy = true
        layoutJob?.cancel()
        layoutJob = scope.launch(layoutDispatcher) {
            val setup = buildSetup(v, s)
            e.configure(setup, currentTheme())
            (e as? TextEngine)?.pinned = (a.first - 1)..(a.first + 1)
            e.ensure(a.first)
            ensureActive()
            val page = e.align(e.pageOf(a.first, a.second))
            val fraction = if (isScroll(s) && !e.fixed) {
                val st = e.offsetOf(PagePos(a.first, page))
                val en = e.endOffsetOf(PagePos(a.first, page))
                if (en > st) ((a.second - st).toFloat() / (en - st)).coerceIn(0f, 0.95f) else 0f
            } else 0f
            e.ensure(a.first + 1)
            e.ensure(a.first - 1)
            withContext(Dispatchers.Swing) {
                laidOutSettings = s
                pendingAnchor = null
                readingAnchor = a
                pos = PagePos(a.first, page)
                scrollFraction = fraction
                layoutBusy = false
                rebuildBookmarkPages()
                refreshUi()
                _ui.update { it.copy(loading = false, laidOut = true, scrollMode = isScroll(s), columns = setup.columns) }
                host?.refresh()
                if (isScroll(s)) host?.scrollTo(pos, fraction)
                e.prefetch(pos)
            }
            val order = (0 until e.chapterCount).sortedBy { kotlin.math.abs(it - a.first) }
            for (c in order) {
                ensureActive()
                e.ensure(c)
            }
            withContext(Dispatchers.Swing) {
                rebuildBookmarkPages()
                refreshUi()
                host?.refresh()
                book?.let { b -> e.totalPages()?.let { total -> if (b.pageCount != total && !e.fixed) repo.setPageCount(b.id, total) } }
            }
        }
    }

    private fun ensureAsync(chapter: Int) {
        val e = engine ?: return
        if (chapter !in 0 until e.chapterCount || e.isReady(chapter)) return
        scope.launch(layoutDispatcher) {
            e.ensure(chapter)
            withContext(Dispatchers.Swing) { host?.refresh() }
        }
    }

    private fun refreshUi() {
        val e = engine ?: return
        if (!e.isReady(pos.chapter)) return
        val offset = e.offsetOf(pos)
        val progress = e.progress(pos.chapter, offset)
        val gp = e.globalPage(pos)
        val total = e.totalPages()
        val label = when {
            e.fixed -> I18n.format("page_of", pos.page + 1, e.pageCount(0))
            gp != null && total != null -> I18n.format("page_of", gp, total)
            else -> I18n.format("page_of_chapter", pos.page + 1, e.pageCount(pos.chapter))
        }
        val title = when {
            e is FixedEngine -> e.outline.lastOrNull { it.page <= pos.page }?.title ?: book?.title.orEmpty()
            else -> text?.sectionTitleAt(pos.chapter, e.endOffsetOf(pos)) ?: e.chapterTitle(pos.chapter)
        }
        (e as? TextEngine)?.pinned = (pos.chapter - 1)..(pos.chapter + 1)
        _ui.update {
            it.copy(
                chapter = pos.chapter,
                chapterTitle = title,
                progress = progress,
                pageLabel = label,
                pagesLeftInChapter = (e.pageCount(pos.chapter) - pos.page - e.step).coerceAtLeast(0),
                bookmarked = isBookmarked(),
            )
        }
    }

    private fun isBookmarked(): Boolean {
        val e = engine ?: return false
        if (!e.isReady(pos.chapter)) return false
        val s = e.offsetOf(pos)
        val en = e.endOffsetOf(PagePos(pos.chapter, (pos.page + e.step - 1).coerceAtMost(e.pageCount(pos.chapter) - 1)))
        return _ui.value.bookmarks.any { it.chapter == pos.chapter && it.offset in s until en.coerceAtLeast(s + 1) }
    }

    private fun rebuildBookmarkPages() {
        val e = engine ?: return
        deco.bookmarkedPages = _ui.value.bookmarks.mapNotNull { b -> if (e.isReady(b.chapter)) PagePos(b.chapter, e.pageOf(b.chapter, b.offset)) else null }.toSet()
        _ui.update { it.copy(bookmarked = isBookmarked()) }
        host?.refresh()
    }

    private fun onPageChanged() {
        val e = engine ?: return
        readingAnchor = null
        pagesTurned++
        refreshUi()
        e.prefetch(pos)
        ensureAsync(pos.chapter + 1)
        ensureAsync(pos.chapter - 1)
        scheduleSave()
        if (_ui.value.jumpBack != null && pagesTurned % 6 == 0) _ui.update { it.copy(jumpBack = null) }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(700)
            savePosition()
        }
    }

    fun savePosition() {
        val e = engine ?: return
        if (!e.isReady(pos.chapter) || layoutBusy) return
        val (c, o) = anchor()
        val progress = if (e.fixed) e.progress(0, pos.page) else e.progress(c, o)
        app.library.launch { updatePosition(bookId, c, o, progress) }
    }

    // Navigation

    /** The page to draw at [offset] from the current one (-1 previous, 0 current, 1 next). */
    fun pageAt(offset: Int): PagePos? {
        val e = engine ?: return null
        if (layoutBusy) return null
        val p = when (offset) {
            0 -> pos
            1 -> e.next(pos)
            else -> e.prev(pos)
        } ?: return null
        return if (e.isReady(p.chapter)) p else null
    }

    fun canFlip(forward: Boolean): Boolean {
        val e = engine ?: return false
        if (layoutBusy) return false
        val target = if (forward) e.next(pos) else e.prev(pos)
        if (target == null) {
            e.neighborPending(pos, forward)?.let { ensureAsync(it) }
            if (forward && e.neighborPending(pos, true) == null && e.next(pos) == null) {
                _ui.update { it.copy(endReached = true) }
                savePosition()
            }
        }
        return target != null
    }

    fun onFlipped(forward: Boolean) {
        val e = engine ?: return
        val np = (if (forward) e.next(pos) else e.prev(pos)) ?: return
        pos = np
        clearSearchHighlight()
        onPageChanged()
        if (_ui.value.endReached) _ui.update { it.copy(endReached = false) }
    }

    fun onScrollPosition(p: PagePos, fraction: Float) {
        val changed = p != pos
        if (changed || kotlin.math.abs(fraction - scrollFraction) > 0.001f) readingAnchor = null
        pos = p
        scrollFraction = fraction
        if (changed) onPageChanged() else scheduleSave()
    }

    fun goTo(chapter: Int, offset: Int, rememberJump: Boolean = false, highlight: HighlightRange? = null) {
        val e = engine ?: return
        if (rememberJump && e.isReady(pos.chapter)) _ui.update { it.copy(jumpBack = pos.chapter to e.offsetOf(pos)) }
        deco.search = highlight
        scope.launch(layoutDispatcher) {
            e.ensure(chapter)
            val page = e.align(e.pageOf(chapter, offset))
            withContext(Dispatchers.Swing) {
                pos = PagePos(chapter.coerceIn(0, e.chapterCount - 1), page)
                scrollFraction = 0f
                onPageChanged()
                host?.refresh()
                if (scrollMode) host?.scrollTo(pos, 0f)
            }
        }
    }

    fun goToToc(t: TocEntry) {
        if (t.page >= 0) goTo(0, t.page, rememberJump = true)
        else goTo(t.chapter, t.anchor?.let { text?.anchors(t.chapter)?.get(it) } ?: 0, rememberJump = true)
    }

    fun goBack() {
        val j = _ui.value.jumpBack ?: return
        _ui.update { it.copy(jumpBack = null) }
        goTo(j.first, j.second)
    }

    fun goToProgress(p: Float) {
        val e = engine ?: return
        val (c, o) = e.locate(p)
        goTo(c, o)
    }

    /** Page number (1-based, whole book) or percent, as typed in the "Go to" box. */
    fun goToPage(page: Int) {
        val e = engine ?: return
        if (e.fixed) return goTo(0, (page - 1).coerceIn(0, e.pageCount(0) - 1))
        e.fromGlobal(page)?.let { p -> goTo(p.chapter, e.offsetOf(p)) }
    }

    fun totalPages(): Int? = engine?.let { if (it.fixed) it.pageCount(0) else it.totalPages() }

    fun chapterStep(forward: Boolean) {
        val e = engine ?: return
        if (e is FixedEngine) {
            val outline = e.outline.filter { it.depth == 0 }
            val target = if (forward) outline.firstOrNull { it.page > pos.page }?.page else outline.lastOrNull { it.page < pos.page }?.page
            goTo(0, target ?: (pos.page + if (forward) 10 else -10).coerceIn(0, e.pageCount(0) - 1))
            return
        }
        val t = text ?: return
        val (c, o) = t.chapterStep(pos.chapter, e.offsetOf(pos), e.endOffsetOf(pos), forward)
        goTo(c, o)
    }

    fun toggleMenu(show: Boolean? = null) {
        val v = show ?: !_ui.value.menu
        _ui.update { it.copy(menu = v) }
    }

    fun openPanel(p: Panel) {
        _ui.update { it.copy(panel = if (it.panel == p) Panel.NONE else p) }
    }

    fun closePanel() {
        _ui.update { it.copy(panel = Panel.NONE) }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun toggleBookmark() {
        val e = engine ?: return
        if (!e.isReady(pos.chapter)) return
        val s = e.offsetOf(pos)
        val en = e.endOffsetOf(PagePos(pos.chapter, (pos.page + e.step - 1).coerceAtMost(e.pageCount(pos.chapter) - 1)))
        val existing = _ui.value.bookmarks.filter { it.chapter == pos.chapter && it.offset in s until en.coerceAtLeast(s + 1) }
        scope.launch {
            if (existing.isNotEmpty()) existing.forEach { repo.deleteBookmark(it.id) } else {
                val snippet = text?.text(pos.chapter, s, (s + 160).coerceAtMost(en))?.replace('\n', ' ') ?: I18n.format("page_number", pos.page + 1)
                repo.addBookmark(Bookmark(bookId = bookId, chapter = pos.chapter, offset = s, progress = e.progress(pos.chapter, s), snippet = snippet, chapterTitle = _ui.value.chapterTitle))
            }
        }
    }

    fun deleteBookmark(id: Long) = scope.launch { repo.deleteBookmark(id) }

    // Search

    fun search(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _ui.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        val e = engine ?: return
        searchJob = scope.launch(Dispatchers.Default) {
            delay(250)
            _ui.update { it.copy(searching = true) }
            val hits = when (e) {
                is TextEngine -> e.text.search(query) { !isActive }
                is FixedEngine -> searchPdf(e, query)
                else -> emptyList()
            }
            ensureActive()
            _ui.update { it.copy(searching = false, searchResults = hits) }
        }
    }

    /** Searches PDF text page by page; a hit's offset is its page. */
    private suspend fun searchPdf(e: FixedEngine, query: String): List<SearchHit> {
        val q = query.trim().lowercase()
        val out = ArrayList<SearchHit>()
        for (p in 0 until e.pages) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val t = e.pageText(p)?.replace(Regex("\\s+"), " ") ?: continue
            var k = t.lowercase().indexOf(q)
            var count = 0
            while (k >= 0 && count < 3) {
                val s = (k - 40).coerceAtLeast(0)
                val se = (k + q.length + 60).coerceAtMost(t.length)
                val prefix = (if (s > 0) 1 else 0) + (k - s)
                out.add(SearchHit(0, p, p + 1, (if (s > 0) "…" else "") + t.substring(s, se) + if (se < t.length) "…" else "", prefix, prefix + q.length))
                if (out.size >= 300) return out
                count++
                k = t.lowercase().indexOf(q, k + q.length)
            }
            if (p % 10 == 0) _ui.update { it.copy(searchResults = ArrayList(out)) }
        }
        return out
    }

    fun openSearchHit(hit: SearchHit) {
        if (engine is FixedEngine) return goTo(0, hit.start, rememberJump = true)
        goTo(hit.chapter, hit.start, rememberJump = true, highlight = HighlightRange(hit.chapter, hit.start, hit.end, 0))
    }

    fun clearSearchHighlight() {
        if (deco.search != null) {
            deco.search = null
            host?.refresh()
        }
    }

    fun updateSettings(block: (ReaderSettings) -> ReaderSettings) = app.settings.updateReader(block)

    // Selection and notes

    fun beginSelection(x: Float, y: Float): Boolean {
        val e = engine as? TextEngine ?: return false
        if (scrollMode) return false
        val off = e.offsetAt(pos, x, y) ?: return false
        selChapter = pos.chapter
        selStart = off
        selEnd = off
        return true
    }

    fun extendSelection(x: Float, y: Float) {
        val e = engine as? TextEngine ?: return
        val off = e.offsetAt(pos, x, y) ?: return
        selEnd = off
        showSelection(false)
    }

    fun selectWord(x: Float, y: Float) {
        val e = engine as? TextEngine ?: return
        if (scrollMode) return
        val w = e.wordAt(pos, x, y) ?: return
        selChapter = pos.chapter
        selStart = w.first
        selEnd = w.last + 1
        showSelection(true)
    }

    /** Selects the paragraph around a point, for triple clicks. */
    fun selectParagraph(x: Float, y: Float) {
        val e = engine as? TextEngine ?: return
        val off = e.offsetAt(pos, x, y) ?: return
        val t = e.text.plainText(pos.chapter)
        if (t.isEmpty()) return
        val s = t.lastIndexOf('\n', (off - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val en = t.indexOf('\n', off).let { if (it < 0) t.length else it }
        if (en <= s) return
        selChapter = pos.chapter
        selStart = s
        selEnd = en
        showSelection(true)
    }

    fun endSelection() {
        if (selStart == selEnd) {
            clearSelection()
            return
        }
        showSelection(true)
    }

    private fun showSelection(toolbar: Boolean, highlight: Highlight? = null) {
        val e = engine as? TextEngine ?: return
        val a = minOf(selStart, selEnd)
        val b = maxOf(selStart, selEnd)
        if (b <= a) return
        deco.selection = HighlightRange(selChapter, a, b, 0)
        host?.refresh()
        if (!toolbar) {
            if (_ui.value.selection != null) _ui.update { it.copy(selection = null) }
            return
        }
        val shape: Shape = e.rangeShape(pos, a, b) ?: return
        val txt = e.text.text(selChapter, a, b)
        _ui.update { it.copy(selection = SelectionUi(selChapter, a, b, txt, shape.bounds, highlight?.id, highlight?.color ?: -1, highlight?.note), menu = false) }
    }

    fun clearSelection() {
        deco.selection = null
        selStart = 0
        selEnd = 0
        _ui.update { it.copy(selection = null) }
        host?.refresh()
    }

    fun highlightSelection(color: Int, note: String? = null) {
        val sel = _ui.value.selection ?: return
        val e = engine ?: return
        scope.launch {
            if (sel.highlightId != null) {
                _ui.value.highlights.firstOrNull { it.id == sel.highlightId }?.let { repo.updateHighlight(it.copy(color = color, note = note ?: it.note)) }
            } else {
                repo.addHighlight(Highlight(bookId = bookId, chapter = sel.chapter, start = sel.start, end = sel.end, text = sel.text, color = color, note = note, chapterTitle = _ui.value.chapterTitle, progress = e.progress(sel.chapter, sel.start)))
            }
        }
        clearSelection()
    }

    fun deleteHighlight(id: Long) {
        scope.launch { repo.deleteHighlight(id) }
        clearSelection()
    }

    fun updateHighlightNote(h: Highlight, note: String?) {
        scope.launch { repo.updateHighlight(h.copy(note = note?.trim()?.takeIf { it.isNotBlank() })) }
    }

    private fun highlightAt(x: Float, y: Float): Highlight? {
        val e = engine as? TextEngine ?: return null
        val off = e.offsetAt(pos, x, y) ?: return null
        return _ui.value.highlights.firstOrNull { it.chapter == pos.chapter && off >= it.start && off < it.end }
    }

    /**
     * A click on the page: closes whatever is open, follows a link, opens a highlight, or turns the page when
     * clicking the side of it. Returns whether a page turn was requested.
     */
    fun onClick(x: Float, y: Float, width: Int): Int {
        val u = _ui.value
        if (u.selection != null) {
            clearSelection()
            return 0
        }
        if (u.footnote != null) {
            _ui.update { it.copy(footnote = null) }
            return 0
        }
        clearSearchHighlight()
        val e = engine
        if (e is TextEngine && !scrollMode) {
            e.linkAt(pos, x, y)?.let { href ->
                openLink(href, x, y)
                return 0
            }
            highlightAt(x, y)?.let { h ->
                selChapter = h.chapter
                selStart = h.start
                selEnd = h.end
                showSelection(true, h)
                return 0
            }
        }
        if (scrollMode || !settings.value.clickToTurn) {
            toggleMenu()
            return 0
        }
        val w = width.toFloat()
        val action = when {
            x < w * 0.3f -> -1
            x > w * 0.7f -> 1
            else -> 0
        }
        val directed = if (_ui.value.rtl) -action else action
        if (directed == 0) toggleMenu()
        return directed
    }

    fun linkUnder(x: Float, y: Float): String? = (engine as? TextEngine)?.takeIf { !scrollMode }?.linkAt(pos, x, y)

    private fun openLink(href: String, x: Float, y: Float) {
        val t = text ?: return
        if (BookText.hasScheme(href)) {
            if (SystemIntegration.isOpenableLink(href)) _ui.update { it.copy(message = "link:${href.trim()}") }
            return
        }
        val target = t.resolveLink(href, pos.chapter) ?: return
        val note = t.footnote(target.first, target.second)
        val here = pos.chapter to (engine?.offsetOf(pos) ?: 0)
        if (t.looksLikeNote(href, here, target, note)) {
            _ui.update { it.copy(footnote = FootnoteUi(note, target.first, target.second, java.awt.Rectangle(x.toInt(), y.toInt(), 1, 1))) }
        } else {
            goTo(target.first, target.second, rememberJump = true)
        }
    }

    fun followFootnote() {
        val f = _ui.value.footnote ?: return
        _ui.update { it.copy(footnote = null) }
        goTo(f.chapter, f.offset, rememberJump = true)
    }

    fun dismissFootnote() {
        _ui.update { it.copy(footnote = null) }
    }

    fun titleAt(progress: Float): String {
        val e = engine ?: return ""
        val (c, o) = e.locate(progress)
        if (e is FixedEngine) return e.outline.lastOrNull { it.page <= o }?.title ?: I18n.format("page_number", o + 1)
        return text?.sectionTitleAt(c, o) ?: e.chapterTitle(c)
    }

    fun markFinished() {
        _ui.update { it.copy(endReached = false) }
        scope.launch { repo.markFinished(bookId) }
    }

    fun dismissEnd() {
        _ui.update { it.copy(endReached = false) }
    }

    /** Turns the page (or scrolls a screen) from the keyboard or mouse wheel. */
    fun keyFlip(forward: Boolean) {
        if (engine == null) return
        if (scrollMode) {
            val v = viewport ?: return
            host?.scrollBy(if (forward) v.height * 0.88f else -v.height * 0.88f)
        } else host?.flip(forward)
    }

    // Reading sessions

    fun onSessionStart() {
        if (sessionStart != 0L) return
        sessionStart = System.currentTimeMillis()
        pagesTurned = 0
    }

    fun onSessionEnd() {
        val start = sessionStart
        if (start == 0L) return
        sessionStart = 0L
        val duration = System.currentTimeMillis() - start
        val pages = pagesTurned
        savePosition()
        app.library.launch { recordSession(bookId, start, duration, pages) }
    }

    // Auto page turn

    fun toggleAutoTurn() {
        if (_ui.value.autoTurn) stopAutoTurn() else startAutoTurn()
    }

    private fun startAutoTurn() {
        stopTts()
        _ui.update { it.copy(autoTurn = true, menu = false, panel = Panel.NONE) }
        if (scrollMode) {
            val v = viewport ?: return
            host?.autoScroll(v.height / settings.value.autoTurnSeconds.coerceAtLeast(3).toFloat())
            return
        }
        autoJob?.cancel()
        autoJob = scope.launch {
            while (isActive) {
                delay(settings.value.autoTurnSeconds.coerceAtLeast(3) * 1000L)
                val e = engine ?: break
                if (e.next(pos) == null && e.neighborPending(pos, true) == null) {
                    stopAutoTurn()
                    break
                }
                host?.flip(true)
            }
        }
    }

    fun stopAutoTurn() {
        autoJob?.cancel()
        autoJob = null
        if (_ui.value.autoTurn) host?.autoScroll(0f)
        _ui.update { it.copy(autoTurn = false) }
    }

    fun onReachedEnd() = stopAutoTurn()

    // Read aloud

    fun toggleSpeech() {
        if (_ui.value.speaking) {
            if (_ui.value.ttsPaused) resumeTts() else pauseTts()
        } else startTts()
    }

    fun startTts(fromOffset: Int? = null) {
        val t = text ?: return
        val engineRef = engine ?: return
        if (app.speech == null) {
            _ui.update { it.copy(message = tr("tts_unavailable_desktop")) }
            return
        }
        stopAutoTurn()
        val chapter = pos.chapter
        val offset = fromOffset ?: if (engineRef.isReady(chapter)) engineRef.offsetOf(pos) else 0
        _ui.update { it.copy(speaking = true, ttsPaused = false, menu = false) }
        ttsChapter = chapter
        ttsSentences = t.sentences(chapter, offset)
        ttsIndex = 0
        speakNext()
    }

    private fun speakNext() {
        val t = text ?: return
        val sp = app.speech ?: return
        if (!_ui.value.speaking || _ui.value.ttsPaused) return
        while (ttsIndex >= ttsSentences.size) {
            val next = ttsChapter + 1
            if (next >= t.chapterCount) {
                stopTts()
                return
            }
            ttsChapter = next
            ttsSentences = t.sentences(next, 0)
            ttsIndex = 0
        }
        val r = ttsSentences[ttsIndex]
        val chapter = ttsChapter
        onSentence(chapter, r.first, r.last + 1)
        val s = settings.value
        sp.speak(t.text(chapter, r.first, r.last + 1).ifBlank { " " }, s.ttsRate, s.ttsVoice) {
            scope.launch {
                if (!_ui.value.speaking || _ui.value.ttsPaused || chapter != ttsChapter) return@launch
                ttsIndex++
                speakNext()
            }
        }
    }

    /** Highlights the sentence being read and turns the page when it moves past the visible ones. */
    private fun onSentence(ch: Int, s: Int, en: Int) {
        deco.speaking = HighlightRange(ch, s, en, 0)
        val e = engine ?: return
        if (!e.isReady(pos.chapter)) return
        val lastPage = PagePos(pos.chapter, (pos.page + e.step - 1).coerceAtMost(e.pageCount(pos.chapter) - 1))
        if (ch != pos.chapter || s >= e.endOffsetOf(lastPage) || s < e.offsetOf(pos)) {
            if (ch == pos.chapter && s >= e.endOffsetOf(lastPage) && e.pageOf(ch, s) <= pos.page + e.step * 2 - 1 && !scrollMode) host?.flip(true)
            else goTo(ch, s)
        } else host?.refresh()
    }

    private fun pauseTts() {
        app.speech?.stop()
        _ui.update { it.copy(ttsPaused = true) }
    }

    private fun resumeTts() {
        _ui.update { it.copy(ttsPaused = false) }
        speakNext()
    }

    fun stopTts() {
        app.speech?.stop()
        deco.speaking = null
        if (_ui.value.speaking) host?.refresh()
        _ui.update { it.copy(speaking = false, ttsPaused = false) }
    }

    fun speakFromSelection() {
        val sel = _ui.value.selection ?: return
        clearSelection()
        startTts(sel.start)
    }

    fun close() {
        onSessionEnd()
        stopTts()
        stopAutoTurn()
        savePosition()
        layoutJob?.cancel()
        val e = engine
        engine = null
        scope.cancel()
        e?.close()
        runCatching { closer?.invoke() }
    }
}
