package com.readarea.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, HighlightEntity::class, CollectionEntity::class, BookCollectionEntity::class, ReadingSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun collections(): CollectionDao
    abstract fun notes(): NoteDao
    abstract fun stats(): StatsDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "readarea.db")
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
