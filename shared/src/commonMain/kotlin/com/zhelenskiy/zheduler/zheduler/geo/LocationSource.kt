package com.zhelenskiy.zheduler.zheduler.geo

/**
 * Where the device is, asked of whatever this platform has.
 *
 * Answered by the phone's positioning service, by the browser, or by nothing at all — a desktop
 * has no notion of the question. The engine treats all three failures alike: a null fix is not a
 * device outside every area, it is a device whose whereabouts are unknown, and nothing fires on
 * one. See [PlaceReading.known].
 *
 * Asked only when some rule is actually watching a place. Positioning costs battery and, on the
 * phones, a permission prompt, and neither is worth spending on a database with no such rule in it.
 */
fun interface LocationSource {
    /** Where the device is now, or `null` if it cannot be told. */
    suspend fun currentFix(): GeoFix?
}

/** For platforms with nowhere to ask, and for tests that are not about location. */
object NoLocationSource : LocationSource {
    override suspend fun currentFix(): GeoFix? = null
}
