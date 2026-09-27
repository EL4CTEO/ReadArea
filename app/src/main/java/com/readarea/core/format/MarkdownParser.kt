package com.readarea.core.format

class MarkdownParser(private val source: String, private val fallbackTitle: String) {

    fun parse(): ParsedBook {
        val html = toHtml(source)
        val converter = HtmlConverter("index.md")
        val blocks = converter.convert(html)
        val title = converter.firstHeading ?: fallbackTitle
        return ParsedBook(BookMeta(title = title), listOf(Chapter(title, "index.md", blocks)), emptyList(), ResourceProvider { null })
    }

    companion object {
        private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
        private val HR = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
        private val UL = Regex("^(\\s*)[-*+]\\s+(.*)$")
        private val OL = Regex("^(\\s*)\\d+[.)]\\s+(.*)$")
        private val IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
        private val LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
        private val AUTOLINK = Regex("<(https?://[^>]+)>")
        private val CODE = Regex("`([^`]+)`")
        private val BOLD = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
        private val ITALIC = Regex("(\\*|_)(?=\\S)(.+?)(?<=\\S)\\1")
        private val STRIKE = Regex("~~(.+?)~~")

        fun toHtml(md: String): String {
            val lines = md.replace("\r\n", "\n").split('\n')
            val out = StringBuilder()
            val para = StringBuilder()
            var inCode = false
            val listStack = ArrayList<String>()
            var inQuote = false

            fun flushPara() {
                if (para.isNotEmpty()) {
                    out.append("<p>").append(inline(para.toString().trim())).append("</p>\n")
                    para.setLength(0)
                }
            }
            fun closeLists() {
                while (listStack.isNotEmpty()) out.append("</").append(listStack.removeAt(listStack.size - 1)).append(">")
            }
            fun closeQuote() {
                if (inQuote) {
                    flushPara()
                    out.append("</blockquote>")
                    inQuote = false
                }
            }

            var i = 0
            while (i < lines.size) {
                val raw = lines[i]
                if (raw.trimStart().startsWith("```") || raw.trimStart().startsWith("~~~")) {
                    flushPara()
                    if (!inCode) {
                        closeLists()
                        out.append("<pre>")
                        inCode = true
                    } else {
                        out.append("</pre>\n")
                        inCode = false
                    }
                    i++
                    continue
                }
                if (inCode) {
                    out.append(esc(raw)).append('\n')
                    i++
                    continue
                }
                var line = raw
                if (line.trimStart().startsWith(">")) {
                    if (!inQuote) {
                        flushPara()
                        closeLists()
                        out.append("<blockquote>")
                        inQuote = true
                    }
                    line = line.trimStart().removePrefix(">").removePrefix(" ")
                } else if (inQuote && line.isBlank()) {
                    closeQuote()
                    i++
                    continue
                }
                if (line.isBlank()) {
                    flushPara()
                    closeLists()
                    i++
                    continue
                }
                val next = lines.getOrNull(i + 1)
                if (next != null && para.isEmpty() && line.isNotBlank() && next.isNotBlank() && (next.trim().all { it == '=' } || next.trim().all { it == '-' } && next.trim().length >= 2)) {
                    closeLists()
                    val level = if (next.trim().first() == '=') 1 else 2
                    out.append("<h$level>").append(inline(line.trim())).append("</h$level>\n")
                    i += 2
                    continue
                }
                HEADING.matchEntire(line)?.let { m ->
                    flushPara()
                    closeLists()
                    val level = m.groupValues[1].length
                    out.append("<h$level>").append(inline(m.groupValues[2])).append("</h$level>\n")
                    i++
                    return@let
                } ?: run {
                    if (HR.matches(line)) {
                        flushPara()
                        closeLists()
                        out.append("<hr/>\n")
                    } else {
                        val ul = UL.matchEntire(line)
                        val ol = if (ul == null) OL.matchEntire(line) else null
                        val m = ul ?: ol
                        if (m != null) {
                            flushPara()
                            val depth = m.groupValues[1].length / 2 + 1
                            val tag = if (ul != null) "ul" else "ol"
                            while (listStack.size > depth) out.append("</").append(listStack.removeAt(listStack.size - 1)).append(">")
                            while (listStack.size < depth) {
                                out.append("<").append(tag).append(">")
                                listStack.add(tag)
                            }
                            val item = m.groupValues[2]
                            val task = when {
                                item.startsWith("[ ] ") -> "☐ " + item.substring(4)
                                item.startsWith("[x] ", true) -> "☑ " + item.substring(4)
                                else -> item
                            }
                            out.append("<li>").append(inline(task)).append("</li>")
                        } else {
                            if (listStack.isNotEmpty() && raw.startsWith("  ")) {
                                out.append("<p>").append(inline(line.trim())).append("</p>")
                            } else {
                                closeLists()
                                if (line.endsWith("  ")) {
                                    para.append(line.trim()).append("\u0000BR\u0000")
                                } else {
                                    para.append(line.trim()).append(' ')
                                }
                            }
                        }
                    }
                    i++
                }
            }
            flushPara()
            closeLists()
            closeQuote()
            if (inCode) out.append("</pre>")
            return out.toString().replace("\u0000BR\u0000", "<br/>")
        }

        private fun inline(s: String): String {
            val codes = ArrayList<String>()
            var t = CODE.replace(s) { m ->
                codes.add(m.groupValues[1])
                "\u0001${codes.size - 1}\u0001"
            }
            t = esc(t)
            t = IMAGE.replace(t) { m -> "<img src=\"${m.groupValues[2]}\" alt=\"${m.groupValues[1]}\"/>" }
            t = LINK.replace(t) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
            t = AUTOLINK.replace(t) { m -> "<a href=\"${m.groupValues[1]}\">${m.groupValues[1]}</a>" }
            t = t.replace("&lt;br&gt;", "<br/>").replace("&lt;br/&gt;", "<br/>")
            t = BOLD.replace(t) { m -> "<b>${m.groupValues[2]}</b>" }
            t = ITALIC.replace(t) { m -> "<i>${m.groupValues[2]}</i>" }
            t = STRIKE.replace(t) { m -> "<s>${m.groupValues[1]}</s>" }
            return Regex("\u0001(\\d+)\u0001").replace(t) { m -> "<code>${esc(codes[m.groupValues[1].toInt()])}</code>" }
        }

        private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
