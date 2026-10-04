package com.lunara.music.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        SearchHistoryEntity::class,
        UserPrefEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LunaraDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun userPrefDao(): UserPrefDao

    companion object {
        @Volatile
        private var INSTANCE: LunaraDatabase? = null

        fun getDatabase(context: Context): LunaraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LunaraDatabase::class.java,
                    "lunara_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
