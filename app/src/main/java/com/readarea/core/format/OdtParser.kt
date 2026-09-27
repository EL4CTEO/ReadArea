package com.readarea.core.format

class OdtParser(private val zip: ZipAccess, private val fallbackTitle: String) {

    private class Style(var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false, var sup: Boolean = false, var sub: Boolean = false, var align: String? = null, var parent: String? = null)

    fun parse(metadataOnly: Boolean = false): ParsedBook {
        val metaXml = zip.read("meta.xml")?.let { XmlNode.parse(TextDecoder.decode(it)) }
        val title = metaXml?.find("title")?.text?.trim()?.ifEmpty { null } ?: fallbackTitle
        val author = (metaXml?.find("creator") ?: metaXml?.find("initial-creator"))?.text?.trim().orEmpty()
        val content = zip.read("content.xml")?.let { TextDecoder.decode(it) }
        val meta = BookMeta(title = title, author = author, coverRef = findCover(content))
        if (metadataOnly) return ParsedBook(meta, emptyList(), emptyList(), zip)
        if (content == null) throw BookParseException(ParseError.INVALID, "Invalid ODT document")
        val styles = HashMap<String, Style>()
        zip.read("styles.xml")?.let { collectStyles(XmlNode.parse(TextDecoder.decode(it)), styles) }
        val root = XmlNode.parse(content)
        collectStyles(root, styles)
        val body = root.find("text") ?: root.find("body") ?: root
        val sb = StringBuilder()
        body.children.forEach { emit(it, sb, styles) }
        val blocks = HtmlConverter("content.xml").convert(sb.toString())
        return ParsedBook(meta, listOf(Chapter(title, "content.xml", blocks)), emptyList(), zip)
    }

    private fun findCover(content: String?): String? {
        if (content != null) {
            val body = content.indexOf("<office:body").coerceAtLeast(0)
            val m = CoverImages.ODT_IMAGE.find(content, body)
            if (m != null) {
                val href = PathUtil.normalize(PathUtil.decode(m.groupValues[1]))
                if (CoverImages.textLength(content.substring(body, m.range.first)) < 150 && CoverImages.isRaster(href)) return href
            }
        }
        return zip.entries.firstOrNull { it.equals("Thumbnails/thumbnail.png", true) }
    }

    private fun collectStyles(root: XmlNode, out: MutableMap<String, Style>) {
        for (s in root.findAll("style")) {
            val name = s["name"] ?: continue
            val st = Style(parent = s["parent-style-name"])
            s.child("text-properties")?.let { tp ->
                st.bold = tp["font-weight"] == "bold" || (tp["font-weight"]?.toIntOrNull() ?: 0) >= 600
                st.italic = tp["font-style"] == "italic"
                st.underline = tp["text-underline-style"].let { it != null && it != "none" }
                st.strike = tp["text-line-through-style"].let { it != null && it != "none" }
                tp["text-position"]?.let { pos ->
                    st.sup = pos.startsWith("super") || pos.startsWith("3")
                    st.sub = pos.startsWith("sub") || pos.startsWith("-")
                }
            }
            s.child("paragraph-properties")?.let { pp -> st.align = pp["text-align"] }
            out[name] = st
        }
    }

    private fun resolve(name: String?, styles: Map<String, Style>): Style? {
        var cur = name?.let { styles[it] } ?: return null
        val merged = Style(cur.bold, cur.italic, cur.underline, cur.strike, cur.sup, cur.sub, cur.align)
        var guard = 0
        while (cur.parent != null && guard++ < 8) {
            val p = styles[cur.parent!!] ?: break
            if (merged.align == null) merged.align = p.align
            merged.bold = merged.bold || p.bold
            merged.italic = merged.italic || p.italic
            cur = p
        }
        return merged
    }

    private fun emit(n: XmlNode, sb: StringBuilder, styles: Map<String, Style>) {
        when (n.name) {
            "h" -> {
                val level = (n["outline-level"]?.toIntOrNull() ?: 1).coerceIn(1, 6)
                sb.append("<h$level>")
                inline(n, sb, styles)
                sb.append("</h$level>\n")
            }
            "p" -> {
                val st = resolve(n["style-name"], styles)
                val alignStyle = when (st?.align) {
                    "center" -> " style=\"text-align:center\""
                    "end", "right" -> " style=\"text-align:right\""
                    else -> ""
                }
                val isHeading = n["style-name"]?.let { it.startsWith("Heading", true) || it.equals("Title", true) } == true
                if (isHeading) sb.append("<h2>") else sb.append("<p$alignStyle>")
                val start = sb.length
                inline(n, sb, styles, st)
                if (sb.length == start) sb.append("&#160;")
                if (isHeading) sb.append("</h2>\n") else sb.append("</p>\n")
            }
            "list" -> n.children("list-item").forEach { li ->
                sb.append("<ul><li>")
                li.children.forEach { c ->
                    if (c.name == "p" || c.name == "h") inline(c, sb, styles) else emit(c, sb, styles)
                }
                sb.append("</li></ul>\n")
            }
            "frame" -> n.find("image")?.get("href")?.let { sb.append("<img src=\"/").append(it).append("\"/>") }
            "table-of-content", "tracked-changes", "sequence-decls", "note-body", "forms" -> {}
            else -> n.children.forEach { emit(it, sb, styles) }
        }
    }

    private fun inline(n: XmlNode, sb: StringBuilder, styles: Map<String, Style>, base: Style? = null) {
        for (c in n.content) {
            if (c is String) {
                sb.append(Fb2Parser.esc(c))
                continue
            }
            val node = c as XmlNode
            when (node.name) {
                "span" -> {
                    val st = resolve(node["style-name"], styles)
                    val tags = ArrayList<String>()
                    if (st?.bold == true && base?.bold != true) tags.add("b")
                    if (st?.italic == true && base?.italic != true) tags.add("i")
                    if (st?.underline == true) tags.add("u")
                    if (st?.strike == true) tags.add("s")
                    if (st?.sup == true) tags.add("sup")
                    if (st?.sub == true) tags.add("sub")
                    tags.forEach { sb.append('<').append(it).append('>') }
                    inline(node, sb, styles, base)
                    tags.asReversed().forEach { sb.append("</").append(it).append('>') }
                }
                "a" -> {
                    sb.append("<a href=\"").append(Fb2Parser.esc(node["href"] ?: "")).append("\">")
                    inline(node, sb, styles, base)
                    sb.append("</a>")
                }
                "line-break" -> sb.append("<br/>")
                "tab" -> sb.append(' ')
                "s" -> repeat((node["c"]?.toIntOrNull() ?: 1).coerceAtMost(20)) { sb.append("&#160;") }
                "note" -> node.child("note-citation")?.let { sb.append("<sup>").append(Fb2Parser.esc(it.text)).append("</sup>") }
                "frame" -> node.find("image")?.get("href")?.let { sb.append("<img src=\"/").append(it).append("\"/>") }
                "bookmark", "bookmark-start" -> node["name"]?.let { sb.append("<a id=\"").append(Fb2Parser.esc(it)).append("\"></a>") }
                "annotation", "soft-page-break" -> {}
                else -> inline(node, sb, styles, base)
            }
        }
    }
}
