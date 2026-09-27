package com.readarea

import android.app.Application
import android.content.Context
import com.readarea.data.LibraryRepository
import com.readarea.data.SettingsRepository
import com.readarea.data.db.AppDatabase

class ReadAreaApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val library: LibraryRepository by lazy { LibraryRepository(this, database, settings) }
}

val Context.app: ReadAreaApp get() = applicationContext as ReadAreaApp
