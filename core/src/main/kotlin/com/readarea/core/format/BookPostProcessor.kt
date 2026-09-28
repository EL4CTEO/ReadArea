package com.readarea.core.format

object BookPostProcessor {
    private const val MAX_CHAPTER_CHARS = 60_000

    fun process(book: ParsedBook, splitHeadings: Boolean): ParsedBook {
        var chapters = book.chapters.filter { it.blocks.isNotEmpty() }
        var toc = book.toc
        if (chapters.size != book.chapters.size) {
            val remap = HashMap<Int, Int>()
            var k = 0
            book.chapters.forEachIndexed { i, c -> if (c.blocks.isNotEmpty()) remap[i] = k++ else remap[i] = k.coerceAtMost(chapters.size - 1) }
            toc = toc.map { it.copy(chapter = remap[it.chapter] ?: 0) }
        }
        if (chapters.isEmpty()) chapters = listOf(Chapter(book.meta.title, "empty", listOf(Block(BlockKind.PARAGRAPH, listOf(Run(" "))))))
        if (splitHeadings && chapters.size == 1) chapters = splitByHeadings(chapters[0])
        val result = ArrayList<Chapter>()
        val mapping = ArrayList<IntRange>()
        for (c in chapters) {
            val start = result.size
            result.addAll(splitBySize(c))
            mapping.add(start until result.size)
        }
        // Where each anchor landed in a chapter that was split, indexed once per chapter: a table of contents
        // with thousands of entries into one long chapter would otherwise search all of it for each entry.
        val anchorsIn = HashMap<Int, Map<String, Int>>()
        fun anchorIndex(range: IntRange): Map<String, Int> = anchorsIn.getOrPut(range.first) {
            val index = HashMap<String, Int>()
            for (idx in range) for (b in result[idx].blocks) for (a in b.anchors) index.putIfAbsent(a.id, idx)
            index
        }
        toc = if (toc.isEmpty()) buildToc(result) else toc.map { item ->
            val range = mapping.getOrNull(item.chapter) ?: return@map item
            if (range.first == range.last || item.anchor == null) item.copy(chapter = range.first)
            else item.copy(chapter = anchorIndex(range)[item.anchor] ?: range.first)
        }
        var meta = book.meta
        if (meta.language == null) TextDirection.guessCjkLanguage(sample(result))?.let { meta = meta.copy(language = it) }
        if (!meta.rtl) {
            val rtl = if (meta.language != null) TextDirection.isRtlLanguage(meta.language) else TextDirection.looksRtl(sample(result))
            if (rtl) meta = meta.copy(rtl = true)
        }
        return ParsedBook(meta, result, toc, book.resources)
    }

    private fun sample(chapters: List<Chapter>): String {
        val sb = StringBuilder()
        for (c in chapters) for (b in c.blocks) {
            sb.append(b.text)
            if (sb.length > 3000) return sb.toString()
        }
        return sb.toString()
    }

    private fun splitByHeadings(chapter: Chapter): List<Chapter> {
        val blocks = chapter.blocks
        val level = (1..3).firstOrNull { lvl -> blocks.count { it.kind == BlockKind.HEADING && it.level == lvl } >= 2 } ?: return listOf(chapter)
        val out = ArrayList<Chapter>()
        var cur = ArrayList<Block>()
        // Whether the current section has anything but its headings yet; tracked rather than searched for at
        // each heading, which was quadratic in a run of consecutive headings.
        var hasBody = false
        var title = chapter.title
        for (b in blocks) {
            val splits = b.kind == BlockKind.HEADING && b.level <= level
            if (splits && hasBody) {
                out.add(Chapter(title, "${chapter.href}#${out.size}", cur))
                cur = ArrayList()
                hasBody = false
            }
            if (splits) title = b.text.replace('\n', ' ').trim() else hasBody = true
            cur.add(b)
        }
        if (cur.isNotEmpty()) out.add(Chapter(title, "${chapter.href}#${out.size}", cur))
        return out.mapIndexed { i, c -> if (i == 0) c.copy(href = chapter.href) else c }
    }

    private fun splitBySize(chapter: Chapter): List<Chapter> {
        if (chapter.textLength <= MAX_CHAPTER_CHARS) return listOf(chapter)
        val out = ArrayList<Chapter>()
        var cur = ArrayList<Block>()
        var len = 0
        for (b in chapter.blocks) {
            val bl = b.length + 1
            if (len + bl > MAX_CHAPTER_CHARS && cur.isNotEmpty()) {
                out.add(Chapter(chapter.title, if (out.isEmpty()) chapter.href else "${chapter.href}~${out.size}", cur))
                cur = ArrayList()
                len = 0
            }
            if (bl > MAX_CHAPTER_CHARS * 2 && b.kind == BlockKind.PARAGRAPH && b.runs.size == 1) {
                val text = b.text
                var start = 0
                while (start < text.length) {
                    var end = (start + MAX_CHAPTER_CHARS).coerceAtMost(text.length)
                    if (end < text.length) {
                        val sp = text.lastIndexOf(' ', end)
                        if (sp > start) end = sp + 1
                    }
                    val s0 = start
                    val e0 = end
                    cur.add(b.copy(runs = listOf(b.runs[0].copy(text = text.substring(start, end))), anchors = if (start == 0) b.anchors else emptyList(), ruby = b.ruby.filter { it.start >= s0 && it.end <= e0 }.map { it.copy(start = it.start - s0, end = it.end - s0) }))
                    out.add(Chapter(chapter.title, if (out.isEmpty()) chapter.href else "${chapter.href}~${out.size}", cur))
                    cur = ArrayList()
                    start = end
                }
                continue
            }
            cur.add(b)
            len += bl
        }
        if (cur.isNotEmpty()) out.add(Chapter(chapter.title, if (out.isEmpty()) chapter.href else "${chapter.href}~${out.size}", cur))
        return out
    }

    private fun buildToc(chapters: List<Chapter>): List<TocItem> {
        val out = ArrayList<TocItem>()
        var lastTitle: String? = null
        chapters.forEachIndexed { i, c ->
            val headings = c.blocks.filter { it.kind == BlockKind.HEADING && it.level <= 3 }
            val title = c.title.ifBlank { headings.firstOrNull()?.text?.replace('\n', ' ')?.trim().orEmpty() }
            if (title.isNotEmpty() && title != lastTitle) {
                out.add(TocItem(title.take(120), i, null, 0))
                lastTitle = title
            }
        }
        return if (out.size <= 1 && chapters.size > 1) chapters.indices.map { i -> TocItem("Part ${i + 1}", i, null, 0) } else out
    }
}
