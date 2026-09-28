package com.readarea.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun library(n: Int): Pair<Database, List<Long>> {
        val db = Database(tmp.newFile("library.db"))
        val ids = db.transaction { (0 until n).map { db.insertBook(Book(path = "/books/$it.epub", fileName = "$it.epub", folder = "/books", format = "EPUB", title = "Book $it")) } }
        return db to ids
    }

    @Test
    fun actionsOnAWholeLibraryWork() {
        // What a select-all in a large library sends: every bulk action takes thousands of books at once.
        val (db, ids) = library(5_000)
        db.use {
            db.setFavorite(ids, true)
            assertTrue(db.books().all { it.favorite })
            db.setStatus(ids, 2, 1L)
            assertTrue(db.books().all { it.status == 2 && it.finishedAt == 1L })
            db.resetProgress(ids)
            assertTrue(db.books().all { it.status == 0 && it.finishedAt == 0L })
            val shelf = db.createShelf("All", 0)
            db.addToShelf(shelf, ids)
            assertEquals(ids.size, db.shelves().first().count)
            db.removeFromShelf(shelf, ids)
            assertEquals(0, db.shelves().first().count)
            db.setMissing(ids, true)
            assertEquals(0, db.books().size)
            db.deleteBooks(ids)
            assertEquals(0, db.books(onlyPresent = false).size)
        }
    }
}
