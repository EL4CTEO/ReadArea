package com.readarea.core.format

class Attributes(private val map: Map<String, String>) {
    operator fun get(name: String): String? = map[name] ?: map.entries.firstOrNull { it.key.substringAfter(':') == name }?.value

    fun has(name: String): Boolean = get(name) != null

    val all: Map<String, String> get() = map

    companion object {
        val EMPTY = Attributes(emptyMap())
    }
}

interface HtmlHandler {
    fun startTag(name: String, attrs: Attributes, selfClosing: Boolean)
    fun endTag(name: String)
    fun text(text: String)
    fun rawText(tag: String, content: String) {}
}

class HtmlTokenizer(private val src: String) {
    private var pos = 0

    fun parse(handler: HtmlHandler) {
        val n = src.length
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                handler.text(Entities.decode(text.toString()))
                text.setLength(0)
            }
        }
        while (pos < n) {
            val c = src[pos]
            if (c != '<') {
                val next = src.indexOf('<', pos).let { if (it < 0) n else it }
                text.append(src, pos, next)
                pos = next
                continue
            }
            if (src.startsWith("<!--", pos)) {
                flushText()
                val end = src.indexOf("-->", pos + 4)
                pos = if (end < 0) n else end + 3
                continue
            }
            if (src.startsWith("<![CDATA[", pos)) {
                flushText()
                val end = src.indexOf("]]>", pos + 9)
                val content = if (end < 0) src.substring(pos + 9) else src.substring(pos + 9, end)
                handler.text(content)
                pos = if (end < 0) n else end + 3
                continue
            }
            if (pos + 1 < n && (src[pos + 1] == '!' || src[pos + 1] == '?')) {
                flushText()
                val end = src.indexOf('>', pos)
                pos = if (end < 0) n else end + 1
                continue
            }
            if (pos + 1 < n && src[pos + 1] == '/') {
                val end = src.indexOf('>', pos)
                if (end < 0) {
                    text.append(src, pos, n)
                    pos = n
                    continue
                }
                flushText()
                val name = normalizeName(src.substring(pos + 2, end).trim())
                pos = end + 1
                if (name.isNotEmpty()) handler.endTag(name)
                continue
            }
            if (pos + 1 < n && src[pos + 1].isLetter()) {
                flushText()
                readStartTag(handler)
                continue
            }
            text.append(c)
            pos++
        }
        flushText()
    }

    private fun readStartTag(handler: HtmlHandler) {
        val n = src.length
        var i = pos + 1
        val nameStart = i
        while (i < n && !src[i].isWhitespace() && src[i] != '>' && src[i] != '/') i++
        val rawName = src.substring(nameStart, i)
        val name = normalizeName(rawName)
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            while (i < n && src[i].isWhitespace()) i++
            if (i >= n) break
            val ch = src[i]
            if (ch == '>') {
                i++
                break
            }
            if (ch == '/') {
                if (i + 1 < n && src[i + 1] == '>') {
                    selfClosing = true
                    i += 2
                    break
                }
                i++
                continue
            }
            val attrStart = i
            while (i < n && !src[i].isWhitespace() && src[i] != '=' && src[i] != '>' && !(src[i] == '/' && i + 1 < n && src[i + 1] == '>')) i++
            val attrName = src.substring(attrStart, i).lowercase()
            while (i < n && src[i].isWhitespace()) i++
            var value = ""
            if (i < n && src[i] == '=') {
                i++
                while (i < n && src[i].isWhitespace()) i++
                if (i < n && (src[i] == '"' || src[i] == '\'')) {
                    val quote = src[i]
                    val end = src.indexOf(quote, i + 1).let { if (it < 0) n else it }
                    value = src.substring(i + 1, end)
                    i = if (end < n) end + 1 else n
                } else {
                    val vs = i
                    while (i < n && !src[i].isWhitespace() && src[i] != '>') i++
                    value = src.substring(vs, i)
                }
            }
            if (attrName.isNotEmpty() && !attrs.containsKey(attrName)) attrs[attrName] = Entities.decode(value)
        }
        pos = i
        handler.startTag(name, Attributes(attrs), selfClosing)
        if (!selfClosing && !rawName.contains(':') && (name == "script" || name == "style" || name == "binary")) {
            val close = "</$rawName"
            var end = indexOfIgnoreCase(close, pos)
            if (end < 0) end = n
            handler.rawText(name, src.substring(pos, end))
            val gt = src.indexOf('>', end)
            pos = if (gt < 0) n else gt + 1
            handler.endTag(name)
        }
    }

    private fun indexOfIgnoreCase(needle: String, from: Int): Int {
        var i = from
        val last = src.length - needle.length
        while (i <= last) {
            if (src.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    companion object {
        fun normalizeName(raw: String): String {
            val trimmed = raw.trim().substringBefore(' ')
            return trimmed.substringAfter(':').lowercase()
        }
    }
}

object Entities {
    private val named: Map<String, String> = hashMapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "‌", "zwj" to "‍",
        "shy" to "­", "mdash" to "—", "ndash" to "–", "hellip" to "…", "bull" to "•",
        "middot" to "·", "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“",
        "rdquo" to "”", "bdquo" to "„", "laquo" to "«", "raquo" to "»", "lsaquo" to "‹",
        "rsaquo" to "›", "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "plusmn" to "±", "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼",
        "frac34" to "¾", "sup1" to "¹", "sup2" to "²", "sup3" to "³", "para" to "¶",
        "sect" to "§", "dagger" to "†", "Dagger" to "‡", "prime" to "′", "Prime" to "″",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "curren" to "¤",
        "iexcl" to "¡", "iquest" to "¿", "ordf" to "ª", "ordm" to "º", "micro" to "µ",
        "not" to "¬", "macr" to "¯", "acute" to "´", "cedil" to "¸", "uml" to "¨",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
        "infin" to "∞", "ne" to "≠", "le" to "≤", "ge" to "≥", "minus" to "−",
        "hearts" to "♥", "spades" to "♠", "clubs" to "♣", "diams" to "♦", "loz" to "◊",
        "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã", "Auml" to "Ä",
        "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç", "Egrave" to "È", "Eacute" to "É",
        "Ecirc" to "Ê", "Euml" to "Ë", "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î",
        "Iuml" to "Ï", "ETH" to "Ð", "Ntilde" to "Ñ", "Ograve" to "Ò", "Oacute" to "Ó",
        "Ocirc" to "Ô", "Otilde" to "Õ", "Ouml" to "Ö", "Oslash" to "Ø", "Ugrave" to "Ù",
        "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü", "Yacute" to "Ý", "THORN" to "Þ",
        "szlig" to "ß", "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
        "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç", "egrave" to "è",
        "eacute" to "é", "ecirc" to "ê", "euml" to "ë", "igrave" to "ì", "iacute" to "í",
        "icirc" to "î", "iuml" to "ï", "eth" to "ð", "ntilde" to "ñ", "ograve" to "ò",
        "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ", "ouml" to "ö", "oslash" to "ø",
        "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý",
        "thorn" to "þ", "yuml" to "ÿ", "OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š",
        "scaron" to "š", "Yuml" to "Ÿ", "fnof" to "ƒ", "circ" to "ˆ", "tilde" to "˜",
        "Alpha" to "Α", "Beta" to "Β", "Gamma" to "Γ", "Delta" to "Δ", "Omega" to "Ω",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "lambda" to "λ", "mu" to "μ", "pi" to "π", "sigma" to "σ", "omega" to "ω",
        "check" to "✓", "star" to "☆", "starf" to "★",
    )

    fun decode(s: String): String {
        val amp = s.indexOf('&')
        if (amp < 0) return s
        val sb = StringBuilder(s.length)
        sb.append(s, 0, amp)
        var i = amp
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi > i + 1 && semi - i <= 12) {
                val ent = s.substring(i + 1, semi)
                val rep = resolve(ent)
                if (rep != null) {
                    sb.append(rep)
                    i = semi + 1
                    continue
                }
            }
            val loose = listOf("amp", "lt", "gt", "quot", "nbsp").firstOrNull { s.startsWith(it, i + 1) }
            if (loose != null) {
                sb.append(named[loose])
                i += loose.length + 1
                continue
            }
            sb.append('&')
            i++
        }
        return sb.toString()
    }

    private fun resolve(ent: String): String? {
        if (ent.startsWith("#")) {
            val code = if (ent.length > 1 && (ent[1] == 'x' || ent[1] == 'X')) ent.substring(2).toIntOrNull(16) else ent.substring(1).toIntOrNull()
            if (code == null || code <= 0 || code > 0x10FFFF) return null
            return String(Character.toChars(code))
        }
        return named[ent]
    }
}
