package app.trimgallery.engine.android

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.trimgallery.core.domain.places.MapTiles
import app.trimgallery.core.domain.places.MbTiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user's own basemap pack, read from an `.mbtiles` file (BUILD.md § 9 v1.1).
 *
 * An MBTiles pack is an ordinary SQLite database, so this needs no map library and nothing
 * added to STACK.md — the platform's own `SQLiteDatabase` opens it read-only. Every piece
 * of coordinate arithmetic lives in shared code (`MbTiles`), including the slippy-to-TMS row
 * flip that is the one thing about this format everybody gets wrong; what is left here is
 * three SQL statements.
 *
 * **Opened read-only, always.** The pack is a file the user chose from their own storage,
 * and this app has exactly one component allowed to write to anything of theirs
 * (ARCHITECTURE.md § 14). A basemap reader is not it.
 */
class MbTilesFile private constructor(private val db: SQLiteDatabase) : MbTiles.Rows {

    override suspend fun tileData(zoom: Int, column: Int, tmsRow: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            db.rawQuery(TILE_SQL, arrayOf(zoom.toString(), column.toString(), tmsRow.toString())).use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else null
            }
        }

    override suspend fun metadata(): Map<String, String> = withContext(Dispatchers.IO) {
        buildMap {
            runCatching {
                db.rawQuery(METADATA_SQL, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        put(name, cursor.getString(1).orEmpty())
                    }
                }
            }
        }
    }

    override suspend fun tileCount(): Long = withContext(Dispatchers.IO) {
        runCatching {
            db.rawQuery(COUNT_SQL, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }.getOrDefault(0L)
    }

    fun close() = db.close()

    companion object {
        private const val TILE_SQL =
            "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1"
        private const val METADATA_SQL = "SELECT name, value FROM metadata"
        private const val COUNT_SQL = "SELECT COUNT(*) FROM tiles"

        /**
         * Opens a pack and decides whether it can be used.
         *
         * @param file a path, not a `content://` URI. SQLite needs a real file, and the
         *   document picker hands back a URI — so the caller copies the pack into
         *   app-private storage once, after showing the user how large it is. That copy is
         *   the honest cost of an app with no network: a pack is tens to hundreds of
         *   megabytes, and it is the user's choice to spend that.
         *
         * A file that is not a database throws rather than returning garbage, and is
         * reported as "not a pack Trim can read" — the same answer as a database with no
         * tiles table, because to the person who picked it they are the same mistake.
         */
        suspend fun open(file: File): Result = withContext(Dispatchers.IO) {
            val db = try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: SQLiteException) {
                return@withContext Result.Refused(MbTiles.Rejection.NOT_A_PACK)
            }

            val rows = MbTilesFile(db)
            val opened = try {
                MbTiles.open(rows.metadata(), rows.tileCount())
            } catch (e: SQLiteException) {
                db.close()
                return@withContext Result.Refused(MbTiles.Rejection.NOT_A_PACK)
            }

            when (opened) {
                is MbTiles.Opened.Ready -> Result.Ready(MbTiles.Source(rows, opened.metadata), rows)
                is MbTiles.Opened.Refused -> {
                    db.close()
                    Result.Refused(opened.rejection)
                }
            }
        }
    }

    sealed interface Result {
        data class Ready(val tiles: MapTiles, val file: MbTilesFile) : Result
        data class Refused(val rejection: MbTiles.Rejection) : Result
    }
}
