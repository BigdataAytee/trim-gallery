package app.trimgallery.core.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.trimgallery.core.data.db.TrimDatabase

/**
 * The Android connection to the shared schema.
 *
 * The schema itself is in `commonMain` and identical on both platforms (ARCHITECTURE.md
 * § 4); only the driver differs, which is the whole reason SQLDelight was chosen over Room
 * (PROJECT.md).
 */
object AndroidDatabase {

    const val FILE_NAME = "trim.db"

    /**
     * @param name the database file, or null for one held in memory.
     *
     * Null exists for the emulator tests, and it is worth the parameter: they have to run
     * against **this** driver and **this** callback, because the bug they were written for
     * was a foreign key that is on here and off in the JVM driver the unit tests use. A
     * test that opened its own connection would be testing a different database than the
     * phone has, which is exactly how the crash loop was certified green.
     */
    fun create(context: Context, name: String? = FILE_NAME): TrimDatabase = TrimDatabase(
        AndroidSqliteDriver(
            schema = TrimDatabase.Schema,
            context = context,
            name = name,
            callback = object : AndroidSqliteDriver.Callback(TrimDatabase.Schema) {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // SCHEMA.md's `ON DELETE CASCADE` on job and undo_entry is inert
                    // without this: SQLite has foreign keys off by default, and a
                    // media_item deleted with orphaned undo rows behind it would leave
                    // originals in the bin that nothing can restore.
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }
            },
        ),
    )
}
