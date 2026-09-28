package com.readarea.core.library

import com.readarea.core.theme.ReadingThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun day(d: Long, minutes: Int) = DayTotal(d, minutes * 60_000L, 1)

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        val days = listOf(day(100, 5), day(99, 10), day(98, 2), day(96, 30), day(95, 30), day(94, 30), day(93, 30))
        assertEquals(Streaks(3, 4), ReadingStats.streaks(days, 100))
        assertEquals(Streaks(3, 4), ReadingStats.streaks(days, 101))
        assertEquals(Streaks(0, 4), ReadingStats.streaks(days, 102))
    }

    @Test
    fun shortDaysDontCount() {
        val days = listOf(DayTotal(10, 59_000, 1), day(9, 3))
        assertEquals(Streaks(1, 1), ReadingStats.streaks(days, 10))
        assertEquals(Streaks(0, 1), ReadingStats.streaks(days, 11))
        assertEquals(Streaks(0, 0), ReadingStats.streaks(emptyList(), 10))
    }

    @Test
    fun markdownExportQuotesEveryLineAndSortsByPosition() {
        val md = NotesExport.markdown(
            "My notes",
            listOf(
                ExportedBook("Book\nTitle", "Ann", listOf(ExportedHighlight("second", null, "Ch 2", 0.6f), ExportedHighlight("first\nline two", "why", "Ch 1", 0.1f))),
                ExportedBook("Empty", "", emptyList()),
            ),
        )
        assertTrue(md.startsWith("# My notes\n\n## Book Title\n*Ann*\n\n> first\n> line two\n\nwhy\n\n— Ch 1, 10%"))
        assertTrue(md.indexOf("first") < md.indexOf("second"))
        assertFalse(md.contains("Empty"))
    }

    @Test
    fun builtInThemesAreReadable() {
        for (t in ReadingThemes.all) {
            assertTrue("${t.id} text contrast", ReadingThemes.contrast(t.text, t.background) >= 7.0)
            assertTrue("${t.id} secondary contrast", ReadingThemes.contrast(t.secondary, t.background) >= 2.2)
            assertEquals(t.dark, ReadingThemes.luminance(t.background) < 0.4)
        }
    }

    @Test
    fun themeResolutionFollowsNightMode() {
        assertEquals("night", ReadingThemes.resolve("paper", "night", autoNight = true, systemDark = true, customBg = 0, customFg = 0, texture = true).id)
        assertEquals("paper", ReadingThemes.resolve("paper", "night", autoNight = false, systemDark = true, customBg = 0, customFg = 0, texture = true).id)
        assertFalse(ReadingThemes.resolve("paper", "night", autoNight = false, systemDark = false, customBg = 0, customFg = 0, texture = false).texture)
        assertEquals("paper", ReadingThemes.resolve("nonsense", "night", false, false, 0, 0, true).id)
        val custom = ReadingThemes.resolve("custom", "night", false, false, 0xFF101010.toInt(), 0xFFEEEEEE.toInt(), false)
        assertTrue(custom.dark)
    }
}
