package com.zhelenskiy.zheduler.zheduler.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How far a place is, as the places screens say it.
 *
 * The number has to mean the same thing the rule does, or it is worse than no number: someone
 * walking towards a place and watching a figure count down expects to arrive when it reaches zero.
 */
class MetresOutsideTest {

    private val centre = GeoPoint(51.5, -0.12)
    private fun fence(radius: Double) = GeoArea("the office", centre, radius)

    @Test
    fun `the distance is to the edge rather than to the middle`() {
        // A place a hundred metres wide, stood two hundred metres from its centre: a hundred
        // metres from arriving. Measured to the centre the screen would say two hundred, which
        // matches nothing the user can observe and never reaches zero where the rule fires.
        val twoHundredOut = GeoPoint(51.5, -0.1171)
        val toEdge = metresOutside(twoHundredOut, fence(100.0))

        assertTrue(toEdge in 60.0..140.0, "expected about 100 m to the edge, got $toEdge")
    }

    @Test
    fun `standing anywhere inside is no distance at all`() {
        assertEquals(0.0, metresOutside(centre, fence(200.0)))
        // Just inside the edge is still inside: how deeply is not a thing anyone is asking.
        val nearTheEdge = GeoPoint(51.5, -0.1186)
        assertEquals(0.0, metresOutside(nearTheEdge, fence(200.0)))
    }

    @Test
    fun `a wider fence is nearer from the same spot`() {
        // Which is the property that makes the number worth showing beside the radius slider:
        // widening the fence brings its edge towards you, and the figure has to say so.
        val out = GeoPoint(51.5, -0.1171)
        val narrow = metresOutside(out, fence(50.0))
        val wide = metresOutside(out, fence(150.0))

        assertTrue(wide < narrow, "$wide should be nearer than $narrow")
    }

    @Test
    fun `a fence is measured as it is enforced rather than as it was typed`() {
        // `radius()` clamps what a fence can mean, and a rule is enforced on the clamped figure.
        // Measured against the raw number the screen would disagree with the rule at the extremes.
        val out = GeoPoint(51.5, -0.1171)
        val absurd = GeoArea("the office", centre, radiusMeters = -5.0)

        assertEquals(metresOutside(out, fence(GeoArea.MIN_RADIUS_METERS)), metresOutside(out, absurd))
    }
}
