package app.trimgallery.core.domain.places

/**
 * Reading a basemap the user supplied, as an MBTiles pack.
 *
 * The decision this implements (recorded in PROJECT.md): BUILD.md wants a map with offline
 * tiles and PRD.md R8 forbids the `INTERNET` permission for the life of the product, so
 * tiles can never be fetched. The user brings their own pack — an `.mbtiles` file, which is
 * an ordinary SQLite database — and picks it with the same document picker the app already
 * uses for folders. Nothing new is added to the approved stack: MBTiles is SQLite, and this
 * app already ships SQLDelight.
 *
 * The cost, stated plainly because the UI has to state it too: **the map is empty until the
 * user adds a pack.** That is a worse first run than a map that just works, and it is the
 * price of an app that cannot reach the network. [MapTiles.available] exists so the screen
 * can say so rather than showing a grey grid that reads as broken.
 */
object MbTiles {

    /**
     * The row-numbering trap, and the reason this is shared code with a test rather than
     * three lines in a platform file.
     *
     * MBTiles stores rows in **TMS** order, counting from the bottom of the world; slippy
     * map tiles — what `Geo.tileOf` produces, and what every map library expects — count
     * from the top. Get it wrong and the map is not blank or broken: it renders, upside
     * down by hemisphere, which is exactly the sort of bug that survives a demo.
     */
    fun tmsRow(tile: Geo.Tile): Int = (1 shl tile.zoom) - 1 - tile.y

    /** And back, for reading a pack's own bounds. */
    fun xyzRow(zoom: Int, tmsRow: Int): Int = (1 shl zoom) - 1 - tmsRow

    /** What the tiles in a pack actually are. */
    enum class Format(val key: String) {
        PNG("png"),
        JPG("jpg"),
        WEBP("webp"),

        /**
         * Vector tiles. A pack of these is a valid MBTiles file this app cannot draw:
         * rendering them means a style, a font stack and a vector renderer, none of which
         * is in the approved stack.
         *
         * Recognised rather than ignored so the user gets *"this pack holds vector tiles,
         * which Trim can't draw — you need a raster pack"* instead of a map that silently
         * stays empty after they went and found a file.
         */
        PBF("pbf"),
        ;

        val isRenderable: Boolean get() = this != PBF

        companion object {
            fun of(value: String?): Format? {
                val normalised = value?.trim()?.lowercase() ?: return null
                return entries.firstOrNull { it.key == normalised }
                    ?: when (normalised) {
                        "jpeg" -> JPG
                        "mvt", "pbf.gz", "application/x-protobuf" -> PBF
                        else -> null
                    }
            }
        }
    }

    /** A pack's `metadata` table, read into the few things the map needs. */
    data class Metadata(
        val name: String?,
        val format: Format?,
        val minZoom: Int?,
        val maxZoom: Int?,
        val bounds: Geo.Bounds?,
    ) {
        /**
         * The zoom levels the map may ask for.
         *
         * A pack that does not say gets a conservative default rather than the whole range:
         * asking for zoom 19 of a pack that stops at 12 returns nothing for every tile on
         * screen, which looks identical to a broken map.
         */
        val zoomRange: IntRange
            get() {
                val min = minZoom ?: DEFAULT_MIN_ZOOM
                val max = maxZoom ?: DEFAULT_MAX_ZOOM
                return if (max < min) min..min else min..max
            }
    }

    const val DEFAULT_MIN_ZOOM = 0
    const val DEFAULT_MAX_ZOOM = 14

    /** Why a pack the user picked cannot be used. Each is shown to them, in these words. */
    enum class Rejection(val explanation: String) {
        NOT_A_PACK("That file isn't a map pack Trim can read."),
        VECTOR_TILES("That pack holds vector tiles, which Trim can't draw. You need a raster pack."),
        NO_TILES("That pack is empty."),
    }

    sealed interface Opened {
        data class Ready(val metadata: Metadata) : Opened
        data class Refused(val rejection: Rejection) : Opened
    }

    /**
     * Reads a pack's metadata table and decides whether it can be used.
     *
     * @param tileCount how many rows the `tiles` table holds, so an empty pack is refused
     *   here rather than discovered one blank tile at a time.
     */
    fun open(metadata: Map<String, String>, tileCount: Long): Opened {
        if (metadata.isEmpty() && tileCount <= 0) return Opened.Refused(Rejection.NOT_A_PACK)
        if (tileCount <= 0) return Opened.Refused(Rejection.NO_TILES)

        val parsed = parse(metadata)
        val format = parsed.format
        if (format != null && !format.isRenderable) return Opened.Refused(Rejection.VECTOR_TILES)
        return Opened.Ready(parsed)
    }

    /** Parses the key/value rows. Every field is optional; the spec makes none of them required. */
    fun parse(metadata: Map<String, String>): Metadata {
        val lower = metadata.mapKeys { it.key.trim().lowercase() }
        return Metadata(
            name = lower["name"]?.takeIf { it.isNotBlank() },
            format = Format.of(lower["format"]),
            minZoom = lower["minzoom"]?.trim()?.toIntOrNull(),
            maxZoom = lower["maxzoom"]?.trim()?.toIntOrNull(),
            bounds = parseBounds(lower["bounds"]),
        )
    }

    /**
     * `bounds` is "west,south,east,north" in degrees, per the MBTiles spec.
     *
     * Anything that does not parse becomes null rather than an error: a malformed bounds
     * only costs the map its "this pack covers Britain" line, and refusing a pack whose
     * tiles are perfectly good over a cosmetic field would be the wrong trade.
     */
    fun parseBounds(value: String?): Geo.Bounds? {
        val parts = value?.split(',')?.map { it.trim().toDoubleOrNull() } ?: return null
        if (parts.size != 4 || parts.any { it == null }) return null
        val (west, south, east, north) = parts.map { it!! }
        if (south > north || west > east) return null
        return Geo.Bounds(south = south, west = west, north = north, east = east)
    }

    /** The rows a platform reader has to be able to fetch. Deliberately two methods. */
    interface Rows {
        /** `SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?` */
        suspend fun tileData(zoom: Int, column: Int, tmsRow: Int): ByteArray?

        /** `SELECT name, value FROM metadata` */
        suspend fun metadata(): Map<String, String>

        /** `SELECT COUNT(*) FROM tiles` */
        suspend fun tileCount(): Long
    }

    /**
     * A [MapTiles] over a pack.
     *
     * The flip from slippy to TMS happens here and nowhere else, so a platform reader is a
     * plain SQL query with no coordinate maths in it to get wrong.
     */
    class Source(private val rows: Rows, private val metadata: Metadata) : MapTiles {
        override val available: Boolean = true
        override val zoomRange: IntRange = metadata.zoomRange

        override suspend fun tile(tile: Geo.Tile): ByteArray? {
            if (tile.zoom !in zoomRange) return null
            if (metadata.bounds?.let { !overlaps(it, Geo.boundsOf(tile)) } == true) return null
            return rows.tileData(tile.zoom, tile.x, tmsRow(tile))
        }

        private fun overlaps(a: Geo.Bounds, b: Geo.Bounds): Boolean =
            a.west <= b.east && a.east >= b.west && a.south <= b.north && a.north >= b.south
    }

    private operator fun <T> List<T>.component4(): T = this[3]
}
