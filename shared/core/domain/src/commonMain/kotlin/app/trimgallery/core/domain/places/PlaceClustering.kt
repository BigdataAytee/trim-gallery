package app.trimgallery.core.domain.places

import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaItem

/**
 * Grouping a library's photographs into places (BUILD.md § 9 v1.1, *"Map view"*).
 *
 * A map of a hundred thousand pins is not a map, and neither is one whose pins jump about
 * as it is zoomed. Both are decisions rather than rendering, so both are here.
 *
 * **There are no place names.** PRD.md R8 forbids the `INTERNET` permission for the life of
 * the product, so there is no reverse geocoder and no gazetteer: a place is a shape and a
 * span of time, described by the photographs in it, and the app must never guess at
 * "Barcelona". Where a name is wanted the honest source is the user typing one.
 */
object PlaceClustering {

    /**
     * How close, in screen pixels, two pins have to be before they merge.
     *
     * In pixels rather than metres, because clustering by distance alone leaves a map of
     * Iceland looking half as busy as a map of Kenya — a degree of longitude is a different
     * distance at each. Sixty is about two thumb-widths at typical density, which is the
     * point at which two pins stop being separately tappable.
     */
    const val MERGE_PIXELS = 60.0

    /**
     * The radius, in metres, within which photographs are treated as one place regardless of
     * zoom, for grouping that is not about a map at all — Memories, and "places you have
     * been".
     *
     * 250 m is roughly a city block or a large park: close enough that a person would say
     * "the same place", far enough that a phone's GPS drift indoors does not split one
     * afternoon into four places.
     */
    const val SAME_PLACE_METERS = 250.0

    /** One pin. */
    data class Cluster(
        val center: GeoPoint,
        val items: List<MediaItem>,
        val bounds: Geo.Bounds,
    ) {
        val count: Int get() = items.size

        /**
         * The photograph the pin shows.
         *
         * A favourite first, then the newest — a favourite is the user's own statement about
         * which picture from that place is the one, and nothing the app could compute beats
         * it.
         */
        val cover: MediaItem?
            get() = items.filter { it.favourite }.maxByOrNull { it.takenAt?.toEpochMilliseconds() ?: 0L }
                ?: items.maxByOrNull { it.takenAt?.toEpochMilliseconds() ?: 0L }
    }

    /**
     * Clusters for the map at a zoom level.
     *
     * Single-pass and greedy, seeded by the most recent photograph, which makes the result
     * stable: the same library at the same zoom always produces the same pins, so panning
     * away and back does not rearrange the map under the user's finger. A k-means would give
     * slightly tidier groups and a map that moves when nothing has changed.
     */
    fun clusterForZoom(items: List<MediaItem>, zoom: Int, mergePixels: Double = MERGE_PIXELS): List<Cluster> {
        val located = items.filter { it.location != null && !it.hidden }
        if (located.isEmpty()) return emptyList()

        val ordered = located.sortedByDescending { it.takenAt?.toEpochMilliseconds() ?: 0L }
        val radiusAt = { latitude: Double -> Geo.metersPerPixel(latitude, zoom) * mergePixels }
        return greedy(ordered, radiusAt)
    }

    /**
     * Clusters at a fixed radius on the ground, for everything that is not a map.
     *
     * Memories asks "which places has this person been photographed in", and that question
     * has an answer that does not depend on how far the map happens to be zoomed.
     */
    fun clusterByDistance(items: List<MediaItem>, radiusMeters: Double = SAME_PLACE_METERS): List<Cluster> {
        val located = items.filter { it.location != null && !it.hidden }
        if (located.isEmpty()) return emptyList()
        val ordered = located.sortedByDescending { it.takenAt?.toEpochMilliseconds() ?: 0L }
        return greedy(ordered) { radiusMeters }
    }

    /**
     * The clustering itself.
     *
     * A photograph joins the first cluster whose centre it is within reach of, and the
     * centre is *not* recomputed as members arrive. Moving it would let a chain of
     * photographs a hundred metres apart each drag one cluster across a whole city — the
     * classic single-linkage failure, which on a map looks like one pin swallowing a
     * country.
     */
    private fun greedy(ordered: List<MediaItem>, radiusAt: (Double) -> Double): List<Cluster> {
        val seeds = mutableListOf<GeoPoint>()
        val members = mutableListOf<MutableList<MediaItem>>()

        for (item in ordered) {
            val point = item.location ?: continue
            val index = seeds.indexOfFirst { seed ->
                Geo.distanceMeters(seed, point) <= radiusAt(seed.lat)
            }
            if (index >= 0) {
                members[index] += item
            } else {
                seeds += point
                members += mutableListOf(item)
            }
        }

        return seeds.indices.map { i ->
            val points = members[i].mapNotNull { it.location }
            Cluster(
                // The centroid of what actually joined, not the seed: the seed is only how
                // the group was found, and a pin on it would sit at the edge of its own
                // photographs.
                center = Geo.centroid(points) ?: seeds[i],
                items = members[i].toList(),
                bounds = Geo.bounds(points) ?: Geo.Bounds(seeds[i].lat, seeds[i].lon, seeds[i].lat, seeds[i].lon),
            )
        }.sortedByDescending { it.count }
    }

    /** How much of the library the map can show at all — the rest has no location. */
    fun locatedFraction(items: List<MediaItem>): Double {
        if (items.isEmpty()) return 0.0
        return items.count { it.location != null }.toDouble() / items.size
    }
}
