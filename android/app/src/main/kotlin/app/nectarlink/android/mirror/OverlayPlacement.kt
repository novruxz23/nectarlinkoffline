// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where an overlay is on the screen and how big, in pixels, and whether it is a bubble. */
data class OverlayPlacement(
    val x: Int,
    val y: Int,
    /** The width and height of the whole overlay (picture and bars). */
    val width: Int,
    val height: Int,
    val collapsed: Boolean = false,
) {
    /** `x,y,w,h,c`: what is remembered for a window's app. */
    fun encode(): String = "$x,$y,$width,$height,${if (collapsed) 1 else 0}"

    companion object {
        /** The least an overlay is, in device-independent pixels, along its shorter side. */
        const val MIN_DP = 120

        fun decode(text: String?): OverlayPlacement? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 5) return null
            val n = parts.map { it.trim().toIntOrNull() ?: return null }
            if (n[2] <= 0 || n[3] <= 0) return null
            return OverlayPlacement(n[0], n[1], n[2], n[3], n[4] == 1)
        }

        /**
         * The first place for the `index`-th window: about 70 % of the screen's width (or the
         * height it allows), in the window's shape, each a little lower and further in than the last.
         */
        fun initial(
            screenW: Int,
            screenH: Int,
            srcW: Int,
            srcH: Int,
            index: Int,
            density: Float,
        ): OverlayPlacement {
            val aspect = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 16f / 9f
            val maxW = (screenW * 0.7f)
            val maxH = (screenH * 0.5f)
            var w = min(maxW, maxH * aspect)
            var h = w / aspect
            val floor = MIN_DP * density
            if (min(w, h) < floor) {
                val grow = floor / min(w, h)
                w *= grow
                h *= grow
            }
            val step = (24 * density).roundToInt() * index
            return clamp(
                OverlayPlacement(
                    x = ((screenW - w) / 2f).roundToInt() + step,
                    y = (screenH * 0.12f).roundToInt() + step,
                    width = w.roundToInt(),
                    height = h.roundToInt(),
                ),
                screenW,
                screenH,
                density,
            )
        }

        /** `placement` kept on the screen, and no smaller than the least. */
        fun clamp(placement: OverlayPlacement, screenW: Int, screenH: Int, density: Float): OverlayPlacement {
            val floor = (MIN_DP * density).roundToInt()
            val w = placement.width.coerceIn(min(floor, screenW), max(floor, screenW))
            val h = placement.height.coerceIn(min(floor, screenH), max(floor, screenH))
            val x = placement.x.coerceIn(0, max(0, screenW - w))
            val y = placement.y.coerceIn(0, max(0, screenH - h))
            return placement.copy(x = x, y = y, width = w, height = h)
        }

        /**
         * The size an overlay has after being scaled by `factor` around its center (a pinch),
         * kept in the window's shape and on the screen.
         */
        fun scaled(
            placement: OverlayPlacement,
            factor: Float,
            screenW: Int,
            screenH: Int,
            density: Float,
        ): OverlayPlacement {
            val w = (placement.width * factor).roundToInt()
            val h = (placement.height * factor).roundToInt()
            val cx = placement.x + placement.width / 2
            val cy = placement.y + placement.height / 2
            return clamp(placement.copy(x = cx - w / 2, y = cy - h / 2, width = w, height = h), screenW, screenH, density)
        }
    }
}
