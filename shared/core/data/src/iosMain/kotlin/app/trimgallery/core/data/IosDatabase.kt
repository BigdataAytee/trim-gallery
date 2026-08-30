package app.trimgallery.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import app.trimgallery.core.data.db.TrimDatabase
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

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
            // WAL, for the same reason the night pass checkpoints every file: a reader on the
            // main thread drawing the gallery must not be blocked by the writer recording a
            // replace (ARCHITECTURE.md § 8).
            //
            // On `DatabaseConfiguration`, not on `Extended`. Written the other way round from
            // the documentation and never compiled until the iOS job ran, because Kotlin/Native
            // needs a Mac and this repository was written without one.
            journalMode = JournalMode.WAL,
            extendedConfig = DatabaseConfiguration.Extended(
                foreignKeyConstraints = true,
                basePath = applicationSupportPath(),
            ),
        ),
    )

    /**
     * Application Support, created if it is not there yet.
     *
     * iOS does not create this directory for you, and sqliter's default puts the file under
     * Documents — which is the one place it must not be: an app that declares
     * `UIFileSharingEnabled` exposes Documents to the Files app, and the index of someone's
     * photo library is not a document they meant to share. Application Support is also
     * excluded from iCloud backup by default, which is the right answer for a cache that can
     * be rebuilt from the library itself.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun applicationSupportPath(): String {
        val base = NSSearchPathForDirectoriesInDomains(
            directory = NSApplicationSupportDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).first() as String
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = base,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return base
    }
}
