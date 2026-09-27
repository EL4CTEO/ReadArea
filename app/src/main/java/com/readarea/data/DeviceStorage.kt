package com.readarea.data

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.readarea.core.format.BookFormat
import java.io.File

object DeviceStorage {
    const val DEVICE = "device"

    private val skipAlways = setOf(BookFormat.HTML)
    private val smallText = setOf(BookFormat.TXT, BookFormat.MD)

    fun hasAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        runCatching { return Environment.isExternalStorageManager() }
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow("android:manage_external_storage", Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    val needsSettingsScreen: Boolean get() = Build.VERSION.SDK_INT >= 30

    fun accessIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    )

    fun roots(context: Context): List<File> {
        val out = LinkedHashSet<File>()
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { out.add(it) }
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val path = dir.absolutePath
            val idx = path.indexOf("/Android/data/")
            if (idx > 0) out.add(File(path.substring(0, idx)))
        }
        return out.filter { it.canRead() }
    }

    data class Found(val file: File, val format: BookFormat)

    fun walk(roots: List<File>, onFound: (Found) -> Unit) {
        val stack = ArrayDeque<Pair<File, Int>>()
        roots.forEach { stack.addLast(it to 0) }
        var visited = 0
        while (stack.isNotEmpty() && visited < 60_000) {
            val (dir, depth) = stack.removeLast()
            visited++
            val children = dir.listFiles() ?: continue
            for (f in children) {
                val name = f.name
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth >= 18) continue
                    if (depth == 0 && name == "Android") {
                        val media = File(f, "media")
                        if (media.canRead()) stack.addLast(media to depth + 2)
                        continue
                    }
                    if (name.equals("cache", true) || name.equals("thumbnails", true) || name.equals("logs", true)) continue
                    stack.addLast(f to depth + 1)
                    continue
                }
                val format = BookFormat.fromFileName(name) ?: continue
                if (format in skipAlways) continue
                val size = f.length()
                if (size < 64 || format in smallText && size < 16_384) continue
                onFound(Found(f, format))
            }
        }
    }

    fun realPath(uri: String): String? {
        val u = uri.toUri()
        if (u.scheme == "file") return u.path
        if (u.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getDocumentId(u) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (volume.equals("primary", true)) runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull() ?: return null else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun fileUri(file: File): String = Uri.fromFile(file).toString()
}
