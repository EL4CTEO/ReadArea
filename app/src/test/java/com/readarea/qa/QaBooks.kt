package com.readarea.qa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.readarea.core.format.TestBooks
import java.io.ByteArrayOutputStream

object QaBooks {
    private val sentences = listOf(
        "The lighthouse keeper climbed the spiral stairs before dawn, counting each step out of habit.",
        "Salt had crept into every seam of the old door, and it groaned whenever the wind changed.",
        "She kept a notebook of passing ships, their names written in a small, careful hand.",
        "“You’ll never see the same sea twice,” her grandmother used to say — and she was right.",
        "By noon the fog had lifted, leaving the harbour bright and strangely quiet.",
        "Nobody in the village remembered when the lighthouse had last gone dark.",
        "He folded the letter twice, then a third time, as if that might make it lighter.",
        "The kettle whistled; outside, gulls argued over the remains of a fisherman’s lunch.",
        "Every winter the storms rearranged the beach, and every spring the children claimed it again.",
        "There is a kind of patience that only people who live beside the ocean understand.",
        "The map was older than the town hall and far more honest about the rocks.",
        "At night the beam swept the water in long, slow circles, steady as a heartbeat.",
        "Her brother arrived on the Tuesday ferry with two suitcases and no plan at all.",
        "Some books are read once; others are lived in, like a house with many rooms.",
        "The café by the pier served tea strong enough to stand a spoon in.",
        "In the attic they found a brass telescope wrapped in a moth-eaten blanket.",
        "Rain drummed on the roof until it became a sound you stopped hearing.",
        "The mayor gave a speech that everyone agreed was far too long.",
        "When the power failed, the whole street gathered in the lighthouse to wait it out.",
        "Years later, she would remember that summer as the one when everything began.",
    )

    fun paragraph(seed: Int, n: Int = 4): String = (0 until n).joinToString(" ") { sentences[(seed * 7 + it * 3) % sentences.size] }

    fun image(w: Int, h: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    fun realisticEpub(title: String = "The Harbour Light", chapters: Int = 5): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        files["mimetype"] = "application/epub+zip".toByteArray()
        files["META-INF/container.xml"] = "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>".toByteArray()
        val manifest = StringBuilder("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/><item id=\"fig\" href=\"images/fig.png\" media-type=\"image/png\"/><item id=\"notes\" href=\"notes.xhtml\" media-type=\"application/xhtml+xml\"/>")
        val spine = StringBuilder()
        val nav = StringBuilder()
        for (c in 1..chapters) {
            manifest.append("<item id=\"c$c\" href=\"ch$c.xhtml\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"c$c\"/>")
            nav.append("<li><a href=\"ch$c.xhtml\">Chapter $c</a></li>")
            val body = StringBuilder("<h1 class=\"chapter\">Chapter $c<br/><span class=\"subtitle\">Part of the tide</span></h1>")
            body.append("<p class=\"first\"><span class=\"dropcap\">T</span>his chapter opens with a quiet morning. ").append(paragraph(c)).append("</p>")
            for (p in 1..24) {
                when (p) {
                    5 -> body.append("<blockquote><p>").append(paragraph(c + p, 2)).append("</p></blockquote>")
                    9 -> body.append("<ul><li>").append(paragraph(c + p, 1)).append("</li><li>Nets, rope and a lantern</li><li>").append(paragraph(c + p + 1, 1)).append("</li></ul>")
                    12 -> body.append("<div class=\"figure\"><img src=\"images/fig.png\" alt=\"A map\"/><p class=\"caption\">Figure $c. The harbour at low tide.</p></div>")
                    15 -> body.append("<h2 id=\"s$c\">A change in the weather</h2>")
                    else -> {
                        body.append("<p>").append(paragraph(c * 31 + p))
                        if (p == 3) body.append(" The fisher&shy;man’s cot&shy;tage stood at 12&#160;Harbour&#160;Row.<a href=\"notes.xhtml#n$c\" epub:type=\"noteref\"><sup>$c</sup></a>")
                        if (p == 7) body.append("<br/>A second line, set apart.")
                        if (p == 20) body.append(" <span class=\"sc\">Small caps</span> and <em>emphasis</em> and <strong>strong words</strong> sit side by side.")
                        body.append("</p>")
                    }
                }
            }
            files["OEBPS/ch$c.xhtml"] = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Chapter $c</title><link rel="stylesheet" href="style.css"/></head>
<body>$body</body></html>""".toByteArray()
        }
        files["OEBPS/notes.xhtml"] = ("<html xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>" + (1..chapters).joinToString("") { "<aside id=\"n$it\" epub:type=\"footnote\"><p>Note $it: the address was later renamed.</p></aside>" } + "</body></html>").toByteArray()
        files["OEBPS/style.css"] = "h1.chapter { text-align: center; text-transform: uppercase; } .subtitle { font-style: italic; font-size: 0.7em; } p.first { text-indent: 0; } .dropcap { font-size: 2.2em; } .sc { font-variant: small-caps; } .caption { text-align: center; font-size: 0.85em; }".toByteArray()
        files["OEBPS/images/fig.png"] = image(900, 500, Color.rgb(70, 110, 140))
        manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
        files["OEBPS/nav.xhtml"] = "<html xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol>$nav</ol></nav></body></html>".toByteArray()
        files["OEBPS/content.opf"] = """<package version="3.0"><metadata><dc:title>$title</dc:title><dc:creator>Mara Quill</dc:creator><dc:language>en</dc:language></metadata>
<manifest>$manifest</manifest><spine>$spine<itemref idref="notes" linear="no"/></spine></package>""".toByteArray()
        return TestBooks.zip(files)
    }
}
