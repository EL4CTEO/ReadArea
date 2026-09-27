package com.readarea.core.format

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class RtfParser(private val data: ByteArray, private val fallbackTitle: String) {

    private data class State(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var sup: Boolean = false,
        var sub: Boolean = false,
        var skip: Boolean = false,
        var uc: Int = 1,
        var dest: String? = null,
    )

    private val out = StringBuilder()
    private val para = StringBuilder()
    private var align: String? = null
    private var outline = -1
    private var charset: Charset = Charset.forName("windows-1252")
    private val pendingBytes = ByteArrayOutputStream()
    private var infoTitle = StringBuilder()
    private var infoAuthor = StringBuilder()

    fun parse(): ParsedBook {
        val src = String(data, Charsets.ISO_8859_1)
        if (!src.startsWith("{\\rtf")) throw BookParseException(ParseError.INVALID, "Not a valid RTF document")
        val stack = ArrayList<State>()
        var st = State()
        var i = 0
        var skipChars = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '{' -> {
                    flushBytes(st)
                    stack.add(st.copy())
                    i++
                }
                c == '}' -> {
                    flushBytes(st)
                    st = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else State()
                    i++
                }
                c == '\\' && i + 1 < n -> {
                    val next = src[i + 1]
                    when {
                        next == '\'' && i + 3 < n -> {
                            val hex = src.substring(i + 2, i + 4).toIntOrNull(16)
                            i += 4
                            if (skipChars > 0) {
                                skipChars--
                            } else if (hex != null && !st.skip) {
                                pendingBytes.write(hex)
                            }
                        }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < n && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var k = j
                            if (k < n && (src[k] == '-' || src[k].isDigit())) {
                                k++
                                while (k < n && src[k].isDigit()) k++
                            }
                            val param = if (k > j) src.substring(j, k).toIntOrNull() else null
                            if (k < n && src[k] == ' ') k++
                            i = k
                            flushBytes(st)
                            if (word == "u" && param != null) {
                                if (!st.skip) emit(st, (if (param < 0) param + 65536 else param).toChar().toString())
                                skipChars = st.uc
                            } else {
                                control(word, param, st)
                            }
                        }
                        next == '*' -> {
                            st.skip = true
                            i += 2
                        }
                        next == '~' -> { emit(st, " "); i += 2 }
                        next == '-' -> { emit(st, "­"); i += 2 }
                        next == '_' -> { emit(st, "‑"); i += 2 }
                        next == '\n' || next == '\r' -> { paragraph(); i += 2 }
                        else -> {
                            emit(st, next.toString())
                            i += 2
                        }
                    }
                }
                c == '\n' || c == '\r' -> i++
                else -> {
                    if (skipChars > 0) skipChars-- else {
                        flushBytes(st)
                        emit(st, c.toString())
                    }
                    i++
                }
            }
        }
        flushBytes(st)
        paragraph()
        val title = infoTitle.toString().trim().ifEmpty { fallbackTitle }
        val blocks = HtmlConverter("doc.rtf").convert(out.toString())
        return ParsedBook(BookMeta(title = title, author = infoAuthor.toString().trim()), listOf(Chapter(title, "doc.rtf", blocks)), emptyList(), ResourceProvider { null })
    }

    private fun flushBytes(st: State) {
        if (pendingBytes.size() == 0) return
        val text = String(pendingBytes.toByteArray(), charset)
        pendingBytes.reset()
        if (!st.skip) emit(st, text)
    }

    private fun control(word: String, param: Int?, st: State) {
        when (word) {
            "ansicpg" -> param?.let { cp -> runCatching { Charset.forName(if (cp == 65001) "UTF-8" else "windows-$cp") }.getOrNull()?.let { charset = it } }
            "fonttbl", "colortbl", "stylesheet", "pict", "object", "header", "footer", "headerl", "headerr", "footerl", "footerr",
            "footnote", "generator", "listtable", "listoverridetable", "rsidtbl", "xmlnstbl", "themedata", "colorschememapping",
            "datastore", "latentstyles", "pgdsctbl", "fldinst", "bkmkstart", "bkmkend", "shppict", "nonshppict", "operator",
            "company", "keywords", "comment", "doccomm", "subject", "category", "mmathPr", "wgrffmtfilter", "filetbl", "revtbl", "xe", "tc" -> st.skip = true
            "info" -> st.dest = "info"
            "title" -> if (st.dest == "info") st.dest = "title" else st.skip = true
            "author" -> if (st.dest == "info") st.dest = "author" else st.skip = true
            "par", "sect", "page" -> paragraph()
            "line" -> if (!st.skip) para.append("<br/>")
            "tab" -> emit(st, " ")
            "pard" -> {
                align = null
                outline = -1
            }
            "plain" -> {
                st.bold = false; st.italic = false; st.underline = false; st.strike = false; st.sup = false; st.sub = false
            }
            "b" -> st.bold = param != 0
            "i" -> st.italic = param != 0
            "ul" -> st.underline = param != 0
            "ulnone" -> st.underline = false
            "strike" -> st.strike = param != 0
            "super" -> st.sup = true
            "sub" -> st.sub = true
            "nosupersub" -> { st.sup = false; st.sub = false }
            "qc" -> align = "center"
            "qr" -> align = "right"
            "qj" -> align = "justify"
            "ql" -> align = null
            "outlinelevel" -> outline = param ?: -1
            "uc" -> st.uc = param ?: 1
            "emdash" -> emit(st, "—")
            "endash" -> emit(st, "–")
            "bullet" -> emit(st, "•")
            "lquote" -> emit(st, "‘")
            "rquote" -> emit(st, "’")
            "ldblquote" -> emit(st, "“")
            "rdblquote" -> emit(st, "”")
            "emspace", "enspace", "qmspace" -> emit(st, " ")
        }
    }

    private fun emit(st: State, text: String) {
        if (st.skip) return
        when (st.dest) {
            "title" -> { infoTitle.append(text); return }
            "author" -> { infoAuthor.append(text); return }
            "info" -> return
        }
        var t = Fb2Parser.esc(text)
        if (st.bold) t = "<b>$t</b>"
        if (st.italic) t = "<i>$t</i>"
        if (st.underline) t = "<u>$t</u>"
        if (st.strike) t = "<s>$t</s>"
        if (st.sup) t = "<sup>$t</sup>"
        if (st.sub) t = "<sub>$t</sub>"
        para.append(t)
    }

    private fun paragraph() {
        val content = para.toString()
        para.setLength(0)
        val style = when (align) {
            "center" -> " style=\"text-align:center\""
            "right" -> " style=\"text-align:right\""
            else -> ""
        }
        if (outline in 0..5) {
            val level = outline + 1
            out.append("<h$level>").append(content).append("</h$level>\n")
        } else if (content.isBlank()) {
            out.append("<p>&#160;</p>\n")
        } else {
            out.append("<p$style>").append(content).append("</p>\n")
        }
    }
}
