package com.readarea.desktop.security

import com.readarea.core.book.LocalImages
import com.readarea.desktop.book.Images
import com.readarea.desktop.platform.SingleInstance
import com.readarea.desktop.platform.SpeechEngine
import com.readarea.desktop.platform.SystemIntegration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

/** Attacks on the desktop app's own surface: links in books, the single-instance socket, speech, images. */
class DesktopSecurityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun onlyWebAndEmailLinksOpen() {
        for (bad in listOf(
            "file:///etc/passwd", "FILE:///C:/Windows/System32/calc.exe", "javascript:alert(1)", "data:text/html,<script>x</script>",
            "ms-settings:privacy", "smb://attacker/share", "\\\\attacker\\share\\x", "vbscript:msgbox", "jar:file:/x!/y", "about:blank",
            "http:///nohost", "https://", "  ", "chrome://settings", "x-apple.systempreferences:", "tel:123", "http://" + "a".repeat(3000) + ".com",
        )) assertNull(bad, SystemIntegration.safeLink(bad))
        assertEquals("https://example.com/a?b=c", SystemIntegration.safeLink("  https://example.com/a?b=c "))
        assertEquals("mailto:someone@example.com", SystemIntegration.safeLink("mailto:someone@example.com"))
    }

    @Test
    fun linksAreShownWithEveryCharacterVisible() {
        // A right-to-left override could make "https://example.com/‮gpj.exe" read as ending in ".jpg".
        val shown = SystemIntegration.safeLink("https://example.com/\u202Egpj.exe")!!
        assertTrue(shown, shown.all { it.code in 0x21..0x7E })
        assertTrue(shown, shown.contains("%E2%80%AE"))
        // Hosts must be plain ASCII: a look-alike Unicode host can't be opened at all.
        assertNull(SystemIntegration.safeLink("https://аpple.com/"))
    }

    @Test
    fun bookTextCantCommandTheSpeechEngine() {
        val cleaned = SpeechEngine.clean("Hello [[rate 900]] world [[inpt PHON]]" + "[[" + "x".repeat(80) + "]] \u0007bell\u0000 end")
        assertFalse(cleaned, cleaned.contains("[["))
        assertFalse(cleaned, cleaned.any { it.code < 0x20 })
        assertTrue(cleaned, cleaned.startsWith("Hello") && cleaned.endsWith("end"))
    }

    private fun listen(dir: File, got: MutableList<List<File>>) = SingleInstance(dir, requestTimeoutMs = 400).also { assertTrue(it.listen { files -> got.add(files) }) }

    @Test
    fun singleInstanceTakesRequestsOnlyWithItsToken() {
        val dir = tmp.newFolder("data")
        val got = CopyOnWriteArrayList<List<File>>()
        val book = tmp.newFile("book.epub").apply { writeText("x") }
        listen(dir, got).use {
            assertTrue(SingleInstance(dir).forward(listOf(book, File("/definitely/missing.epub"))))
            Thread.sleep(200)
            assertEquals(listOf(listOf(book.absoluteFile)), got.toList())
            // The socket and token are for this user only.
            if (File("/").canRead() && java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, "instance.token").toPath())))
            }
            fun raw(bytes: ByteArray) = runCatching {
                SocketChannel.open(UnixDomainSocketAddress.of(File(dir, "instance.sock").toPath())).use { ch ->
                    ch.write(ByteBuffer.wrap(bytes))
                    val reply = ByteBuffer.allocate(8)
                    ch.read(reply)
                    String(reply.array(), 0, reply.position())
                }
            }.getOrDefault("")
            assertEquals("", raw("READAREA1\nwrong-token\n${book.path}\n\n".toByteArray()))
            assertEquals("", raw(ByteArray(70_000) { 'a'.code.toByte() }))
            // A client that never finishes its request is cut off after the timeout instead of held forever.
            val start = System.currentTimeMillis()
            assertEquals("", raw("READAREA1\n".toByteArray()))
            assertTrue(System.currentTimeMillis() - start < 3000)
            Thread.sleep(200)
            assertEquals(1, got.size)
        }
    }

    /** A PNG whose header claims a size far beyond what's decoded, with almost no pixel data. */
    private fun pngClaiming(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val body = type.toByteArray() + data
            out.write(body)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(body) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array())
        chunk("IDAT", java.util.zip.Deflater().run { setInput(ByteArray(1000)); finish(); val b = ByteArray(2000); ByteArray(deflate(b)).also { System.arraycopy(b, 0, it, 0, it.size) } })
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun imageBombsAreNotDecoded() {
        val start = System.currentTimeMillis()
        assertNull(Images.size(pngClaiming(100_000, 100_000)))
        assertNull(Images.decode(pngClaiming(100_000, 100_000)))
        assertNull(Images.decode(pngClaiming(Int.MAX_VALUE, 1)))
        // Within the size limit but truncated data: a clean failure, not a 1 GB allocation.
        val img = Images.decode(pngClaiming(16_000, 16_000))
        assertTrue(img == null || img.width.toLong() * img.height <= Images.MAX_PIXELS)
        assertTrue(System.currentTimeMillis() - start < 10_000)
    }

    @Test
    fun localImagesStayInsideTheBooksFolder() {
        val root = tmp.newFolder("books")
        File(root, "pics").mkdirs()
        val inside = File(root, "pics/a.png").apply { writeBytes(pngClaiming(1, 1)) }
        val secret = tmp.newFile("secret.png").apply { writeBytes(pngClaiming(1, 1)) }
        val link = File(root, "pics/link.png")
        runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }
        val images = LocalImages(root, "index.md")
        assertTrue(images.read("pics/a.png") != null)
        for (path in listOf("../secret.png", "pics/../../secret.png", secret.absolutePath, "/etc/passwd", "file:///etc/passwd", "C:\\Windows\\win.ini", "pics/a.png:stream", "pics/link.png", "pics/a.txt", "")) {
            assertNull(path, images.read(path))
        }
        assertTrue(inside.isFile)
    }
}
