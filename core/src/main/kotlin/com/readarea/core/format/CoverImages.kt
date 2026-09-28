package com.readarea.core.format

object CoverImages {
    class Found(val start: Int, val value: String)

    fun isRaster(path: String): Boolean {
        val l = path.lowercase().substringBefore('?')
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".bmp")
    }

    fun firstTagAttr(markup: String, tag: String, attr: String, from: Int = 0): Found? {
        val open = "<$tag"
        var i = from
        while (true) {
            val s = markup.indexOf(open, i)
            if (s < 0) return null
            val after = s + open.length
            val end = markup.indexOf('>', after)
            if (end < 0) return null
            val next = markup[after]
            if (next.isWhitespace() || next == '/' || next == '>') {
                attrValue(markup.substring(after, end), attr)?.let { return Found(s, it) }
            }
            i = end + 1
        }
    }

    private fun attrValue(tag: String, attr: String): String? {
        val key = "$attr=\""
        var i = 0
        while (true) {
            val a = tag.indexOf(key, i)
            if (a < 0) return null
            val vs = a + key.length
            if (a > 0 && tag[a - 1].isWhitespace()) {
                val ve = tag.indexOf('"', vs)
                return if (ve > vs) tag.substring(vs, ve) else null
            }
            i = vs
        }
    }

    fun docxTextBefore(xml: String, until: Int, stopAt: Int = 150): Int {
        var total = 0
        var i = 0
        while (i < until && total < stopAt) {
            val s = xml.indexOf("<w:t", i)
            if (s < 0 || s >= until) break
            val gt = xml.indexOf('>', s)
            if (gt < 0 || gt >= until) break
            val c = xml[s + 4]
            if ((c == '>' || c.isWhitespace()) && xml[gt - 1] != '/') {
                val lt = xml.indexOf('<', gt + 1)
                if (lt < 0) break
                if (xml.startsWith("</w:t>", lt)) total += xml.substring(gt + 1, lt).trim().length
                i = lt
            } else {
                i = gt + 1
            }
        }
        return total
    }

    fun stripTags(s: String, with: String = " "): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val lt = s.indexOf('<', i)
            if (lt < 0) {
                sb.append(s, i, s.length)
                break
            }
            val gt = s.indexOf('>', lt + 1)
            if (gt < 0) {
                sb.append(s, i, s.length)
                break
            }
            sb.append(s, i, lt)
            if (gt > lt + 1) sb.append(with) else sb.append("<>")
            i = gt + 1
        }
        return sb.toString()
    }

    fun textLength(markup: String): Int = Entities.decode(stripTags(markup)).count { !it.isWhitespace() }
}
