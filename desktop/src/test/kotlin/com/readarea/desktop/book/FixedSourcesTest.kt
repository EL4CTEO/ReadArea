package com.readarea.desktop.book

import com.readarea.desktop.qa.SampleLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

class FixedSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun inkPixels(img: java.awt.image.BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height step 3) for (x in 0 until img.width step 3) if ((img.getRGB(x, y) and 0xFFFFFF) != 0xFFFFFF) n++
        return n
    }

    @Test
    fun rendersPdfPagesWithOutlineAndText() {
        val dir = SampleLibrary.create(tmp.newFolder("books"))
        PdfSource.open(File(dir, "Atlas of Clouds.pdf")).use { src ->
            assertEquals(8, src.pageCount)
            val img = src.render(0, 420, 600)
            File("build/screens").mkdirs()
            ImageIO.write(img, "png", File("build/screens/pdf_page0.png"))
            assertTrue("page has content: ${inkPixels(img)}", inkPixels(img) > 200)
            assertEquals("white paper", 0xFFFFFF, img.getRGB(5, 5) and 0xFFFFFF)
            assertEquals(0.707f, src.pageAspect(0), 0.01f)
            assertEquals(7, src.outline().size)
            assertTrue(src.pageText(1)!!, src.pageText(1)!!.contains("Chapter 1"))
            assertEquals("Atlas of Clouds" to "R. Okafor", src.documentInfo())
            val crop = src.contentBounds(0)
            assertTrue("crop $crop", crop != null && crop.width < 1f)
        }
    }

    @Test
    fun rendersComicPages() {
        val dir = SampleLibrary.create(tmp.newFolder("books"))
        CbzSource(File(dir, "Moonlit Harbor.cbz")).use { src ->
            assertEquals(6, src.pageCount)
            val img = src.render(0, 400, 600)
            assertTrue(inkPixels(img) > 200)
            assertEquals(400f / 600f, img.width.toFloat() / img.height, 0.02f)
        }
    }
}
