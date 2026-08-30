package app.trimgallery.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import app.trimgallery.core.data.db.TrimDatabase
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode

/**
 * The iOS connection to the shared schema (milestone 15).
 *
 * The schema is in `commonMain` and byte-for-byte the same on both platforms
 * (ARCHITECTURE.md § 4) — only the driver differs, which is exactly why SQLDelight was
 * chosen over Room (PROJECT.md: Room's KMP support has no Kotlin/Native iOS target).
 *
 * Two things the Android side gets from its `Context` and this has to say explicitly:
 *
 * - **Foreign keys on.** SQLite has them off by default, and SCHEMA.md's `ON DELETE CASCADE`
 *   is inert without the pragma. A `media_item` deleted with orphaned `undo_entry` rows
 *   behind it leaves originals in the bin that nothing can restore — the one class of bug
 *   this app must never have.
 * - **Where the file lives.** In the app's Application Support directory, not Documents:
 *   Documents is exposed to the Files app when the app declares it, and a user browsing
 *   their own files should not find the index of their photo library sitting there to be
 *   dragged somewhere.
 */
object IosDatabase {

    const val FILE_NAME = "trim.db"

    fun create(): TrimDatabase = TrimDatabase(driver())

    fun driver(): SqlDriver = NativeSqliteDriver(
        configuration = DatabaseConfiguration(
            name = FILE_NAME,
            version = TrimDatabase.Schema.version.toInt(),
            create = { connection -> wrapConnection(connection) { TrimDatabase.Schema.create(it) } },
            upgrade = { connection, from, to ->
                wrapConnection(connection) { TrimDatabase.Schema.migrate(it, from.toLong(), to.toLong()) }
            },
            extendedConfig = DatabaseConfiguration.Extended(
                foreignKeyConstraints = true,
                // WAL, for the same reason the night pass checkpoints every file: a reader
                // on the main thread drawing the gallery must not be blocked by the writer
                // recording a replace (ARCHITECTURE.md § 8).
                journalMode = JournalMode.WAL,
            ),
        ),
    )
}
