package com.readarea.desktop.tools

import com.readarea.desktop.ui.theme.AppIcon
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Writes the app icon in every format the installers need, from the one vector drawing: PNGs for Linux,
 * an .ico for Windows and an .icns for macOS. Run at build time: `IconExport <output dir>`.
 */
object IconExport {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val out = File(args.firstOrNull() ?: error("usage: IconExport <output dir>")).apply { mkdirs() }
        for (size in listOf(16, 32, 48, 64, 128, 256, 512)) File(out, "ReadArea-$size.png").writeBytes(png(size))
        File(out, "ReadArea.png").writeBytes(png(512))
        File(out, "ReadArea.ico").writeBytes(ico(listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)))
        File(out, "ReadArea.icns").writeBytes(icns())
    }

    fun png(size: Int, margin: Float = 0.06f): ByteArray = ByteArrayOutputStream().also { ImageIO.write(AppIcon.image(size, margin), "png", it) }.toByteArray()

    /** An ICO with PNG-compressed images, which Windows has read since Vista. */
    fun ico(sizes: List<Int>): ByteArray {
        val images = sizes.map { png(it) }
        val out = ByteArrayOutputStream()
        val le = LittleEndian(out)
        le.short(0)
        le.short(1)
        le.short(sizes.size)
        var offset = 6 + 16 * sizes.size
        sizes.forEachIndexed { i, size ->
            out.write(if (size >= 256) 0 else size)
            out.write(if (size >= 256) 0 else size)
            out.write(0)
            out.write(0)
            le.short(1)
            le.short(32)
            le.int(images[i].size)
            le.int(offset)
            offset += images[i].size
        }
        images.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** An ICNS with PNG entries for every size and density Finder and the Dock ask for. */
    fun icns(): ByteArray {
        val margin = 0.098f
        val entries = listOf(
            "icp4" to 16, "icp5" to 32, "icp6" to 64, "ic07" to 128, "ic08" to 256, "ic09" to 512,
            "ic10" to 1024, "ic11" to 32, "ic12" to 64, "ic13" to 256, "ic14" to 512,
        ).map { (type, size) -> type to png(size, margin) }
        val body = ByteArrayOutputStream()
        val data = DataOutputStream(body)
        for ((type, bytes) in entries) {
            data.writeBytes(type)
            data.writeInt(bytes.size + 8)
            data.write(bytes)
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeBytes("icns")
            writeInt(body.size() + 8)
            write(body.toByteArray())
        }
        return out.toByteArray()
    }

    private class LittleEndian(private val out: ByteArrayOutputStream) {
        fun short(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        fun int(v: Int) {
            short(v and 0xFFFF)
            short((v ushr 16) and 0xFFFF)
        }
    }
}
