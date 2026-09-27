package com.readarea.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.readarea.core.format.BookFormat
import com.readarea.core.format.TestBooks
import com.readarea.data.ReaderSettings
import com.readarea.reader.ReadingThemes
import com.readarea.reader.engine.Decorations
import com.readarea.reader.engine.FixedEngine
import com.readarea.reader.engine.PagePos
import com.readarea.reader.engine.PageSetup
import com.readarea.ui.CoverArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LoaderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun page(n: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRect(60f, 60f, 540f, 840f, p)
        p.color = Color.WHITE
        p.textSize = 200f
        c.drawText("$n", 240f, 520f, p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun epubMetadataStreamsWithoutCopy() {
        val f = File(ctx.filesDir, "meta.epub").apply { writeBytes(TestBooks.epub(title = "Streamed", author = "Nia")) }
        val opened = BookLoader.open(ctx, Uri.fromFile(f).toString(), BookFormat.EPUB, f.name, "k1", metadataOnly = true) as ReflowableBook
        assertEquals("Streamed", opened.meta.title)
        assertEquals("Nia", opened.meta.author)
        assertNotNull(opened.book.coverBytes())
        val full = BookLoader.open(ctx, Uri.fromFile(f).toString(), BookFormat.EPUB, f.name, "k1") as ReflowableBook
        assertEquals(4, full.book.chapters.size)
        full.close()
    }

    @Test
    fun officeDocumentsUseEmbeddedCovers() {
        val art = CoverArt.make("Field Notes", "Kim Park", 0xFF2E4057.toInt(), 0xFF1B2838.toInt(), Color.WHITE, 0)
        fun meta(bytes: ByteArray, format: BookFormat, name: String): ReflowableBook {
            val f = File(ctx.filesDir, name).apply { writeBytes(bytes) }
            return BookLoader.open(ctx, Uri.fromFile(f).toString(), format, name, name, metadataOnly = true) as ReflowableBook
        }
        val lead = meta(TestBooks.docx("Field Notes", "Kim Park", thumbnail = CoverArt.make("x", "y", Color.RED, Color.RED, Color.WHITE, 3, jpeg = true), leadingImage = art), BookFormat.DOCX, "lead.docx")
        assertEquals("Field Notes", lead.meta.title)
        assertEquals("word/media/image1.png", lead.meta.coverRef)
        assertNotNull(lead.book.coverBytes())
        val thumb = meta(TestBooks.docx("Memo", "Kim Park", thumbnail = art), BookFormat.DOCX, "thumb.docx")
        assertEquals("docProps/thumbnail.jpeg", thumb.meta.coverRef)
        assertNotNull(thumb.book.coverBytes())
        val plain = meta(TestBooks.docx("Plain", "Kim Park"), BookFormat.DOCX, "plain.docx")
        assertEquals(null, plain.meta.coverRef)
        val odt = meta(TestBooks.odt("Essay", "Lee Moss", thumbnail = art), BookFormat.ODT, "essay.odt")
        assertEquals("Essay", odt.meta.title)
        assertEquals("Thumbnails/thumbnail.png", odt.meta.coverRef)
        assertNotNull(odt.book.coverBytes())
        val full = BookLoader.open(ctx, Uri.fromFile(File(ctx.filesDir, "lead.docx")).toString(), BookFormat.DOCX, "lead.docx", "lead2") as ReflowableBook
        assertEquals("word/media/image1.png", full.meta.coverRef)
        assertTrue(full.book.chapters[0].blocks.size > 5)
        full.close()
    }

    @Test
    fun comicRendersPagesInNaturalOrder() {
        val zip = TestBooks.zip(linkedMapOf("p10.png" to page(10, Color.BLUE), "p2.png" to page(2, Color.RED), "p1.png" to page(1, Color.rgb(20, 120, 60)), "__MACOSX/._p1.png" to ByteArray(10)))
        val f = File(ctx.filesDir, "comic.cbz").apply { writeBytes(zip) }
        val meta = BookLoader.open(ctx, Uri.fromFile(f).toString(), BookFormat.CBZ, f.name, "c1", metadataOnly = true) as ReflowableBook
        assertEquals("p1.png", meta.meta.coverRef)
        assertNotNull(meta.book.coverBytes())
        val opened = BookLoader.open(ctx, Uri.fromFile(f).toString(), BookFormat.CBZ, f.name, "c1") as FixedBook
        assertEquals(3, opened.source.pageCount)
        val engine = FixedEngine(opened.source, false, "Comic")
        val s = ReaderSettings(theme = "night")
        engine.configure(PageSetup(1080, 2340, 2.75f, 1f, s, 80, 60), ReadingThemes.resolve(s))
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        engine.drawPage(Canvas(bmp), PagePos(0, 1), Decorations())
        val center = bmp.getPixel(540, 770)
        assertTrue(Color.red(center) > 150 && Color.green(center) < 90)
        File("build/screens").mkdirs()
        File("build/screens/comic_page2.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        engine.close()
    }
}
