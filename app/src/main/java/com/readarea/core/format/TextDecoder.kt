package com.readarea.core.format

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

object TextDecoder {
    private val XML_ENC = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']")
    private val META_ENC = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, hint: String? = null): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        if (bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte()) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte()) return String(bytes, Charsets.UTF_16BE)
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val declared = hint ?: XML_ENC.find(head)?.groupValues?.get(1) ?: META_ENC.find(head)?.groupValues?.get(1)
        if (declared != null && !declared.equals("utf-8", true) && !declared.equals("utf8", true)) {
            val cs = runCatching { Charset.forName(declared) }.getOrNull()
            if (cs != null) return String(bytes, cs)
        }
        strictUtf8(bytes)?.let { return it }
        return String(bytes, guessLegacy(bytes))
    }

    private fun strictUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun guessLegacy(bytes: ByteArray): Charset {
        var high = 0
        var cyrLower = 0
        val limit = minOf(bytes.size, 200_000)
        for (i in 0 until limit) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) {
                high++
                if (b in 0xE0..0xFF) cyrLower++
            }
        }
        if (high > 50 && cyrLower * 100 / high > 60) return Charset.forName("windows-1251")
        return Charset.forName("windows-1252")
    }
}

interface ZipAccess : ResourceProvider {
    val entries: List<String>
    fun close() {}
}

class FileZipAccess(file: File) : ZipAccess {
    private val zip = ZipFile(file)
    private val index: Map<String, String>
    override val entries: List<String>

    init {
        val names = ArrayList<String>()
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val entry = e.nextElement()
            if (!entry.isDirectory) names.add(entry.name)
        }
        entries = names
        index = names.associateBy { it.lowercase() }
    }

    override fun read(path: String): ByteArray? {
        val name = if (zip.getEntry(path) != null) path else index[path.lowercase()] ?: index[PathUtil.decode(path).lowercase()] ?: return null
        return synchronized(zip) {
            val entry = zip.getEntry(name) ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    override fun close() {
        runCatching { zip.close() }
    }
}

class MemoryZipAccess(input: java.io.InputStream, keep: (String, Long) -> Boolean) : ZipAccess {
    private val data = LinkedHashMap<String, ByteArray>()
    private val index = HashMap<String, String>()
    override val entries: List<String>

    init {
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                names.add(e.name)
                if (keep(e.name, e.size)) {
                    val bytes = zin.readBytes()
                    if (bytes.size <= MAX_ENTRY) {
                        data[e.name] = bytes
                        index[e.name.lowercase()] = e.name
                    }
                }
            }
        }
        entries = names
    }

    override fun read(path: String): ByteArray? = data[path] ?: index[path.lowercase()]?.let { data[it] } ?: index[PathUtil.decode(path).lowercase()]?.let { data[it] }

    companion object {
        const val MAX_ENTRY = 6 * 1024 * 1024
    }
}

class MapResources(private val map: Map<String, () -> ByteArray?>) : ResourceProvider {
    override fun read(path: String): ByteArray? = (map[path] ?: map[path.removePrefix("#")])?.invoke()
}
