package dev.avijit.orbittuner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedSong::class, CachedSongFts::class, Favourite::class, PlaylistEntity::class,
        PlaylistItemEntity::class, SongPrefs::class, SyncState::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun library(): LibraryDao
    abstract fun syncState(): SyncStateDao

    companion object {
        fun open(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "orbittuner.db").addMigrations(*MIGRATIONS).build()

        /** v2: playlist.syncError (service rejected the last push). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist ADD COLUMN syncError TEXT")
            }
        }

        /** v3: cached_song.status, so another account's review/hidden songs can be evicted on a switch. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_song ADD COLUMN status TEXT NOT NULL DEFAULT 'published'")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
