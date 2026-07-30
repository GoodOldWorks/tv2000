package com.tv2000.app.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StorageVolumeEntity::class,
        ChannelEntity::class,
        EpisodeEntity::class,
        ChannelPlaybackStateEntity::class,
        EpisodePlaybackEntity::class,
        AppStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class Tv2000Database : RoomDatabase() {
    abstract fun mediaCatalogDao(): MediaCatalogDao

    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        @Volatile
        private var instance: Tv2000Database? = null

        fun get(context: Context): Tv2000Database =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Tv2000Database::class.java,
                    DATABASE_NAME,
                ).build().also { database ->
                    instance = database
                }
            }

        private const val DATABASE_NAME = "tv2000.db"
    }
}
