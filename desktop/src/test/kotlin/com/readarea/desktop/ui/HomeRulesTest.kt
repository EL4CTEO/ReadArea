package com.readarea.desktop.ui

import com.readarea.core.library.BookStatus
import com.readarea.desktop.data.Book
import com.readarea.desktop.ui.home.alsoReading
import com.readarea.desktop.ui.home.continueReadingBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeRulesTest {
    private fun book(id: Long, opened: Long = 0, status: Int = BookStatus.NEW, progress: Float = 0f) =
        Book(id = id, path = "/books/$id.epub", fileName = "$id.epub", format = "EPUB", title = "Book $id", lastOpenedAt = opened, status = status, progress = progress)

    @Test
    fun theCardFollowsTheBookOpenedLast() {
        val reading = book(1, opened = 1_000, status = BookStatus.READING, progress = 0.4f)
        val next = book(2, opened = 2_000) // opened just now, no page turned yet: still new, at 0%
        assertEquals(1L, continueReadingBook(listOf(reading))?.id)
        assertEquals("opening another book moves the card to it", 2L, continueReadingBook(listOf(reading, next))?.id)
        assertEquals(2L, continueReadingBook(listOf(next, reading))?.id)
    }

    @Test
    fun finishedAndNeverOpenedBooksAreNotOffered() {
        val finished = book(1, opened = 5_000, status = BookStatus.FINISHED, progress = 1f)
        val unopened = book(2)
        val reading = book(3, opened = 1_000, status = BookStatus.READING, progress = 0.2f)
        assertNull(continueReadingBook(emptyList()))
        assertNull(continueReadingBook(listOf(finished, unopened)))
        assertEquals(3L, continueReadingBook(listOf(finished, unopened, reading))?.id)
    }

    @Test
    fun theOtherBooksUnderWayFollowByRecency() {
        val a = book(1, opened = 1_000, status = BookStatus.READING, progress = 0.4f)
        val b = book(2, opened = 3_000, status = BookStatus.READING, progress = 0.1f)
        val c = book(3, opened = 2_000, status = BookStatus.READING, progress = 0.7f)
        val opened = book(4, opened = 9_000) // the card's own book, not repeated in the row
        val started = book(5, opened = 500, status = BookStatus.WANT, progress = 0.3f) // set aside part way through
        val done = book(6, opened = 8_000, status = BookStatus.FINISHED, progress = 1f)
        val all = listOf(a, b, c, opened, started, done)
        val current = continueReadingBook(all)
        assertEquals(4L, current?.id)
        assertEquals(listOf(2L, 3L, 1L, 5L), alsoReading(all, current).map { it.id })
    }
}
