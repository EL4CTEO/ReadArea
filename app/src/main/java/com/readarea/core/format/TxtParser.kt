package com.readarea.core.format

class TxtParser(private val text: String, private val fallbackTitle: String) {

    private val aozoraHeadings = HashMap<String, Int>()
    private var aozora = false

    fun parse(): ParsedBook {
        var normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        aozora = (normalized.contains('《') || normalized.contains("［＃")) && TextDirection.guessCjkLanguage(normalized.take(20_000)) == "ja"
        if (aozora) normalized = cleanAozora(normalized)
        val paragraphs = splitParagraphs(normalized)
        val headingIdx = paragraphs.indices.filter { isHeading(paragraphs[it]) || aozoraHeadings.containsKey(paragraphs[it].trim()) }
        val chapters = ArrayList<Chapter>()
        val toc = ArrayList<TocItem>()
        if (headingIdx.size >= 2) {
            if (headingIdx.first() > 0) {
                val pre = paragraphs.subList(0, headingIdx.first())
                if (pre.any { it.isNotBlank() }) chapters.add(Chapter(fallbackTitle, "txt0", pre.map { para(it) }))
            }
            headingIdx.forEachIndexed { i, start ->
                val end = if (i + 1 < headingIdx.size) headingIdx[i + 1] else paragraphs.size
                val raw = paragraphs[start].trim()
                val (title, ruby) = if (aozora) parseRuby(raw) else raw to emptyList()
                val blocks = ArrayList<Block>()
                blocks.add(Block(BlockKind.HEADING, listOf(Run(title, RunStyle.BOLD)), align = Align.CENTER, level = aozoraHeadings[raw] ?: 2, noIndent = true, ruby = ruby))
                for (k in start + 1 until end) blocks.add(para(paragraphs[k]))
                chapters.add(Chapter(title, "txt${chapters.size}", blocks))
                toc.add(TocItem(title, chapters.size - 1, null, 0))
            }
        } else {
            val blocks = paragraphs.map { para(it) }
            chapters.add(Chapter(fallbackTitle, "txt0", blocks))
        }
        return ParsedBook(BookMeta(title = fallbackTitle, language = if (aozora) "ja" else null, vertical = aozora), chapters, toc, ResourceProvider { null })
    }

    private fun para(s: String): Block {
        val t = s.trim()
        if (t.isEmpty()) return Block(BlockKind.PARAGRAPH, listOf(Run(" ")), noIndent = true)
        if (!aozora) return Block(BlockKind.PARAGRAPH, listOf(Run(t)))
        val (plain, ruby) = parseRuby(t)
        return Block(BlockKind.PARAGRAPH, listOf(Run(plain.ifEmpty { " " })), ruby = ruby)
    }

    private fun cleanAozora(s: String): String {
        val lines = s.split('\n').toMutableList()
        val dashes = lines.indices.filter { i -> lines[i].trim().let { it.length >= 20 && it.all { c -> c == '-' } } }.take(2)
        if (dashes.size == 2 && dashes[0] < 80 && dashes[1] - dashes[0] < 80) for (i in dashes[1] downTo dashes[0]) lines.removeAt(i)
        val out = StringBuilder(s.length)
        for (line in lines) {
            val heading = AOZORA_HEADING.find(line)
            val cleaned = AOZORA_NOTE.replace(line, "").trimEnd()
            if (heading != null && cleaned.isNotBlank()) {
                aozoraHeadings[cleaned.trim()] = when (heading.groupValues[1]) {
                    "大" -> 1
                    "小" -> 3
                    else -> 2
                }
                out.append(cleaned).append('\n')
                continue
            }
            if (line.contains("［＃改ページ］") || line.contains("［＃改丁］")) {
                out.append("\n\n")
                continue
            }
            out.append(cleaned).append('\n')
        }
        return out.toString()
    }

    private fun splitParagraphs(s: String): List<String> {
        val lines = s.split('\n')
        val nonEmpty = lines.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return emptyList()
        val blankCount = lines.size - nonEmpty.size
        val lengths = nonEmpty.map { it.trimEnd().length }.sorted()
        val median = lengths[lengths.size / 2]
        val wrappedCount = nonEmpty.count { it.trimEnd().length in (median - 15)..(median + 3) }
        val hardWrapped = median in 50..100 && wrappedCount * 100 / nonEmpty.size > 55 && blankCount > nonEmpty.size / 20
        if (!hardWrapped) {
            val out = ArrayList<String>()
            var blankRun = 0
            for (l in lines) {
                if (l.isBlank()) {
                    blankRun++
                    if (blankRun == 2) out.add("")
                } else {
                    blankRun = 0
                    out.add(l)
                }
            }
            return out
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (l in lines) {
            if (l.isBlank()) {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                continue
            }
            val trimmed = l.trim()
            if (cur.isNotEmpty() && (isHeading(trimmed) || l.startsWith("    ") && cur.length > 0 && l.length < median - 20)) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) {
                if (cur.endsWith("-") && trimmed.firstOrNull()?.isLowerCase() == true) cur.setLength(cur.length - 1) else cur.append(' ')
            }
            cur.append(trimmed)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    companion object {
        private val AOZORA_HEADING = Regex("［＃「[^」]*」は(大|中|小)?見出し］")
        private val AOZORA_NOTE = Regex("［＃[^］]*］")

        fun parseRuby(s: String): Pair<String, List<Ruby>> {
            if (s.indexOf('《') < 0) return s.replace("｜", "") to emptyList()
            val sb = StringBuilder(s.length)
            val out = ArrayList<Ruby>()
            var marker = -1
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '｜') {
                    marker = sb.length
                    i++
                    continue
                }
                if (c == '《') {
                    val close = s.indexOf('》', i + 1)
                    if (close > i && close - i <= 40) {
                        val rt = s.substring(i + 1, close)
                        var start = marker
                        if (start < 0) {
                            start = sb.length
                            while (start > 0 && isKanji(sb[start - 1])) start--
                        }
                        if (start < sb.length && rt.isNotBlank()) out.add(Ruby(start, sb.length, rt))
                        marker = -1
                        i = close + 1
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString() to out
        }

        private fun isKanji(c: Char): Boolean {
            val code = c.code
            return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF || c == '々' || c == '〆' || c == 'ヶ' || c == '〇' || c == 'ヵ' || Character.isSurrogate(c)
        }

        private val HEADING = listOf(
            Regex("^(chapter|part|book|volume|prologue|epilogue|introduction|preface|foreword|afterword|interlude|act|scene)\\b[\\s\\S]{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^(глава|часть|пролог|эпилог|книга|том|раздел)\\b[\\s\\S]{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^(capítulo|capitulo|chapitre|kapitel|capitolo|rozdział|hoofdstuk|kapittel|luku|bölüm)\\b[\\s\\S]{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^第[0-9０-９一二三四五六七八九十百千零〇两]+[章节回卷部篇集話幕].{0,40}$"),
            Regex("^(\\d{1,3}|[IVXLC]{1,7})[.)]?$"),
            Regex("^(\\*\\s*){3,}$"),
        )

        fun isHeading(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty() || t.length > 70) return false
            if (t.endsWith(",") || t.endsWith(";")) return false
            return HEADING.any { it.matches(t) } && !HEADING.last().matches(t)
        }
    }
}
