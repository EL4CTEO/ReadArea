package com.readarea.core.format

import java.util.Base64

class Fb2Parser(private val xml: String) {
    private var sectionCounter = 0

    fun parse(metadataOnly: Boolean = false): ParsedBook {
        val root = XmlNode.parse(xml)
        val fb = root.find("fictionbook") ?: root
        val binaries = HashMap<String, () -> ByteArray?>()
        fb.findAll("binary").forEach { b ->
            val id = b["id"] ?: return@forEach
            val data = b.text
            binaries[id] = { runCatching { Base64.getMimeDecoder().decode(data.trim()) }.getOrNull() }
        }
        val resources = MapResources(binaries)
        val info = fb.find("title-info")
        val title = info?.child("book-title")?.text?.trim().orEmpty()
        val authors = info?.children("author").orEmpty().map { a ->
            val parts = listOf("first-name", "middle-name", "last-name").mapNotNull { a.child(it)?.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
            if (parts.isNotEmpty()) parts.joinToString(" ") else a.child("nickname")?.text?.trim().orEmpty()
        }.filter { it.isNotEmpty() }
        val annotation = info?.child("annotation")?.let { ann ->
            ann.children.filter { it.name == "p" }.joinToString("\n") { it.text.trim() }.ifBlank { ann.text.trim() }
        }
        val seq = info?.child("sequence")
        val cover = info?.child("coverpage")?.child("image")?.get("href")?.removePrefix("#")
        val meta = BookMeta(
            title = title,
            author = authors.joinToString(", "),
            language = info?.child("lang")?.text?.trim(),
            description = annotation?.takeIf { it.isNotBlank() },
            series = seq?.get("name")?.takeIf { it.isNotBlank() },
            seriesIndex = seq?.get("number")?.toFloatOrNull(),
            publisher = fb.find("publish-info")?.child("publisher")?.text?.trim(),
            coverRef = cover,
        )
        if (metadataOnly) return ParsedBook(meta, emptyList(), emptyList(), resources)

        val bodies = fb.children("body")
        val chapters = ArrayList<Chapter>()
        val toc = ArrayList<TocItem>()
        bodies.forEachIndexed { bi, body ->
            val isNotes = body["name"] != null
            val sections = body.children("section")
            val preamble = StringBuilder()
            for (c in body.content) {
                if (c is XmlNode && c.name != "section") emit(c, preamble, 1)
            }
            if (sections.isEmpty()) {
                val href = "body$bi"
                val t = if (isNotes) body.child("title")?.text?.trim()?.ifEmpty { null } ?: "Notes" else title
                chapters.add(Chapter(t, href, HtmlConverter(href).convert(preamble.toString())))
                toc.add(TocItem(t, chapters.size - 1, null, 0))
                return@forEachIndexed
            }
            if (isNotes) {
                val sb = StringBuilder(preamble)
                sections.forEach { emitSection(it, sb, 1, null, 0) }
                val href = "body$bi"
                val t = body.child("title")?.text?.replace(WS, " ")?.trim()?.ifEmpty { null } ?: "Notes"
                chapters.add(Chapter(t, href, HtmlConverter(href).convert(sb.toString())))
                toc.add(TocItem(t, chapters.size - 1, null, 0))
                return@forEachIndexed
            }
            sections.forEachIndexed { si, section ->
                val href = "body${bi}_$si"
                val sb = StringBuilder()
                if (si == 0 && preamble.isNotEmpty()) sb.append(preamble)
                val chapterToc = ArrayList<Pair<String, Pair<String, Int>>>()
                emitSection(section, sb, 1, chapterToc, 0)
                val blocks = HtmlConverter(href).convert(sb.toString())
                val chapterTitle = chapterToc.firstOrNull()?.second?.first ?: "${chapters.size + 1}"
                chapters.add(Chapter(chapterTitle, href, blocks))
                val idx = chapters.size - 1
                if (chapterToc.isEmpty()) toc.add(TocItem(chapterTitle, idx, null, 0))
                chapterToc.forEachIndexed { k, (anchor, pair) ->
                    toc.add(TocItem(pair.first, idx, if (k == 0) null else anchor, pair.second))
                }
            }
        }
        return ParsedBook(meta, chapters, toc, resources)
    }

    private fun emitSection(section: XmlNode, sb: StringBuilder, depth: Int, toc: MutableList<Pair<String, Pair<String, Int>>>?, tocDepth: Int) {
        val id = section["id"] ?: "fb2sec${sectionCounter++}"
        sb.append("<div id=\"").append(esc(id)).append("\">")
        val titleNode = section.child("title")
        if (titleNode != null && toc != null) {
            val t = titleNode.children.filter { it.name == "p" }.joinToString(" ") { it.text.trim() }.ifBlank { titleNode.text }.replace(WS, " ").trim()
            if (t.isNotEmpty()) toc.add(id to (t to tocDepth))
        }
        for (c in section.content) {
            when {
                c is String -> {}
                (c as XmlNode).name == "section" -> emitSection(c, sb, depth + 1, toc, tocDepth + 1)
                else -> emit(c, sb, depth)
            }
        }
        sb.append("</div>")
    }

    private fun emit(n: XmlNode, sb: StringBuilder, depth: Int) {
        when (n.name) {
            "title" -> {
                val level = (depth + 1).coerceAtMost(6)
                val lines = n.children.filter { it.name == "p" }
                sb.append("<h$level>")
                if (lines.isEmpty()) inline(n, sb) else lines.forEachIndexed { i, p ->
                    if (i > 0) sb.append("<br/>")
                    inline(p, sb)
                }
                sb.append("</h$level>")
            }
            "subtitle" -> { sb.append("<h6 style=\"text-align:center\">"); inline(n, sb); sb.append("</h6>") }
            "p" -> { sb.append("<p>"); inline(n, sb); sb.append("</p>") }
            "empty-line" -> sb.append("<p>&#160;</p>")
            "epigraph" -> { sb.append("<blockquote style=\"font-style:italic\">"); n.children.forEach { emit(it, sb, depth) }; sb.append("</blockquote>") }
            "cite" -> { sb.append("<blockquote>"); n.children.forEach { emit(it, sb, depth) }; sb.append("</blockquote>") }
            "annotation" -> { sb.append("<blockquote style=\"font-style:italic\">"); n.children.forEach { emit(it, sb, depth) }; sb.append("</blockquote>") }
            "poem" -> { sb.append("<div class=\"poem\">"); n.children.forEach { emit(it, sb, depth) }; sb.append("</div>") }
            "stanza" -> { n.children.forEach { emit(it, sb, depth) }; sb.append("<p>&#160;</p>") }
            "v" -> { sb.append("<p class=\"verse\">"); inline(n, sb); sb.append("</p>") }
            "text-author" -> { sb.append("<p style=\"text-align:right;font-style:italic\">"); inline(n, sb); sb.append("</p>") }
            "date" -> { sb.append("<p style=\"text-align:right\">"); inline(n, sb); sb.append("</p>") }
            "image" -> n["href"]?.let { sb.append("<img src=\"").append(esc(it)).append("\"/>") }
            "table" -> {
                sb.append("<table>")
                n.children("tr").forEach { tr ->
                    sb.append("<tr><p>")
                    tr.children.forEachIndexed { i, td -> if (i > 0) sb.append(" │ "); inline(td, sb) }
                    sb.append("</p></tr>")
                }
                sb.append("</table>")
            }
            else -> n.children.forEach { emit(it, sb, depth) }
        }
    }

    private fun inline(n: XmlNode, sb: StringBuilder) {
        for (c in n.content) {
            if (c is String) {
                sb.append(esc(c))
                continue
            }
            val node = c as XmlNode
            when (node.name) {
                "emphasis" -> wrap("i", node, sb)
                "strong" -> wrap("b", node, sb)
                "strikethrough" -> wrap("s", node, sb)
                "sub" -> wrap("sub", node, sb)
                "sup" -> wrap("sup", node, sb)
                "code" -> wrap("code", node, sb)
                "a" -> {
                    val href = node["href"] ?: ""
                    val note = node["type"] == "note"
                    sb.append("<a href=\"").append(esc(href)).append("\">")
                    if (note) sb.append("<sup>")
                    inline(node, sb)
                    if (note) sb.append("</sup>")
                    sb.append("</a>")
                }
                "image" -> node["href"]?.let { sb.append("<img src=\"").append(esc(it)).append("\"/>") }
                else -> inline(node, sb)
            }
        }
    }

    private fun wrap(tag: String, n: XmlNode, sb: StringBuilder) {
        sb.append('<').append(tag).append('>')
        inline(n, sb)
        sb.append("</").append(tag).append('>')
    }

    companion object {
        private val WS = Regex("\\s+")
        fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
