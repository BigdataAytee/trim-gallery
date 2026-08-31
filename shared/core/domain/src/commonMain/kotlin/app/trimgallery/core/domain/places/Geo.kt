package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The arithmetic the map view and Memories both need (BUILD.md § 9 v1.1: *"Map view with
 * offline tiles"*).
 *
 * It is here, shared and tested, rather than in a map library's helper class for the usual
 * reason and one extra: this app has no `INTERNET` permission (PRD.md R8) and therefore no
 * geocoder, so every question it can answer about a place — how far apart two photographs
 * were taken, which tile they fall in, what a group of them spans — has to be answered from
 * the coordinates themselves.
 */
object Geo {

    /**
     * Mean Earth radius, in metres — the right sphere for measuring distance on the ground.
     */
    const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * The WGS84 equatorial radius, which is the sphere Web Mercator is *defined* on.
     *
     * A different constant from [EARTH_RADIUS_M] on purpose, and not interchangeable with
     * it. Ground distances want the mean radius, because a great circle between two
     * photographs runs across latitudes; tile scale wants the equatorial one, because that
     * is the number the projection and every tile pack in the world were built with.
     * Substituting the mean radius here makes a map's scale bar wrong by a tenth of a
     * percent and, more to the point, disagree with the tiles it is drawn over.
     */
    const val MERCATOR_RADIUS_M = 6_378_137.0

    /**
     * The Web Mercator limit, ±85.0511°.
     *
     * Beyond it the projection runs to infinity. Photographs *are* taken past 85° north —
     * research stations, flights over the pole — and a map that divided by zero on one of
     * them would be a crash reachable from a user's own library, so latitudes are clamped
     * rather than assumed to be in range.
     */
    const val MERCATOR_LIMIT = 85.05112878

    /**
     * Great-circle distance in metres, by the haversine formula.
     *
     * Haversine rather than the flat approximation because the flat one is wrong by more
     * than a kilometre at the distances trips are made of, and rather than Vincenty because
     * the ellipsoidal correction is a fraction of a percent — far below the accuracy of the
     * GPS fix in a photograph, which is where the real error lives.
     */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.lat.toRadians()
        val lat2 = b.lat.toRadians()
        val dLat = (b.lat - a.lat).toRadians()
        val dLon = (b.lon - a.lon).toRadians()

        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        // asin(sqrt(h)) rather than atan2: h is clamped to 1 anyway, and this form does not
        // lose precision for the very small distances two photographs usually sit apart.
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, h)))
    }

    /** A rectangle on the map, in degrees. */
    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
        val center: GeoPoint get() = GeoPoint(lat = (south + north) / 2, lon = (west + east) / 2)

        /** The diagonal in metres, for "this trip covered 40 km". */
        val spanMeters: Double
            get() = Geo.distanceMeters(GeoPoint(south, west), GeoPoint(north, east))

        fun contains(point: GeoPoint): Boolean = point.lat in south..north && point.lon in west..east

        /** Grown by a margin so pins are not against the edge of the view. */
        fun padded(fraction: Double = 0.1): Bounds {
            val latPad = (north - south) * fraction
            val lonPad = (east - west) * fraction
            return Bounds(
                south = (south - latPad).coerceAtLeast(-90.0),
                west = (west - lonPad).coerceAtLeast(-180.0),
                north = (north + latPad).coerceAtMost(90.0),
                east = (east + lonPad).coerceAtMost(180.0),
            )
        }
    }

    /**
     * The rectangle containing every point.
     *
     * Null for an empty list rather than a zero-size box at the origin: the map has to be
     * able to tell "no photographs have a location" from "every photograph was taken in the
     * Gulf of Guinea", and (0, 0) is where a stripped coordinate lands.
     */
    fun bounds(points: List<GeoPoint>): Bounds? {
        if (points.isEmpty()) return null
        var south = points.first().lat
        var north = south
        var west = points.first().lon
        var east = west
        for (point in points) {
            south = min(south, point.lat)
            north = max(north, point.lat)
            west = min(west, point.lon)
            east = max(east, point.lon)
        }
        return Bounds(south = south, west = west, north = north, east = east)
    }

    /**
     * The mean position of a set of points.
     *
     * Averaged as unit vectors rather than as numbers, because averaging longitudes puts the
     * centre of two photographs either side of the date line in the middle of Africa. The
     * cost is a little trigonometry; the alternative is a pin that is wrong by half a world
     * for anyone who has been to Fiji.
     */
    fun centroid(points: List<GeoPoint>): GeoPoint? {
        if (points.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (point in points) {
            val lat = point.lat.toRadians()
            val lon = point.lon.toRadians()
            x += cos(lat) * cos(lon)
            y += cos(lat) * sin(lon)
            z += sin(lat)
        }
        val count = points.size
        x /= count
        y /= count
        z /= count

        // Every point cancelling out — antipodes, or a ring around the globe — has no
        // meaningful centre. The first point is a defensible stand-in and, unlike (0, 0),
        // is somewhere the user has actually been.
        val hypotenuse = sqrt(x * x + y * y)
        if (hypotenuse < 1e-12 && abs(z) < 1e-12) return points.first()

        return GeoPoint(
            lat = atan2(z, hypotenuse).toDegrees(),
            lon = atan2(y, x).toDegrees(),
        )
    }

    /** A slippy-map tile: the addressing scheme every offline tile pack uses. */
    data class Tile(val zoom: Int, val x: Int, val y: Int)

    /**
     * Which tile a point falls in, at a zoom level.
     *
     * The standard Web Mercator scheme, so a tile pack the user supplies — an MBTiles file,
     * say — can be addressed without a library and without a network.
     */
    fun tileOf(point: GeoPoint, zoom: Int): Tile {
        val z = zoom.coerceIn(0, MAX_ZOOM)
        val scale = 1 shl z
        val lat = point.lat.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT).toRadians()
        val x = ((point.lon + 180.0) / 360.0 * scale).toInt().coerceIn(0, scale - 1)
        val y = ((1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0 * scale).toInt().coerceIn(0, scale - 1)
        return Tile(z, x, y)
    }

    /** The rectangle a tile covers, for drawing it in the right place. */
    fun boundsOf(tile: Tile): Bounds {
        val scale = 1 shl tile.zoom.coerceIn(0, MAX_ZOOM)
        fun lonAt(x: Int) = x.toDouble() / scale * 360.0 - 180.0
        fun latAt(y: Int) = atan(sinh(PI * (1 - 2.0 * y / scale))).toDegrees()
        return Bounds(
            south = latAt(tile.y + 1),
            west = lonAt(tile.x),
            north = latAt(tile.y),
            east = lonAt(tile.x + 1),
        )
    }

    /**
     * How many metres one pixel covers at a latitude and zoom.
     *
     * What decides when two pins merge: clustering by distance alone would leave a map of
     * Iceland looking half as busy as a map of Kenya, because a degree of longitude is a
     * different distance at each.
     */
    fun metersPerPixel(latitude: Double, zoom: Int): Double {
        val lat = latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT).toRadians()
        val scale = 1 shl zoom.coerceIn(0, MAX_ZOOM)
        return 2 * PI * MERCATOR_RADIUS_M * cos(lat) / (TILE_PIXELS * scale)
    }

    /** The deepest zoom a tile pack is expected to hold. */
    const val MAX_ZOOM = 20

    /** The universal slippy-map tile size. */
    const val TILE_PIXELS = 256

    private fun Double.toRadians(): Double = this * PI / 180.0
    private fun Double.toDegrees(): Double = this * 180.0 / PI
}
