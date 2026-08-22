package com.zhelenskiy.zheduler.zheduler.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator, which is the projection every slippy map is drawn in.
 *
 * The world at zoom `z` is a square of `256 * 2^z` pixels, cut into 256-pixel tiles. Everything
 * here converts between three ways of naming a spot on it: degrees, that pixel square, and the
 * tile that contains it. Kept apart from the drawing so the arithmetic can be tested without a
 * screen — the projection is the part that is easy to get subtly wrong and impossible to eyeball.
 */
object TileMath {

    const val TILE_SIZE = 256

    /**
     * The furthest north and south the projection reaches.
     *
     * Mercator sends the poles to infinity, so every implementation cuts it off; this is the
     * latitude that makes the world exactly square, and it is what the tile servers use.
     */
    const val MAX_LATITUDE = 85.05112878

    /** How far in the tiles go. OpenStreetMap serves to 19. */
    const val MIN_ZOOM = 1
    const val MAX_ZOOM = 19

    /** How many pixels the whole world is across at [zoom]. */
    fun worldSize(zoom: Int): Double = TILE_SIZE * 2.0.pow(zoom)

    /** The horizontal pixel of [longitude] in the world square at [zoom]. */
    fun longitudeToX(longitude: Double, zoom: Int): Double =
        (longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * worldSize(zoom)

    /** The vertical pixel of [latitude], which is where the projection stops being linear. */
    fun latitudeToY(latitude: Double, zoom: Int): Double {
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val radians = clamped * PI / 180.0
        val mercator = ln(tan(radians) + 1.0 / cos(radians))
        return (1.0 - mercator / PI) / 2.0 * worldSize(zoom)
    }

    fun xToLongitude(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun yToLatitude(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return 180.0 / PI * atan(sinh(n))
    }

    fun toPixels(point: GeoPoint, zoom: Int): Pair<Double, Double> {
        val sane = point.sane()
        return longitudeToX(sane.longitude, zoom) to latitudeToY(sane.latitude, zoom)
    }

    /**
     * The point at a world pixel, brought back into range.
     *
     * The pixel need not be inside the world square: dragging the map east goes on for as long as
     * the finger does, and asking for the point at pixel −40,000 is how "round the back" is
     * spelled. Left unwrapped, the answer is a longitude of −430 — which every drawing path here
     * would wrap for itself, but which would also be handed to a tap and saved as the coordinates
     * of a place that is nowhere.
     */
    fun toPoint(x: Double, y: Double, zoom: Int): GeoPoint =
        GeoPoint(latitude = yToLatitude(y, zoom), longitude = xToLongitude(x, zoom)).sane()

    /** The index of the tile containing pixel [value], as a whole number of tiles. */
    fun tileIndex(value: Double): Int = floor(value / TILE_SIZE).toInt()

    /** How many tiles across the world is at [zoom], which is what a column index wraps at. */
    fun tileCount(zoom: Int): Int = 1 shl zoom

    /**
     * A tile column brought into range by going round the world.
     *
     * Longitude wraps and the map is scrolled sideways without limit, so a viewport straddling the
     * antimeridian asks for column −1, which is the last column. Rows do not wrap: there is nothing
     * above the north pole, and a request for one is simply not drawn.
     */
    fun wrapColumn(x: Int, zoom: Int): Int {
        val count = tileCount(zoom)
        val wrapped = x % count
        return if (wrapped < 0) wrapped + count else wrapped
    }

    /**
     * How many metres one pixel covers at [latitude] and [zoom].
     *
     * Mercator stretches with latitude, so this is what a distance on the ground is worth on the
     * screen — how the radius of an area becomes the radius of a circle to draw.
     */
    fun metersPerPixel(latitude: Double, zoom: Int): Double {
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return EQUATOR_METERS * cos(clamped * PI / 180.0) / worldSize(zoom)
    }

    /**
     * The zoom at which a circle of [radiusMeters] fits in a viewport [viewportPixels] across.
     *
     * Used to open the map on a place already framed by the area it is about, rather than at some
     * fixed zoom that shows a fence either as a dot or as the whole screen.
     */
    fun zoomFor(radiusMeters: Double, latitude: Double, viewportPixels: Double): Int {
        if (viewportPixels <= 0 || radiusMeters <= 0) return DEFAULT_ZOOM
        // The circle is drawn across the middle half of the viewport, so there is map around it.
        val wanted = radiusMeters * 2 / (viewportPixels / 2)
        // Where even the whole world is too small to frame it — a fence of several thousand
        // kilometres — the widest view is the best answer there is. Falling back on the closest
        // would open a continent-sized area at street level, which is the wrongest of the two.
        return (MIN_ZOOM..MAX_ZOOM).lastOrNull { metersPerPixel(latitude, it) >= wanted } ?: MIN_ZOOM
    }

    /** Where the map opens when nothing says otherwise: close enough to recognise a street. */
    const val DEFAULT_ZOOM = 15

    /** The circumference at the equator, which is what a Mercator pixel is measured against. */
    private const val EQUATOR_METERS = 40_075_016.686
}

/** One tile of the map, at a zoom, a column and a row. */
data class TileKey(val zoom: Int, val x: Int, val y: Int) {
    /** Whether this names a tile that can exist: rows do not wrap, so most of them do not. */
    val isReal: Boolean get() = zoom in TileMath.MIN_ZOOM..TileMath.MAX_ZOOM &&
        x in 0 until TileMath.tileCount(zoom) &&
        y in 0 until TileMath.tileCount(zoom)
}
