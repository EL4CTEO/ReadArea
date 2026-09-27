package com.readarea.core.format

enum class BookFormat(val label: String, val extensions: List<String>, val fixedLayout: Boolean) {
    EPUB("EPUB", listOf("epub"), false),
    PDF("PDF", listOf("pdf"), true),
    MOBI("MOBI", listOf("mobi", "azw", "azw3", "prc"), false),
    FB2("FB2", listOf("fb2", "fb2.zip", "fbz"), false),
    TXT("TXT", listOf("txt", "text"), false),
    HTML("HTML", listOf("html", "htm", "xhtml"), false),
    MD("Markdown", listOf("md", "markdown"), false),
    DOCX("DOCX", listOf("docx"), false),
    ODT("ODT", listOf("odt"), false),
    RTF("RTF", listOf("rtf"), false),
    CBZ("Comic", listOf("cbz"), true);

    companion object {
        fun fromFileName(name: String): BookFormat? {
            val lower = name.lowercase()
            return entries.firstOrNull { f -> f.extensions.any { lower.endsWith(".$it") } }
        }

        fun fromMime(mime: String?): BookFormat? = when (mime?.lowercase()) {
            "application/epub+zip" -> EPUB
            "application/pdf" -> PDF
            "application/x-mobipocket-ebook", "application/vnd.amazon.ebook" -> MOBI
            "application/x-fictionbook+xml", "application/x-fictionbook", "text/fb2+xml" -> FB2
            "text/plain" -> TXT
            "text/html", "application/xhtml+xml" -> HTML
            "text/markdown", "text/x-markdown" -> MD
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
            "application/vnd.oasis.opendocument.text" -> ODT
            "application/rtf", "text/rtf" -> RTF
            "application/vnd.comicbook+zip", "application/x-cbz" -> CBZ
            else -> null
        }

        fun byName(name: String): BookFormat = entries.firstOrNull { it.name == name } ?: TXT
    }
}

object RunStyle {
    const val BOLD = 1
    const val ITALIC = 2
    const val UNDERLINE = 4
    const val STRIKE = 8
    const val SUP = 16
    const val SUB = 32
    const val MONO = 64
    const val SMALL = 128
    const val BIG = 256
    const val UPPER = 512
    const val MARK = 1024
    const val EMPHASIS = 2048
}

data class Run(val text: String, val style: Int = 0, val link: String? = null, val scale: Float = 1f)

enum class Align { START, CENTER, END, JUSTIFY }

enum class BlockKind { PARAGRAPH, HEADING, QUOTE, PRE, LIST_ITEM, VERSE, IMAGE, RULE, CAPTION }

data class Anchor(val id: String, val offset: Int)

data class Ruby(val start: Int, val end: Int, val text: String)

data class Block(
    val kind: BlockKind,
    val runs: List<Run> = emptyList(),
    val align: Align? = null,
    val level: Int = 0,
    val image: String? = null,
    val anchors: List<Anchor> = emptyList(),
    val noIndent: Boolean = false,
    val scale: Float = 1f,
    val ruby: List<Ruby> = emptyList(),
) {
    val text: String get() = runs.joinToString("") { it.text }
    val length: Int get() = runs.sumOf { it.text.length }
}

data class Chapter(val title: String, val href: String, val blocks: List<Block>) {
    val textLength: Int get() = blocks.sumOf { if (it.kind == BlockKind.IMAGE) 1 else it.length + 1 }
}

data class TocItem(val title: String, val chapter: Int, val anchor: String?, val depth: Int)

data class BookMeta(
    val title: String,
    val author: String = "",
    val language: String? = null,
    val description: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val publisher: String? = null,
    val coverRef: String? = null,
    val rtl: Boolean = false,
    val vertical: Boolean = false,
)

object TextDirection {
    private val rtlLanguages = setOf("ar", "he", "iw", "fa", "ur", "yi", "ji", "ps", "dv", "ckb", "sd", "ug", "syr")

    fun isRtlLanguage(tag: String?): Boolean {
        val lang = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return false
        return lang in rtlLanguages
    }

    fun looksRtl(sample: CharSequence): Boolean {
        var rtl = 0
        var ltr = 0
        for (ch in sample) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
            }
        }
        return rtl > 40 && rtl > ltr * 2
    }

    fun isCjkLanguage(tag: String?): Boolean {
        val lang = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return false
        return lang == "ja" || lang == "zh" || lang == "ko" || lang == "yue" || lang == "cmn"
    }

    fun guessCjkLanguage(sample: CharSequence): String? {
        var kana = 0
        var han = 0
        var hangul = 0
        var letters = 0
        for (ch in sample) {
            val c = ch.code
            when {
                c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF9F -> kana++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF -> han++
                c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F -> hangul++
                ch.isLetter() -> letters++
            }
        }
        val cjk = kana + han + hangul
        if (cjk < 30 || cjk < letters) return null
        return when {
            hangul > kana && hangul > han / 2 -> "ko"
            kana * 10 > han -> "ja"
            else -> "zh"
        }
    }
}

fun interface ResourceProvider {
    fun read(path: String): ByteArray?
}

class ParsedBook(
    val meta: BookMeta,
    val chapters: List<Chapter>,
    val toc: List<TocItem>,
    val resources: ResourceProvider,
) {
    fun coverBytes(): ByteArray? = meta.coverRef?.let { resources.read(it) }
}

enum class ParseError { INVALID, DRM, UNSUPPORTED, EMPTY }

class BookParseException(val reason: ParseError, message: String) : Exception(message)
