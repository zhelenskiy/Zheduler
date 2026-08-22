package com.zhelenskiy.zheduler.zheduler.components.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.TileMath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the map is looking, and what moving it does.
 *
 * Worth pinning because it is the part a desktop user drives constantly and the part where a
 * mistake is a map that fights back: zooming towards the middle of the window rather than towards
 * the pointer means the street being looked at slides away every time the wheel turns.
 */
class MapCameraTest {

    private val viewport = Size(800f, 600f)
    private val bigBen = GeoPoint(51.5007, -0.1246)

    private fun camera(zoom: Int = 14) = MapCamera(bigBen, zoom)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, what: String) {
        assertTrue(abs(expected - actual) < tolerance, "$what: expected about $expected, was $actual")
    }

    @Test
    fun `zooming about a point leaves that point where it was`() {
        // The whole of what makes a wheel zoom feel right. A quarter of the way across and down,
        // which is nowhere near the centre, so a centre-anchored zoom would move it a long way.
        val camera = camera()
        val focus = Offset(200f, 150f)
        val before = camera.pointAt(focus, viewport)

        camera.zoomAbout(1, focus, viewport)
        val after = camera.pointAt(focus, viewport)

        assertEquals(15, camera.zoom)
        // Within a pixel at zoom 15, which is a couple of metres on the ground.
        assertClose(before.latitude, after.latitude, 1e-5, "latitude under the pointer")
        assertClose(before.longitude, after.longitude, 1e-5, "longitude under the pointer")
    }

    @Test
    fun `zooming out about a point also leaves it where it was`() {
        val camera = camera()
        val focus = Offset(700f, 500f)
        val before = camera.pointAt(focus, viewport)

        camera.zoomAbout(-1, focus, viewport)
        val after = camera.pointAt(focus, viewport)

        assertEquals(13, camera.zoom)
        assertClose(before.latitude, after.latitude, 1e-4, "latitude under the pointer")
        assertClose(before.longitude, after.longitude, 1e-4, "longitude under the pointer")
    }

    @Test
    fun `zooming about the centre is the same as zooming`() {
        val camera = camera()
        val centre = Offset(viewport.width / 2, viewport.height / 2)

        camera.zoomAbout(1, centre, viewport)

        assertEquals(15, camera.zoom)
        assertClose(bigBen.latitude, camera.center.latitude, 1e-6, "latitude")
        assertClose(bigBen.longitude, camera.center.longitude, 1e-6, "longitude")
    }

    @Test
    fun `a zoom that cannot happen moves nothing`() {
        // At the far end of the range the wheel keeps turning; the map must simply sit there
        // rather than drifting a little further with every notch.
        val camera = MapCamera(bigBen, TileMath.MAX_ZOOM)
        val focus = Offset(50f, 50f)

        camera.zoomAbout(1, focus, viewport)

        assertEquals(TileMath.MAX_ZOOM, camera.zoom)
        assertEquals(bigBen.latitude, camera.center.latitude)
        assertEquals(bigBen.longitude, camera.center.longitude)
    }

    @Test
    fun `the map cannot be dragged off the top of the world`() {
        val camera = camera(zoom = 3)

        repeat(20) { camera.panBy(Offset(0f, 5_000f), viewport) }

        assertTrue(camera.center.latitude <= TileMath.MAX_LATITUDE)
        assertTrue(camera.center.latitude > 0, "still somewhere in the northern hemisphere")
    }

    @Test
    fun `dragging sideways goes round rather than stopping`() {
        val camera = camera(zoom = 3)

        repeat(40) { camera.panBy(Offset(-5_000f, 0f), viewport) }

        // No assertion about where it ends up — only that it stayed a real place, which is what
        // wrapping the longitude is for.
        assertTrue(camera.center.longitude in -180.0..180.0)
    }
}
