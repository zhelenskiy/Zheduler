package com.zhelenskiy.zheduler.zheduler.components.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.OpenStreetMap
import com.zhelenskiy.zheduler.zheduler.geo.TileKey
import com.zhelenskiy.zheduler.zheduler.geo.TileMath
import kotlin.math.roundToInt

/**
 * Where the map is looking.
 *
 * Zoom is a whole number, which is the zoom the tiles themselves come in. Drawing between two of
 * them means scaling every tile by a fraction and rounding each one's edges independently, which
 * shows as hairline gaps across the map; for choosing a spot, a step of zoom per press is no loss.
 */
@Stable
class MapCamera(center: GeoPoint, zoom: Int) {
    var center: GeoPoint by mutableStateOf(center)
        private set

    var zoom: Int by mutableStateOf(zoom.coerceIn(TileMath.MIN_ZOOM, TileMath.MAX_ZOOM))
        private set

    fun moveTo(point: GeoPoint) {
        center = point.sane()
    }

    fun zoomTo(value: Int) {
        zoom = value.coerceIn(TileMath.MIN_ZOOM, TileMath.MAX_ZOOM)
    }

    fun zoomBy(steps: Int) = zoomTo(zoom + steps)

    fun show(point: GeoPoint, zoom: Int) {
        moveTo(point)
        zoomTo(zoom)
    }

    /**
     * Slides the view by [pan] screen pixels, as a drag does.
     *
     * The vertical is bounded by the edges of the projection so a drag cannot fling the map off
     * into the grey above the north pole; the horizontal is not, because longitude goes round.
     */
    internal fun panBy(pan: Offset, viewport: Size) {
        val world = TileMath.worldSize(zoom)
        val (x, y) = TileMath.toPixels(center, zoom)
        val movedX = x - pan.x
        val movedY = y - pan.y
        val halfHeight = viewport.height / 2
        val boundedY = if (world <= viewport.height) world / 2
        else movedY.coerceIn(halfHeight.toDouble(), world - halfHeight)
        center = TileMath.toPoint(movedX, boundedY, zoom)
    }

    companion object {
        /** Kept across a recreation: coming back to a rotated screen at the default zoom loses the user's place. */
        val Saver: Saver<MapCamera, Any> = listSaver(
            save = { listOf(it.center.latitude, it.center.longitude, it.zoom) },
            restore = { saved ->
                MapCamera(
                    center = GeoPoint(saved[0] as Double, saved[1] as Double),
                    zoom = saved[2] as Int,
                )
            },
        )
    }
}

@Composable
fun rememberMapCamera(
    center: GeoPoint,
    zoom: Int = TileMath.DEFAULT_ZOOM,
    key: String = "map",
): MapCamera = rememberSaveable(key = key, saver = MapCamera.Saver) { MapCamera(center, zoom) }

/**
 * An OpenStreetMap, drawn from its own raster tiles.
 *
 * There is no map SDK behind this. A tile is a 256-pixel PNG named by zoom, column and row, so a
 * map is a fetch and some arithmetic — and doing it that way is what lets the same map run on the
 * phones, the desktop and both browser builds, none of which any one map library covers. See
 * [TileMath] for the projection and [OpenStreetMap] for what the tile servers are owed.
 *
 * @param areas the places in question: a pin each, and the circle that is how near counts as being
 *   there. More than one because a rule may watch several.
 * @param highlights other points worth showing — search results waiting to be picked.
 * @param onTap where the user pressed, in degrees, or null for a map that is only being looked at.
 */
@Composable
fun OsmMap(
    camera: MapCamera,
    modifier: Modifier = Modifier,
    areas: List<GeoArea> = emptyList(),
    highlights: List<GeoPoint> = emptyList(),
    onTap: ((GeoPoint) -> Unit)? = null,
) {
    val cache = rememberTileCache()
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .clipToBounds()
            .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    // A pinch is reported as the ratio since the *last pointer event*, which for a
                    // real gesture is a per-frame 1.01 or so. Compared against a threshold on its
                    // own it never crosses one and the map simply does not zoom; multiplied up
                    // across the gesture it steps when the fingers have really moved that far.
                    var pinched = 1f
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        camera.panBy(pan, size.toSize())
                        // Exactly one is what a single finger reports, so this is also where a
                        // gesture that is not a pinch clears what the last one left part-way.
                        if (zoomChange == 1f) pinched = 1f
                        pinched *= zoomChange
                        while (pinched >= PINCH_STEP) {
                            camera.zoomBy(1)
                            pinched /= PINCH_STEP
                        }
                        while (pinched <= 1f / PINCH_STEP) {
                            camera.zoomBy(-1)
                            pinched *= PINCH_STEP
                        }
                    }
                }
                .pointerInput(camera, onTap) {
                    detectTapGestures(
                        onDoubleTap = { camera.zoomBy(1) },
                        onTap = onTapped@{ offset ->
                            val tap = onTap ?: return@onTapped
                            tap(camera.pointAt(offset, size.toSize()))
                        },
                    )
                }
        ) {
            drawMap(camera, cache, size, areas, highlights, colors.primary, colors.outlineVariant)
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalIconButton(onClick = { camera.zoomBy(1) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in")
            }
            FilledTonalIconButton(onClick = { camera.zoomBy(-1) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out")
            }
        }

        // Required by the tile server's terms, not decoration.
        Text(
            text = OpenStreetMap.ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(colors.surface.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** The point under [offset], for a viewport of [viewport]. */
private fun MapCamera.pointAt(offset: Offset, viewport: Size): GeoPoint {
    val (x, y) = TileMath.toPixels(center, zoom)
    return TileMath.toPoint(
        x = x - viewport.width / 2 + offset.x,
        y = y - viewport.height / 2 + offset.y,
        zoom = zoom,
    )
}

private fun DrawScope.drawMap(
    camera: MapCamera,
    cache: TileCache,
    viewport: Size,
    areas: List<GeoArea>,
    highlights: List<GeoPoint>,
    accent: Color,
    grid: Color,
) {
    if (viewport.width <= 0 || viewport.height <= 0) return
    // Read so that the draw depends on it: clearing the failed tiles has to bring the draw round
    // again, and it is the draw that asks for them. See TileCache.retryFailed.
    @Suppress("UNUSED_EXPRESSION") cache.revision
    val zoom = camera.zoom
    val (centerX, centerY) = TileMath.toPixels(camera.center, zoom)
    // The world pixel at the top-left corner of the view, which everything below is drawn relative to.
    val originX = centerX - viewport.width / 2
    val originY = centerY - viewport.height / 2

    val visible = mutableSetOf<TileKey>()
    val firstColumn = TileMath.tileIndex(originX)
    val lastColumn = TileMath.tileIndex(originX + viewport.width)
    val firstRow = TileMath.tileIndex(originY)
    val lastRow = TileMath.tileIndex(originY + viewport.height)

    for (column in firstColumn..lastColumn) {
        for (row in firstRow..lastRow) {
            // The column is wrapped and the row is not: going east far enough comes back round,
            // going north far enough runs out of world.
            val key = TileKey(zoom, TileMath.wrapColumn(column, zoom), row)
            if (!key.isReal) continue
            visible += key
            cache.request(key)
            val image = cache.tiles[key]
            // Whole pixels, and from the tile's own index so neighbours land exactly 256 apart:
            // rounding each edge separately is what puts hairlines between tiles.
            val left = (column * TileMath.TILE_SIZE - originX).roundToInt()
            val top = (row * TileMath.TILE_SIZE - originY).roundToInt()
            if (image == null) {
                // Something rather than nothing while it loads, so panning into new ground does
                // not read as the map having broken.
                drawRect(
                    color = grid,
                    topLeft = Offset(left.toFloat(), top.toFloat()),
                    size = Size(TileMath.TILE_SIZE.toFloat(), TileMath.TILE_SIZE.toFloat()),
                )
            } else {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(TileMath.TILE_SIZE, TileMath.TILE_SIZE),
                )
            }
        }
    }
    cache.keepOnly(visible)

    val world = TileMath.worldSize(zoom)

    /**
     * Every place on screen this point is drawn at.
     *
     * More than one, because the tiles repeat: at a wide zoom the world is narrower than the
     * screen — 1024 pixels across at zoom 2, which is less than a phone — so the same ground is
     * drawn two or three times side by side, and a pin belongs at each copy. Bringing the pin to a
     * single copy instead put it off the edge of a map that was plainly showing the place.
     */
    fun screensOf(point: GeoPoint, reach: Double): List<Offset> {
        val (x, y) = TileMath.toPixels(point, zoom)
        val top = (y - originY).toFloat()
        var screenX = x - originX
        // Back to the first copy at or before the left edge, then forward across the viewport.
        while (screenX > 0) screenX -= world
        val places = mutableListOf<Offset>()
        while (screenX <= viewport.width + reach) {
            if (screenX >= -reach) places += Offset(screenX.toFloat(), top)
            screenX += world
        }
        return places
    }

    highlights.forEach { point ->
        screensOf(point, PIN_REACH).forEach { at ->
            drawCircle(color = accent.copy(alpha = 0.35f), radius = 10f, center = at)
            drawCircle(color = accent, radius = 5f, center = at)
        }
    }

    areas.forEach { area ->
        val radiusPixels =
            (area.radius() / TileMath.metersPerPixel(area.point.sane().latitude, zoom)).toFloat()
        val drawable = radiusPixels.isFinite() && radiusPixels > 0
        // A circle reaches as far as its own radius: one centred off screen can still cover the
        // whole of it, and culling by the pin's size alone would drop the ring the user is looking
        // at. Bounded, because a circle wider than a few worlds is drawn everywhere anyway.
        val reach = if (drawable) radiusPixels.toDouble().coerceAtMost(world) else PIN_REACH
        screensOf(area.point, reach).forEach { at ->
            if (drawable) {
                drawCircle(color = accent.copy(alpha = 0.15f), radius = radiusPixels, center = at)
                drawCircle(color = accent, radius = radiusPixels, center = at, style = Stroke(width = 2f))
            }
            // A ring rather than a dot, so a pin is still visible over a dark tile.
            drawCircle(color = Color.White, radius = 7f, center = at)
            drawCircle(color = accent, radius = 5f, center = at)
        }
    }
}

/** How far beyond the edge a bare pin is still worth drawing: its own size. */
private const val PIN_REACH = 16.0

/**
 * How far a pinch has to travel before it is worth a whole step of zoom.
 *
 * A step of zoom doubles the scale, so a doubling of the gesture is the honest threshold — and
 * what is left over is carried on rather than thrown away, so a long pinch keeps stepping.
 */
private const val PINCH_STEP = 2f
