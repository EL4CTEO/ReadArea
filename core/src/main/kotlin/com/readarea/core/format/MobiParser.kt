package com.readarea.core.format

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MobiParser(private val data: ByteArray, private val fallbackTitle: String) {

    private val buf = ByteBuffer.wrap(data)
    private lateinit var offsets: IntArray
    private var firstImage = -1
    private var encoding = "windows-1252"

    private fun record(i: Int): ByteArray {
        val start = offsets[i]
        val end = if (i + 1 < offsets.size) offsets[i + 1] else data.size
        if (start < 0 || end > data.size || start > end) return ByteArray(0)
        return data.copyOfRange(start, end)
    }

    fun parse(metadataOnly: Boolean = false): ParsedBook {
        if (data.size < 78 + 8) throw BookParseException(ParseError.INVALID, "Invalid MOBI file")
        val type = String(data, 60, 8, Charsets.ISO_8859_1)
        if (type != "BOOKMOBI" && type != "TEXtREAd") throw BookParseException(ParseError.UNSUPPORTED, "Unsupported MOBI variant")
        val count = u16(76)
        if (count == 0 || 78L + count * 8L > data.size) throw BookParseException(ParseError.INVALID, "Invalid MOBI file")
        offsets = IntArray(count) { u32(78 + it * 8) }
        val r0 = record(0)
        if (r0.size < 16) throw BookParseException(ParseError.INVALID, "Invalid MOBI file")
        val r0b = ByteBuffer.wrap(r0)
        val compression = r0b.getShort(0).toInt() and 0xFFFF
        val recordCount = r0b.getShort(8).toInt() and 0xFFFF
        val encryption = r0b.getShort(12).toInt() and 0xFFFF
        if (encryption != 0) throw BookParseException(ParseError.DRM, "This book is DRM-protected and can't be opened")
        var title = String(data, 0, 32, Charsets.ISO_8859_1).trim('\u0000', ' ').replace('_', ' ')
        var author = ""
        var description: String? = null
        var publisher: String? = null
        var language: String? = null
        var coverIndex = -1
        var thumbIndex = -1
        var extraFlags = 0
        // Every offset and length below comes from the file, so each is checked before it's used.
        val hasMobi = r0.size >= 132 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (hasMobi) {
            val headerLen = r0b.getInt(20)
            val enc = r0b.getInt(28)
            encoding = if (enc == 65001) "UTF-8" else "windows-1252"
            val nameOff = r0b.getInt(84)
            val nameLen = r0b.getInt(88)
            if (nameOff > 0 && nameLen >= 0 && nameOff.toLong() + nameLen <= r0.size) title = String(r0, nameOff, nameLen, charset(encoding))
            firstImage = r0b.getInt(108)
            if (headerLen >= 0xE4 && 0xF4 <= r0.size) extraFlags = r0b.getShort(0xF2).toInt() and 0xFFFF
            val exthFlags = r0b.getInt(128)
            if (exthFlags and 0x40 != 0) {
                val exth = 16L + headerLen
                if (headerLen >= 0 && exth + 12 <= r0.size && String(r0, exth.toInt(), 4, Charsets.ISO_8859_1) == "EXTH") {
                    val n = r0b.getInt(exth.toInt() + 8)
                    var p = exth.toInt() + 12
                    val authors = ArrayList<String>()
                    var k = 0
                    while (k++ < n && p + 8 <= r0.size) {
                        val t = r0b.getInt(p)
                        val len = r0b.getInt(p + 4)
                        if (len < 8 || p.toLong() + len > r0.size) break
                        val value = r0.copyOfRange(p + 8, p + len)
                        when (t) {
                            100 -> authors.add(String(value, charset(encoding)).trim())
                            101 -> publisher = String(value, charset(encoding)).trim()
                            103 -> description = EpubParser.stripTags(String(value, charset(encoding)))
                            201 -> if (value.size >= 4) coverIndex = ByteBuffer.wrap(value).int
                            202 -> if (value.size >= 4) thumbIndex = ByteBuffer.wrap(value).int
                            503 -> title = String(value, charset(encoding)).trim()
                            524 -> language = String(value, charset(encoding)).trim()
                        }
                        p += len
                    }
                    author = authors.distinct().joinToString(", ")
                }
            }
        }
        val coverIdx = if (coverIndex >= 0) coverIndex else thumbIndex
        val coverRef = if (coverIdx >= 0 && firstImage > 0) "img:${firstImage + coverIdx}" else null
        val resources = ResourceProvider { path -> imageFor(path) }
        val meta = BookMeta(title = title.ifBlank { fallbackTitle }, author = author, language = language, description = description, publisher = publisher, coverRef = coverRef)
        if (metadataOnly) return ParsedBook(meta, emptyList(), emptyList(), resources)

        if (compression == 17480) throw BookParseException(ParseError.UNSUPPORTED, "This MOBI uses Huffman compression, which isn't supported yet")
        val raw = ByteArrayOutputStream()
        for (i in 1..recordCount.coerceAtMost(offsets.size - 1)) {
            var rec = record(i)
            val trail = trailingSize(rec, extraFlags)
            if (trail in 1..rec.size) rec = rec.copyOf(rec.size - trail)
            raw.write(if (compression == 2) palmDocDecompress(rec) else rec)
            if (raw.size() > Limits.MARKUP) throw BookParseException(ParseError.TOO_LARGE, "This book is too large to open")
        }
        var bytes = raw.toByteArray()
        bytes = insertFileposAnchors(bytes)
        val html = String(bytes, charset(encoding))
        val parts = html.split(PAGEBREAK).filter { it.isNotBlank() }
        val chapters = ArrayList<Chapter>()
        parts.forEachIndexed { i, part ->
            val href = "part$i"
            val conv = HtmlConverter(href)
            val blocks = conv.convert(part)
            if (blocks.isNotEmpty()) chapters.add(Chapter(conv.firstHeading ?: "", href, blocks))
        }
        if (chapters.isEmpty()) throw BookParseException(ParseError.EMPTY, "This book appears to be empty")
        return ParsedBook(meta, chapters, emptyList(), resources)
    }

    private fun imageFor(path: String): ByteArray? {
        val idx = when {
            path.startsWith("img:") -> path.removePrefix("img:").toIntOrNull()
            path.startsWith("recindex:") -> path.removePrefix("recindex:").toIntOrNull()?.let { firstImage + it - 1 }
            path.startsWith("kindle:embed:") -> path.removePrefix("kindle:embed:").substringBefore('?').let { base32(it) }?.let { firstImage + it - 1 }
            else -> null
        } ?: return null
        if (idx < 0 || idx >= offsets.size) return null
        return record(idx)
    }

    private fun base32(s: String): Int? {
        var v = 0
        for (c in s.uppercase()) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'V' -> c - 'A' + 10
                else -> return null
            }
            v = v * 32 + d
        }
        return v
    }

    private fun insertFileposAnchors(bytes: ByteArray): ByteArray {
        val ascii = String(bytes, Charsets.ISO_8859_1)
        val positions = FILEPOS.findAll(ascii).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 0 until bytes.size }.toSortedSet()
        if (positions.isEmpty()) return bytes
        val out = ByteArrayOutputStream(bytes.size + positions.size * 24)
        var last = 0
        for (p in positions) {
            var pos = p
            if (pos < last) continue
            // Step back out of a tag the position falls inside. Only nearby: an unbounded search per
            // position would be quadratic on text with many positions and no tags.
            val floor = maxOf(last, pos - 2048)
            var lt = -1
            var gt = -1
            var k = pos
            while (k >= floor && gt < 0 && lt < 0) {
                when (ascii[k]) {
                    '<' -> lt = k
                    '>' -> gt = k
                }
                k--
            }
            if (lt >= 0) pos = lt
            out.write(bytes, last, pos - last)
            out.write("<a id=\"filepos$p\"></a>".toByteArray(Charsets.ISO_8859_1))
            last = pos
        }
        out.write(bytes, last, bytes.size - last)
        return out.toByteArray()
    }

    private fun u16(off: Int): Int = buf.getShort(off).toInt() and 0xFFFF
    private fun u32(off: Int): Int = buf.getInt(off)

    companion object {
        private val PAGEBREAK = Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE)
        private val FILEPOS = Regex("filepos=[\"']?0*(\\d+)", RegexOption.IGNORE_CASE)

        /** Text records hold 4 KB; anything decompressing past this is malformed or hostile. */
        private const val MAX_RECORD_OUTPUT = 1 shl 20

        fun palmDocDecompress(input: ByteArray): ByteArray {
            var buffer = ByteArray(minOf(input.size * 2 + 16, 64 * 1024))
            var len = 0
            fun put(b: Int) {
                if (len >= buffer.size) {
                    if (buffer.size >= MAX_RECORD_OUTPUT) throw BookParseException(ParseError.INVALID, "Invalid MOBI text record")
                    buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_RECORD_OUTPUT))
                }
                buffer[len++] = b.toByte()
            }
            var i = 0
            while (i < input.size) {
                val c = input[i++].toInt() and 0xFF
                when {
                    c in 1..8 -> {
                        var k = 0
                        while (k < c && i < input.size) {
                            put(input[i++].toInt())
                            k++
                        }
                    }
                    c < 0x80 -> put(c)
                    c >= 0xC0 -> {
                        put(' '.code)
                        put(c xor 0x80)
                    }
                    else -> {
                        if (i >= input.size) break
                        val next = input[i++].toInt() and 0xFF
                        val v = (c shl 8) or next
                        val dist = (v shr 3) and 0x7FF
                        val n = (v and 7) + 3
                        if (dist in 1..len) {
                            repeat(n) { put(buffer[len - dist].toInt()) }
                        }
                    }
                }
            }
            return buffer.copyOf(len)
        }

        fun trailingSize(data: ByteArray, flags: Int): Int {
            var num = 0
            var test = flags shr 1
            while (test != 0) {
                if (test and 1 != 0) num += trailingEntry(data, data.size - num)
                test = test shr 1
            }
            if (flags and 1 != 0 && data.size - num - 1 >= 0) num += (data[data.size - num - 1].toInt() and 0x3) + 1
            return num
        }

        private fun trailingEntry(data: ByteArray, sizeIn: Int): Int {
            var size = sizeIn
            var bitpos = 0
            var result = 0
            if (size <= 0) return 0
            while (true) {
                val v = data[size - 1].toInt() and 0xFF
                result = result or ((v and 0x7F) shl bitpos)
                bitpos += 7
                size -= 1
                if (v and 0x80 != 0 || bitpos >= 28 || size == 0) return result
            }
        }
    }
}
