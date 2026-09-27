package com.readarea.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import androidx.core.net.toUri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readarea.app
import com.readarea.core.BookLoader
import com.readarea.core.FixedBook
import com.readarea.core.ReflowableBook
import com.readarea.core.format.BookFormat
import com.readarea.core.format.BookParseException
import com.readarea.core.format.ParseError
import com.readarea.core.format.TextDirection
import com.readarea.core.format.TocItem
import com.readarea.data.ReaderSettings
import com.readarea.data.db.BookEntity
import com.readarea.data.db.BookmarkEntity
import com.readarea.data.db.HighlightEntity
import com.readarea.reader.engine.Decorations
import com.readarea.reader.engine.FixedEngine
import com.readarea.reader.engine.HighlightRange
import com.readarea.reader.engine.PageEngine
import com.readarea.reader.engine.PagePos
import com.readarea.reader.engine.PageSetup
import com.readarea.reader.engine.SearchHit
import com.readarea.reader.engine.TextEngine
import com.readarea.reader.view.PageFlipView
import com.readarea.reader.view.ScrollPageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.readarea.R

data class Viewport(
    val width: Int,
    val height: Int,
    val top: Int,
    val bottom: Int,
    val columns: Int,
    val hinge: Float,
    val density: Float,
    val fontScale: Float,
)

enum class Panel { NONE, CONTENTS, TYPOGRAPHY, THEME, LIGHT, PAGING, SEARCH, SPEECH, MORE }

data class SelectionUi(val chapter: Int, val start: Int, val end: Int, val text: String, val anchor: RectF, val highlightId: Long? = null, val color: Int = -1, val note: String? = null)

data class FootnoteUi(val text: String, val chapter: Int, val offset: Int)

data class ReaderUi(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val book: BookEntity? = null,
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
    val toc: List<TocItem> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val highlights: List<HighlightEntity> = emptyList(),
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchHit> = emptyList(),
    val selection: SelectionUi? = null,
    val footnote: FootnoteUi? = null,
    val speaking: Boolean = false,
    val ttsPaused: Boolean = false,
    val autoTurn: Boolean = false,
    val brightnessPreview: Float? = null,
    val jumpBack: Pair<Int, Int>? = null,
    val message: String? = null,
    val laidOut: Boolean = false,
    val endReached: Boolean = false,
    val ttsAvailable: Boolean = true,
    val rtl: Boolean = false,
    val bookRtl: Boolean = false,
    val vertical: Boolean = false,
    val bookVertical: Boolean = false,
    val bookCjk: Boolean = false,
)

sealed interface ViewCommand {
    data object Refresh : ViewCommand
    data object RefreshCurrent : ViewCommand
    data object RefreshNeighbors : ViewCommand
    data class Flip(val forward: Boolean) : ViewCommand
    data class Selection(val path: Path?, val start: PointF?, val end: PointF?) : ViewCommand
    data class ScrollTo(val pos: PagePos, val fraction: Float) : ViewCommand
    data class AutoScroll(val speed: Float) : ViewCommand
    data class ScrollBy(val dy: Float) : ViewCommand
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx = application
    private val repo = application.app.library
    private val settingsRepo = application.app.settings
    private val db = application.app.database

    val settings: StateFlow<ReaderSettings> = settingsRepo.reader.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    private val _ui = MutableStateFlow(ReaderUi())
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()
    private val _commands = MutableSharedFlow<ViewCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<ViewCommand> = _commands

    var engine: PageEngine? = null
        private set
    val deco = Decorations()
    var pos = PagePos(0, 0)
        private set
    private var bookId = 0L
    private var book: BookEntity? = null
    private var viewport: Viewport? = null
    private var systemDark = (application.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private var laidOutSettings: ReaderSettings? = null
    private val layoutDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var layoutJob: Job? = null
    private var saveJob: Job? = null
    private var autoJob: Job? = null
    private var searchJob: Job? = null
    @Volatile private var layoutBusy = true
    private var pendingAnchor: Pair<Int, Int>? = null
    private var readingAnchor: Pair<Int, Int>? = null
    private var scrollFraction = 0f
    private var sessionStart = 0L
    private var pagesTurned = 0
    private var selStart = 0
    private var selEnd = 0
    private var selChapter = 0
    private var brightnessLevel = -1f

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsSentences: List<IntRange> = emptyList()
    private var ttsChapter = 0
    private var ttsIndex = 0
    private var ttsQueued = 0

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

    private fun errorText(e: Throwable): String = when {
        e is BookParseException && e.reason == ParseError.DRM -> ctx.getString(R.string.error_drm)
        e is BookParseException && e.reason == ParseError.UNSUPPORTED -> ctx.getString(R.string.error_unsupported)
        e is BookParseException && e.reason == ParseError.EMPTY -> ctx.getString(R.string.error_empty)
        e is BookParseException && e.reason == ParseError.TOO_LARGE -> ctx.getString(R.string.error_too_large)
        e is OutOfMemoryError -> ctx.getString(R.string.error_too_large)
        e is BookParseException -> ctx.getString(R.string.error_invalid)
        e is java.io.FileNotFoundException || e is SecurityException -> ctx.getString(R.string.error_missing_file)
        else -> ctx.getString(R.string.error_generic)
    }

    private fun rtlFor(s: ReaderSettings): Boolean = when (s.pageDirection) {
        "rtl" -> true
        "ltr" -> false
        else -> _ui.value.bookRtl || verticalFor(s)
    }

    fun open(id: Long, at: Pair<Int, Int>? = null) {
        if (bookId == id && engine != null) {
            at?.let { goTo(it.first, it.second) }
            return
        }
        bookId = id
        viewModelScope.launch {
            val b = repo.get(id)
            if (b == null) {
                _ui.update { it.copy(loading = false, error = ctx.getString(R.string.error_not_in_library)) }
                return@launch
            }
            book = b
            deco.bookTitle = b.title
            _ui.update { it.copy(title = b.title, author = b.author, format = BookFormat.byName(b.format), book = b) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val format = BookFormat.byName(b.format)
                    when (val opened = BookLoader.open(ctx, b.uri, format, b.fileName, "b${b.id}")) {
                        is ReflowableBook -> {
                            val maxImg = (Runtime.getRuntime().maxMemory() / 10).toInt().coerceIn(8 shl 20, 48 shl 20)
                            TextEngine(opened.book, maxImg)
                        }
                        is FixedBook -> FixedEngine(opened.source, format == BookFormat.PDF, b.title)
                    }
                }
            }
            result.onFailure { e ->
                _ui.update { it.copy(loading = false, error = errorText(e)) }
            }
            result.onSuccess { e ->
                engine = e
                if (e is FixedEngine) e.onPageRendered = { _commands.tryEmit(ViewCommand.RefreshCurrent) }
                pendingAnchor = at?.let { it.first.coerceIn(0, e.chapterCount - 1) to it.second } ?: (b.chapter.coerceIn(0, e.chapterCount - 1) to b.offset)
                val toc = (e as? TextEngine)?.book?.toc.orEmpty()
                val meta = (e as? TextEngine)?.book?.meta
                val bookRtl = meta?.rtl ?: false
                val bookVertical = meta?.vertical ?: false
                val bookCjk = meta != null && (TextDirection.isCjkLanguage(meta.language) || bookVertical)
                _ui.update { it.copy(toc = toc, chapterCount = e.chapterCount, fixed = e.fixed, ttsAvailable = e is TextEngine, bookRtl = bookRtl, bookVertical = bookVertical, bookCjk = bookCjk) }
                _ui.update { it.copy(rtl = rtlFor(settings.value), vertical = verticalFor(settings.value)) }
                launch { db.notes().bookmarks(id).collect { list -> onBookmarks(list) } }
                launch { db.notes().highlights(id).collect { list -> onHighlights(list) } }
                launch { settings.collect { s -> onSettings(s) } }
                if (b.metaLoaded.not()) launch(Dispatchers.IO) { repo.loadMetadata(b) }
                relayout()
            }
        }
    }

    fun setSystemDark(dark: Boolean) {
        if (systemDark == dark) return
        systemDark = dark
        applyTheme()
    }

    private fun currentTheme(): ReadingTheme = ReadingThemes.resolve(settings.value, systemDark)

    private fun applyTheme() {
        val e = engine ?: return
        e.applyTheme(currentTheme())
        _commands.tryEmit(ViewCommand.Refresh)
    }

    private fun onSettings(s: ReaderSettings) {
        val prev = laidOutSettings
        if (prev == null) return
        val setupChanged = listOf(
            prev.fontFamily != s.fontFamily, prev.fontSize != s.fontSize, prev.fontWeight != s.fontWeight,
            prev.lineSpacing != s.lineSpacing, prev.paragraphSpacing != s.paragraphSpacing, prev.indent != s.indent,
            prev.marginH != s.marginH, prev.marginV != s.marginV, prev.justify != s.justify, prev.hyphenation != s.hyphenation,
            prev.letterSpacing != s.letterSpacing, prev.publisherStyles != s.publisherStyles, prev.showHeader != s.showHeader,
            prev.showFooter != s.showFooter, prev.pdfCrop != s.pdfCrop, isScroll(prev) != isScroll(s),
            prev.pageDirection != s.pageDirection, verticalFor(prev) != verticalFor(s),
        ).any { it }
        _ui.update { it.copy(rtl = rtlFor(s), vertical = verticalFor(s)) }
        if (setupChanged) {
            relayout()
        } else {
            engine?.configure(buildSetup(viewport ?: return, s), currentTheme())
            laidOutSettings = s
            _commands.tryEmit(ViewCommand.Refresh)
        }
        tts?.setSpeechRate(s.ttsRate)
        tts?.setPitch(s.ttsPitch)
    }

    fun onViewport(v: Viewport) {
        if (v == viewport) return
        viewport = v
        relayout()
    }

    private fun buildSetup(v: Viewport, s: ReaderSettings): PageSetup {
        val scroll = isScroll(s)
        return PageSetup(v.width, v.height, v.density, v.fontScale, s, if (scroll) 0 else v.top, if (scroll) 0 else v.bottom, if (scroll) 1 else v.columns, v.hinge, scroll, rtlFor(s), verticalFor(s))
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

    private fun relayout() {
        val e = engine ?: return
        val v = viewport ?: return
        val s = settings.value
        val a = anchor()
        pendingAnchor = a
        layoutBusy = true
        layoutJob?.cancel()
        layoutJob = viewModelScope.launch(layoutDispatcher) {
            val setup = buildSetup(v, s)
            e.configure(setup, currentTheme())
            e.ensure(a.first)
            ensureActive()
            val page = e.align(e.pageOf(a.first, a.second))
            val fraction = if (scrollMode && !e.fixed) {
                val st = e.offsetOf(PagePos(a.first, page))
                val en = e.endOffsetOf(PagePos(a.first, page))
                if (en > st) ((a.second - st).toFloat() / (en - st)).coerceIn(0f, 0.95f) else 0f
            } else 0f
            e.ensure(a.first + 1)
            e.ensure(a.first - 1)
            withContext(Dispatchers.Main) {
                laidOutSettings = s
                pendingAnchor = null
                readingAnchor = a
                pos = PagePos(a.first, page)
                scrollFraction = fraction
                layoutBusy = false
                refreshUi()
                _ui.update { it.copy(loading = false, laidOut = true) }
                _commands.tryEmit(ViewCommand.Refresh)
                if (scrollMode) _commands.tryEmit(ViewCommand.ScrollTo(pos, fraction))
                e.prefetch(pos)
            }
            val order = (0 until e.chapterCount).sortedBy { kotlin.math.abs(it - a.first) }
            for (c in order) {
                ensureActive()
                e.ensure(c)
            }
            withContext(Dispatchers.Main) {
                refreshUi()
                _commands.tryEmit(ViewCommand.Refresh)
            }
        }
    }

    private fun ensureAsync(chapter: Int) {
        val e = engine ?: return
        if (chapter !in 0 until e.chapterCount || e.isReady(chapter)) return
        viewModelScope.launch(layoutDispatcher) {
            e.ensure(chapter)
            withContext(Dispatchers.Main) { _commands.tryEmit(ViewCommand.RefreshNeighbors) }
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
            e.fixed -> ctx.getString(R.string.page_of, pos.page + 1, e.pageCount(0))
            gp != null && total != null -> ctx.getString(R.string.page_of, gp, total)
            else -> ctx.getString(R.string.page_of_chapter, pos.page + 1, e.pageCount(pos.chapter))
        }
        val tocTitle = (e as? TextEngine)?.let { te ->
            val items = te.book.toc
            items.lastOrNull { it.chapter < pos.chapter || (it.chapter == pos.chapter && (it.anchor == null || (te.anchors(it.chapter)[it.anchor] ?: 0) <= e.endOffsetOf(pos))) }?.title
        }
        _ui.update {
            it.copy(
                chapter = pos.chapter,
                chapterTitle = tocTitle ?: e.chapterTitle(pos.chapter),
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

    private fun onBookmarks(list: List<BookmarkEntity>) {
        _ui.update { it.copy(bookmarks = list) }
        rebuildBookmarkPages()
        _ui.update { it.copy(bookmarked = isBookmarked()) }
    }

    private fun rebuildBookmarkPages() {
        val e = engine ?: return
        deco.bookmarkedPages = _ui.value.bookmarks.mapNotNull { b ->
            if (e.isReady(b.chapter)) PagePos(b.chapter, e.pageOf(b.chapter, b.offset)) else null
        }.toSet()
        _commands.tryEmit(ViewCommand.Refresh)
    }

    private fun onHighlights(list: List<HighlightEntity>) {
        _ui.update { it.copy(highlights = list) }
        deco.highlights = list.map { HighlightRange(it.chapter, it.start, it.end, ReadingThemes.highlightColors.getOrElse(it.color) { ReadingThemes.highlightColors[0] }) }
        _commands.tryEmit(ViewCommand.Refresh)
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
        saveJob = viewModelScope.launch {
            delay(700)
            savePosition()
        }
    }

    fun savePosition() {
        val e = engine ?: return
        if (!e.isReady(pos.chapter) || layoutBusy) return
        val (c, o) = anchor()
        val progress = if (e.fixed) e.progress(0, pos.page) else e.progress(c, o)
        val id = bookId
        viewModelScope.launch(Dispatchers.IO) { db.books().updatePosition(id, c, o, progress, System.currentTimeMillis()) }
    }

    fun goTo(chapter: Int, offset: Int, rememberJump: Boolean = false, highlight: HighlightRange? = null) {
        val e = engine ?: return
        if (rememberJump && e.isReady(pos.chapter)) _ui.update { it.copy(jumpBack = pos.chapter to e.offsetOf(pos)) }
        deco.search = highlight
        viewModelScope.launch(layoutDispatcher) {
            e.ensure(chapter)
            val page = e.align(e.pageOf(chapter, offset))
            withContext(Dispatchers.Main) {
                pos = PagePos(chapter, page)
                scrollFraction = 0f
                onPageChanged()
                _commands.tryEmit(ViewCommand.Refresh)
                if (scrollMode) _commands.tryEmit(ViewCommand.ScrollTo(pos, 0f))
            }
        }
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

    fun chapterStep(forward: Boolean) {
        val e = engine ?: return
        if (e.fixed) {
            val target = (pos.page + if (forward) 10 else -10).coerceIn(0, e.pageCount(0) - 1)
            goTo(0, target)
            return
        }
        val toc = (e as? TextEngine)?.book?.toc.orEmpty().filter { it.depth == 0 }
        if (toc.size > 1) {
            val te = e as TextEngine
            fun off(t: TocItem) = t.anchor?.let { te.anchors(t.chapter)[it] } ?: 0
            val cur = pos.chapter to e.offsetOf(pos)
            val target = if (forward) toc.firstOrNull { it.chapter > cur.first || (it.chapter == cur.first && off(it) > e.endOffsetOf(pos)) }
            else toc.lastOrNull { it.chapter < cur.first || (it.chapter == cur.first && off(it) < cur.second) }
            if (target != null) {
                goTo(target.chapter, off(target))
                return
            }
        }
        val c = (pos.chapter + if (forward) 1 else -1).coerceIn(0, e.chapterCount - 1)
        goTo(c, 0)
    }

    fun toggleMenu(show: Boolean? = null) {
        val v = show ?: !_ui.value.menu
        _ui.update { it.copy(menu = v, panel = if (v) it.panel else Panel.NONE) }
    }

    fun openPanel(p: Panel) {
        _ui.update { it.copy(panel = p, menu = p == Panel.NONE && it.menu) }
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
        val en = e.endOffsetOf(pos)
        val existing = _ui.value.bookmarks.filter { it.chapter == pos.chapter && it.offset in s until en.coerceAtLeast(s + 1) }
        viewModelScope.launch(Dispatchers.IO) {
            if (existing.isNotEmpty()) existing.forEach { db.notes().deleteBookmark(it.id) } else {
                val snippet = when (e) {
                    is TextEngine -> e.text(pos.chapter, s, (s + 160).coerceAtMost(en)).replace('\n', ' ')
                    else -> ctx.getString(R.string.page_number, pos.page + 1)
                }
                db.notes().insertBookmark(BookmarkEntity(bookId = bookId, chapter = pos.chapter, offset = s, progress = e.progress(pos.chapter, s), snippet = snippet, chapterTitle = _ui.value.chapterTitle))
            }
        }
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch(Dispatchers.IO) { db.notes().deleteBookmark(id) }

    fun search(query: String) {
        val e = engine as? TextEngine ?: return
        _ui.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _ui.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            delay(250)
            _ui.update { it.copy(searching = true) }
            val hits = e.search(query)
            _ui.update { it.copy(searching = false, searchResults = hits) }
        }
    }

    fun openSearchHit(hit: SearchHit) {
        closePanel()
        toggleMenu(false)
        goTo(hit.chapter, hit.start, rememberJump = true, highlight = HighlightRange(hit.chapter, hit.start, hit.end, 0))
    }

    fun clearSearchHighlight() {
        if (deco.search != null) {
            deco.search = null
            _commands.tryEmit(ViewCommand.Refresh)
        }
    }

    fun onBrightnessDrag(delta: Float, done: Boolean) {
        val s = settings.value
        if (brightnessLevel < 0f) brightnessLevel = if (s.brightnessSystem) 0.6f else s.brightness
        brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
        if (done) {
            val level = brightnessLevel
            brightnessLevel = -1f
            _ui.update { it.copy(brightnessPreview = null) }
            viewModelScope.launch { settingsRepo.updateReader { it.copy(brightness = level, brightnessSystem = false) } }
        } else {
            _ui.update { it.copy(brightnessPreview = brightnessLevel) }
        }
    }

    fun updateSettings(block: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsRepo.updateReader(block) }
    }

    fun onLongPress(x: Float, y: Float) {
        val e = engine as? TextEngine ?: return
        if (scrollMode) return
        val w = e.wordAt(pos, x, y) ?: return
        selChapter = pos.chapter
        selStart = w.first
        selEnd = w.last + 1
        updateSelection(showToolbar = false)
    }

    fun onSelectionDrag(handle: Int, x: Float, y: Float) {
        val e = engine as? TextEngine ?: return
        val off = e.offsetAt(pos, x, y) ?: return
        if (handle == 0) {
            selStart = off.coerceAtMost(selEnd - 1)
        } else {
            selEnd = off.coerceAtLeast(selStart + 1)
        }
        updateSelection(showToolbar = false)
    }

    fun onSelectionDragEnd() {
        updateSelection(showToolbar = true)
    }

    private fun updateSelection(showToolbar: Boolean, highlight: HighlightEntity? = null) {
        val e = engine as? TextEngine ?: return
        val path = Path()
        if (!e.selectionPath(pos, selStart, selEnd, path)) return
        val sp = e.handlePoint(pos, selStart, false)
        val ep = e.handlePoint(pos, selEnd, true)
        _commands.tryEmit(ViewCommand.Selection(path, sp, ep))
        if (showToolbar) {
            val b = RectF()
            path.computeBounds(b, true)
            val text = e.text(selChapter, selStart, selEnd)
            _ui.update { it.copy(selection = SelectionUi(selChapter, selStart, selEnd, text, b, highlight?.id, highlight?.color ?: -1, highlight?.note), menu = false) }
        } else {
            _ui.update { it.copy(selection = null) }
        }
    }

    fun clearSelection() {
        _commands.tryEmit(ViewCommand.Selection(null, null, null))
        _ui.update { it.copy(selection = null) }
    }

    fun highlightSelection(color: Int, note: String? = null) {
        val sel = _ui.value.selection ?: return
        val e = engine ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (sel.highlightId != null) {
                _ui.value.highlights.firstOrNull { it.id == sel.highlightId }?.let { db.notes().updateHighlight(it.copy(color = color, note = note ?: it.note)) }
            } else {
                db.notes().insertHighlight(
                    HighlightEntity(
                        bookId = bookId, chapter = sel.chapter, start = sel.start, end = sel.end, text = sel.text, color = color, note = note,
                        chapterTitle = _ui.value.chapterTitle, progress = e.progress(sel.chapter, sel.start),
                    ),
                )
            }
        }
        clearSelection()
    }

    fun deleteHighlight(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.notes().deleteHighlight(id) }
        clearSelection()
    }

    fun updateHighlightNote(h: HighlightEntity, note: String?) {
        viewModelScope.launch(Dispatchers.IO) { db.notes().updateHighlight(h.copy(note = note?.takeIf { it.isNotBlank() })) }
    }

    private fun highlightAt(x: Float, y: Float): HighlightEntity? {
        val e = engine as? TextEngine ?: return null
        val off = e.offsetAt(pos, x, y) ?: return null
        return _ui.value.highlights.firstOrNull { it.chapter == pos.chapter && off >= it.start && off < it.end }
    }

    fun onTap(x: Float, y: Float, width: Int, height: Int) {
        val u = _ui.value
        if (u.selection != null) {
            clearSelection()
            return
        }
        if (u.footnote != null) {
            _ui.update { it.copy(footnote = null) }
            return
        }
        if (u.panel != Panel.NONE) {
            closePanel()
            return
        }
        if (u.menu) {
            toggleMenu(false)
            return
        }
        clearSearchHighlight()
        val e = engine
        if (e is TextEngine && !scrollMode) {
            e.linkAt(pos, x, y)?.let { href ->
                openLink(href)
                return
            }
            highlightAt(x, y)?.let { h ->
                selChapter = h.chapter
                selStart = h.start
                selEnd = h.end
                updateSelection(true, h)
                return
            }
        }
        if (scrollMode) {
            toggleMenu(true)
            return
        }
        val zone = settings.value.tapZones
        val w = width.toFloat()
        val h = height.toFloat()
        val action = when (zone) {
            "off" -> 0
            "forward" -> when {
                x < w * 0.25f -> -1
                x in w * 0.33f..w * 0.67f && y in h * 0.3f..h * 0.7f -> 0
                else -> 1
            }
            "rows" -> when {
                y < h * 0.3f -> -1
                y > h * 0.7f -> 1
                else -> 0
            }
            else -> when {
                x < w * 0.3f -> -1
                x > w * 0.7f -> 1
                else -> 0
            }
        }
        val directed = if (_ui.value.rtl && zone != "rows") -action else action
        when (directed) {
            0 -> toggleMenu(true)
            else -> _commands.tryEmit(ViewCommand.Flip(directed > 0))
        }
    }

    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    private fun openLink(href: String) {
        val e = engine as? TextEngine ?: return
        if (schemePrefix.containsMatchIn(href.trim())) {
            if (com.readarea.data.SafeFiles.isOpenableLink(href.trim().toUri())) _ui.update { it.copy(message = "link:${href.trim()}") }
            return
        }
        val target = e.resolveLink(href, pos.chapter) ?: return
        val text = e.footnote(target.first, target.second)
        val short = text.length in 1..1200 && (target.first != pos.chapter || kotlin.math.abs(target.second - e.offsetOf(pos)) > 0)
        val looksLikeNote = href.contains("note", true) || href.contains("fn", true) || href.contains("#n") || text.length < 600
        if (short && looksLikeNote) {
            _ui.update { it.copy(footnote = FootnoteUi(text, target.first, target.second)) }
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

    fun volumeFlip(forward: Boolean): Boolean {
        val u = _ui.value
        if (!settings.value.volumeKeys || u.menu || u.panel != Panel.NONE || engine == null) return false
        if (scrollMode) {
            val v = viewport ?: return false
            _commands.tryEmit(ViewCommand.ScrollBy(if (forward) v.height * 0.85f else -v.height * 0.85f))
            return true
        }
        _commands.tryEmit(ViewCommand.Flip(forward))
        return true
    }

    val currentScrollFraction: Float get() = scrollFraction

    fun titleAt(progress: Float): String {
        val e = engine ?: return ""
        val (c, o) = e.locate(progress)
        if (e.fixed) return ctx.getString(R.string.page_number, o + 1)
        val te = e as? TextEngine
        val toc = te?.book?.toc.orEmpty()
        return toc.lastOrNull { it.chapter < c || (it.chapter == c && (it.anchor == null || (te?.anchors(c)?.get(it.anchor) ?: 0) <= o)) }?.title ?: e.chapterTitle(c)
    }

    fun markFinished() {
        val id = bookId
        _ui.update { it.copy(endReached = false) }
        viewModelScope.launch(Dispatchers.IO) { repo.setStatus(listOf(id), com.readarea.data.db.BookStatus.FINISHED) }
    }

    fun dismissEnd() {
        _ui.update { it.copy(endReached = false) }
    }

    fun keyFlip(forward: Boolean) {
        if (engine == null) return
        if (scrollMode) {
            val v = viewport ?: return
            _commands.tryEmit(ViewCommand.ScrollBy(if (forward) v.height * 0.85f else -v.height * 0.85f))
        } else _commands.tryEmit(ViewCommand.Flip(forward))
    }

    fun onSessionStart() {
        sessionStart = System.currentTimeMillis()
        pagesTurned = 0
    }

    fun onSessionEnd() {
        val start = sessionStart
        if (start == 0L) return
        sessionStart = 0L
        val duration = System.currentTimeMillis() - start
        val pages = pagesTurned
        val id = bookId
        savePosition()
        viewModelScope.launch(Dispatchers.IO) { repo.recordSession(id, start, duration, pages) }
    }

    fun toggleAutoTurn() {
        if (_ui.value.autoTurn) stopAutoTurn() else startAutoTurn()
    }

    private fun startAutoTurn() {
        stopTts()
        _ui.update { it.copy(autoTurn = true, menu = false, panel = Panel.NONE) }
        if (scrollMode) {
            val v = viewport ?: return
            _commands.tryEmit(ViewCommand.AutoScroll(v.height / settings.value.autoTurnSeconds.coerceAtLeast(3).toFloat()))
            return
        }
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            while (isActive) {
                delay(settings.value.autoTurnSeconds.coerceAtLeast(3) * 1000L)
                val e = engine ?: break
                if (e.next(pos) == null && e.neighborPending(pos, true) == null) {
                    stopAutoTurn()
                    break
                }
                _commands.tryEmit(ViewCommand.Flip(true))
            }
        }
    }

    fun stopAutoTurn() {
        autoJob?.cancel()
        autoJob = null
        if (_ui.value.autoTurn) _commands.tryEmit(ViewCommand.AutoScroll(0f))
        _ui.update { it.copy(autoTurn = false) }
    }

    fun toggleSpeech() {
        if (_ui.value.speaking) {
            if (_ui.value.ttsPaused) resumeTts() else pauseTts()
        } else startTts()
    }

    fun startTts(fromOffset: Int? = null) {
        val e = engine as? TextEngine ?: return
        stopAutoTurn()
        val chapter = pos.chapter
        val offset = fromOffset ?: if (e.isReady(chapter)) e.offsetOf(pos) else 0
        _ui.update { it.copy(speaking = true, ttsPaused = false, menu = false, panel = Panel.NONE) }
        val start = {
            ttsChapter = chapter
            ttsSentences = e.sentences(chapter, offset)
            ttsIndex = 0
            ttsQueued = 0
            speakQueue()
        }
        if (tts != null && ttsReady) {
            start()
            return
        }
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val t = tts ?: return@TextToSpeech
                val loc = e.locale()
                if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc else t.language = Locale.getDefault()
                t.setSpeechRate(settings.value.ttsRate)
                t.setPitch(settings.value.ttsPitch)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        viewModelScope.launch(Dispatchers.Main) { onUtteranceStart(utteranceId) }
                    }

                    override fun onDone(utteranceId: String) {
                        viewModelScope.launch(Dispatchers.Main) { onUtteranceDone(utteranceId) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        viewModelScope.launch(Dispatchers.Main) { utteranceId?.let { onUtteranceDone(it) } }
                    }
                })
                viewModelScope.launch(Dispatchers.Main) { start() }
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    _ui.update { it.copy(speaking = false, message = ctx.getString(R.string.tts_unavailable)) }
                }
            }
        }
    }

    private fun speakQueue() {
        val t = tts ?: return
        val e = engine as? TextEngine ?: return
        while (ttsQueued < 3) {
            val idx = ttsIndex + ttsQueued
            if (idx >= ttsSentences.size) {
                if (ttsQueued == 0) {
                    val next = ttsChapter + 1
                    if (next >= e.chapterCount) {
                        stopTts()
                        return
                    }
                    ttsChapter = next
                    ttsSentences = e.sentences(next, 0)
                    ttsIndex = 0
                    continue
                }
                return
            }
            val r = ttsSentences[idx]
            val text = e.text(ttsChapter, r.first, r.last + 1)
            val params = Bundle()
            t.speak(text.ifBlank { " " }, TextToSpeech.QUEUE_ADD, params, "$ttsChapter:$idx:${r.first}:${r.last + 1}")
            ttsQueued++
        }
    }

    private fun onUtteranceStart(id: String) {
        val parts = id.split(':')
        if (parts.size < 4) return
        val ch = parts[0].toIntOrNull() ?: return
        val s = parts[2].toIntOrNull() ?: return
        val en = parts[3].toIntOrNull() ?: return
        deco.speaking = HighlightRange(ch, s, en, 0)
        val e = engine ?: return
        if (!_ui.value.speaking) return
        val lastPage = PagePos(pos.chapter, (pos.page + e.step - 1).coerceAtMost(e.pageCount(pos.chapter) - 1))
        if (ch != pos.chapter || s >= e.endOffsetOf(lastPage) || s < e.offsetOf(pos)) {
            if (ch == pos.chapter && s >= e.endOffsetOf(lastPage) && e.pageOf(ch, s) <= pos.page + e.step * 2 - 1 && !scrollMode) {
                _commands.tryEmit(ViewCommand.Flip(true))
            } else {
                goTo(ch, s)
            }
        } else {
            _commands.tryEmit(ViewCommand.RefreshCurrent)
        }
    }

    private fun onUtteranceDone(id: String) {
        if (!_ui.value.speaking || _ui.value.ttsPaused) return
        val parts = id.split(':')
        val ch = parts.getOrNull(0)?.toIntOrNull() ?: return
        val idx = parts.getOrNull(1)?.toIntOrNull() ?: return
        if (ch != ttsChapter && ttsQueued > 0) {
            ttsQueued--
            speakQueue()
            return
        }
        if (idx >= ttsIndex) {
            ttsQueued = (ttsQueued - (idx - ttsIndex + 1)).coerceAtLeast(0)
            ttsIndex = idx + 1
        }
        speakQueue()
    }

    private fun pauseTts() {
        tts?.stop()
        ttsQueued = 0
        _ui.update { it.copy(ttsPaused = true) }
    }

    private fun resumeTts() {
        _ui.update { it.copy(ttsPaused = false) }
        speakQueue()
    }

    fun stopTts() {
        tts?.stop()
        ttsQueued = 0
        deco.speaking = null
        if (_ui.value.speaking) _commands.tryEmit(ViewCommand.RefreshCurrent)
        _ui.update { it.copy(speaking = false, ttsPaused = false) }
    }

    fun speakFromSelection() {
        val sel = _ui.value.selection ?: return
        clearSelection()
        startTts(sel.start)
    }

    val flipCallback = object : PageFlipView.Callback {
        override fun canFlip(forward: Boolean): Boolean {
            val e = engine ?: return false
            if (layoutBusy) return false
            val target = if (forward) e.next(pos) else e.prev(pos)
            if (target == null) {
                e.neighborPending(pos, forward)?.let { ensureAsync(it) }
            }
            return target != null
        }

        override fun drawPage(offset: Int, canvas: Canvas): Boolean {
            val e = engine ?: return false
            if (layoutBusy || e.theme == null) return false
            val p = when (offset) {
                0 -> pos
                1 -> e.next(pos)
                else -> e.prev(pos)
            }
            if (p == null || !e.isReady(p.chapter)) {
                e.theme?.let { canvas.drawColor(it.background) }
                return false
            }
            e.drawPage(canvas, p, deco)
            return true
        }

        override fun onFlipped(forward: Boolean) {
            val e = engine ?: return
            val np = (if (forward) e.next(pos) else e.prev(pos)) ?: return
            pos = np
            clearSearchHighlight()
            onPageChanged()
            if (_ui.value.endReached) _ui.update { it.copy(endReached = false) }
        }

        override fun onTap(x: Float, y: Float) {
            val v = viewport ?: return
            this@ReaderViewModel.onTap(x, y, v.width, v.height)
        }

        override fun onLongPress(x: Float, y: Float) = this@ReaderViewModel.onLongPress(x, y)
        override fun onSelectionDrag(handle: Int, x: Float, y: Float) = this@ReaderViewModel.onSelectionDrag(handle, x, y)
        override fun onSelectionDragEnd() = this@ReaderViewModel.onSelectionDragEnd()
        override fun onBrightnessDrag(delta: Float, done: Boolean) = this@ReaderViewModel.onBrightnessDrag(delta, done)
        override fun onUserActivity() {
            activityListener?.invoke()
        }

        override fun onFlipBlocked(forward: Boolean) {
            val e = engine ?: return
            if (forward && e.next(pos) == null && e.neighborPending(pos, true) == null) {
                _ui.update { it.copy(endReached = true) }
                savePosition()
            }
        }
    }

    var activityListener: (() -> Unit)? = null

    val scrollCallback = object : ScrollPageView.Callback {
        override fun next(pos: PagePos): PagePos? = engine?.next(pos).also { if (it == null) engine?.neighborPending(pos, true)?.let { c -> ensureAsync(c) } }
        override fun prev(pos: PagePos): PagePos? = engine?.prev(pos).also { if (it == null) engine?.neighborPending(pos, false)?.let { c -> ensureAsync(c) } }
        override fun pageHeight(pos: PagePos): Float = engine?.scrollHeight(pos) ?: 0f
        override fun drawBackground(canvas: Canvas) {
            val e = engine ?: return
            val th = e.theme ?: return
            val s = e.setup ?: return
            if (e.fixed) canvas.drawColor(if (th.dark) 0xFF101010.toInt() else 0xFFE9E9E9.toInt())
            else com.readarea.reader.engine.PageChrome.drawBackground(canvas, th, s.width, s.height)
        }

        override fun drawSlice(canvas: Canvas, pos: PagePos) {
            engine?.drawScrollSlice(canvas, pos, deco)
        }

        override fun drawOverlay(canvas: Canvas) {}

        override fun onPosition(pos: PagePos, fraction: Float) {
            val changed = pos != this@ReaderViewModel.pos
            if (changed || kotlin.math.abs(fraction - scrollFraction) > 0.001f) readingAnchor = null
            this@ReaderViewModel.pos = pos
            scrollFraction = fraction
            if (changed) onPageChanged() else scheduleSave()
        }

        override fun onTap(x: Float, y: Float) {
            val v = viewport ?: return
            this@ReaderViewModel.onTap(x, y, v.width, v.height)
        }

        override fun onUserActivity() {
            activityListener?.invoke()
        }

        override fun onBrightnessDrag(delta: Float, done: Boolean) = this@ReaderViewModel.onBrightnessDrag(delta, done)

        override fun renderDetail(pos: PagePos, region: RectF, w: Int, h: Int, done: (Bitmap?) -> Unit) {
            val e = engine as? FixedEngine ?: return done(null)
            e.submit { done(e.renderRegion(pos.page, region, w, h)) }
        }

        override fun onReachedEnd() {
            stopAutoTurn()
        }
    }

    override fun onCleared() {
        tts?.shutdown()
        tts = null
        engine?.close()
    }
}
