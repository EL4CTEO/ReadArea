package com.readarea.qa

import android.content.ClipboardManager
import android.graphics.Color
import android.speech.tts.TextToSpeech
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import com.readarea.core.format.TestBooks
import com.readarea.reader.Panel
import com.readarea.reader.ReaderActivity
import com.readarea.reader.VerticalTextTest
import com.readarea.reader.engine.PagePos
import com.readarea.reader.engine.TextEngine
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
import org.robolectric.shadows.ShadowTextToSpeech

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi", shadows = [NoMagnifier::class, QuietTts::class])
class ReaderModesTest : ReaderQa() {

    private fun hasText(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(t: String, substring: Boolean = false) {
        compose.onAllNodesWithText(t, substring = substring, useUnmergedTree = true)[0].performClick()
        settle()
    }

    private fun clickIcon(desc: String) {
        compose.onAllNodesWithContentDescription(desc)[0].performClick()
        settle()
    }

    private fun inkShare(sc: ActivityScenario<ReaderActivity>): Float {
        val bmp = viewPixels(sc)
        val bg = sc.get { it.vm.engine!!.theme!!.background }
        var ink = 0
        var total = 0
        for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) {
            total++
            val c = bmp.getPixel(x, y)
            if (kotlin.math.abs(Color.red(c) - Color.red(bg)) + kotlin.math.abs(Color.green(c) - Color.green(bg)) + kotlin.math.abs(Color.blue(c) - Color.blue(bg)) > 90) ink++
        }
        return ink.toFloat() / total
    }

    @Test
    fun scrollModeScrollsAndKeepsYourPlace() {
        runBlocking { app.settings.updateReader { it.copy(pageAnim = "scroll") } }
        var saved: Pair<Int, Int>
        open().use { sc ->
            val (w, h) = size(sc)
            assertTrue(pageView(sc) is com.readarea.reader.view.ScrollPageView)
            assertTrue("text is drawn", inkShare(sc) > 0.005f)
            val start = sc.get { it.vm.ui.value.progress }
            repeat(6) { drag(sc, w * 0.5f, h * 0.85f, w * 0.5f, h * 0.15f, steps = 20, ms = 900) }
            sc.waitFor(what = "progress moves") { it.vm.ui.value.progress > start }
            assertTrue("still showing text after scrolling", inkShare(sc) > 0.005f)
            shot("scroll_mode.png")
            tap(sc, w * 0.5f, h * 0.5f)
            assertTrue("tapping shows the menu in scroll mode", sc.get { it.vm.ui.value.menu })
            tap(sc, w * 0.5f, h * 0.5f)
            idle(1500)
            saved = runBlocking { app.database.books().get(bookId)!!.let { it.chapter to it.offset } }
            assertTrue("scrolling saves the place", saved.first > 0 || saved.second > 0)
        }
        open().use { sc ->
            val e = engine(sc)
            val p = pos(sc)
            assertEquals(saved.first, p.chapter)
            assertTrue("reopens at the saved place: $saved in ${e.offsetOf(p)}..${e.endOffsetOf(p)}", e.offsetOf(p) <= saved.second && saved.second <= e.endOffsetOf(p))
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h560dp-xhdpi")
    fun wideScreensShowTwoPagesSideBySide() {
        open().use { sc ->
            val (w, h) = size(sc)
            assertEquals(2, sc.get { it.vm.engine!!.step })
            assertShowing(sc, "spread")
            shot("spread.png")
            tap(sc, w * 0.9f, h * 0.5f)
            assertEquals(PagePos(0, 2), pos(sc))
            assertShowing(sc, "spread after turn")
            swipe(sc, w * 0.2f, w * 0.8f, h * 0.8f)
            assertEquals(PagePos(0, 0), pos(sc))
        }
    }

    @Test
    fun everyFormatOpensAndTurnsPages() {
        val long = (1..14).joinToString("\n\n") { TestBooks.lorem(90, it) }
        val html = "<h1>Chapter One</h1>" + (1..14).joinToString("") { "<p>${TestBooks.lorem(90, it)}</p>" }
        val fb2 = """<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Fb Book</book-title><lang>en</lang></title-info></description><body><section><title><p>One</p></title>${(1..14).joinToString("") { "<p>${TestBooks.lorem(90, it)}</p>" }}</section></body></FictionBook>"""
        val rtf = "{\\rtf1\\ansi{\\info{\\title Rtf Book}}" + (1..14).joinToString("") { "\\pard ${TestBooks.lorem(90, it)}\\par" } + "}"
        val odtText = TestBooks.odt("Odt Book", "Lee Moss")
        val comic = TestBooks.zip(linkedMapOf("p1.png" to QaBooks.image(600, 900, Color.RED), "p2.png" to QaBooks.image(600, 900, Color.BLUE), "p3.png" to QaBooks.image(600, 900, Color.GREEN)))
        val books = listOf(
            Triple("a.epub", TestBooks.epub(chapters = 2, paragraphs = 12), "EPUB"),
            Triple("b.fb2", fb2.toByteArray(), "FB2"),
            Triple("c.mobi", TestBooks.mobi("Mobi Book", "Mo", "<html><body>$html</body></html>"), "MOBI"),
            Triple("d.txt", long.toByteArray(), "TXT"),
            Triple("e.md", ("# Markdown Book\n\n" + long).toByteArray(), "MD"),
            Triple("f.html", "<html><body>$html</body></html>".toByteArray(), "HTML"),
            Triple("g.rtf", rtf.toByteArray(), "RTF"),
            Triple("h.docx", TestBooks.docx("Docx Book", "Kim Park"), "DOCX"),
            Triple("i.odt", odtText, "ODT"),
            Triple("j.cbz", comic, "CBZ"),
        )
        val problems = mutableListOf<String>()
        for ((name, bytes, format) in books) {
            val id = addBook(name, bytes, format, name)
            val sc = ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(app, id))
            try {
                sc.waitFor(what = name) { it.vm.ui.value.error != null || (it.vm.ui.value.laidOut && !it.vm.ui.value.loading) }
                idle()
                val err = sc.get { it.vm.ui.value.error }
                if (err != null) {
                    problems += "$name: $err"
                    continue
                }
                if (inkShare(sc) < 0.003f) problems += "$name: first page is blank"
                shot("format_$name.png")
                val canTurn = sc.get { it.vm.engine!!.next(it.vm.pos) != null }
                if (canTurn) {
                    val (w, h) = size(sc)
                    val before = pos(sc)
                    tap(sc, w * 0.9f, h * 0.5f)
                    if (pos(sc) == before) problems += "$name: page did not turn"
                    else if (difference(viewPixels(sc), expectedPixels(sc, pos(sc))) > 0.01f) problems += "$name: turned page is not what's shown"
                } else if (format !in setOf("DOCX", "ODT")) problems += "$name: only one page"
            } finally {
                sc.close()
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun readAloudHighlightsTheSentenceAndTurnsPages() {
        open().use { sc ->
            val (w, h) = size(sc)
            tap(sc, w * 0.5f, h * 0.5f)
            click("Listen")
            assertEquals(Panel.SPEECH, sc.get { it.vm.ui.value.panel })
            click("From this page", substring = true)
            val tts = QuietTts.last
            assertNotNull("a speech engine is created", tts)
            tts!!.init.onInit(TextToSpeech.SUCCESS)
            idle()
            sc.waitFor(what = "sentences queued") { tts.spoken.size >= 3 }
            val e = engine(sc)
            val sentences = e.sentences(0, 0)
            assertEquals("starts with the first sentence of the page", e.text(0, sentences[0].first, sentences[0].last + 1), tts.spoken[0].first)
            val listener = tts.listener!!
            listener.onStart(tts.spoken[0].second)
            idle()
            assertTrue(sc.get { it.vm.ui.value.speaking })
            assertTrue("the speech bar is shown", hasText("Reading aloud", substring = true))
            val spoken = sc.get { it.vm.deco.speaking }
            assertNotNull(spoken)
            assertEquals(sentences[0].first, spoken!!.start)
            assertShowing(sc, "speaking page")
            val plain = sc.get { a -> val d = a.vm.deco.speaking; a.vm.deco.speaking = null; d }.let { d -> expectedPixels(sc, pos(sc)).also { sc.onActivity { a -> a.vm.deco.speaking = d } } }
            assertTrue("the spoken sentence is tinted on the page", difference(viewPixels(sc), plain) > 0.002f)
            var done = 0
            while (pos(sc).page == 0 && done < 200) {
                val id = tts.spoken[done].second
                listener.onStart(id)
                idle(80)
                listener.onDone(id)
                idle(80)
                done++
            }
            advance(1500)
            idle()
            assertEquals("the page turns when speech reaches the next page", PagePos(0, 1), pos(sc))
            val cur = sc.get { it.vm.deco.speaking }!!
            assertTrue("the sentence being read is on the new page", cur.start < e.endOffsetOf(pos(sc)) && cur.end > e.offsetOf(pos(sc)))
            assertShowing(sc, "page after speech")
            val ids = tts.spoken.map { it.second.split(':')[1].toInt() }
            assertEquals("sentences are queued in order without gaps", (0 until ids.size).toList(), ids)
            clickIcon("Stop")
            sc.waitFor { !it.vm.ui.value.speaking }
            assertNull(sc.get { it.vm.deco.speaking })
        }
    }

    @Test
    fun autoTurnTurnsPagesByItselfAndStops() {
        runBlocking { app.settings.updateReader { it.copy(autoTurnSeconds = 5) } }
        open().use { sc ->
            val (w, h) = size(sc)
            tap(sc, w * 0.5f, h * 0.5f)
            clickIcon("More")
            click("Auto page turn")
            assertTrue(sc.get { it.vm.ui.value.autoTurn })
            assertTrue(hasText("Turning every 5s", substring = true))
            advance(6000)
            idle(300)
            assertEquals(PagePos(0, 1), pos(sc))
            advance(5000)
            idle(300)
            assertEquals(PagePos(0, 2), pos(sc))
            assertShowing(sc, "auto turned page")
            click("Turning every 5s", substring = true)
            assertFalse(sc.get { it.vm.ui.value.autoTurn })
            advance(6000)
            idle(300)
            assertEquals(PagePos(0, 2), pos(sc))
        }
    }

    @Test
    fun verticalJapaneseBookTurnsSelectsAndSearches() {
        val id = addBook("hikari.epub", VerticalTextTest.jaEpub(), "EPUB", "ひかりの塔")
        open(id).use { sc ->
            val (w, h) = size(sc)
            assertTrue(sc.get { it.vm.ui.value.vertical })
            assertTrue(sc.get { it.vm.ui.value.rtl })
            assertShowing(sc, "vertical first page")
            tap(sc, w * 0.1f, h * 0.5f)
            assertEquals("tapping the left edge goes forward in vertical text", PagePos(0, 1), pos(sc))
            assertShowing(sc, "vertical page 2")
            swipe(sc, w * 0.15f, w * 0.85f, h * 0.8f)
            assertEquals(PagePos(0, 2), pos(sc))
            val e = engine(sc)
            val text = e.plainText(0)
            val from = e.offsetOf(pos(sc))
            val at = text.indexOf("灯台", from)
            assertTrue(at in from until e.endOffsetOf(pos(sc)))
            val path = android.graphics.Path()
            assertTrue(e.selectionPath(pos(sc), at, at + 2, path))
            val r = android.graphics.RectF().also { path.computeBounds(it, true) }
            longPress(sc, r.centerX(), r.top + r.height() * 0.25f)
            sc.waitFor(5_000, "vertical selection") { it.vm.ui.value.selection != null }
            val sel = sc.get { it.vm.ui.value.selection!! }
            assertTrue("long-press selects the characters under the finger, got '${sel.text}'", sel.text.contains("灯") || sel.text.contains("台"))
            clickIcon("Find")
            sc.waitFor(what = "search from selection") { it.vm.ui.value.panel == Panel.SEARCH && it.vm.ui.value.searchResults.isNotEmpty() && !it.vm.ui.value.searching }
            val hits = sc.get { it.vm.ui.value.searchResults }
            assertTrue(hits.all { e.plainText(it.chapter).substring(it.start, it.end) == sel.text })
        }
    }

    @Test
    fun copyPutsTheSelectedWordOnTheClipboard() {
        open().use { sc ->
            val word = wordOnPage(sc) { wd, _, _ -> wd.length >= 7 }
            val ink = inkOnScreen(sc, word.second, word.third)
            longPress(sc, ink.centerX(), ink.centerY())
            sc.waitFor { it.vm.ui.value.selection != null }
            clickIcon("Copy")
            val cm = app.getSystemService(ClipboardManager::class.java)
            assertEquals(word.first, cm.primaryClip!!.getItemAt(0).text.toString())
            assertNull(sc.get { it.vm.ui.value.selection })
        }
    }

    @Test
    fun searchFindsAllMatchesAcrossChaptersAndIgnoresAccentsAndCase() {
        open().use { sc ->
            val e = engine(sc)
            val all = (0 until e.chapterCount).sumOf { c -> e.ensure(c); Regex("lighthouse", RegexOption.IGNORE_CASE).findAll(e.plainText(c)).count() }
            sc.onActivity { it.vm.openPanel(Panel.SEARCH) }
            settle()
            compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("LIGHTHOUSE")) }
            sc.waitFor(what = "results") { !it.vm.ui.value.searching && it.vm.ui.value.searchResults.isNotEmpty() }
            val hits = sc.get { it.vm.ui.value.searchResults }
            assertEquals(all, hits.size)
            assertTrue(hits.map { it.chapter }.toSet().size > 1)
            sc.onActivity { it.vm.search("cafe") }
            sc.waitFor(what = "accent-insensitive") { a -> !a.vm.ui.value.searching && a.vm.ui.value.searchResults.let { r -> r.isNotEmpty() && r.all { e.plainText(it.chapter).substring(it.start, it.end).lowercase() == "café" } } }
            val cafe = sc.get { it.vm.ui.value.searchResults.first() }
            assertEquals("café", e.plainText(cafe.chapter).substring(cafe.start, cafe.end).lowercase())
            assertTrue(e is TextEngine)
        }
    }
}
