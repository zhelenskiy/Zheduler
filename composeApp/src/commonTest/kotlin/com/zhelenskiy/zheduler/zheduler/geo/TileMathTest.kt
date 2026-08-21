package com.zhelenskiy.zheduler.zheduler.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The projection the map is drawn in.
 *
 * Worth pinning because a mistake here is invisible rather than obviously wrong: a map with the
 * latitude formula slightly off still shows streets and still pans, it just puts the pin somewhere
 * the user did not press — and the circle it draws for a two-hundred-metre fence is then a
 * two-hundred-metre circle of the wrong place.
 *
 * The expected values are Web Mercator's, worked out independently rather than read off this
 * implementation.
 */
class TileMathTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, what: String) {
        assertTrue(abs(expected - actual) < tolerance, "$what: expected about $expected, was $actual")
    }

    @Test
    fun `the world is one tile at zoom zero and doubles each step`() {
        assertEquals(256.0, TileMath.worldSize(0))
        assertEquals(512.0, TileMath.worldSize(1))
        assertEquals(256.0 * 4096, TileMath.worldSize(12))
        assertEquals(1, TileMath.tileCount(0))
        assertEquals(4096, TileMath.tileCount(12))
    }

    @Test
    fun `the meridians land where they should`() {
        assertEquals(0.0, TileMath.longitudeToX(-180.0, 0))
        assertEquals(128.0, TileMath.longitudeToX(0.0, 0))
        assertEquals(256.0, TileMath.longitudeToX(180.0, 0))
    }

    @Test
    fun `the equator is halfway down and the cut-off is the very top`() {
        assertClose(128.0, TileMath.latitudeToY(0.0, 0), 1e-9, "the equator")
        // The projection is cut at the latitude that makes the world square, so it is the edge.
        assertClose(0.0, TileMath.latitudeToY(TileMath.MAX_LATITUDE, 0), 1e-6, "the northern cut-off")
        assertClose(256.0, TileMath.latitudeToY(-TileMath.MAX_LATITUDE, 0), 1e-6, "the southern cut-off")
    }

    @Test
    fun `a known place lands on the tile every map server puts it on`() {
        // Big Ben at zoom 12. Independently: x = (lon+180)/360 * 4096 = 2046.55,
        // y = (1 - ln(tan φ + sec φ)/π)/2 * 4096 = 1362.02.
        val (x, y) = TileMath.toPixels(GeoPoint(51.5074, -0.1278), 12)
        assertClose(2046.54592 * TileMath.TILE_SIZE, x, 1.0, "x")
        assertClose(1362.02454 * TileMath.TILE_SIZE, y, 1.0, "y")
        assertEquals(2046, TileMath.tileIndex(x))
        assertEquals(1362, TileMath.tileIndex(y))
    }

    @Test
    fun `degrees survive the round trip through pixels`() {
        listOf(
            GeoPoint(51.5074, -0.1278),
            GeoPoint(-33.8688, 151.2093),
            GeoPoint(0.0, 0.0),
            GeoPoint(64.1466, -21.9426),
        ).forEach { point ->
            val (x, y) = TileMath.toPixels(point, 16)
            val back = TileMath.toPoint(x, y, 16)
            assertClose(point.latitude, back.latitude, 1e-6, "latitude of $point")
            assertClose(point.longitude, back.longitude, 1e-6, "longitude of $point")
        }
    }

    @Test
    fun `a column past the edge of the world comes round the other side`() {
        // Longitude wraps and the map scrolls sideways without limit, so a viewport straddling the
        // antimeridian asks for a column that does not exist. Rows do not wrap, and must not.
        assertEquals(3, TileMath.wrapColumn(-1, zoom = 2))
        assertEquals(0, TileMath.wrapColumn(4, zoom = 2))
        assertEquals(2, TileMath.wrapColumn(2, zoom = 2))
    }

    @Test
    fun `a tile above the north pole is not a tile`() {
        assertTrue(TileKey(zoom = 2, x = 0, y = 0).isReal)
        assertFalse(TileKey(zoom = 2, x = 0, y = -1).isReal, "there is nothing above the top row")
        assertFalse(TileKey(zoom = 2, x = 0, y = 4).isReal)
        assertFalse(TileKey(zoom = 2, x = -1, y = 0).isReal, "columns are wrapped before they get here")
        assertFalse(TileKey(zoom = TileMath.MAX_ZOOM + 1, x = 0, y = 0).isReal)
    }

    @Test
    fun `a pixel is worth fewer metres the further from the equator it is`() {
        // The whole equator across 256 pixels: 40,075,016.686 / 256.
        assertClose(156_543.034, TileMath.metersPerPixel(0.0, 0), 0.01, "at the equator, zoom 0")
        // Mercator stretches by 1/cos(latitude), so sixty degrees north is half.
        assertClose(76.437, TileMath.metersPerPixel(60.0, 10), 0.01, "at 60° north, zoom 10")
        assertTrue(TileMath.metersPerPixel(60.0, 10) < TileMath.metersPerPixel(0.0, 10))
    }

    @Test
    fun `a fence opens at a zoom that frames it`() {
        val viewport = 800.0
        listOf(100.0, 500.0, 5_000.0, 100_000.0).forEach { radius ->
            val zoom = TileMath.zoomFor(radius, latitude = 51.5, viewportPixels = viewport)
            val diameterInPixels = radius * 2 / TileMath.metersPerPixel(51.5, zoom)
            assertTrue(
                diameterInPixels <= viewport,
                "a ${radius}m fence at zoom $zoom is $diameterInPixels px across a $viewport px view",
            )
            assertTrue(zoom in TileMath.MIN_ZOOM..TileMath.MAX_ZOOM)
        }
    }

    @Test
    fun `a viewport with no size yet does not ask for a nonsense zoom`() {
        // The first frame measures zero, and a zoom worked out from it would be whatever the
        // arithmetic makes of a division by nothing.
        assertEquals(TileMath.DEFAULT_ZOOM, TileMath.zoomFor(200.0, 51.5, viewportPixels = 0.0))
        assertEquals(TileMath.DEFAULT_ZOOM, TileMath.zoomFor(0.0, 51.5, viewportPixels = 800.0))
    }
}
