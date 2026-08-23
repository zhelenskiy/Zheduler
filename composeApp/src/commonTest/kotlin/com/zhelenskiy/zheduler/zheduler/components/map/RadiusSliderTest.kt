package com.zhelenskiy.zheduler.zheduler.components.map

import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The slider that says how near counts as being there.
 *
 * It moves by ratio rather than by metres, over a range from a doorstep to a county — which is the
 * sort of arithmetic that reads as obviously right and is easy to get wrong at one end. What these
 * pin is that both ends are reachable and that the bottom of the range is usable rather than
 * jumping from the smallest fence straight to something ten times larger.
 */
class RadiusSliderTest {

    @Test
    fun `both ends of the slider are reachable`() {
        assertEquals(GeoArea.MIN_RADIUS_METERS, sliderToRadius(0f))
        assertEquals(GeoArea.MAX_RADIUS_METERS, sliderToRadius(1f))
        assertEquals(0f, radiusToSlider(GeoArea.MIN_RADIUS_METERS))
        assertEquals(1f, radiusToSlider(GeoArea.MAX_RADIUS_METERS))
    }

    @Test
    fun `a radius survives the trip to the slider and back`() {
        listOf(1.0, 5.0, 50.0, 200.0, 1_000.0, 25_000.0, 400_000.0).forEach { radius ->
            val back = sliderToRadius(radiusToSlider(radius))
            assertEquals(radius, back, "$radius m came back as $back m")
        }
    }

    @Test
    fun `the smallest fences are a metre apart rather than ten`() {
        // The floor is a metre, so the rounding step has to be one down there too: at ten metres
        // apart every position between the floor and 10 m would round to nothing and be clamped
        // back, and a 3 m fence could not be asked for at all — which is the whole point of
        // allowing one beside a condition that is exact.
        val small = (0..200).map { sliderToRadius(it / 1000f) }.distinct().filter { it < 10 }
        assertEquals(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0),
            small,
            "every whole metre under ten should be askable for",
        )
        assertTrue(small.all { it >= GeoArea.MIN_RADIUS_METERS })
    }

    @Test
    fun `a position off the end of the slider is not a radius off the end of the range`() {
        assertEquals(GeoArea.MIN_RADIUS_METERS, sliderToRadius(-1f))
        assertEquals(GeoArea.MAX_RADIUS_METERS, sliderToRadius(2f))
        assertEquals(0f, radiusToSlider(0.0))
        assertEquals(1f, radiusToSlider(Double.MAX_VALUE))
    }
}
