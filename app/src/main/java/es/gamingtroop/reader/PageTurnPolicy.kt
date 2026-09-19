package es.gamingtroop.reader

import kotlin.math.abs

/** Physical screen coordinates, independent of the zoomed document or reading direction. */
object PageTurnPolicy {
    // Unknown future/old values safely retain the original swipe default.
    fun usesEdgeTaps(mode: String) = mode == "edges"

    fun canPan(offset: Int, range: Int, extent: Int, direction: Int, tolerance: Int): Boolean {
        val maximum = (range.toLong() - extent).coerceAtLeast(0)
        if(maximum <= 0) return false
        val margin = tolerance.coerceAtLeast(0)
        return if(direction < 0) offset > margin else maximum - offset > margin
    }

    // 1 = next, -1 = previous, 0 = not a page turn.
    fun tap(mode: String, x: Float, width: Int, rtl: Boolean): Int {
        if (!usesEdgeTaps(mode) || width <= 0) return 0
        val side = when {
            x in 0f..(width * .22f) -> -1
            x in (width * .78f)..width.toFloat() -> 1
            else -> 0
        }
        return if (rtl) -side else side
    }

    fun swipe(mode: String, dx: Float, dy: Float, density: Float, rtl: Boolean,
              multiplePointers: Boolean, zoomed: Boolean,
              couldPanLeftAtStart: Boolean, couldPanRightAtStart: Boolean): Int {
        if (usesEdgeTaps(mode) || multiplePointers || abs(dx) <= 64 * density || abs(dx) <= abs(dy) * 1.5f) return 0
        // Judge the boundary at DOWN, not UP: a pan reaching an edge must not turn
        // a page as well. The next deliberate outward swipe does turn it.
        if (zoomed && if (dx < 0) couldPanRightAtStart else couldPanLeftAtStart) return 0
        return if ((dx < 0) != rtl) 1 else -1
    }
}
