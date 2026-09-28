package com.readarea.desktop.data

import com.readarea.desktop.platform.AppDirs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryFoldersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun removingAFolderWithItsBooksRemovesTheirCoversToo() {
        val dirs = AppDirs(tmp.newFolder("data"), tmp.newFolder("cache")).init()
        val db = Database(dirs.database)
        val settings = SettingsStore(dirs.settings)
        Library(db, settings, dirs).use { library ->
            val folder = tmp.newFolder("Books").path
            val ownCover = File(dirs.covers, "cover_1_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val otherCover = File(dirs.covers, "cover_2_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val inFolder = db.insertBook(Book(path = "$folder/a.epub", fileName = "a.epub", folder = folder, format = "EPUB", title = "A", coverPath = ownCover.path))
            val elsewhere = db.insertBook(Book(path = "/elsewhere/b.epub", fileName = "b.epub", folder = "/elsewhere", format = "EPUB", title = "B", coverPath = otherCover.path))

            library.removeFolder(folder, removeBooks = false)
            Thread.sleep(200)
            assertNotNull("keeping the books keeps their covers", db.book(inFolder))
            assertTrue(ownCover.exists())

            library.removeFolder(folder, removeBooks = true)
            waitFor("the cover to go") { !ownCover.exists() }
            assertNull("the folder's books are gone", db.book(inFolder))
            assertNotNull("other folders are untouched", db.book(elsewhere))
            assertTrue("and so are their covers", otherCover.exists())
            assertFalse(ownCover.exists())
        }
        settings.close()
        db.close()
    }
}
