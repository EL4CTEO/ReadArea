package com.readarea.desktop.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class IconExportTest {
    private val pngMagic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    @Test
    fun icoDirectoryPointsAtPngImagesOfEachSize() {
        val sizes = listOf(16, 32, 256)
        val ico = IconExport.ico(sizes)
        val b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, b.getShort(0).toInt())
        assertEquals(1, b.getShort(2).toInt())
        assertEquals(sizes.size, b.getShort(4).toInt())
        sizes.forEachIndexed { i, size ->
            val entry = 6 + i * 16
            assertEquals(size % 256, ico[entry].toInt() and 0xFF)
            val length = b.getInt(entry + 8)
            val offset = b.getInt(entry + 12)
            assertArrayEquals(pngMagic, ico.copyOfRange(offset, offset + 4))
            val img = ImageIO.read(ico.copyOfRange(offset, offset + length).inputStream())
            assertEquals(size, img.width)
        }
    }

    @Test
    fun icnsChunksAddUpAndHoldPngs() {
        val icns = IconExport.icns()
        val b = ByteBuffer.wrap(icns)
        assertEquals("icns", String(icns, 0, 4))
        assertEquals(icns.size, b.getInt(4))
        var pos = 8
        val types = ArrayList<String>()
        while (pos < icns.size) {
            types.add(String(icns, pos, 4))
            val len = b.getInt(pos + 4)
            assertArrayEquals(pngMagic, icns.copyOfRange(pos + 8, pos + 12))
            pos += len
        }
        assertEquals(icns.size, pos)
        assertTrue(types.containsAll(listOf("ic07", "ic08", "ic09", "ic10", "icp4", "icp5")))
    }

    @Test
    fun iconIsOpaqueInTheMiddleAndClearAtTheCorners() {
        val img = com.readarea.desktop.ui.theme.AppIcon.image(128)
        assertEquals(0, img.getRGB(0, 0) ushr 24)
        assertEquals(255, img.getRGB(64, 64) ushr 24)
    }
}
