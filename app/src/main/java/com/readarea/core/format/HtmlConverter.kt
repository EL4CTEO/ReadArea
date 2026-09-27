package com.readarea.core.format

class HtmlConverter(
    private val basePath: String,
    private val stylesheet: Stylesheet = Stylesheet(),
    private val loadCss: ((String) -> String?)? = null,
) : HtmlHandler {

    private class Frame(
        val tag: String,
        val isBlock: Boolean,
        val style: Int,
        val scale: Float,
        val link: String?,
        val align: Align?,
        val hidden: Boolean,
        val upper: Boolean,
        val noIndent: Boolean,
        val kind: BlockKind?,
        val level: Int,
        var counter: Int = 0,
        val ordered: Boolean = false,
    )

    val blocks = ArrayList<Block>()
    var docTitle: String? = null
        private set
    var firstHeading: String? = null
        private set

    private val stack = ArrayList<Frame>()
    private val runs = ArrayList<Run>()
    private val runText = StringBuilder()
    private var runStyle = 0
    private var runLink: String? = null
    private var runScale = 1f
    private var blockLen = 0
    private var blockOpen = false
    private var blockKind = BlockKind.PARAGRAPH
    private var blockAlign: Align? = null
    private var blockLevel = 0
    private var blockNoIndent = false
    private var blockHasBreak = false
    private val blockAnchors = ArrayList<Anchor>()
    private val pendingAnchors = ArrayList<Anchor>()
    private var pendingPrefix: String? = null
    private var lastWasSpace = true
    private var inHead = false
    private var inTitle = false
    private val titleText = StringBuilder()

    fun convert(html: String): List<Block> {
        HtmlTokenizer(html).parse(this)
        finish()
        return blocks
    }

    private fun top(): Frame? = stack.lastOrNull()

    override fun startTag(name: String, attrs: Attributes, selfClosing: Boolean) {
        when (name) {
            "head" -> { inHead = true; return }
            "title" -> if (inHead || blocks.isEmpty() && !blockOpen) {
                inTitle = !selfClosing
                titleText.setLength(0)
                return
            }
            "link" -> {
                if (attrs["rel"]?.contains("stylesheet", true) == true) {
                    attrs["href"]?.let { href -> loadCss?.invoke(PathUtil.resolve(basePath, href))?.let { stylesheet.add(it) } }
                }
                return
            }
            "meta", "base" -> return
        }
        if (inHead) return
        val parent = top()
        if (parent?.hidden == true) {
            if (!selfClosing && name !in VOID) stack.add(Frame(name, false, 0, 1f, null, null, true, false, false, null, 0))
            return
        }
        val id = attrs["id"] ?: if (name == "a") attrs["name"] else null
        if (id != null) addAnchor(id)

        val css = stylesheet.compute(name, attrs["class"], attrs["id"], attrs["style"])
        val display = css["display"]
        val hidden = display == "none" || name in HIDDEN || attrs["hidden"] != null

        when (name) {
            "br" -> {
                if (!hidden) lineBreak()
                return
            }
            "hr" -> {
                if (!hidden) {
                    flushBlock()
                    addBlock(Block(BlockKind.RULE, anchors = takePending()))
                }
                return
            }
            "img", "image" -> {
                if (!hidden) {
                    val src = attrs["src"] ?: attrs["href"] ?: attrs["xlink:href"] ?: attrs["recindex"]?.let { "recindex:$it" }
                    if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                        flushBlock()
                        addBlock(Block(BlockKind.IMAGE, runs = listOf(Run(attrs["alt"] ?: "")), image = resolveImage(src), anchors = takePending(), align = Align.CENTER))
                    }
                }
                if (!selfClosing && name == "image") stack.add(Frame(name, false, parent?.style ?: 0, parent?.scale ?: 1f, parent?.link, parent?.align, true, false, false, null, 0))
                return
            }
        }
        if (name in VOID) return

        if (name == "p" || name == "li" || name == "dt" || name == "dd") {
            val idx = stack.indexOfLast { it.tag == name }
            val stopIdx = stack.indexOfLast { it.tag in STOPS }
            if (idx >= 0 && idx > stopIdx) popTo(idx)
        }

        val p = top()
        val isBlock = when (display) {
            "block", "list-item", "table", "table-row", "flex", "grid" -> true
            "inline", "inline-block" -> false
            else -> name in BLOCKS
        }
        var style = p?.style ?: 0
        var scale = p?.scale ?: 1f
        var align = p?.align
        var upper = p?.upper ?: false
        var noIndent = p?.noIndent ?: false
        var kind: BlockKind? = null
        var level = 0
        var prefix: String? = null
        var link = p?.link
        when (name) {
            "b", "strong", "th", "dt" -> style = style or RunStyle.BOLD
            "i", "em", "cite", "dfn", "var", "address" -> style = style or RunStyle.ITALIC
            "u", "ins" -> style = style or RunStyle.UNDERLINE
            "s", "strike", "del" -> style = style or RunStyle.STRIKE
            "sup" -> style = style or RunStyle.SUP
            "sub" -> style = style or RunStyle.SUB
            "code", "tt", "kbd", "samp" -> style = style or RunStyle.MONO
            "small" -> scale *= 0.85f
            "big" -> scale *= 1.2f
            "mark" -> style = style or RunStyle.MARK
            "center" -> align = Align.CENTER
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                kind = BlockKind.HEADING
                level = name[1] - '0'
                style = style or RunStyle.BOLD
            }
            "blockquote" -> kind = BlockKind.QUOTE
            "pre" -> kind = BlockKind.PRE
            "li" -> {
                kind = BlockKind.LIST_ITEM
                val list = stack.lastOrNull { it.tag == "ul" || it.tag == "ol" }
                level = stack.count { it.tag == "ul" || it.tag == "ol" }.coerceAtLeast(1)
                prefix = if (list != null) {
                    list.counter++
                    if (list.ordered) "${list.counter}. " else if (level % 2 == 0) "◦ " else "• "
                } else "• "
            }
            "figcaption", "caption" -> kind = BlockKind.CAPTION
            "a" -> {
                val href = attrs["href"] ?: attrs["xlink:href"] ?: attrs["filepos"]?.let { "#filepos${it.trimStart('0').ifEmpty { "0" }}" }
                if (!href.isNullOrBlank()) link = resolveLink(href)
            }
        }
        val cls = attrs["class"]?.lowercase() ?: ""
        if (isBlock && (cls.contains("poem") || cls.contains("stanza") || cls.contains("verse"))) kind = BlockKind.VERSE

        css["font-weight"]?.let { w ->
            val num = w.toIntOrNull()
            style = if (w == "bold" || w == "bolder" || (num != null && num >= 600)) style or RunStyle.BOLD
            else if (w == "normal" || w == "lighter" || (num != null && num < 600)) style and RunStyle.BOLD.inv() else style
        }
        css["font-style"]?.let { s ->
            style = if (s.startsWith("italic") || s.startsWith("oblique")) style or RunStyle.ITALIC else if (s == "normal") style and RunStyle.ITALIC.inv() else style
        }
        (css["text-decoration"] ?: css["text-decoration-line"])?.let { d ->
            if (d.contains("underline")) style = style or RunStyle.UNDERLINE
            if (d.contains("line-through")) style = style or RunStyle.STRIKE
            if (d == "none") style = style and (RunStyle.UNDERLINE or RunStyle.STRIKE).inv()
        }
        css["vertical-align"]?.let { v ->
            if (v == "super") style = style or RunStyle.SUP
            if (v == "sub") style = style or RunStyle.SUB
        }
        css["font-family"]?.let { f -> if (f.contains("monospace") || f.contains("courier")) style = style or RunStyle.MONO }
        if (kind != BlockKind.HEADING) Stylesheet.fontScale(css["font-size"])?.let { scale = (scale * it).coerceIn(0.6f, 2.2f) }
        css["text-align"]?.let { a ->
            align = when (a) {
                "center", "-webkit-center" -> Align.CENTER
                "right", "end" -> Align.END
                "left", "start" -> Align.START
                "justify" -> Align.JUSTIFY
                else -> align
            }
        }
        (attrs["align"])?.let { a -> if (a.equals("center", true)) align = Align.CENTER else if (a.equals("right", true)) align = Align.END }
        css["text-transform"]?.let { upper = it == "uppercase" }
        css["text-indent"]?.let { t -> noIndent = t.startsWith("0") || t.startsWith("-") }
        if (css["font-variant"] == "small-caps") scale *= 0.9f

        if (isBlock) flushBlock()
        if (prefix != null) pendingPrefix = prefix
        val frame = Frame(name, isBlock, style, scale, link, align, hidden, upper, noIndent, kind, level, ordered = name == "ol")
        if (name == "ol") frame.counter = (attrs["start"]?.toIntOrNull() ?: 1) - 1
        if (selfClosing) {
            if (isBlock) flushBlock()
            return
        }
        stack.add(frame)
    }

    override fun endTag(name: String) {
        when (name) {
            "head" -> { inHead = false; return }
            "title" -> if (inTitle) {
                inTitle = false
                docTitle = titleText.toString().trim().replace(WS, " ").ifEmpty { null }
                return
            }
        }
        if (inHead) return
        val idx = stack.indexOfLast { it.tag == name }
        if (idx < 0) return
        popTo(idx)
    }

    private fun popTo(idx: Int) {
        var hadBlock = false
        while (stack.size > idx) {
            val f = stack.removeAt(stack.size - 1)
            if (f.isBlock) hadBlock = true
        }
        if (hadBlock) flushBlock()
    }

    override fun text(text: String) {
        if (inTitle) {
            titleText.append(text)
            return
        }
        if (inHead) return
        val f = top()
        if (f?.hidden == true) return
        val pre = stack.any { it.tag == "pre" }
        val content = if (f?.upper == true) text.uppercase() else text
        for (ch in content) {
            if (pre) {
                when (ch) {
                    '\r' -> {}
                    '\n' -> if (blockOpen) appendChar('\n', f) else { }
                    '\t' -> { appendChar(' ', f); appendChar(' ', f); appendChar(' ', f); appendChar(' ', f) }
                    else -> appendChar(ch, f)
                }
                lastWasSpace = false
                continue
            }
            if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r' || ch == '\u000C') {
                if (!lastWasSpace && blockOpen) {
                    appendChar(' ', f)
                    lastWasSpace = true
                }
            } else {
                appendChar(ch, f)
                lastWasSpace = false
            }
        }
    }

    override fun rawText(tag: String, content: String) {
        if (tag == "style") stylesheet.add(content)
    }

    private fun appendChar(ch: Char, f: Frame?) {
        if (!blockOpen) openBlock(f)
        val style = f?.style ?: 0
        val link = f?.link
        val scale = f?.scale ?: 1f
        if (runText.isNotEmpty() && (style != runStyle || link != runLink || scale != runScale)) flushRun()
        runStyle = style
        runLink = link
        runScale = scale
        runText.append(ch)
        blockLen++
    }

    private fun openBlock(f: Frame?) {
        blockOpen = true
        blockLen = 0
        var kind = BlockKind.PARAGRAPH
        var level = 0
        val heading = stack.lastOrNull { it.kind == BlockKind.HEADING }
        when {
            heading != null -> { kind = BlockKind.HEADING; level = heading.level }
            stack.any { it.kind == BlockKind.PRE } -> kind = BlockKind.PRE
            stack.any { it.kind == BlockKind.CAPTION } -> kind = BlockKind.CAPTION
            stack.any { it.kind == BlockKind.LIST_ITEM } -> { kind = BlockKind.LIST_ITEM; level = stack.last { it.kind == BlockKind.LIST_ITEM }.level }
            stack.any { it.kind == BlockKind.VERSE } -> kind = BlockKind.VERSE
            stack.any { it.kind == BlockKind.QUOTE } -> { kind = BlockKind.QUOTE; level = stack.count { it.kind == BlockKind.QUOTE } }
        }
        blockKind = kind
        blockLevel = level
        blockAlign = f?.align
        blockNoIndent = f?.noIndent ?: false
        blockHasBreak = false
        blockAnchors.clear()
        blockAnchors.addAll(pendingAnchors)
        pendingAnchors.clear()
        val prefix = pendingPrefix
        pendingPrefix = null
        if (prefix != null) {
            runText.append(prefix)
            runStyle = 0
            runLink = null
            runScale = 1f
            blockLen += prefix.length
            flushRun()
        }
    }

    private fun lineBreak() {
        if (!blockOpen) return
        val f = top()
        trimTrailingSpaces()
        appendChar('\n', f)
        blockHasBreak = true
        lastWasSpace = true
    }

    private fun flushRun() {
        if (runText.isEmpty()) return
        runs.add(Run(runText.toString(), runStyle, runLink, runScale))
        runText.setLength(0)
    }

    private fun trimTrailingSpaces() {
        while (runText.isNotEmpty() && (runText.last() == ' ')) {
            runText.setLength(runText.length - 1)
            blockLen--
        }
    }

    private fun flushBlock() {
        if (!blockOpen) {
            pendingPrefix = null
            return
        }
        flushRun()
        while (runs.isNotEmpty()) {
            val last = runs.last()
            val trimmed = last.text.trimEnd(' ', '\n')
            if (trimmed.isEmpty()) {
                runs.removeAt(runs.size - 1)
            } else {
                if (trimmed.length != last.text.length) runs[runs.size - 1] = last.copy(text = trimmed)
                break
            }
        }
        val len = runs.sumOf { it.text.length }
        val anchors = blockAnchors.map { if (it.offset > len) it.copy(offset = len) else it }
        if (runs.isNotEmpty() && runs.any { r -> r.text.any { !it.isWhitespace() || it == ' ' } }) {
            val block = Block(
                kind = blockKind,
                runs = ArrayList(runs),
                align = blockAlign,
                level = blockLevel,
                anchors = anchors,
                noIndent = blockNoIndent || blockHasBreak || blockKind != BlockKind.PARAGRAPH,
            )
            if (blockKind == BlockKind.HEADING && firstHeading == null) firstHeading = block.text.replace('\n', ' ').trim()
            addBlock(block)
        } else {
            pendingAnchors.addAll(0, anchors.map { it.copy(offset = 0) })
        }
        runs.clear()
        runText.setLength(0)
        blockOpen = false
        blockLen = 0
        lastWasSpace = true
    }

    private fun addBlock(block: Block) {
        blocks.add(block)
    }

    private fun addAnchor(id: String) {
        if (blockOpen) blockAnchors.add(Anchor(id, blockLen)) else pendingAnchors.add(Anchor(id, 0))
    }

    private fun takePending(): List<Anchor> {
        val list = ArrayList(pendingAnchors)
        pendingAnchors.clear()
        return list
    }

    private fun finish() {
        flushBlock()
        if (pendingAnchors.isNotEmpty()) {
            if (blocks.isEmpty()) {
                blocks.add(Block(BlockKind.PARAGRAPH, listOf(Run(" ")), anchors = takePending()))
            } else {
                val last = blocks.last()
                blocks[blocks.size - 1] = last.copy(anchors = last.anchors + takePending().map { it.copy(offset = last.length) })
            }
        }
    }

    private fun resolveImage(src: String): String {
        if (src.startsWith("recindex:") || src.startsWith("kindle:")) return src
        if (src.startsWith("#")) return src.substring(1)
        return PathUtil.resolve(basePath, src.substringBefore('#'))
    }

    private fun resolveLink(href: String): String {
        val h = href.trim()
        if (h.contains("://") || h.startsWith("mailto:") || h.startsWith("tel:")) return h
        if (h.startsWith("#")) return "$basePath$h"
        val file = h.substringBefore('#')
        val frag = h.substringAfter('#', "")
        val resolved = PathUtil.resolve(basePath, file)
        return if (frag.isEmpty()) resolved else "$resolved#$frag"
    }

    companion object {
        private val WS = Regex("\\s+")
        private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "area", "base", "col", "embed", "param", "source", "track", "wbr")
        private val HIDDEN = setOf("script", "style", "noscript", "template", "button", "select", "input", "textarea", "iframe", "object", "audio", "video", "canvas", "math", "map")
        private val STOPS = setOf("ul", "ol", "dl", "table", "blockquote", "div", "section", "body")
        val BLOCKS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "dt", "dd", "pre", "section", "article",
            "aside", "header", "footer", "figure", "figcaption", "main", "address", "center", "table", "tr", "caption",
            "ul", "ol", "dl", "body", "html", "nav", "hgroup", "details", "summary", "tbody", "thead", "tfoot",
        )
    }
}

object PathUtil {
    fun resolve(base: String, relative: String): String {
        val rel = decode(relative.trim())
        if (rel.startsWith("/")) return normalize(rel.substring(1))
        val dir = base.substringBeforeLast('/', "")
        return normalize(if (dir.isEmpty()) rel else "$dir/$rel")
    }

    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun decode(s: String): String {
        if (!s.contains('%')) return s
        return try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            s
        }
    }
}
