package com.readarea.desktop.qa

import com.readarea.core.format.TestBooks
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Books of every format, with real covers and text, for trying the app and its screenshots. */
object SampleLibrary {
    private val titles = listOf(
        Triple("The Lighthouse Keeper", "Mara Ellison", Color(0x2E4057)),
        Triple("Salt and Cedar", "Jonah Reyes", Color(0x9A5B34)),
        Triple("A Field Guide to Quiet", "Ines Varga", Color(0x4F7A5A)),
        Triple("Winter Letters", "Theo Marchetti", Color(0x5E5CA8)),
        Triple("The Cartographer's Daughter", "Amara Osei", Color(0xB0463C)),
        Triple("Small Hours", "Lena Fischer", Color(0x3F6E8C)),
    )

    fun cover(title: String, author: String, color: Color, w: Int = 600, h: Int = 900): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, color.brighter(), 0f, h.toFloat(), color.darker().darker())
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 255, 255, 40)
        for (i in 0 until 12) g.fillOval(w / 2 - 40 * i, h / 2 - 40 * i + 120, 80 * i, 80 * i)
        g.color = Color(0xF7EFE2)
        g.font = Font(Font.SERIF, Font.BOLD, 58)
        var y = 220
        val words = title.split(' ')
        var line = ""
        for (word in words) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (g.fontMetrics.stringWidth(next) > w - 100) {
                g.drawString(line, (w - g.fontMetrics.stringWidth(line)) / 2, y)
                y += 72
                line = word
            } else line = next
        }
        g.drawString(line, (w - g.fontMetrics.stringWidth(line)) / 2, y)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
        g.drawString(author.uppercase(), (w - g.fontMetrics.stringWidth(author.uppercase())) / 2, h - 90)
        g.dispose()
        return png(img)
    }

    fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun jpeg(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()

    fun create(dir: File): File {
        dir.mkdirs()
        titles.forEachIndexed { i, (t, a, c) ->
            File(dir, t.replace(' ', '_').replace("'", "") + ".epub").writeBytes(TestBooks.epub(chapters = 4 + i % 3, paragraphs = 18, title = t, author = a, coverImage = cover(t, a, c)))
        }
        File(dir, "Untitled draft.epub").writeBytes(TestBooks.epub(chapters = 2, paragraphs = 10, withCover = false, title = "Notes Toward a Garden", author = "Pia Lindqvist"))
        File(dir, "arabic").mkdirs()
        File(dir, "arabic/الرحلة.epub").writeBytes(arabicEpub())
        File(dir, "japanese").mkdirs()
        File(dir, "japanese/吾輩は猫である.epub").writeBytes(japaneseEpub())
        File(dir, "Field Notes.md").writeText(markdown())
        File(dir, "img").mkdirs()
        File(dir, "img/sketch.png").writeBytes(cover("Sketch", "", Color(0x4F7A5A), 400, 260))
        File(dir, "A Short Letter.txt").writeText("A Short Letter\n\nDear friend,\n\n" + (1..12).joinToString("\n\n") { TestBooks.lorem(70, it) } + "\n\nYours, M.")
        File(dir, "Atlas of Clouds.pdf").writeBytes(pdf())
        File(dir, "Moonlit Harbor.cbz").writeBytes(comic())
        File(dir, "Report.docx").writeBytes(TestBooks.docx("Quarterly Garden Report", "Pia Lindqvist"))
        File(dir, "Folk Tales.fb2").writeText(fb2())
        return dir
    }

    private fun arabicEpub(): ByteArray {
        val para = "كانت السفينة تبحر ببطء نحو الميناء القديم، والشمس تغيب خلف الجبال البعيدة. وقف البحار على السطح يتأمل الأفق ويتذكر حكايات جدته عن المدن التي لا تنام."
        val body = (1..14).joinToString("") { "<p>$para</p>" }
        return epubWith("الرحلة", "سلمى الحداد", "ar", "rtl", listOf("<h1>الفصل الأول</h1>$body", "<h1>الفصل الثاني</h1>$body"), cover("The Voyage", "Salma Haddad", Color(0x7D4E7A)))
    }

    private fun japaneseEpub(): ByteArray {
        val p = "<p><ruby>吾輩<rt>わがはい</rt></ruby>は<ruby>猫<rt>ねこ</rt></ruby>である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。吾輩はここで始めて人間というものを見た。</p>"
        val body = (1..16).joinToString("") { p }
        return epubWith("吾輩は猫である", "夏目漱石", "ja", "rtl", listOf("<h1>一</h1>$body", "<h1>二</h1>$body"), cover("I Am a Cat", "Natsume Soseki", Color(0x1F1F24)), vertical = true)
    }

    private fun epubWith(title: String, author: String, lang: String, dir: String, chapters: List<String>, cover: ByteArray, vertical: Boolean = false): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        files["mimetype"] = "application/epub+zip".toByteArray()
        files["META-INF/container.xml"] = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()
        val manifest = StringBuilder("""<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        val spine = StringBuilder()
        val nav = StringBuilder()
        chapters.forEachIndexed { i, c ->
            manifest.append("""<item id="c$i" href="c$i.xhtml" media-type="application/xhtml+xml"/>""")
            spine.append("""<itemref idref="c$i"/>""")
            nav.append("""<li><a href="c$i.xhtml">${Regex("<h1>(.*?)</h1>").find(c)?.groupValues?.get(1) ?: "$i"}</a></li>""")
            files["OEBPS/c$i.xhtml"] = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" lang="$lang" dir="$dir"><head><title>$title</title>${if (vertical) "<style>html{writing-mode:vertical-rl}</style>" else ""}</head><body>$c</body></html>""".toByteArray()
        }
        files["OEBPS/cover.png"] = cover
        files["OEBPS/nav.xhtml"] = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>""".toByteArray()
        files["OEBPS/content.opf"] = """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:language>$lang</dc:language>${if (vertical) "<meta name=\"primary-writing-mode\" content=\"vertical-rl\"/>" else ""}</metadata><manifest>$manifest</manifest><spine page-progression-direction="$dir">$spine</spine></package>""".toByteArray()
        return TestBooks.zip(files)
    }

    private fun markdown(): String = """
        # Field Notes

        Observations from a *summer* spent walking the coast, with a sketch or two.

        ## The tide pools

        ${TestBooks.lorem(90, 3)}

        ![A sketch](img/sketch.png)

        > ${TestBooks.lorem(30, 4)}

        ## Birds

        - Oystercatchers, loud and busy
        - A single heron, patient as ever
        - Terns, diving over the bay

        ${TestBooks.lorem(120, 5)}
    """.trimIndent()

    private fun fb2(): String {
        val sections = (1..3).joinToString("") { i -> "<section><title><p>Tale $i</p></title>" + (1..8).joinToString("") { "<p>${TestBooks.lorem(60, i * 10 + it)}</p>" } + "</section>" }
        return """<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Folk Tales of the North</book-title><author><first-name>Anna</first-name><last-name>Korhonen</last-name></author><lang>en</lang></title-info></description><body>$sections</body></FictionBook>"""
    }

    private fun pdf(): ByteArray {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN)
            val bold = PDType1Font(Standard14Fonts.FontName.TIMES_BOLD)
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            doc.documentInformation.title = "Atlas of Clouds"
            doc.documentInformation.author = "R. Okafor"
            for (p in 0 until 8) {
                val page = PDPage(PDRectangle.A5)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(bold, 20f)
                    cs.newLineAtOffset(50f, 540f)
                    cs.showText(if (p == 0) "Atlas of Clouds" else "Chapter $p")
                    cs.setFont(font, 11f)
                    cs.setLeading(15f)
                    cs.newLineAtOffset(0f, -30f)
                    val words = TestBooks.lorem(320, p).split(' ')
                    var line = StringBuilder()
                    for (w in words) {
                        if (line.length + w.length > 62) {
                            cs.showText(line.toString())
                            cs.newLine()
                            line = StringBuilder()
                        }
                        line.append(w).append(' ')
                    }
                    cs.showText(line.toString())
                    cs.endText()
                    cs.setNonStrokingColor(0.6f, 0.75f, 0.9f)
                    cs.addRect(50f, 60f, 320f, 90f)
                    cs.fill()
                }
                if (p > 0) outline.addLast(PDOutlineItem().apply { title = "Chapter $p"; destination = PDPageFitDestination().apply { setPage(page) } })
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun comic(): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        for (i in 1..6) {
            val img = BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0xFDF6E3)
            g.fillRect(0, 0, 800, 1200)
            g.color = Color(0x1B2838)
            for (row in 0 until 3) for (col in 0 until 2) {
                g.drawRect(40 + col * 370, 40 + row * 380, 350, 360)
                g.color = Color.getHSBColor((i * 0.13f + row * 0.2f + col * 0.1f) % 1f, 0.35f, 0.9f)
                g.fillOval(90 + col * 370, 100 + row * 380, 250, 200)
                g.color = Color(0x1B2838)
            }
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 40)
            g.drawString("Page $i", 330, 1170)
            g.dispose()
            files["page_${i}.jpg"] = jpeg(img)
        }
        return TestBooks.zip(files)
    }
}
