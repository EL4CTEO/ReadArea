package com.readarea.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.readarea.MainActivity
import com.readarea.ReadAreaApp
import com.readarea.core.format.TestBooks
import com.readarea.data.db.BookEntity
import com.readarea.data.db.BookStatus
import com.readarea.data.db.ReadingSessionEntity
import com.readarea.reader.ReaderActivity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.junit.Assert.assertTrue
import java.time.LocalDate

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class AppScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val out = File("build/screens").apply { mkdirs() }
    private var firstId = 0L

    private val seed = listOf(
        Triple("The Lighthouse Keeper", "Mara Quill", 0.42f),
        Triple("Salt and Starlight", "Jonah Reyes", 0.0f),
        Triple("A Brief History of Tea", "Imogen Hart", 1f),
        Triple("Northern Rivers", "Tomas Lind", 0.12f),
        Triple("The Clockmaker's Daughter", "Elena Moss", 0.0f),
        Triple("Paper Gardens", "Yuki Tanaka", 0.77f),
        Triple("Midnight Library Notes", "Owen Price", 0.0f),
        Triple("Coastlines", "Ada Writer", 0.0f),
    )

    @Before
    fun setUp() {
        com.readarea.TestIsolation.reset()
        val app = ApplicationProvider.getApplicationContext<ReadAreaApp>()
        runBlocking {
            app.settings.updateApp { it.copy(askedDeviceScan = true) }
            seed.forEachIndexed { i, (title, author, progress) ->
                val f = File(app.filesDir, "book$i.epub")
                f.writeBytes(TestBooks.epub(chapters = 4, paragraphs = 12, withCover = false, title = title, author = author))
                val now = System.currentTimeMillis()
                val id = app.database.books().insert(
                    BookEntity(
                        uri = Uri.fromFile(f).toString(), fileName = f.name, format = if (i == 3) "PDF" else "EPUB", size = f.length(), title = title, author = author,
                        addedAt = now - i * 3_600_000L, lastOpenedAt = if (progress > 0f) now - i * 60_000L else 0L, progress = progress,
                        status = when {
                            progress >= 1f -> BookStatus.FINISHED
                            progress > 0f -> BookStatus.READING
                            i == 4 -> BookStatus.WANT
                            else -> BookStatus.NEW
                        },
                        favorite = i == 1, metaLoaded = true, readingMs = (progress * 4 * 3_600_000).toLong(),
                    ),
                )
                if (i == 0) firstId = id
            }
            val today = LocalDate.now().toEpochDay()
            for (d in 0 until 40) {
                if (d % 5 == 3) continue
                app.database.stats().insert(ReadingSessionEntity(bookId = firstId, start = System.currentTimeMillis() - d * 86_400_000L, durationMs = ((d * 7) % 50 + 8) * 60_000L, pages = 20, day = today - d))
            }
        }
    }

    private fun settle(ms: Long = 1500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        val img = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, name).outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(text: String) {
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun mainScreens() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("Continue reading")
            settle()
            shot("app_home.png")
            compose.onAllNodesWithText("Library")[0].performClick()
            waitText("8 books")
            settle()
            shot("app_library.png")
            compose.onAllNodesWithText("Stats")[0].performClick()
            waitText("Last 7 days")
            settle()
            shot("app_stats.png")
            compose.onAllNodesWithText("Shelves")[0].performClick()
            waitText("Make your first shelf")
            settle()
            shot("app_shelves.png")
        }
    }

    @Test
    fun libraryShowsEmbeddedCovers() {
        val app = ApplicationProvider.getApplicationContext<ReadAreaApp>()
        val white = 0xFFF7F1E6.toInt()
        runBlocking {
            for (i in seed.indices) {
                val b = app.database.books().get(firstId + i) ?: continue
                val (title, author) = seed[i].first to seed[i].second
                val (ext, format, bytes) = when (i) {
                    1 -> Triple("docx", "DOCX", TestBooks.docx(title, author, thumbnail = CoverArt.make(title, author, 0xFF3F6E8C.toInt(), 0xFF16293A.toInt(), white, 1, jpeg = true)))
                    3 -> Triple("txt", "TXT", TestBooks.lorem(400).toByteArray())
                    5 -> Triple("odt", "ODT", TestBooks.odt(title, author, thumbnail = CoverArt.make(title, author, 0xFFE9DFCC.toInt(), 0xFFCDBB98.toInt(), 0xFF3A2E22.toInt(), 2)))
                    6 -> Triple("cbz", "CBZ", TestBooks.zip(linkedMapOf("001.png" to CoverArt.make(title, author, 0xFF1F1F24.toInt(), 0xFF3A2D5C.toInt(), 0xFFF5B971.toInt(), 3), "002.png" to TestBooks.PNG_1x1)))
                    else -> {
                        val palette = listOf(
                            Triple(0xFF2E4057.toInt(), 0xFF0F1A26.toInt(), 0xFFF2C14E.toInt()),
                            Triple(0xFFB0463C.toInt(), 0xFF5A1D18.toInt(), white),
                            Triple(0xFF4F7A5A.toInt(), 0xFF1E3326.toInt(), white),
                            Triple(0xFF9A5B34.toInt(), 0xFF3E2211.toInt(), 0xFFF3C27A.toInt()),
                        )[i % 4]
                        Triple("epub", "EPUB", TestBooks.epub(chapters = 2, paragraphs = 6, withCover = true, title = title, author = author, coverImage = CoverArt.make(title, author, palette.first, palette.second, palette.third, i % 4)))
                    }
                }
                val f = File(app.filesDir, "cover$i.$ext").apply { writeBytes(bytes) }
                val updated = b.copy(uri = Uri.fromFile(f).toString(), fileName = if (format == "CBZ" || format == "TXT") "$title.$ext" else f.name, format = format, size = f.length(), metaLoaded = false)
                app.database.books().update(updated)
                app.library.loadMetadata(updated)
            }
            val covers = app.database.books().all().mapNotNull { it.coverPath }
            assertTrue(covers.size == 7)
            covers.forEach { com.readarea.ui.components.CoverCache.load(it) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("Continue reading")
            settle()
            shot("app_home_covers.png")
            compose.onAllNodesWithText("Library")[0].performClick()
            waitText("8 books")
            settle()
            shot("app_library_covers.png")
        }
    }

    @Test
    @Config(qualifiers = "+ja")
    fun japaneseUi() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("続きを読む")
            settle()
            shot("app_home_ja.png")
            compose.onAllNodesWithText("ライブラリ")[0].performClick()
            waitText("8冊")
            settle()
            shot("app_library_ja.png")
        }
    }

    @Test
    @Config(qualifiers = "+ar")
    fun arabicUi() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("متابعة القراءة")
            settle()
            shot("app_home_ar.png")
            compose.onAllNodesWithText("الإحصاءات")[0].performClick()
            waitText("آخر 7 أيام")
            settle()
            shot("app_stats_ar.png")
        }
    }

    @Test
    @Config(qualifiers = "w841dp-h701dp-xhdpi")
    fun foldableHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("Continue reading")
            settle()
            shot("app_home_unfolded.png")
        }
    }

    @Test
    fun readerMenus() {
        val ctx = ApplicationProvider.getApplicationContext<ReadAreaApp>()
        ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(ctx, firstId)).use { sc ->
            var ready = false
            val end = System.currentTimeMillis() + 20_000
            while (!ready && System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                sc.onActivity { ready = it.vm.ui.value.laidOut }
                Thread.sleep(50)
            }
            settle(800)
            shot("reader_page.png")
            compose.onRoot().performTouchInput { click(center) }
            settle(800)
            shot("reader_menu.png")
            compose.onAllNodesWithText("Text")[0].performClick()
            settle(1000)
            shot("reader_typography.png")
            sc.onActivity { it.vm.closePanel() }
            settle(600)
            sc.onActivity { it.vm.openPanel(com.readarea.reader.Panel.PAGING) }
            settle(1000)
            shot("reader_paging.png")
            sc.onActivity { it.vm.openPanel(com.readarea.reader.Panel.CONTENTS) }
            settle(1000)
            shot("reader_contents.png")
            sc.onActivity {
                it.vm.closePanel()
                it.vm.toggleMenu(false)
            }
            settle(600)
            sc.onActivity {
                it.vm.onLongPress(300f, 900f)
                it.vm.onSelectionDrag(1, 900f, 1100f)
                it.vm.onSelectionDragEnd()
            }
            settle(800)
            shot("reader_selection.png")
            var selected = ""
            sc.onActivity { selected = it.vm.ui.value.selection?.text.orEmpty() }
            org.junit.Assert.assertTrue(selected.length > 20)
            sc.onActivity { it.vm.highlightSelection(2, "A thought") }

            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                var n = 0
                sc.onActivity { n = it.vm.ui.value.highlights.size }
                n == 1
            }
            settle(600)
            shot("reader_highlighted.png")
            sc.onActivity { it.vm.updateSettings { s -> s.copy(fontSize = 24f, theme = "night") } }
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                var ok = false
                sc.onActivity { ok = it.vm.settings.value.fontSize == 24f && it.vm.ui.value.laidOut }
                ok
            }
            settle(1500)
            shot("reader_night_large.png")
            sc.onActivity { it.vm.updateSettings { s -> s.copy(pageAnim = "scroll", theme = "sepia") } }
            settle(2500)
            shot("reader_scroll.png")

        }
    }
}
