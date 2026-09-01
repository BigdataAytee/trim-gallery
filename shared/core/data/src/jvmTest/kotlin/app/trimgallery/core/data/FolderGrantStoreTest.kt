package app.trimgallery.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a folder's mode does in the database, against the real schema.
 *
 * `folder_grant` sat in the schema for nine milestones with one SELECT and no writer, so
 * every claim about it was untested. These are the three that matter: the choice survives a
 * round trip, saving again does not mint a second identity for the same folder, and a mode
 * string nobody recognises does not throw on the way into Settings.
 *
 * On the JVM because the in-memory JDBC driver is here; the schema is the shipped one.
 */
class FolderGrantStoreTest {

    @Test
    fun storesTheModeChosenForAFolder() = runTest {
        val repository = repository()
        repository.saveFolderGrant(grant(CAMERA, FolderMode.FREE))

        assertEquals(FolderMode.FREE, repository.folderGrant(CAMERA)?.mode)
    }

    @Test
    fun readsBackNothingForAFolderNobodyHasConfigured() = runTest {
        assertNull(repository().folderGrant(CAMERA))
    }

    @Test
    fun savingAgainKeepsTheRowItAlreadyHas() = runTest {
        // The property the two-statement write exists for. `INSERT OR REPLACE` would pass
        // the mode assertion and fail this one, having deleted the row and made a new id —
        // and with it any `last_scanned_at` the scanner had written.
        val repository = repository()
        repository.saveFolderGrant(grant(CAMERA, FolderMode.KEEP))
        val first = repository.folderGrant(CAMERA)

        repository.saveFolderGrant(grant(CAMERA, FolderMode.FREE))
        val second = repository.folderGrant(CAMERA)

        assertEquals(first?.id, second?.id)
        assertEquals(FolderMode.FREE, second?.mode)
    }

    @Test
    fun keepsEachFolderSeparate() = runTest {
        val repository = repository()
        repository.saveFolderGrant(grant(CAMERA, FolderMode.FREE))
        repository.saveFolderGrant(grant(SCREENSHOTS, FolderMode.KEEP))

        assertEquals(FolderMode.FREE, repository.folderGrant(CAMERA)?.mode)
        assertEquals(FolderMode.KEEP, repository.folderGrant(SCREENSHOTS)?.mode)
    }

    @Test
    fun clearsTheOffloadTargetWhenTheModeIsNoLongerOffload() = runTest {
        // A destination left behind on a folder set back to Keep is a trap for whoever
        // reads the row next.
        val repository = repository()
        repository.saveFolderGrant(
            grant(CAMERA, FolderMode.OFFLOAD).copy(offloadRef = MediaRef(SD_CARD)),
        )
        repository.saveFolderGrant(grant(CAMERA, FolderMode.KEEP))

        assertNull(repository.folderGrant(CAMERA)?.offloadRef)
    }

    @Test
    fun readsAnUnrecognisedModeAsKeep() = runTest {
        val driver = driver()
        val repository = repository(driver)
        repository.saveFolderGrant(grant(CAMERA, FolderMode.FREE))
        // A row a newer build wrote, or a hand-edited database. Whatever it means, the
        // reading that cannot remove a file is the one to fall back to.
        driver.execute(null, "UPDATE folder_grant SET mode = 'ARCHIVE_TO_MARS'", 0)

        assertEquals(FolderMode.KEEP, repository.folderGrant(CAMERA)?.mode)
    }

    private fun grant(ref: String, mode: FolderMode) = FolderGrant(
        // Empty: the repository mints the id, which is what the settings screen does.
        id = "",
        platformRef = MediaRef(ref),
        mode = mode,
        displayName = ref.substringAfterLast('/'),
    )

    private fun driver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TrimDatabase.Schema.create(it) }

    private fun repository(driver: JdbcSqliteDriver = driver()): TrimRepository {
        var minted = 0
        return TrimRepository(
            db = TrimDatabase(driver),
            io = Dispatchers.Unconfined,
            newId = { "id-${minted++}" },
            nowMs = { 0L },
            readSettings = { Settings() },
            readTier = { Tier.FREE },
            monthStartMs = { 0L },
        )
    }

    private companion object {
        const val CAMERA = "content://tree/primary:DCIM/Camera"
        const val SCREENSHOTS = "content://tree/primary:Pictures/Screenshots"
        const val SD_CARD = "content://tree/1A2B-3C4D:Backup"
    }
}
