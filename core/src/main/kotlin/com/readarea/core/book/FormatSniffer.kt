package com.readarea.core.book

import com.readarea.core.format.BookFormat
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Works out a file's format from its contents, falling back to the extension only for plain text.
 *
 * Files opened from elsewhere ("Open with", drag and drop, a downloads folder) often carry the wrong
 * extension or none. Trusting the contents instead of the name also means a file can't pick which
 * parser handles it just by being renamed.
 */
object FormatSniffer {

    fun detect(file: File): BookFormat? {
        val byName = BookFormat.fromFileName(file.name)
        val head = runCatching { head(file, 4096) }.getOrNull() ?: return null
        return fromContent(head, file) ?: byName?.takeIf { it.isText() && looksLikeText(head) }
    }

    private fun BookFormat.isText() = this == BookFormat.TXT || this == BookFormat.MD || this == BookFormat.HTML || this == BookFormat.FB2 || this == BookFormat.RTF

    fun head(file: File, max: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val n = minOf(raf.length(), max.toLong()).toInt()
        ByteArray(n).also { raf.readFully(it) }
    }

    private fun fromContent(head: ByteArray, file: File): BookFormat? {
        if (head.startsWith("%PDF-") || head.indexOf("%PDF-", 1024) >= 0) return BookFormat.PDF
        if (head.size >= 68 && (String(head, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" || it == "TEXtREAd" })) return BookFormat.MOBI
        if (head.startsWith("{\\rtf")) return BookFormat.RTF
        if (head.startsWith("PK\u0003\u0004")) return zipFormat(file)
        val text = decodeHead(head)
        val lower = text.trimStart('﻿', ' ', '\t', '\r', '\n').take(2048).lowercase()
        if (lower.contains("<fictionbook")) return BookFormat.FB2
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || (lower.startsWith("<?xml") && lower.contains("<html"))) return BookFormat.HTML
        return null
    }

    private fun zipFormat(file: File): BookFormat? = runCatching {
        ZipFile(file).use { zip ->
            val mimetype = zip.getEntry("mimetype")?.let { e -> zip.getInputStream(e).use { String(it.readNBytes(100), Charsets.US_ASCII).trim() } }
            when {
                mimetype == "application/epub+zip" -> return BookFormat.EPUB
                mimetype == "application/vnd.oasis.opendocument.text" -> return BookFormat.ODT
                zip.getEntry("META-INF/container.xml") != null -> return BookFormat.EPUB
                zip.getEntry("word/document.xml") != null -> return BookFormat.DOCX
            }
            var images = 0
            var checked = 0
            val entries = zip.entries()
            while (entries.hasMoreElements() && checked < 2000) {
                val e = entries.nextElement()
                checked++
                if (e.isDirectory) continue
                if (e.name.endsWith(".fb2", true)) return BookFormat.FB2
                if (ComicPages.isImage(e.name)) images++
            }
            if (images > 0) BookFormat.CBZ else null
        }
    }.getOrNull()

    private fun decodeHead(head: ByteArray): String = when {
        head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> String(head, Charsets.UTF_16LE)
        head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> String(head, Charsets.UTF_16BE)
        else -> String(head, Charsets.ISO_8859_1)
    }

    /** No NUL bytes outside UTF-16 text: binaries renamed to .txt are not books. */
    fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        if (head.size >= 2 && (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() || head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte())) return true
        var nul = 0
        for (b in head) if (b == 0.toByte()) nul++
        return nul == 0
    }

    private fun ByteArray.startsWith(s: String): Boolean {
        if (size < s.length) return false
        for (i in s.indices) if (this[i] != s[i].code.toByte()) return false
        return true
    }

    private fun ByteArray.indexOf(s: String, limit: Int): Int {
        val end = minOf(size - s.length, limit)
        outer@ for (i in 0..end) {
            for (k in s.indices) if (this[i + k] != s[k].code.toByte()) continue@outer
            return i
        }
        return -1
    }
}
