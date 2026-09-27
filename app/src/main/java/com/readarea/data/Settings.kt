package com.readarea.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReaderSettings(
    val fontFamily: String = "serif",
    val fontSize: Float = 19f,
    val fontWeight: Int = 400,
    val lineSpacing: Float = 1.5f,
    val paragraphSpacing: Float = 0.3f,
    val indent: Float = 1.5f,
    val marginH: Int = 24,
    val marginV: Int = 20,
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
    val tapZones: String = "sides",
    val volumeKeys: Boolean = true,
    val screenTimeoutMin: Int = 5,
    val fullscreen: Boolean = true,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val showClock: Boolean = true,
    val showProgressLine: Boolean = true,
    val brightnessSystem: Boolean = true,
    val brightness: Float = 0.5f,
    val warmth: Float = 0f,
    val spread: String = "auto",
    val pageDirection: String = "auto",
    val writingMode: String = "auto",
    val brightnessGesture: Boolean = true,
    val orientation: String = "auto",
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val autoTurnSeconds: Int = 25,
    val pdfCrop: Boolean = false,
    val pdfInvert: Boolean = true,
)

@Serializable
data class AppSettings(
    val themeMode: String = "system",
    val dynamicColor: Boolean = false,
    val accent: Int = 0,
    val libraryGrid: Boolean = true,
    val sort: String = "recent",
    val gridColumns: Int = 3,
    val dailyGoalMinutes: Int = 30,
    val folders: List<String> = emptyList(),
    val onboardingDone: Boolean = false,
    val customFonts: List<String> = emptyList(),
    val showFormatBadges: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val readerKey = stringPreferencesKey("reader")
    private val appKey = stringPreferencesKey("app")

    val reader: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        p[readerKey]?.let { runCatching { json.decodeFromString<ReaderSettings>(it) }.getOrNull() } ?: ReaderSettings()
    }.distinctUntilChanged()

    val app: Flow<AppSettings> = context.dataStore.data.map { p ->
        p[appKey]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    }.distinctUntilChanged()

    suspend fun readerNow(): ReaderSettings = reader.first()
    suspend fun appNow(): AppSettings = app.first()

    suspend fun updateReader(block: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { p ->
            val cur = p[readerKey]?.let { runCatching { json.decodeFromString<ReaderSettings>(it) }.getOrNull() } ?: ReaderSettings()
            p[readerKey] = json.encodeToString(ReaderSettings.serializer(), block(cur))
        }
    }

    suspend fun updateApp(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = p[appKey]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
            p[appKey] = json.encodeToString(AppSettings.serializer(), block(cur))
        }
    }
}
