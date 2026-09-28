package com.readarea.desktop.data

import com.readarea.desktop.platform.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class ReaderSettings(
    val fontFamily: String = "serif",
    val fontSize: Float = 19f,
    val fontWeight: Int = 400,
    val lineSpacing: Float = 1.5f,
    val paragraphSpacing: Float = 0.3f,
    val indent: Float = 1.5f,
    val marginH: Int = 48,
    val marginV: Int = 36,
    val justify: Boolean = true,
    val hyphenation: Boolean = true,
    val letterSpacing: Float = 0f,
    val publisherStyles: Boolean = true,
    val theme: String = "paper",
    val customBg: Int = 0xFFF4EEDC.toInt(),
    val customFg: Int = 0xFF2D2A26.toInt(),
    val texture: Boolean = true,
    val autoNight: Boolean = true,
    val nightTheme: String = "night",
    val pageAnim: String = "curl",
    val animSpeed: Float = 1f,
    val clickToTurn: Boolean = true,
    val wheelTurnsPages: Boolean = true,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val showProgressLine: Boolean = true,
    val dim: Float = 0f,
    val warmth: Float = 0f,
    val spread: String = "auto",
    val pageDirection: String = "auto",
    val writingMode: String = "auto",
    val columnWidth: Int = 680,
    val ttsRate: Float = 1f,
    val ttsVoice: String = "",
    val autoTurnSeconds: Int = 25,
    val pdfCrop: Boolean = false,
    val pdfInvert: Boolean = true,
) {
    /** Settings that change where lines break, so a change means laying the book out again. */
    fun layoutKey(): List<Any> = listOf(
        fontFamily, fontSize, fontWeight, lineSpacing, paragraphSpacing, indent, marginH, marginV, justify, hyphenation,
        letterSpacing, publisherStyles, showHeader, showFooter, pdfCrop, spread, pageDirection, writingMode, columnWidth,
    )

    fun sanitized(): ReaderSettings = copy(
        fontSize = fontSize.coerceIn(10f, 48f),
        fontWeight = fontWeight.coerceIn(100, 900),
        lineSpacing = lineSpacing.coerceIn(1f, 2.5f),
        paragraphSpacing = paragraphSpacing.coerceIn(0f, 2f),
        indent = indent.coerceIn(0f, 4f),
        marginH = marginH.coerceIn(0, 240),
        marginV = marginV.coerceIn(0, 200),
        letterSpacing = letterSpacing.coerceIn(-0.05f, 0.2f),
        animSpeed = animSpeed.coerceIn(0.25f, 3f),
        dim = dim.coerceIn(0f, 0.8f),
        warmth = warmth.coerceIn(0f, 1f),
        columnWidth = columnWidth.coerceIn(360, 1600),
        ttsRate = ttsRate.coerceIn(0.5f, 3f),
        autoTurnSeconds = autoTurnSeconds.coerceIn(3, 600),
    )
}

@Serializable
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false)

@Serializable
data class AppSettings(
    val themeMode: String = "system",
    val accent: Int = 0,
    val language: String = "system",
    val libraryGrid: Boolean = true,
    val sort: String = "recent",
    val coverSize: Int = 1,
    val dailyGoalMinutes: Int = 30,
    val folders: List<String> = emptyList(),
    val watchFolders: Boolean = true,
    val onboardingDone: Boolean = false,
    val customFonts: List<String> = emptyList(),
    val showFormatBadges: Boolean = true,
    val ignored: List<String> = emptyList(),
    val reopenLastBook: Boolean = true,
    val resumeBookId: Long = 0,
    val openBooks: List<Long> = emptyList(),
    val mainWindow: WindowBounds? = null,
    val readerWindow: WindowBounds? = null,
    val sidebarWidth: Int = 300,
) {
    fun sanitized(): AppSettings = copy(
        coverSize = coverSize.coerceIn(0, 2),
        dailyGoalMinutes = dailyGoalMinutes.coerceIn(5, 600),
        folders = folders.distinct().take(200),
        ignored = ignored.distinct().takeLast(5000),
        customFonts = customFonts.distinct().take(200),
        openBooks = openBooks.distinct().take(20),
        sidebarWidth = sidebarWidth.coerceIn(220, 600),
    )
}

@Serializable
private data class SettingsFile(val version: Int = 1, val reader: ReaderSettings = ReaderSettings(), val app: AppSettings = AppSettings())

/**
 * Reader and app settings, kept in memory as flows and written to one JSON file in the background.
 * Writes go to a temporary file that then replaces the real one, so a crash can't leave half a file.
 */
class SettingsStore(private val file: File) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; coerceInputValues = true }
    private val initial: SettingsFile = load()
    private val _reader = MutableStateFlow(initial.reader.sanitized())
    private val _app = MutableStateFlow(initial.app.sanitized())
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()
    val app: StateFlow<AppSettings> = _app.asStateFlow()

    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "settings-writer").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    private val lock = Any()

    private fun load(): SettingsFile {
        if (!file.isFile) return SettingsFile()
        return runCatching { json.decodeFromString(SettingsFile.serializer(), file.readText()) }.getOrElse {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true) }
            SettingsFile()
        }
    }

    fun updateReader(block: (ReaderSettings) -> ReaderSettings) {
        synchronized(lock) { _reader.value = block(_reader.value).sanitized() }
        scheduleWrite()
    }

    fun updateApp(block: (AppSettings) -> AppSettings) {
        synchronized(lock) { _app.value = block(_app.value).sanitized() }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (pending.compareAndSet(false, true)) writer.schedule({ writeNow() }, 250, TimeUnit.MILLISECONDS)
    }

    fun flush() {
        if (pending.get()) writeNow()
    }

    @Synchronized
    private fun writeNow() {
        pending.set(false)
        val text = json.encodeToString(SettingsFile.serializer(), SettingsFile(reader = _reader.value, app = _app.value))
        runCatching {
            file.parentFile?.let { AppDirs.ensurePrivate(it) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            AppDirs.makePrivate(tmp)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override fun close() {
        flush()
        writer.shutdown()
        writer.awaitTermination(2, TimeUnit.SECONDS)
    }
}
