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

    fun create(context: Context): TrimDatabase = TrimDatabase(
        AndroidSqliteDriver(
            schema = TrimDatabase.Schema,
            context = context,
            name = FILE_NAME,
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
