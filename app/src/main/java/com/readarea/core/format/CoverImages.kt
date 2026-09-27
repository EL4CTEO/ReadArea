package com.readarea.core.format

object CoverImages {
    val DOCX_EMBED = Regex("<a:blip\\b[^>]*\\br:embed=\"([^\"]+)\"")
    val DOCX_TEXT = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>")
    val ODT_IMAGE = Regex("<draw:image\\b[^>]*\\bxlink:href=\"([^\"]+)\"")
    private val TAGS = Regex("<[^>]+>")

    fun isRaster(path: String): Boolean {
        val l = path.lowercase().substringBefore('?')
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".bmp")
    }

    fun textLength(markup: String): Int = Entities.decode(markup.replace(TAGS, " ")).count { !it.isWhitespace() }
}
