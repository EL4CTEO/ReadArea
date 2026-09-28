package com.readarea.core.format

class DocxParser(private val zip: ZipAccess, private val fallbackTitle: String) {

    fun parse(metadataOnly: Boolean = false): ParsedBook {
        val core = zip.read("docProps/core.xml")?.let { XmlNode.parse(TextDecoder.decode(it)) }
        val title = core?.find("title")?.text?.trim()?.ifEmpty { null } ?: fallbackTitle
        val author = core?.find("creator")?.text?.trim().orEmpty()
        val rels = HashMap<String, String>()
        zip.read("word/_rels/document.xml.rels")?.let { XmlNode.parse(TextDecoder.decode(it)) }?.findAll("relationship")?.forEach { r ->
            val id = r["id"] ?: return@forEach
            val target = r["target"] ?: return@forEach
            rels[id] = if (r["targetmode"].equals("external", true)) target else PathUtil.resolve("word/document.xml", target)
        }
        val xml = zip.read("word/document.xml")?.let { TextDecoder.decode(it) }
        val meta = BookMeta(title = title, author = author, coverRef = findCover(xml, rels))
        if (metadataOnly) return ParsedBook(meta, emptyList(), emptyList(), zip)
        if (xml == null) throw BookParseException(ParseError.INVALID, "Invalid DOCX document")
        val headingLevels = parseStyles()
        val html = toHtml(xml, rels, headingLevels)
        val blocks = HtmlConverter("word/document.xml").convert(html)
        return ParsedBook(meta, listOf(Chapter(title, "word/document.xml", blocks)), emptyList(), zip)
    }

    private fun findCover(xml: String?, rels: Map<String, String>): String? {
        if (xml != null) {
            val m = CoverImages.firstTagAttr(xml, "a:blip", "r:embed")
            if (m != null) {
                val textBefore = CoverImages.docxTextBefore(xml, m.start)
                val target = rels[m.value]
                if (textBefore < 150 && target != null && CoverImages.isRaster(target)) return target
            }
        }
        return zip.entries.firstOrNull { it.startsWith("docProps/thumbnail", true) && CoverImages.isRaster(it) }
    }

    private fun parseStyles(): Map<String, Int> {
        val out = HashMap<String, Int>()
        val root = zip.read("word/styles.xml")?.let { XmlNode.parse(TextDecoder.decode(it)) } ?: return out
        for (st in root.findAll("style")) {
            val id = st["styleid"] ?: continue
            val name = st.child("name")?.get("val")?.lowercase() ?: ""
            val outline = st.find("outlinelvl")?.get("val")?.toIntOrNull()
            val level = when {
                name == "title" -> 1
                name.startsWith("heading") -> name.filter { it.isDigit() }.toIntOrNull()
                outline != null && outline < 6 -> outline + 1
                else -> null
            }
            if (level != null) out[id] = level.coerceIn(1, 6)
        }
        return out
    }

    /** Word's built-in style ids, for documents written without a styles part. */
    private fun builtInHeading(styleId: String): Int? {
        val id = styleId.lowercase()
        return when {
            id == "title" -> 1
            id.startsWith("heading") -> id.removePrefix("heading").toIntOrNull()?.coerceIn(1, 6)
            else -> null
        }
    }

    private fun toHtml(xml: String, rels: Map<String, String>, headings: Map<String, Int>): String {
        val out = StringBuilder()
        val para = StringBuilder()
        var pStyle: String? = null
        var align: String? = null
        var isList = false
        var inPPr = false
        var inRPr = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var vert: String? = null
        var inText = false
        var skipDepth = 0
        var link: String? = null
        HtmlTokenizer(xml).parse(object : HtmlHandler {
            override fun startTag(name: String, attrs: Attributes, selfClosing: Boolean) {
                if (skipDepth > 0) {
                    if (!selfClosing) skipDepth++
                    return
                }
                when (name) {
                    "p" -> if (!selfClosing) {
                        para.setLength(0)
                        pStyle = null
                        align = null
                        isList = false
                    }
                    "ppr" -> inPPr = !selfClosing
                    "rpr" -> if (!inPPr) inRPr = !selfClosing
                    "pstyle" -> pStyle = attrs["val"]
                    "jc" -> if (inPPr) align = attrs["val"]
                    "numpr" -> if (inPPr) isList = true
                    "r" -> if (!selfClosing) {
                        bold = false; italic = false; underline = false; strike = false; vert = null
                    }
                    "b" -> if (inRPr) bold = on(attrs)
                    "i" -> if (inRPr) italic = on(attrs)
                    "u" -> if (inRPr) underline = attrs["val"] != "none"
                    "strike", "dstrike" -> if (inRPr) strike = on(attrs)
                    "vertalign" -> if (inRPr) vert = attrs["val"]
                    "t" -> inText = !selfClosing
                    "tab" -> if (!inPPr) para.append(' ')
                    "br", "cr" -> if (attrs["type"] != "page") para.append("<br/>")
                    "nobreakhyphen" -> para.append('‑')
                    "softhyphen" -> para.append('­')
                    "hyperlink" -> link = attrs["id"]?.let { rels[it] } ?: attrs["anchor"]?.let { "#$it" }
                    "bookmarkstart" -> attrs["name"]?.let { para.append("<a id=\"").append(Fb2Parser.esc(it)).append("\"></a>") }
                    "blip" -> attrs["embed"]?.let { rels[it] }?.let { para.append("<img src=\"/").append(it).append("\"/>") }
                    "imagedata" -> attrs["id"]?.let { rels[it] }?.let { para.append("<img src=\"/").append(it).append("\"/>") }
                    "del", "instrtext", "deltext", "footnotereference", "endnotereference", "commentreference", "fallback" -> if (!selfClosing) skipDepth = 1
                }
            }

            override fun endTag(name: String) {
                if (skipDepth > 0) {
                    skipDepth--
                    return
                }
                when (name) {
                    "ppr" -> inPPr = false
                    "rpr" -> inRPr = false
                    "t" -> inText = false
                    "hyperlink" -> link = null
                    "p" -> {
                        val content = para.toString()
                        val level = pStyle?.let { headings[it] ?: builtInHeading(it) }
                        val style = when (align) {
                            "center" -> " style=\"text-align:center\""
                            "right", "end" -> " style=\"text-align:right\""
                            "both", "distribute" -> " style=\"text-align:justify\""
                            else -> ""
                        }
                        when {
                            level != null -> out.append("<h$level$style>").append(content).append("</h$level>\n")
                            isList -> out.append("<ul><li>").append(content).append("</li></ul>\n")
                            content.isEmpty() -> out.append("<p>&#160;</p>\n")
                            else -> out.append("<p$style>").append(content).append("</p>\n")
                        }
                        para.setLength(0)
                    }
                }
            }

            override fun text(text: String) {
                if (skipDepth > 0 || !inText) return
                var t = Fb2Parser.esc(text)
                if (bold) t = "<b>$t</b>"
                if (italic) t = "<i>$t</i>"
                if (underline && link == null) t = "<u>$t</u>"
                if (strike) t = "<s>$t</s>"
                when (vert) {
                    "superscript" -> t = "<sup>$t</sup>"
                    "subscript" -> t = "<sub>$t</sub>"
                }
                link?.let { t = "<a href=\"${Fb2Parser.esc(it)}\">$t</a>" }
                para.append(t)
            }
        })
        return out.toString()
    }

    private fun on(attrs: Attributes): Boolean {
        val v = attrs["val"] ?: return true
        return v != "0" && v != "false" && v != "off"
    }
}
