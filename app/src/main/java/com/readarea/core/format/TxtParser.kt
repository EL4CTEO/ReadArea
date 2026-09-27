package com.readarea.core.format

class TxtParser(private val text: String, private val fallbackTitle: String) {

    fun parse(): ParsedBook {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        val paragraphs = splitParagraphs(normalized)
        val headingIdx = paragraphs.indices.filter { isHeading(paragraphs[it]) }
        val chapters = ArrayList<Chapter>()
        val toc = ArrayList<TocItem>()
        if (headingIdx.size >= 2) {
            if (headingIdx.first() > 0) {
                val pre = paragraphs.subList(0, headingIdx.first())
                if (pre.any { it.isNotBlank() }) chapters.add(Chapter(fallbackTitle, "txt0", pre.map { para(it) }))
            }
            headingIdx.forEachIndexed { i, start ->
                val end = if (i + 1 < headingIdx.size) headingIdx[i + 1] else paragraphs.size
                val title = paragraphs[start].trim()
                val blocks = ArrayList<Block>()
                blocks.add(Block(BlockKind.HEADING, listOf(Run(title, RunStyle.BOLD)), align = Align.CENTER, level = 2, noIndent = true))
                for (k in start + 1 until end) blocks.add(para(paragraphs[k]))
                chapters.add(Chapter(title, "txt${chapters.size}", blocks))
                toc.add(TocItem(title, chapters.size - 1, null, 0))
            }
        } else {
            val blocks = paragraphs.map { para(it) }
            chapters.add(Chapter(fallbackTitle, "txt0", blocks))
        }
        return ParsedBook(BookMeta(title = fallbackTitle), chapters, toc, ResourceProvider { null })
    }

    private fun para(s: String): Block {
        val t = s.trim()
        return if (t.isEmpty()) Block(BlockKind.PARAGRAPH, listOf(Run(" ")), noIndent = true)
        else Block(BlockKind.PARAGRAPH, listOf(Run(t)))
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
        private val HEADING = listOf(
            Regex("^(chapter|part|book|volume|prologue|epilogue|introduction|preface|foreword|afterword|interlude|act|scene)\\b[\\s\\S]{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^(глава|часть|пролог|эпилог|книга|том|раздел)\\b[\\s\\S]{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^(capítulo|capitulo|chapitre|kapitel|capitolo|rozdział|hoofdstuk|kapittel|luku|bölüm)\\b[\\s\\S]{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^第[0-9０-９一二三四五六七八九十百千零〇两]+[章节回卷部篇集].{0,40}$"),
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
