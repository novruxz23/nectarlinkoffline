// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import kotlin.math.min

/** The part of a view a picture takes, in the view's pixels. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Where a shared PC window's picture sits inside its overlay, and where a touch on
 * the overlay lands in the window (`docs/protocol/mirror.md` §7).
 *
 * The window is scaled to fit the overlay with its shape kept, so there are bars
 * beside or above it. Input goes to the PC as fractions of the **window**
 * (0 at its left or top, 1 at its right or bottom), after the bars and the scale
 * are taken off, so it holds at any overlay size.
 *
 * `quarterTurns` is how often the picture is turned clockwise in the overlay (0 to 3,
 * any whole number is taken modulo 4); a turned picture is fitted by its turned shape.
 */
object OverlayCoords {
    /** The picture of a `srcW` × `srcH` window, fitted to a `viewW` × `viewH` view and centered. */
    fun fit(viewW: Float, viewH: Float, srcW: Int, srcH: Int, quarterTurns: Int = 0): FitRect {
        if (viewW <= 0f || viewH <= 0f || srcW <= 0 || srcH <= 0) return FitRect(0f, 0f, 0f, 0f)
        val turned = quarterTurns.mod(4) % 2 == 1
        val shownW = (if (turned) srcH else srcW).toFloat()
        val shownH = (if (turned) srcW else srcH).toFloat()
        val scale = min(viewW / shownW, viewH / shownH)
        val w = shownW * scale
        val h = shownH * scale
        return FitRect((viewW - w) / 2f, (viewH - h) / 2f, w, h)
    }

    /**
     * The window position (fractions, x then y) under the touch at `px`, `py` of the
     * view, or null when the touch is on a bar and not on the window.
     */
    fun toWindow(
        px: Float,
        py: Float,
        viewW: Float,
        viewH: Float,
        srcW: Int,
        srcH: Int,
        quarterTurns: Int = 0,
    ): Pair<Float, Float>? {
        val fit = fit(viewW, viewH, srcW, srcH, quarterTurns)
        if (fit.width <= 0f || fit.height <= 0f) return null
        if (px < fit.left || px > fit.right || py < fit.top || py > fit.bottom) return null
        return unturn((px - fit.left) / fit.width, (py - fit.top) / fit.height, quarterTurns)
    }

    /**
     * Like [toWindow], but a touch on a bar (or off the view) is held to the picture's edge:
     * for a drag that started on the window and carries on past it.
     */
    fun toWindowClamped(
        px: Float,
        py: Float,
        viewW: Float,
        viewH: Float,
        srcW: Int,
        srcH: Int,
        quarterTurns: Int = 0,
    ): Pair<Float, Float>? {
        val fit = fit(viewW, viewH, srcW, srcH, quarterTurns)
        if (fit.width <= 0f || fit.height <= 0f) return null
        val u = ((px - fit.left) / fit.width).coerceIn(0f, 1f)
        val v = ((py - fit.top) / fit.height).coerceIn(0f, 1f)
        return unturn(u, v, quarterTurns)
    }

    /** A position in the turned picture (`u`, `v`) as a position in the window. */
    private fun unturn(u: Float, v: Float, quarterTurns: Int): Pair<Float, Float> = when (quarterTurns.mod(4)) {
        1 -> v to (1f - u)
        2 -> (1f - u) to (1f - v)
        3 -> (1f - v) to u
        else -> u to v
    }

    /** The shape (width over height) a window of `srcW` × `srcH` has once turned. */
    fun shownAspect(srcW: Int, srcH: Int, quarterTurns: Int): Float {
        if (srcW <= 0 || srcH <= 0) return 1f
        return if (quarterTurns.mod(4) % 2 == 1) srcH.toFloat() / srcW else srcW.toFloat() / srcH
    }

    /** Wheel notches for a finger that moved `deltaPx` (a finger moving up scrolls down). */
    fun scrollNotches(deltaPx: Float, notchPx: Float): Float = if (notchPx <= 0f) 0f else -deltaPx / notchPx
}
