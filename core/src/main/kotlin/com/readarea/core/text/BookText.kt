package com.readarea.core.text

import com.readarea.core.format.BlockKind
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.TocItem
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

data class SearchHit(val chapter: Int, val start: Int, val end: Int, val snippet: String, val matchStart: Int, val matchEnd: Int)

/**
 * The plain-text view of a book that every platform agrees on.
 *
 * Each chapter is its blocks joined by `\n`, with images and rules standing in as a single [OBJ]
 * character. Offsets into this text are what reading positions, bookmarks, highlights, search hits and
 * read-aloud sentences are stored as, so a position saved by one app means the same place in another.
 */
class BookText(val book: ParsedBook) {
    val chapterCount: Int = book.chapters.size
    private val plain = arrayOfNulls<String>(chapterCount)
    private val anchorMaps = arrayOfNulls<Map<String, Int>>(chapterCount)
    private val folded = arrayOfNulls<Folded>(chapterCount)
    private val lengths = IntArray(chapterCount) { book.chapters[it].textLength }
    private val prefix = LongArray(chapterCount + 1).also { p -> for (i in 0 until chapterCount) p[i + 1] = p[i] + lengths[i] }
    private val total = prefix[chapterCount].coerceAtLeast(1)

    val locale: Locale = book.meta.language?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }?.takeIf { it.language.isNotEmpty() } ?: Locale.getDefault()

    fun chapterLength(chapter: Int): Int = lengths.getOrElse(chapter) { 0 }

    fun plainText(chapter: Int): String {
        plain.getOrNull(chapter)?.let { return it }
        if (chapter !in 0 until chapterCount) return ""
        val sb = StringBuilder()
        val anchors = HashMap<String, Int>()
        for (b in book.chapters[chapter].blocks) {
            val start = sb.length
            for (a in b.anchors) anchors.putIfAbsent(a.id, start + a.offset)
            if (b.kind == BlockKind.IMAGE || b.kind == BlockKind.RULE) sb.append(OBJ) else b.runs.forEach { sb.append(it.text) }
            sb.append('\n')
        }
        val s = sb.toString()
        anchorMaps[chapter] = anchors
        plain[chapter] = s
        return s
    }

    fun anchors(chapter: Int): Map<String, Int> {
        anchorMaps.getOrNull(chapter)?.let { return it }
        plainText(chapter)
        return anchorMaps.getOrNull(chapter) ?: emptyMap()
    }

    /** Fraction of the whole book read at [offset] in [chapter], measured in characters. */
    fun progress(chapter: Int, offset: Int): Float {
        if (chapter !in 0 until chapterCount) return 0f
        return ((prefix[chapter] + offset.coerceIn(0, lengths[chapter])).toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    fun locate(progress: Float): Pair<Int, Int> {
        if (chapterCount == 0) return 0 to 0
        val target = (progress.coerceIn(0f, 1f) * total).toLong()
        for (c in 0 until chapterCount) {
            if (target < prefix[c + 1] || c == chapterCount - 1) return c to (target - prefix[c]).toInt().coerceIn(0, lengths[c])
        }
        return 0 to 0
    }

    fun text(chapter: Int, start: Int, end: Int): String {
        val t = plainText(chapter)
        val s = start.coerceIn(0, t.length)
        return t.substring(s, end.coerceIn(s, t.length)).replace(OBJ.toString(), "").trim()
    }

    fun chapterTitle(chapter: Int): String {
        val t = book.chapters.getOrNull(chapter)?.title.orEmpty()
        if (t.isNotBlank()) return t
        return book.toc.lastOrNull { it.chapter <= chapter }?.title ?: book.meta.title
    }

    fun tocOffset(item: TocItem): Int = item.anchor?.let { anchors(item.chapter)[it] } ?: 0

    /** The table-of-contents entry a reader at [offset] in [chapter] is in, if the book has one. */
    fun tocEntryAt(chapter: Int, offset: Int): TocItem? =
        book.toc.lastOrNull { it.chapter < chapter || (it.chapter == chapter && tocOffset(it) <= offset) }

    fun sectionTitleAt(chapter: Int, offset: Int): String = tocEntryAt(chapter, offset)?.title ?: chapterTitle(chapter)

    /** Where "next chapter" or "previous chapter" goes from a page spanning [start]..[end] of [chapter]. */
    fun chapterStep(chapter: Int, start: Int, end: Int, forward: Boolean): Pair<Int, Int> {
        val toc = book.toc.filter { it.depth == 0 }
        if (toc.size > 1) {
            val target = if (forward) toc.firstOrNull { it.chapter > chapter || (it.chapter == chapter && tocOffset(it) > end) }
            else toc.lastOrNull { it.chapter < chapter || (it.chapter == chapter && tocOffset(it) < start) }
            if (target != null) return target.chapter to tocOffset(target)
        }
        return (chapter + if (forward) 1 else -1).coerceIn(0, (chapterCount - 1).coerceAtLeast(0)) to 0
    }

    fun wordAt(chapter: Int, offset: Int): IntRange? {
        val text = plainText(chapter)
        if (text.isEmpty()) return null
        val o = offset.coerceIn(0, text.length - 1)
        val bi = BreakIterator.getWordInstance(locale)
        bi.setText(text)
        var start = bi.preceding(o + 1).let { if (it == BreakIterator.DONE) 0 else it }
        var end = bi.following(o).let { if (it == BreakIterator.DONE) text.length else it }
        if (end > start && text.substring(start, end).isBlank()) {
            start = o
            end = (o + 1).coerceAtMost(text.length)
        }
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end <= start || text[start] == OBJ) return null
        return start until end
    }

    /**
     * Resolves an internal link to a chapter and offset. External links (with a scheme) return null, so
     * callers decide separately whether those may be opened at all.
     */
    fun resolveLink(href: String, fromChapter: Int): Pair<Int, Int>? {
        if (SCHEME.containsMatchIn(href.trim())) return null
        val file = href.substringBefore('#')
        val frag = href.substringAfter('#', "")
        val chapter = if (file.isEmpty()) null else book.chapters.indexOfFirst { it.href == file }.takeIf { it >= 0 }
        if (frag.isEmpty()) return chapter?.let { it to 0 }
        if (chapter != null) {
            var c = chapter
            while (c < chapterCount && (c == chapter || book.chapters[c].href.startsWith("$file~") || book.chapters[c].href.startsWith("$file#"))) {
                anchors(c)[frag]?.let { return c to it }
                c++
            }
        }
        if (fromChapter in 0 until chapterCount) anchors(fromChapter)[frag]?.let { return fromChapter to it }
        for (c in 0 until chapterCount) anchors(c)[frag]?.let { return c to it }
        return chapter?.let { it to 0 }
    }

    /** The note text starting at a link target: its paragraph and those after it, up to the next anchor. */
    fun footnote(chapter: Int, offset: Int): String {
        val t = plainText(chapter)
        if (t.isEmpty()) return ""
        var start = t.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        if (offset < t.length && t[offset] == '\n') start = offset + 1
        val sb = StringBuilder()
        var p = start
        var paragraphs = 0
        val nextAnchor = anchors(chapter).values.filter { it > offset + 1 }.minOrNull() ?: Int.MAX_VALUE
        while (p < t.length && paragraphs < 6 && sb.length < 1500) {
            val e = t.indexOf('\n', p).let { if (it < 0) t.length else it }
            if (paragraphs > 0 && p >= nextAnchor) break
            val line = t.substring(p, e).replace(OBJ.toString(), "").trim()
            if (line.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
            paragraphs++
            p = e + 1
        }
        return sb.toString()
    }

    /** Whether a link to [target] should show as a pop-up note rather than jump there. */
    fun looksLikeNote(href: String, from: Pair<Int, Int>, target: Pair<Int, Int>, note: String): Boolean {
        val short = note.length in 1..1200 && (target.first != from.first || kotlin.math.abs(target.second - from.second) > 0)
        return short && (href.contains("note", true) || href.contains("fn", true) || href.contains("#n") || note.length < 600)
    }

    private class Folded(val text: String, val map: IntArray)

    private fun folded(chapter: Int): Folded = folded[chapter] ?: fold(plainText(chapter)).also { folded[chapter] = it }

    /**
     * Case-, accent- and punctuation-insensitive search: "cafe" finds "Café", straight quotes find curly
     * ones, and soft hyphens inside words don't hide matches.
     */
    fun search(query: String, limit: Int = 500, shouldStop: () -> Boolean = { false }): List<SearchHit> {
        val q = fold(query.trim()).text
        if (q.length < 2) return emptyList()
        val out = ArrayList<SearchHit>()
        for (c in 0 until chapterCount) {
            if (shouldStop()) return out
            val t = plainText(c)
            val f = folded(c)
            var k = f.text.indexOf(q)
            while (k >= 0) {
                val i = f.map[k]
                val e = f.map[k + q.length - 1] + 1
                val s = (i - 40).coerceAtLeast(0)
                val se = (e + 60).coerceAtMost(t.length)
                val prefix = clean(t.substring(s, i)).length + if (s > 0) 1 else 0
                val match = clean(t.substring(i, e)).length
                out.add(SearchHit(c, i, e, (if (s > 0) "…" else "") + clean(t.substring(s, se)) + (if (se < t.length) "…" else ""), prefix, prefix + match))
                if (out.size >= limit) return out
                k = f.text.indexOf(q, k + q.length)
            }
        }
        return out
    }

    private fun clean(s: String) = s.replace('\n', ' ').replace(OBJ.toString(), "").replace("­", "")

    /** Sentences to read aloud from [from] to the end of the chapter, long ones split near 400 characters. */
    fun sentences(chapter: Int, from: Int, max: Int = 400): List<IntRange> {
        val t = plainText(chapter)
        if (t.isEmpty()) return emptyList()
        val bi = BreakIterator.getSentenceInstance(locale)
        bi.setText(t)
        val out = ArrayList<IntRange>()
        var start = bi.preceding((from + 1).coerceIn(1, t.length)).let { if (it == BreakIterator.DONE) 0 else it }
        if (start < from && t.substring(start, from.coerceAtMost(t.length)).isBlank()) start = from
        var end = bi.following(start)
        while (end != BreakIterator.DONE && out.size < max) {
            var s = start
            var e = end
            while (s < e && (t[s].isWhitespace() || t[s] == OBJ)) s++
            while (e > s && (t[e - 1].isWhitespace() || t[e - 1] == OBJ)) e--
            if (e > s) {
                if (e - s > 600) {
                    var p = s
                    while (p < e) {
                        var q = (p + 400).coerceAtMost(e)
                        if (q < e) {
                            val sp = t.lastIndexOf(' ', q)
                            if (sp > p) q = sp
                        }
                        out.add(p until q)
                        p = q
                    }
                } else out.add(s until e)
            }
            start = end
            end = bi.next()
        }
        return out
    }

    companion object {
        const val OBJ = '￼'
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun hasScheme(href: String): Boolean = SCHEME.containsMatchIn(href.trim())

        private fun fold(src: String): Folded {
            val sb = StringBuilder(src.length)
            val map = IntArray(src.length * 2 + 8)
            var n = 0
            fun put(c: Char, from: Int) {
                if (n == map.size) return
                sb.append(c)
                map[n++] = from
            }
            for (i in src.indices) {
                val c = src[i]
                when (c) {
                    '­', '​', '⁠', '﻿' -> continue
                    '‘', '’', 'ʼ', '′' -> put('\'', i)
                    '“', '”', '„', '«', '»' -> put('"', i)
                    ' ', ' ', ' ' -> put(' ', i)
                    '‐', '‑' -> put('-', i)
                    else -> {
                        if (c.code < 0x80) {
                            put(c.lowercaseChar(), i)
                        } else {
                            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                            for (k in d) if (Character.getType(k) != Character.NON_SPACING_MARK.toInt()) put(k.lowercaseChar(), i)
                        }
                    }
                }
            }
            return Folded(sb.toString(), map.copyOf(n))
        }
    }
}
