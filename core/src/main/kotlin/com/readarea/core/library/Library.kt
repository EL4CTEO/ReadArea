package com.readarea.core.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object BookStatus {
    const val NEW = 0
    const val READING = 1
    const val FINISHED = 2
    const val WANT = 3
}

/** Reading time on one day, as days since the epoch in the reader's time zone. */
data class DayTotal(val day: Long, val ms: Long, val pages: Int)

data class Streaks(val current: Int, val best: Int)

object ReadingStats {
    /** A day counts towards a streak once there was a minute of reading. */
    const val ACTIVE_DAY_MS = 60_000L

    /** Sessions shorter than this (a book opened by mistake) aren't recorded. */
    const val MIN_SESSION_MS = 5_000L

    fun dayOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay()

    fun today(zone: ZoneId = ZoneId.systemDefault()): Long = LocalDate.now(zone).toEpochDay()

    /** The current streak still counts when today has no reading yet but yesterday did. */
    fun streaks(days: List<DayTotal>, today: Long): Streaks {
        val active = days.filter { it.ms >= ACTIVE_DAY_MS }.map { it.day }.toSortedSet()
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
            run = if (prev != Long.MIN_VALUE && day == prev + 1) run + 1 else 1
            best = maxOf(best, run)
            prev = day
        }
        return Streaks(streak, maxOf(best, streak))
    }
}

/** A highlight as exported: the text, the reader's note and where it is. */
data class ExportedHighlight(val text: String, val note: String?, val chapterTitle: String, val progress: Float)

data class ExportedBook(val title: String, val author: String, val highlights: List<ExportedHighlight>)

object NotesExport {
    /** Markdown with one section per book and each highlight as a quote followed by its note. */
    fun markdown(heading: String, books: List<ExportedBook>, withLocation: Boolean = true): String = buildString {
        append("# ").append(oneLine(heading)).append("\n\n")
        for (b in books) {
            if (b.highlights.isEmpty()) continue
            append("## ").append(oneLine(b.title)).append('\n')
            if (b.author.isNotBlank()) append('*').append(oneLine(b.author)).append("*\n")
            append('\n')
            for (h in b.highlights.sortedBy { it.progress }) {
                append("> ").append(h.text.trim().replace("\r", "").replace("\n", "\n> ")).append("\n\n")
                h.note?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
                if (withLocation) {
                    append("— ")
                    if (h.chapterTitle.isNotBlank()) append(oneLine(h.chapterTitle)).append(", ")
                    append((h.progress * 100).toInt().coerceIn(0, 100)).append("%\n\n")
                }
            }
        }
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
