package dev.avijit.orbittuner.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.avijit.orbittuner.data.db.AppDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDb::class.java)

    @Test fun v1ToV2AddsSyncErrorAndKeepsPlaylists() {
        helper.createDatabase("mig.db", 1).use { db ->
            db.execSQL("INSERT INTO playlist (id, name, deleted, updatedAt, dirty) VALUES ('p1', 'Set', 0, 5, 1)")
        }
        helper.runMigrationsAndValidate("mig.db", 2, true, *AppDb.MIGRATIONS).use { db ->
            db.query("SELECT name, dirty, syncError FROM playlist WHERE id = 'p1'").use { c ->
                c.moveToFirst()
                assertEquals("Set", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertNull(c.getString(2))
            }
        }
    }

    @Test fun v2ToV3AddsSongStatusDefaultingToPublished() {
        helper.createDatabase("mig3.db", 2).use { db ->
            db.execSQL(
                "INSERT INTO cached_song (id, title, artist, chordpro, language, categoriesJson, originalKey, version, updatedAt, lastOpenedAt, pinnedOffline) " +
                    "VALUES ('s1', 'T', NULL, '{title: T}', 'en', '[]', NULL, 1, 5, 0, 0)",
            )
        }
        helper.runMigrationsAndValidate("mig3.db", 3, true, *AppDb.MIGRATIONS).use { db ->
            db.query("SELECT title, status FROM cached_song WHERE id = 's1'").use { c ->
                c.moveToFirst()
                assertEquals("T", c.getString(0))
                assertEquals("published", c.getString(1))
            }
        }
    }
}
