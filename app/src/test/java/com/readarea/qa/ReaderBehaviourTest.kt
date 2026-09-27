package com.readarea.qa

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import com.readarea.data.db.BookStatus
import com.readarea.reader.Panel
import com.readarea.reader.ReaderActivity
import com.readarea.reader.engine.PagePos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi", shadows = [NoMagnifier::class])
class ReaderBehaviourTest : ReaderQa() {

    private fun hasText(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(t: String, substring: Boolean = false) {
        compose.onAllNodesWithText(t, substring = substring, useUnmergedTree = true)[0].performClick()
        settle()
    }

    private fun clickIcon(desc: String) {
        compose.onAllNodesWithContentDescription(desc)[0].performClick()
        settle()
    }

    private fun turnsWith(anim: String) {
        runBlocking { app.settings.updateReader { it.copy(pageAnim = anim) } }
        open().use { sc ->
            val (w, h) = size(sc)
            assertEquals(PagePos(0, 0), pos(sc))
            assertShowing(sc, "$anim start")
            swipe(sc, w * 0.85f, w * 0.15f, h * 0.8f)
            assertEquals("$anim: swipe left turns forward", PagePos(0, 1), pos(sc))
            assertShowing(sc, "$anim after swipe")
            tap(sc, w * 0.9f, h * 0.5f)
            assertEquals("$anim: tap right turns forward", PagePos(0, 2), pos(sc))
            assertShowing(sc, "$anim after tap right")
            tap(sc, w * 0.1f, h * 0.5f)
            assertEquals("$anim: tap left turns back", PagePos(0, 1), pos(sc))
            assertShowing(sc, "$anim after tap left")
            swipe(sc, w * 0.15f, w * 0.85f, h * 0.8f)
            assertEquals("$anim: swipe right turns back", PagePos(0, 0), pos(sc))
            assertShowing(sc, "$anim after swipe back")
            swipe(sc, w * 0.85f, w * 0.80f, h * 0.8f, steps = 4, ms = 400)
            assertEquals("$anim: a tiny drag springs back", PagePos(0, 0), pos(sc))
            assertShowing(sc, "$anim after spring back")
            tap(sc, w * 0.1f, h * 0.5f)
            assertEquals("$anim: nothing before the first page", PagePos(0, 0), pos(sc))
            assertFalse(sc.get { it.vm.ui.value.menu })
        }
    }

    @Test
    fun curlTurnsPages() = turnsWith("curl")

    @Test
    fun slideTurnsPages() = turnsWith("slide")

    @Test
    fun fadeTurnsPages() = turnsWith("fade")

    @Test
    fun instantTurnsPages() = turnsWith("none")

    @Test
    fun pagesFlowAcrossChaptersBothWays() {
        open().use { sc ->
            val (w, h) = size(sc)
            val e = engine(sc)
            val last = e.pageCount(0) - 1
            sc.onActivity { it.vm.goTo(0, e.offsetOf(PagePos(0, last))) }
            sc.waitFor { it.vm.pos == PagePos(0, last) }
            idle()
            assertShowing(sc, "last page of chapter 1")
            tap(sc, w * 0.9f, h * 0.5f)
            assertEquals(PagePos(1, 0), pos(sc))
            assertShowing(sc, "first page of chapter 2")
            assertTrue(sc.get { it.vm.ui.value.chapterTitle }.contains("Chapter 2"))
            swipe(sc, w * 0.15f, w * 0.85f, h * 0.8f)
            assertEquals(PagePos(0, last), pos(sc))
            assertShowing(sc, "back on chapter 1")
        }
    }

    @Test
    fun theLastPageOffersToMarkTheBookFinished() {
        open().use { sc ->
            val (w, h) = size(sc)
            val e = engine(sc)
            val lastChapter = e.chapterCount - 1
            sc.waitFor { e.isReady(lastChapter) }
            val lastPage = e.pageCount(lastChapter) - 1
            sc.onActivity { it.vm.goTo(lastChapter, e.offsetOf(PagePos(lastChapter, lastPage))) }
            sc.waitFor { it.vm.pos == PagePos(lastChapter, lastPage) }
            idle()
            tap(sc, w * 0.9f, h * 0.5f)
            waitUntil(what = "end sheet") { hasText("The end") }
            shot("the_end.png")
            click("Mark as finished")
            waitUntil(what = "status saved") { runBlocking { app.database.books().get(bookId)?.status } == BookStatus.FINISHED }
            assertFalse(hasText("The end"))
        }
    }

    @Test
    fun tappingTheMiddleShowsAndHidesTheMenu() {
        open().use { sc ->
            val (w, h) = size(sc)
            tap(sc, w * 0.5f, h * 0.5f)
            assertTrue(sc.get { it.vm.ui.value.menu })
            assertTrue("the menu shows the tools", hasText("Contents"))
            shot("menu.png")
            tap(sc, w * 0.5f, h * 0.5f)
            assertFalse(sc.get { it.vm.ui.value.menu })
            assertFalse(hasText("Contents"))
            assertEquals(PagePos(0, 0), pos(sc))
        }
    }

    @Test
    fun readingPlaceSurvivesClosingTheBook() {
        var where: Pair<Int, Int>
        open().use { sc ->
            val (w, h) = size(sc)
            repeat(3) { tap(sc, w * 0.9f, h * 0.5f) }
            sc.onActivity { it.vm.goTo(1, 0) }
            sc.waitFor { it.vm.pos.chapter == 1 }
            idle()
            repeat(2) { tap(sc, w * 0.9f, h * 0.5f) }
            where = pos(sc).chapter to engine(sc).offsetOf(pos(sc))
            idle(1500)
        }
        waitUntil(what = "position saved") { runBlocking { app.database.books().get(bookId)!!.let { it.chapter to it.offset } } == where }
        open().use { sc ->
            assertEquals(where.first, pos(sc).chapter)
            assertEquals("reopens on the same page", where.second, engine(sc).offsetOf(pos(sc)))
            assertShowing(sc, "reopened page")
        }
    }

    @Test
    fun contentsJumpsToTheChosenChapter() {
        open().use { sc ->
            val (w, h) = size(sc)
            tap(sc, w * 0.5f, h * 0.5f)
            click("Contents")
            assertEquals(Panel.CONTENTS, sc.get { it.vm.ui.value.panel })
            click("Chapter 4")
            sc.waitFor { it.vm.pos.chapter == 3 }
            idle()
            assertEquals(PagePos(3, 0), pos(sc))
            assertEquals(Panel.NONE, sc.get { it.vm.ui.value.panel })
            assertFalse(sc.get { it.vm.ui.value.menu })
            assertTrue(sc.get { it.vm.ui.value.chapterTitle }.contains("Chapter 4"))
            assertShowing(sc, "chapter 4")
        }
    }

    @Test
    fun bookmarksCanBeAddedFoundAndRemoved() {
        open().use { sc ->
            val (w, h) = size(sc)
            repeat(2) { tap(sc, w * 0.9f, h * 0.5f) }
            val marked = pos(sc)
            tap(sc, w * 0.5f, h * 0.5f)
            clickIcon("Bookmark")
            sc.waitFor { it.vm.ui.value.bookmarks.size == 1 && it.vm.ui.value.bookmarked }
            val b = sc.get { it.vm.ui.value.bookmarks[0] }
            assertEquals(marked.chapter, b.chapter)
            assertEquals(engine(sc).offsetOf(marked), b.offset)
            assertTrue("bookmark shows a snippet of the page", b.snippet.length > 20)
            tap(sc, w * 0.5f, h * 0.5f)
            tap(sc, w * 0.9f, h * 0.5f)
            assertFalse("the next page is not bookmarked", sc.get { it.vm.ui.value.bookmarked })
            sc.onActivity { it.vm.goTo(2, 0) }
            sc.waitFor { it.vm.pos.chapter == 2 }
            tap(sc, w * 0.5f, h * 0.5f)
            click("Contents")
            click("Bookmarks (1)")
            click(b.snippet.take(30), substring = true)
            sc.waitFor { it.vm.pos == marked }
            idle()
            assertTrue(sc.get { it.vm.ui.value.bookmarked })
            assertShowing(sc, "bookmarked page")
            tap(sc, w * 0.5f, h * 0.5f)
            clickIcon("Bookmark")
            sc.waitFor { it.vm.ui.value.bookmarks.isEmpty() && !it.vm.ui.value.bookmarked }
        }
    }

    private fun selectWord(sc: ActivityScenario<ReaderActivity>, pick: (String, Int, Int) -> Boolean): Triple<String, Int, Int> {
        val word = wordOnPage(sc, pick)
        val ink = inkOnScreen(sc, word.second, word.third)
        longPress(sc, ink.centerX(), ink.centerY())
        sc.waitFor(5_000, "selection for ${word.first}") { it.vm.ui.value.selection != null }
        return word
    }

    @Test
    fun longPressSelectsExactlyTheWordUnderTheFinger() {
        open().use { sc ->
            val (w, h) = size(sc)
            tap(sc, w * 0.9f, h * 0.5f)
            val e = engine(sc)
            val p = pos(sc)
            val cp = e.pages(p.chapter)!!
            val lines = (cp.layout.getLineForOffset(e.offsetOf(p)) until cp.layout.getLineForOffset(e.endOffsetOf(p) - 1)).toList()
            val checked = mutableListOf<String>()
            for (line in listOf(lines[1], lines[lines.size / 2], lines[lines.size - 2])) {
                val a = cp.layout.getLineStart(line)
                val b = cp.layout.getLineVisibleEnd(line)
                val words = Regex("\\p{L}{4,}").findAll(cp.text.substring(a, b)).toList()
                for (m in listOf(words.first(), words[words.size / 2], words.last())) {
                    val s = a + m.range.first
                    val en = a + m.range.last + 1
                    val ink = inkOnScreen(sc, s, en)
                    longPress(sc, ink.centerX(), ink.centerY())
                    sc.waitFor(5_000, "selection of ${m.value}") { it.vm.ui.value.selection != null }
                    val sel = sc.get { it.vm.ui.value.selection!! }
                    assertEquals("long-press on '${m.value}' (line $line) must select it", m.value, sel.text)
                    checked += sel.text
                    tap(sc, w * 0.5f, h * 0.5f)
                    assertNull(sc.get { it.vm.ui.value.selection })
                }
            }
            assertEquals(9, checked.size)
        }
    }

    @Test
    fun highlightIsSavedDrawnOnTheWordAndCanBeDeleted() {
        open().use { sc ->
            val (w, h) = size(sc)
            val word = selectWord(sc) { wd, _, _ -> wd.length >= 7 }
            shot("selection.png")
            assertTrue("the toolbar is shown", hasText("Copy") || compose.onAllNodesWithContentDescription("Copy").fetchSemanticsNodes().isNotEmpty())
            compose.onAllNodesWithContentDescription("Yellow highlight")[0].performClick()
            sc.waitFor { it.vm.ui.value.highlights.size == 1 && it.vm.ui.value.selection == null }
            idle()
            val hl = sc.get { it.vm.ui.value.highlights[0] }
            assertEquals(word.first, hl.text)
            assertEquals(word.second, hl.start)
            assertEquals(word.third, hl.end)
            val bmp = viewPixels(sc)
            val ink = inkOnScreen(sc, word.second, word.third)
            val bg = engine(sc).theme!!.background
            var tinted = 0
            var total = 0
            for (y in (ink.top + 3).toInt() until (ink.bottom - 3).toInt() step 2) for (x in (ink.left + 2).toInt() until (ink.right - 2).toInt() step 2) {
                val c = bmp.getPixel(x, y)
                total++
                if (android.graphics.Color.blue(c) < android.graphics.Color.blue(bg) - 40) tinted++
            }
            assertTrue("yellow must be painted over the word ($tinted/$total)", tinted > total / 3)
            val ink2 = inkOnScreen(sc, word.second, word.third)
            tap(sc, ink2.centerX(), ink2.centerY())
            val sel = sc.get { it.vm.ui.value.selection }
            assertNotNull("tapping a highlight opens it", sel)
            assertEquals(hl.id, sel!!.highlightId)
            clickIcon("Delete highlight")
            sc.waitFor { it.vm.ui.value.highlights.isEmpty() }
            idle()
            assertTrue("highlight is gone from the page", difference(viewPixels(sc), expectedPixels(sc, pos(sc))) < 0.01f)
            assertEquals(PagePos(0, 0), pos(sc))
            tap(sc, w * 0.9f, h * 0.5f)
            assertEquals(PagePos(0, 1), pos(sc))
        }
    }

    @Test
    fun draggingTheSelectionHandleExtendsIt() {
        open().use { sc ->
            val e = engine(sc)
            val p = pos(sc)
            val cp = e.pages(p.chapter)!!
            val start = wordOnPage(sc) { _, a, _ -> cp.layout.getLineForOffset(a) > cp.layout.getLineForOffset(e.offsetOf(p)) + 3 }
            val line = cp.layout.getLineForOffset(start.second)
            val target = Regex("\\p{L}{4,}").findAll(cp.text.substring(cp.layout.getLineStart(line + 2), cp.layout.getLineVisibleEnd(line + 2))).toList().let { it[it.size / 2] }
            val tStart = cp.layout.getLineStart(line + 2) + target.range.first
            val tEnd = cp.layout.getLineStart(line + 2) + target.range.last + 1
            val a = inkOnScreen(sc, start.second, start.third)
            val b = inkOnScreen(sc, tStart, tEnd)
            longPress(sc, a.centerX(), a.centerY(), dragTo = b.right - 2f to b.centerY())
            sc.waitFor(5_000, "extended selection") { it.vm.ui.value.selection != null }
            val sel = sc.get { it.vm.ui.value.selection!! }
            assertEquals(start.second, sel.start)
            assertTrue("selection must reach '${target.value}' but ends with '${sel.text.takeLast(20)}'", sel.end in tEnd - 1..tEnd + 1)
        }
    }

    @Test
    fun notesAreSavedListedAndExported() {
        open().use { sc ->
            val (w, h) = size(sc)
            val word = selectWord(sc) { wd, _, _ -> wd.length >= 6 }
            clickIcon("Note")
            compose.onAllNodes(hasSetTextAction())[0].performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Check the old map")) }
            settle()
            click("Save")
            sc.waitFor { it.vm.ui.value.highlights.size == 1 }
            val hl = sc.get { it.vm.ui.value.highlights[0] }
            assertEquals(word.first, hl.text)
            assertEquals("Check the old map", hl.note)
            tap(sc, w * 0.5f, h * 0.5f)
            click("Contents")
            click("Notes (1)")
            assertTrue(hasText("Check the old map"))
            click("Export")
            val chooser = sc.get { shadowOf(it).nextStartedActivity }
            assertNotNull(chooser)
            val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            val md = send.getStringExtra(Intent.EXTRA_TEXT)!!
            assertTrue(md, md.contains("> ${word.first}") && md.contains("Check the old map") && md.contains("The Harbour Light"))
        }
        val notes = runBlocking { app.database.notes().allHighlights().first() }
        assertEquals(1, notes.size)
    }

    @Test
    fun footnoteOpensInAPopupAndCanBeFollowed() {
        open().use { sc ->
            val e = engine(sc)
            val text = e.plainText(0)
            val ref = text.indexOf("Harbour Row.") + "Harbour Row.".length
            val page = e.pageOf(0, ref)
            sc.onActivity { it.vm.goTo(0, e.offsetOf(PagePos(0, page))) }
            sc.waitFor { it.vm.pos.page == page }
            idle()
            val ink = inkOnScreen(sc, ref, ref + 1)
            tap(sc, ink.centerX(), ink.centerY())
            waitUntil(what = "footnote popup") { hasText("Note 1: the address was later renamed.", substring = true) }
            shot("footnote.png")
            assertEquals(PagePos(0, page), pos(sc))
            click("Go to note")
            sc.waitFor { it.vm.pos.chapter != 0 }
            idle()
            assertTrue(sc.get { it.vm.ui.value.jumpBack } != null)
            click("Back")
            sc.waitFor { it.vm.pos == PagePos(0, page) }
        }
    }

    @Test
    fun largerTextKeepsYourPlaceAndIsRemembered() {
        open().use { sc ->
            val (w, h) = size(sc)
            sc.onActivity { it.vm.goTo(1, 0) }
            sc.waitFor { it.vm.pos.chapter == 1 }
            repeat(4) { tap(sc, w * 0.9f, h * 0.5f) }
            val e = engine(sc)
            val before = e.offsetOf(pos(sc))
            val pagesBefore = e.pageCount(1)
            val size0 = sc.get { it.vm.settings.value.fontSize }
            tap(sc, w * 0.5f, h * 0.5f)
            click("Text")
            repeat(4) { clickIcon("Larger") }
            sc.waitFor(what = "relayout") { it.vm.settings.value.fontSize == size0 + 4 && it.vm.ui.value.laidOut && e.pageCount(1) > pagesBefore }
            idle(1200)
            val p = pos(sc)
            assertEquals(1, p.chapter)
            assertTrue("page must still contain the text you were reading: was at $before, now $p covering ${e.offsetOf(p)}..${e.endOffsetOf(p)} of ${e.pageCount(1)} pages (was $pagesBefore)", e.offsetOf(p) <= before && before < e.endOffsetOf(p))
            tap(sc, w * 0.5f, h * 0.5f)
            assertShowing(sc, "after font change")
        }
        open().use { sc ->
            assertEquals(com.readarea.data.ReaderSettings().fontSize + 4, sc.get { it.vm.settings.value.fontSize })
        }
    }

    @Test
    fun rightToLeftMirrorsTapsAndSwipes() {
        runBlocking { app.settings.updateReader { it.copy(pageDirection = "rtl") } }
        open().use { sc ->
            val (w, h) = size(sc)
            assertTrue(sc.get { it.vm.ui.value.rtl })
            tap(sc, w * 0.1f, h * 0.5f)
            assertEquals("tap on the left goes forward in RTL", PagePos(0, 1), pos(sc))
            swipe(sc, w * 0.15f, w * 0.85f, h * 0.8f)
            assertEquals("swipe right goes forward in RTL", PagePos(0, 2), pos(sc))
            assertShowing(sc, "rtl")
            tap(sc, w * 0.9f, h * 0.5f)
            assertEquals(PagePos(0, 1), pos(sc))
        }
    }

    @Test
    fun volumeKeysTurnPagesWhenEnabled() {
        runBlocking { app.settings.updateReader { it.copy(volumeKeys = true) } }
        open().use { sc ->
            sc.onActivity { a ->
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
            }
            advance(1500)
            idle(200)
            assertEquals(PagePos(0, 1), pos(sc))
            assertShowing(sc, "after volume key")
            sc.onActivity { a ->
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
            }
            advance(1500)
            idle(200)
            assertEquals(PagePos(0, 0), pos(sc))
        }
    }

    @Test
    fun brightnessSwipeOnTheLeftEdgeDimsTheScreen() {
        open().use { sc ->
            val (w, h) = size(sc)
            drag(sc, w * 0.04f, h * 0.3f, w * 0.04f, h * 0.75f)
            sc.waitFor(what = "brightness saved") { !it.vm.settings.value.brightnessSystem }
            val level = sc.get { it.vm.settings.value.brightness }
            assertTrue("dragging down dims ($level)", level < 0.6f)
            val window = sc.get { it.window.attributes.screenBrightness }
            assertTrue("window brightness follows ($window)", window in 0f..0.6f)
            assertEquals(PagePos(0, 0), pos(sc))
        }
    }
}
