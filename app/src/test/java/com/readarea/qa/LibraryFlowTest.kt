package com.readarea.qa

import android.os.Environment
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.readarea.MainActivity
import com.readarea.ReadAreaApp
import com.readarea.core.format.TestBooks
import com.readarea.data.AppSettings
import com.readarea.data.DeviceStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class LibraryFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAreaApp>()
    private lateinit var root: File

    @Before
    fun setUp() {
        com.readarea.TestIsolation.reset()
        runBlocking { app.settings.updateApp { AppSettings(askedDeviceScan = true) } }
        root = Environment.getExternalStorageDirectory().apply { mkdirs() }
    }

    private fun grantAllFiles() {
        val ops = app.getSystemService(android.app.AppOpsManager::class.java)
        shadowOf(ops).setMode("android:manage_external_storage", android.os.Process.myUid(), app.packageName, android.app.AppOpsManager.MODE_ALLOWED)
    }

    private fun denyAllFiles() {
        val ops = app.getSystemService(android.app.AppOpsManager::class.java)
        shadowOf(ops).setMode("android:manage_external_storage", android.os.Process.myUid(), app.packageName, android.app.AppOpsManager.MODE_ERRORED)
    }

    private fun put(path: String, bytes: ByteArray): File = File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun names(): Set<String> = runBlocking { app.database.books().all().filter { !it.missing }.map { it.fileName }.toSet() }

    @Test
    fun deviceScanFindsBooksAndNeverDeletesFilesOnRemove() {
        grantAllFiles()
        val epub = TestBooks.epub(chapters = 2, paragraphs = 3)
        val novel = put("Books/novel.epub", epub)
        val story = put("Download/deep/a/b/story.epub", epub)
        val sent = put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/sent.epub", epub)
        put(".hidden/secret.epub", epub)
        put("Android/data/com.other/files/private.epub", epub)
        put("notes/tiny.txt", "short note".toByteArray())
        put("notes/long.txt", TestBooks.lorem(4000).toByteArray())
        put("web/page.html", "<html><body>${TestBooks.lorem(3000)}</body></html>".toByteArray())
        assertTrue(DeviceStorage.hasAccess(app))
        runBlocking {
            app.settings.updateApp { it.copy(deviceScan = true) }
            app.library.scanAll()
        }
        assertEquals(setOf("novel.epub", "story.epub", "sent.epub", "long.txt"), names())

        val novelBook = runBlocking { app.database.books().all().first { it.fileName == "novel.epub" } }
        runBlocking { app.library.removeBooks(listOf(novelBook.id), deleteFiles = false) }
        assertTrue("removing from the library must keep the file", novel.exists())
        runBlocking { app.library.scanAll() }
        assertFalse("a removed book must not come back on rescan", "novel.epub" in names())

        val storyBook = runBlocking { app.database.books().all().first { it.fileName == "story.epub" } }
        runBlocking { app.library.removeBooks(listOf(storyBook.id), deleteFiles = true) }
        assertFalse("delete files was chosen", story.exists())

        sent.delete()
        runBlocking { app.library.scanAll() }
        val sentBook = runBlocking { app.database.books().all().first { it.fileName == "sent.epub" } }
        assertTrue("a book whose file disappeared is marked missing", sentBook.missing)

        put("Books/new-arrival.epub", epub)
        runBlocking { app.library.scanAll() }
        assertTrue("new files show up on the next scan", "new-arrival.epub" in names())
    }

    @Test
    fun importedCopiesAreCleanedButUserFilesAreNot() {
        val own = File(app.filesDir, "imported/copy.epub").apply { parentFile!!.mkdirs(); writeBytes(TestBooks.epub(chapters = 1, paragraphs = 2)) }
        val user = put("Books/mine.epub", TestBooks.epub(chapters = 1, paragraphs = 2))
        val ids = runBlocking {
            listOf(
                app.database.books().insert(com.readarea.data.db.BookEntity(uri = android.net.Uri.fromFile(own).toString(), fileName = own.name, format = "EPUB", size = own.length(), title = "Copy")),
                app.database.books().insert(com.readarea.data.db.BookEntity(uri = android.net.Uri.fromFile(user).toString(), fileName = user.name, format = "EPUB", size = user.length(), title = "Mine")),
            )
        }
        runBlocking { app.library.removeBooks(ids, deleteFiles = false) }
        assertFalse("ReadArea's own imported copy is cleaned up", own.exists())
        assertTrue("a file that belongs to the user is never deleted without asking", user.exists())
    }

    @Test
    fun firstLaunchOffersToFindBooksOnce() {
        denyAllFiles()
        runBlocking { app.settings.updateApp { AppSettings() } }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("Find all your books?").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("Not now")[0].performClick()
            compose.waitForIdle()
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("Find all your books?").fetchSemanticsNodes().isEmpty()
            }
        }
        val s = runBlocking { app.settings.app.first() }
        assertTrue(s.askedDeviceScan)
        assertFalse(s.deviceScan)
    }

    private fun waitFor(what: String, timeout: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (cond()) return
            Thread.sleep(40)
        }
        throw AssertionError("$what: not met in time")
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun firstLaunchLeadsStraightToAllTheBooksOnThePhone() {
        denyAllFiles()
        runBlocking { app.settings.updateApp { AppSettings() } }
        put("Download/Harbour.epub", TestBooks.epub(chapters = 1, paragraphs = 3, title = "Harbour Light"))
        put("Documents/School/Notes.epub", TestBooks.epub(chapters = 1, paragraphs = 3, title = "Field Notes"))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitFor("offer") { shown("Find all your books?") }
            compose.onAllNodesWithText("Continue")[0].performClick()
            compose.waitForIdle()
            var sent: android.content.Intent? = null
            sc.onActivity { sent = shadowOf(it).nextStartedActivityForResult?.intent }
            assertEquals(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, sent?.action)
            assertEquals("package:${app.packageName}", sent?.dataString)
            grantAllFiles()
            sc.onActivity { shadowOf(it).receiveResult(sent!!, android.app.Activity.RESULT_CANCELED, null) }
            sc.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            sc.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitFor("books listed on the home screen") { shown("Harbour Light") && shown("Field Notes") }
        }
        val s = runBlocking { app.settings.app.first() }
        assertTrue(s.deviceScan)
    }

    @Test
    fun grantingAccessLaterStillFindsTheBooks() {
        denyAllFiles()
        runBlocking { app.settings.updateApp { AppSettings(askedDeviceScan = true) } }
        put("Download/Later.epub", TestBooks.epub(chapters = 1, paragraphs = 3, title = "Later Book"))
        grantAllFiles()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("books appear once access exists") { shown("Later Book") }
        }
    }

    @Test
    fun turningTheScanOffIsRespected() {
        grantAllFiles()
        runBlocking { app.settings.updateApp { AppSettings(askedDeviceScan = true, deviceScanOff = true) } }
        put("Download/Hidden.epub", TestBooks.epub(chapters = 1, paragraphs = 3, title = "Should Stay Out"))
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("home") { shown("Home") || shown("Library") }
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(40) }
            assertFalse(shown("Should Stay Out"))
        }
        assertFalse(runBlocking { app.settings.app.first() }.deviceScan)
    }
}
