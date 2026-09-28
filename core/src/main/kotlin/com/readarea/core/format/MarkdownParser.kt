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
        private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
        private val UL = Regex("^(\\s*)[-*+]\\s+(.*)$")
        private val OL = Regex("^(\\s*)\\d+[.)]\\s+(.*)$")
        // Link text and targets stop at any bracket (and autolinks at any '<'), so a scan started at one bracket
        // never runs through the next: each character is looked at a bounded number of times.
        private val IMAGE = Regex("!\\[([^\\[\\]\\n]{0,500}+)]\\(([^)\\[\\]\\s]{1,2000}+)(?:\\s++\"[^\"\\n]{0,500}+\")?\\)")
        private val LINK = Regex("\\[([^\\[\\]\\n]{1,500}+)]\\(([^)\\[\\]\\s]{1,2000}+)(?:\\s++\"[^\"\\n]{0,500}+\")?\\)")
        private val AUTOLINK = Regex("<(https?://[^<>\\s]{1,2000}+)>")
        private val CODE = Regex("`([^`]+)`")
        private val BOLD = listOf("**", "__")
        private val ITALIC = listOf("*", "_")
        private val STRIKE = listOf("~~")

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
                    val raw = m.groupValues[2].trimEnd()
                    val text = raw.trimEnd('#').let { if (it.isEmpty() || it.last().isWhitespace()) it.trimEnd() else raw }
                    out.append("<h$level>").append(inline(text)).append("</h$level>\n")
                    i++
                    return@let
                } ?: run {
                    if (isRule(line)) {
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
            val tags = ArrayList<String>()
            fun hold(html: String): String {
                tags.add(html)
                return "\u0003${tags.size - 1}\u0003"
            }
            var t = CODE.replace(s) { m ->
                codes.add(m.groupValues[1])
                "\u0001${codes.size - 1}\u0001"
            }
            t = AUTOLINK.replace(t) { m -> esc(m.groupValues[1]).let { u -> hold("<a href=\"${attr(u)}\">$u</a>") } }
            t = esc(t)
            t = IMAGE.replace(t) { m -> hold("<img src=\"${attr(m.groupValues[2])}\" alt=\"${attr(m.groupValues[1])}\"/>") }
            t = LINK.replace(t) { m -> hold("<a href=\"${attr(m.groupValues[2])}\">") + m.groupValues[1] + "</a>" }
            t = t.replace("&lt;br&gt;", "<br/>").replace("&lt;br/&gt;", "<br/>")
            t = emphasis(t, BOLD, "b", flanking = true)
            t = emphasis(t, ITALIC, "i", flanking = true)
            t = emphasis(t, STRIKE, "s", flanking = false)
            t = Regex("\u0003(\\d+)\u0003").replace(t) { m -> tags[m.groupValues[1].toInt()] }
            return Regex("\u0001(\\d+)\u0001").replace(t) { m -> "<code>${esc(codes[m.groupValues[1].toInt()])}</code>" }
        }

        /**
         * A thematic break: up to three spaces, then three or more of the same `-`, `*` or `_`, spaced as you
         * like. Checked by hand because the regex for it recursed once per repetition and overflowed the stack
         * on a long line of them.
         */
        internal fun isRule(line: String): Boolean {
            val start = line.indexOfFirst { !it.isWhitespace() }
            if (start !in 0..3) return false
            val c = line[start]
            if (c != '-' && c != '*' && c != '_') return false
            var count = 0
            for (k in start until line.length) {
                val ch = line[k]
                if (ch == c) count++ else if (!ch.isWhitespace()) return false
            }
            return count >= 3
        }

        /**
         * Wraps text between a pair of [delims] in [tag], pairing each opener with the nearest closer after it the
         * way the regex `(d)(?=\\S)(.+?)(?<=\\S)\\1` does (with [flanking]; without it, any delimiter closes).
         * The regex retries every opener against the rest of the paragraph, so a paragraph with thousands of
         * openers and no closer took quadratic time. Here, once an opener finds no closer, no later one can
         * either (it would search a subset of the same text), so the delimiter is dropped from the scan.
         */
        internal fun emphasis(s: String, delims: List<String>, tag: String, flanking: Boolean): String {
            val exhausted = BooleanArray(delims.size)
            var out: StringBuilder? = null
            var copied = 0
            var i = 0
            while (i < s.length) {
                val k = delims.indexOfFirst { s.startsWith(it, i) }
                if (k < 0 || exhausted[k]) {
                    i++
                    continue
                }
                val d = delims[k]
                val from = i + d.length
                if (from >= s.length || flanking && s[from].isWhitespace()) {
                    i++
                    continue
                }
                var close = s.indexOf(d, from + 1)
                if (flanking) while (close >= 0 && s[close - 1].isWhitespace()) close = s.indexOf(d, close + 1)
                if (close < 0) {
                    exhausted[k] = true
                    i++
                    continue
                }
                val b = out ?: StringBuilder(s.length + 16).also { out = it }
                b.append(s, copied, i).append('<').append(tag).append('>').append(s, from, close).append("</").append(tag).append('>')
                i = close + d.length
                copied = i
            }
            return out?.append(s, copied, s.length)?.toString() ?: s
        }

        private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun attr(s: String): String = s.replace("\"", "&quot;")
    }
}
