package com.readarea.data

import android.content.Context
import android.net.Uri
import java.io.File

object SafeFiles {
    private val unsafe = Regex("[\\u0000-\\u001f\\u007f:*?\"<>|]")
    val linkSchemes = setOf("http", "https", "mailto")

    fun fileName(raw: String?, fallback: String = "book"): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').replace(unsafe, "_").trim().trimStart('.', ' ').trimEnd('.', ' ')
        if (base.isEmpty()) return fallback
        if (base.length <= 120) return base
        val ext = base.substringAfterLast('.', "").take(10)
        return base.take(110).trimEnd('.', ' ') + if (ext.isNotEmpty()) ".$ext" else ""
    }

    fun inside(dir: File, file: File): Boolean = runCatching { file.canonicalPath.startsWith(dir.canonicalPath + File.separator) }.getOrDefault(false)

    fun isPrivate(context: Context, uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path ?: return true
                val f = File(path)
                listOfNotNull(context.dataDir, context.filesDir.parentFile, File(context.applicationInfo.dataDir)).any { it == f || inside(it, f) }
            }
            "content" -> uri.authority?.startsWith(context.packageName) == true
            else -> true
        }
    }

    fun isOpenableLink(uri: Uri): Boolean = uri.scheme?.lowercase() in linkSchemes
}
