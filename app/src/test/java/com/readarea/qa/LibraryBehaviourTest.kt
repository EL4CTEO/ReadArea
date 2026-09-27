package com.readarea.qa

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.readarea.MainActivity
import com.readarea.ReadAreaApp
import com.readarea.TestIsolation
import com.readarea.data.AppSettings
import com.readarea.data.ReaderSettings
import com.readarea.data.db.BookEntity
import com.readarea.data.db.BookStatus
import com.readarea.data.db.HighlightEntity
import com.readarea.reader.ReaderActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class LibraryBehaviourTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val app: ReadAreaApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var harbour: File
    private val ids = mutableMapOf<String, Long>()

    @Before
    fun setUp() {
        TestIsolation.reset()
        runBlocking {
            app.settings.updateApp { AppSettings(askedDeviceScan = true, reopenLastBook = false) }
            app.settings.updateReader { ReaderSettings() }
        }
        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }
        harbour = File(root, "Books/harbour.epub").apply { parentFile!!.mkdirs(); writeBytes(QaBooks.realisticEpub()) }
        val other = File(root, "Books/storm.epub").apply { writeBytes(QaBooks.realisticEpub("Storm Season", 2)) }
        val third = File(root, "Books/gulls.epub").apply { writeBytes(QaBooks.realisticEpub("Gulls at Noon", 2)) }
        runBlocking {
            ids["harbour"] = app.database.books().insert(BookEntity(uri = Uri.fromFile(harbour).toString(), fileName = harbour.name, folderUri = "device", format = "EPUB", size = harbour.length(), title = "The Harbour Light", author = "Mara Quill", metaLoaded = true))
            ids["storm"] = app.database.books().insert(BookEntity(uri = Uri.fromFile(other).toString(), fileName = other.name, folderUri = "device", format = "EPUB", size = other.length(), title = "Storm Season", author = "Ivo Brand", metaLoaded = true))
            ids["gulls"] = app.database.books().insert(BookEntity(uri = Uri.fromFile(third).toString(), fileName = third.name, folderUri = "device", format = "EPUB", size = third.length(), title = "Gulls at Noon", author = "Ana Reyes", metaLoaded = true))
        }
    }

    private fun idle(ms: Long = 500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        idle(400)
    }

    private fun waitUntil(what: String, timeout: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("$what: not met in time")
    }

    private fun has(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(t: String, substring: Boolean = false, index: Int = 0) {
        compose.onAllNodesWithText(t, substring = substring, useUnmergedTree = true)[index].performClick()
        settle()
    }

    private fun clickIcon(d: String) {
        compose.onAllNodesWithContentDescription(d)[0].performClick()
        settle()
    }

    private fun longPress(t: String) {
        compose.onAllNodesWithText(t, useUnmergedTree = true)[0].performTouchInput { longClick() }
        settle()
    }

    private fun type(text: String, index: Int = 0) {
        compose.onAllNodes(hasSetTextAction())[index].performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        settle()
    }

    private fun book(key: String): BookEntity? = runBlocking { app.database.books().get(ids[key]!!) }

    private fun library(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitUntil("home") { has("Library") }
            click("Library")
            waitUntil("library list") { has("Storm Season") && has("Gulls at Noon") }
            block(sc)
        }
    }

    private fun startedReader(sc: ActivityScenario<MainActivity>): Intent? {
        var i: Intent? = null
        sc.onActivity { i = shadowOf(it).nextStartedActivity }
        return i
    }

    @Test
    fun tappingABookOpensIt() {
        library { sc ->
            click("Storm Season")
            val i = startedReader(sc)
            assertNotNull("tapping a book opens the reader", i)
            assertEquals(ReaderActivity::class.java.name, i!!.component?.className)
            assertEquals(ids["storm"], i.getLongExtra(ReaderActivity.EXTRA_BOOK_ID, -1))
        }
    }

    @Test
    fun removingAsksFirstAndKeepsTheFileUnlessAsked() {
        library { sc ->
            longPress("The Harbour Light")
            assertTrue("long-press selects", has("1 selected"))
            assertNull("long-press must not open the book", startedReader(sc))
            clickIcon("Remove")
            assertTrue(has("Remove book?"))
            click("Cancel")
            assertNotNull(book("harbour"))
            clickIcon("Remove")
            click("Remove")
            waitUntil("removed") { book("harbour") == null }
            assertTrue("the file is kept", harbour.exists())
            waitUntil("gone from list") { !has("The Harbour Light") }
            longPress("Storm Season")
            clickIcon("Remove")
            click("Also delete the file from the device")
            click("Remove")
            waitUntil("removed with file") { book("storm") == null }
            assertFalse("the file is deleted when asked", File(Environment.getExternalStorageDirectory(), "Books/storm.epub").exists())
        }
        runBlocking { app.library.scanAll() }
        assertNull("a removed book stays removed after a rescan", runBlocking { app.database.books().all().firstOrNull { it.fileName == "harbour.epub" } })
    }

    @Test
    fun sharingABookFoundOnTheDeviceWorks() {
        library { sc ->
            var ok = false
            sc.onActivity { ok = com.readarea.ui.library.shareBookFile(it, book("harbour")!!) }
            assertTrue("sharing must not fail", ok)
            val chooser = startedReader(sc)
            assertNotNull("share sheet opens", chooser)
            val send = chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            val stream = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals("content", stream.scheme)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(harbour.length().toInt(), app.contentResolver.openInputStream(stream)!!.use { it.readBytes() }.size)
        }
    }

    @Test
    fun selectionMarksFavoritesAndStatus() {
        library { _ ->
            longPress("The Harbour Light")
            compose.onAllNodesWithText("Gulls at Noon", useUnmergedTree = true)[0].performClick()
            settle()
            assertTrue(has("2 selected"))
            clickIcon("Favorite")
            waitUntil("favorites saved") { book("harbour")!!.favorite && book("gulls")!!.favorite }
            assertFalse(book("storm")!!.favorite)
            clickIcon("Mark as")
            click("Mark as finished")
            waitUntil("status saved") { book("harbour")!!.status == BookStatus.FINISHED && book("gulls")!!.status == BookStatus.FINISHED }
            assertFalse("selection clears after marking", has("2 selected"))
        }
    }

    @Test
    fun booksCanBePutOnANewShelf() {
        library { _ ->
            longPress("Storm Season")
            compose.onAllNodesWithText("Gulls at Noon", useUnmergedTree = true)[0].performClick()
            settle()
            runBlocking { app.library.addToCollection(app.library.createCollection("Summer reads"), listOf(ids["storm"]!!, ids["gulls"]!!)) }
            compose.onAllNodesWithContentDescription("Clear selection")[0].performClick()
            settle()
            waitUntil("shelf created") { runBlocking { app.database.collections().observe().first() }.any { it.collection.name == "Summer reads" && it.count == 2 } }
            click("Shelves")
            waitUntil("shelf listed") { has("Summer reads") }
            click("Summer reads")
            waitUntil("shelf books") { has("Storm Season") && has("Gulls at Noon") }
            assertFalse(has("The Harbour Light"))
        }
    }

    @Test
    fun librarySearchFindsByTitleAndAuthor() {
        library { _ ->
            clickIcon("Search")
            type("quill")
            waitUntil("author match") { has("The Harbour Light") && !has("Storm Season") && !has("Gulls at Noon") }
            type("gulls")
            waitUntil("title match") { has("Gulls at Noon") && !has("The Harbour Light") }
            type("zzzz")
            waitUntil("no match") { has("Nothing matches") }
            clickIcon("Close search")
            waitUntil("all back") { has("The Harbour Light") && has("Storm Season") }
        }
    }

    @Test
    fun notesTabListsHighlightsAndOpensTheBookThere() {
        val id = ids["harbour"]!!
        runBlocking { app.database.notes().insertHighlight(HighlightEntity(bookId = id, chapter = 2, start = 120, end = 140, text = "the harbour bright and strangely quiet", color = 1, note = "Lovely line", chapterTitle = "Chapter 3", progress = 0.45f)) }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitUntil("home") { has("Notes") }
            click("Notes")
            waitUntil("highlight listed") { has("the harbour bright and strangely quiet") && has("Lovely line") }
            click("the harbour bright and strangely quiet")
            val i = startedReader(sc)!!
            assertEquals(id, i.getLongExtra(ReaderActivity.EXTRA_BOOK_ID, -1))
            assertEquals(2, i.getIntExtra(ReaderActivity.EXTRA_CHAPTER, -1))
            assertEquals(120, i.getIntExtra(ReaderActivity.EXTRA_OFFSET, -1))
        }
    }

    @Test
    fun openWithReadAreaAddsTheBookAndOpensIt() {
        val f = File(Environment.getExternalStorageDirectory(), "Download/Tide Tables.epub").apply { parentFile!!.mkdirs(); writeBytes(QaBooks.realisticEpub("Tide Tables", 2)) }
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(f), app, ReaderActivity::class.java)
        ActivityScenario.launch<ReaderActivity>(intent).use { sc ->
            waitUntil("opened", 20_000) { var ok = false; sc.onActivity { ok = it.vm.ui.value.laidOut && it.vm.ui.value.error == null }; ok }
            var title = ""
            sc.onActivity { title = it.vm.ui.value.title }
            assertEquals("Tide Tables", title)
        }
        val added = runBlocking { app.database.books().all() }.firstOrNull { it.title == "Tide Tables" }
        assertNotNull("the book is added to the library", added)
    }

    @Test
    fun appSettingsAreRemembered() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil("home") { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
            clickIcon("Settings")
            waitUntil("settings") { has("Reopen the last book", substring = true) }
            compose.onAllNodesWithText("Reopen the last book", useUnmergedTree = true)[0].performScrollTo().performClick()
            settle()
            waitUntil("saved") { runBlocking { app.settings.app.first().reopenLastBook } }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil("home") { has("Library") }
            assertTrue(runBlocking { app.settings.app.first().reopenLastBook })
        }
    }
}
