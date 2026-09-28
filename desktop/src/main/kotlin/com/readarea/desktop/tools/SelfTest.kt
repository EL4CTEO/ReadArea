package com.readarea.desktop.tools

import com.readarea.core.book.BookOpener
import com.readarea.core.format.BookFormat
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Checks that a packaged build has everything the app relies on: the trimmed Java runtime still has
 * the charsets and locale data books need, the SQLite native library loads, PDFs render, image codecs
 * are registered and books parse. Installers run it in CI; `ReadArea --self-test=report.txt` writes a
 * report and exits with 0 only if every check passed. It touches nothing outside a temporary folder.
 */
object SelfTest {
    fun run(report: File?): Boolean {
        System.setProperty("java.awt.headless", "true")
        val tmp = Files.createTempDirectory("readarea-selftest").toFile()
        System.setProperty("org.sqlite.tmpdir", System.getProperty("org.sqlite.tmpdir") ?: tmp.path)
        val lines = ArrayList<String>()
        var ok = true
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            ok = ok && result.isSuccess
            lines.add(result.fold({ "PASS $name: $it" }, { "FAIL $name: ${it::class.java.simpleName}: ${it.message}" }))
        }
        try {
            check("runtime") {
                require(Runtime.version().feature() >= 21) { "Java ${Runtime.version()}" }
                "Java ${Runtime.version()} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
            }
            check("charsets") {
                // Plain-text and FB2 books still come in these; they live in the jdk.charsets module.
                val names = listOf("windows-1251", "windows-1252", "windows-1256", "KOI8-R", "ISO-8859-7", "Shift_JIS", "EUC-JP", "EUC-KR", "GBK", "GB18030", "Big5")
                val missing = names.filter { !Charset.isSupported(it) }
                require(missing.isEmpty()) { "missing $missing" }
                "${names.size} legacy charsets"
            }
            check("locale data") {
                // Day names and digits in the app's languages come from jdk.localedata.
                val english = DayOfWeek.MONDAY.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                val tags = listOf("ar", "de", "hi", "ja", "ru", "zh")
                val missing = tags.filter { DayOfWeek.MONDAY.getDisplayName(TextStyle.FULL, Locale.forLanguageTag(it)) == english }
                require(missing.isEmpty()) { "no data for $missing" }
                tags.joinToString { DayOfWeek.MONDAY.getDisplayName(TextStyle.FULL, Locale.forLanguageTag(it)) }
            }
            check("sqlite") {
                // The library is a SQLite file in WAL mode; this loads the bundled native library.
                DriverManager.getConnection("jdbc:sqlite:" + File(tmp, "test.db").path).use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("PRAGMA journal_mode=WAL").use { rs -> rs.next(); require(rs.getString(1).equals("wal", true)) { "no WAL" } }
                        st.executeUpdate("CREATE TABLE t(x TEXT)")
                        st.executeUpdate("INSERT INTO t VALUES ('ok')")
                        st.executeQuery("SELECT sqlite_version(), (SELECT x FROM t)").use { rs -> rs.next(); "SQLite ${rs.getString(1)}, WAL" }
                    }
                }
            }
            check("pdf") {
                val bytes = java.io.ByteArrayOutputStream().also { out ->
                    org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
                        val page = org.apache.pdfbox.pdmodel.PDPage()
                        doc.addPage(page)
                        org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                            cs.beginText()
                            cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.TIMES_ROMAN), 48f)
                            cs.newLineAtOffset(72f, 600f)
                            cs.showText("ReadArea")
                            cs.endText()
                        }
                        doc.save(out)
                    }
                }.toByteArray()
                val file = File(tmp, "test.pdf").apply { writeBytes(bytes) }
                com.readarea.desktop.book.PdfSource.open(file).use { src ->
                    val img = src.render(0, 300, 400)
                    val ink = (0 until img.height step 2).sumOf { y -> (0 until img.width step 2).count { x -> img.getRGB(x, y) and 0xFFFFFF != 0xFFFFFF } }
                    require(ink > 20) { "page rendered blank" }
                    "rendered ${img.width}x${img.height}, $ink ink samples"
                }
            }
            check("image codecs") {
                val formats = listOf("png", "jpeg", "gif", "bmp", "webp")
                val missing = formats.filter { !ImageIO.getImageReadersByFormatName(it).hasNext() }
                require(missing.isEmpty()) { "no reader for $missing" }
                val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, Color.RED.rgb) }
                val jpeg = java.io.ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
                require(ImageIO.read(jpeg.inputStream()) != null) { "JPEG round trip failed" }
                formats.joinToString()
            }
            check("books") {
                val fb2 = File(tmp, "test.fb2")
                fb2.writeText("""<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Проверка</book-title></title-info></description><body><section><title><p>Глава</p></title><p>Текст книги.</p></section></body></FictionBook>""")
                BookOpener.open(fb2, BookFormat.FB2).use { b ->
                    require(b.book.meta.title == "Проверка") { "title ${b.book.meta.title}" }
                    require(b.book.chapters.isNotEmpty()) { "no chapters" }
                    "FB2 parsed: ${b.book.chapters.size} chapter(s)"
                }
            }
            check("fonts") {
                val serif = Font(Font.SERIF, Font.PLAIN, 12)
                require(serif.canDisplay('A')) { "no usable fonts" }
                val families = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.size
                "$families font families"
            }
        } finally {
            tmp.deleteRecursively()
        }
        lines.add(if (ok) "OK" else "FAILED")
        val text = lines.joinToString("\n", postfix = "\n")
        if (report != null) report.writeText(text) else print(text)
        return ok
    }
}
