package com.readarea.qa

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.readarea.reader.Panel
import com.readarea.reader.ReaderActivity
import com.readarea.reader.engine.TextEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class ReaderFlowTest : ReaderQa() {
    private fun tintedShare(bmp: Bitmap, r: RectF, dx: Int, dy: Int, bg: Int): Float {
        var tinted = 0
        var total = 0
        val x0 = (r.left + dx + 2).toInt().coerceIn(0, bmp.width - 1)
        val x1 = (r.right + dx - 2).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (r.top + dy + 2).toInt().coerceIn(0, bmp.height - 1)
        val y1 = (r.bottom + dy - 2).toInt().coerceIn(0, bmp.height - 1)
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) {
            val c = bmp.getPixel(x, y)
            val d = kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(bg)) + kotlin.math.abs(android.graphics.Color.green(c) - android.graphics.Color.green(bg)) + kotlin.math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(bg))
            total++
            if (d > 24) tinted++
        }
        return if (total == 0) 0f else tinted.toFloat() / total
    }

    @Test
    fun searchResultJumpsToPageAndHighlightsTheWord() {
        open().use { sc ->
            val engine = sc.get { it.vm.engine as TextEngine }
            for (c in 0 until engine.chapterCount) {
                engine.ensure(c)
                assertEquals("layout text must match searchable text in chapter $c", engine.plainText(c), engine.pages(c)!!.text.toString())
            }
            sc.onActivity { it.vm.openPanel(Panel.SEARCH) }
            idle()
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("fisherman's cottage")
            sc.waitFor { it.vm.ui.value.searchResults.size >= 3 && !it.vm.ui.value.searching }
            idle()
            shot("search_results.png")
            val hits = sc.get { it.vm.ui.value.searchResults }
            val target = hits[2]
            assertEquals("fisherman's cottage", engine.plainText(target.chapter).substring(target.start, target.end).replace("\u00AD", "").replace('\u2019', '\'').lowercase())
            compose.onAllNodesWithText("cottage", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size.let { assertTrue(it >= 3) }
            compose.onAllNodesWithText("cottage", substring = true, useUnmergedTree = true)[3].performClick()
            sc.waitFor { it.vm.pos.chapter == target.chapter && it.vm.ui.value.panel == Panel.NONE }
            idle(1200)
            val bmp = shot("search_jump.png")
            val pos = sc.get { it.vm.pos }
            assertTrue("jumped page must contain the hit", engine.offsetOf(pos) <= target.start && target.end <= engine.endOffsetOf(pos))
            val path = Path()
            assertTrue(engine.selectionPath(pos, target.start, target.end, path))
            val rect = RectF()
            path.computeBounds(rect, true)
            val (ox, oy) = pageOrigin(sc)
            val bg = engine.theme!!.background
            val on = tintedShare(bmp, rect, ox, oy, bg)
            val off = tintedShare(bmp, RectF(rect.left, rect.top - rect.height() * 3, rect.right, rect.bottom - rect.height() * 3), ox, oy, bg)
            assertTrue("highlight should cover the found word (on=$on off=$off)", on > 0.5f && on > off + 0.3f)
            val cp = engine.pages(target.chapter)!!
            val ink = Ink.wordInk(cp, target.start, target.end)!!
            val hl = Ink.pathBounds(cp, target.start, target.end)
            assertTrue("highlight ${hl.left}-${hl.right} must sit on the drawn words ${ink.left}-${ink.right}", kotlin.math.abs(hl.left - ink.left) < 9f && kotlin.math.abs(hl.right - ink.right) < 9f)
        }
    }

    private fun pageColor(sc: ActivityScenario<ReaderActivity>): Int {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val (ox, oy) = pageOrigin(sc)
        return bmp.getPixel(ox + 6, oy + bmp.height / 2)
    }

    private fun close(a: Int, b: Int): Boolean = kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) < 20 && kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) < 20 && kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) < 20

    private fun openReady(): ActivityScenario<ReaderActivity> = open()

    private fun pickTheme(sc: ActivityScenario<ReaderActivity>, label: String) {
        sc.onActivity { it.vm.openPanel(Panel.THEME) }
        idle()
        compose.onAllNodesWithText(label)[0].performClick()
        idle(900)
        sc.onActivity { it.vm.closePanel() }
        idle(900)
    }

    @Test
    @Config(qualifiers = "+night")
    fun pickingALightThemeWorksWhilePhoneIsDark() {
        openReady().use { sc ->
            val night = com.readarea.reader.ReadingThemes.all.first { it.id == "night" }
            assertTrue("dark phone starts on the night theme", close(pageColor(sc), night.background))
            pickTheme(sc, "Paper")
            val paper = com.readarea.reader.ReadingThemes.all.first { it.id == "paper" }
            shot("theme_paper_in_dark.png")
            assertEquals("paper", sc.get { it.vm.engine!!.theme!!.id })
            assertTrue("page must turn paper coloured", close(pageColor(sc), paper.background))
            pickTheme(sc, "Dusk")
            assertEquals("dusk", sc.get { it.vm.engine!!.theme!!.id })
        }
    }

    @Test
    fun pickingThemesWorksWhilePhoneIsLight() {
        openReady().use { sc ->
            pickTheme(sc, "Night")
            val night = com.readarea.reader.ReadingThemes.all.first { it.id == "night" }
            assertEquals("night", sc.get { it.vm.engine!!.theme!!.id })
            assertTrue(close(pageColor(sc), night.background))
            pickTheme(sc, "Sepia")
            assertEquals("sepia", sc.get { it.vm.engine!!.theme!!.id })
        }
    }

    @Test
    fun appDarkModeSettingReachesTheReader() {
        runBlocking { app.settings.updateApp { it.copy(themeMode = "dark") } }
        openReady().use { sc ->
            sc.waitFor { it.vm.engine?.theme?.dark == true }
            idle(1200)
            shot("app_dark_reader.png")
            val night = com.readarea.reader.ReadingThemes.all.first { it.id == "night" }
            val px = pageColor(sc)
            assertTrue("page ${Integer.toHexString(px)} theme ${sc.get { it.vm.engine!!.theme!!.id }} expected ${Integer.toHexString(night.background)}", close(px, night.background))
        }
    }

    private fun resumeId(): Long = runBlocking { app.settings.app.first().resumeBookId }

    private fun launchedReaderId(): Long? {
        var started: android.content.Intent? = null
        ActivityScenario.launch(com.readarea.MainActivity::class.java).use { m ->
            val end = System.currentTimeMillis() + 8_000
            while (started == null && System.currentTimeMillis() < end) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                m.onActivity { started = shadowOf(it).peekNextStartedActivity() }
                Thread.sleep(40)
            }
        }
        val i = started ?: return null
        assertEquals(ReaderActivity::class.java.name, i.component?.className)
        return i.getLongExtra("book_id", -1L).takeIf { it > 0 } ?: i.extras?.keySet()?.firstNotNullOfOrNull { k -> i.extras?.get(k) as? Long }
    }

    @Test
    fun appReopensTheBookYouWereReading() {
        openReady().use { sc ->
            sc.waitFor { resumeId() == bookId }
        }
        assertEquals(bookId, launchedReaderId())
    }

    @Test
    fun leavingTheReaderWithBackOpensHomeNextTime() {
        openReady().use { sc ->
            sc.waitFor { resumeId() == bookId }
            sc.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            sc.waitFor(8_000) { resumeId() == 0L }
        }
        assertEquals(null, launchedReaderId())
    }

    @Test
    fun reopenCanBeTurnedOff() {
        openReady().use { sc -> sc.waitFor { resumeId() == bookId } }
        runBlocking { app.settings.updateApp { it.copy(reopenLastBook = false) } }
        assertEquals(null, launchedReaderId())
    }
}
