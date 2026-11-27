// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCoordsTest {
    private val eps = 0.001f

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>?) {
        assertNotNull(actual)
        assertEquals(expected.first, actual!!.first, eps)
        assertEquals(expected.second, actual.second, eps)
    }

    @Test
    fun `a wide window is letterboxed above and below in a square overlay`() {
        val fit = OverlayCoords.fit(400f, 400f, 1600, 800)
        assertEquals(0f, fit.left, eps)
        assertEquals(400f, fit.width, eps)
        assertEquals(200f, fit.height, eps)
        assertEquals(100f, fit.top, eps)
    }

    @Test
    fun `a tall window is pillarboxed beside in a wide overlay`() {
        val fit = OverlayCoords.fit(800f, 400f, 400, 800)
        assertEquals(200f, fit.width, eps)
        assertEquals(400f, fit.height, eps)
        assertEquals(300f, fit.left, eps)
        assertEquals(0f, fit.top, eps)
    }

    @Test
    fun `a window of the overlay's own shape fills it`() {
        val fit = OverlayCoords.fit(683f, 384f, 1366, 768)
        assertEquals(0f, fit.left, eps)
        assertEquals(0f, fit.top, eps)
        assertEquals(683f, fit.width, eps)
        assertEquals(384f, fit.height, eps)
    }

    @Test
    fun `nothing to fit gives an empty picture`() {
        assertEquals(0f, OverlayCoords.fit(0f, 100f, 100, 100).width, eps)
        assertEquals(0f, OverlayCoords.fit(100f, 100f, 0, 100).width, eps)
    }

    @Test
    fun `the corners and the center of the picture are the window's`() {
        // 1366x768 window in a 700x500 overlay: scale 700/1366, picture 700 x ~393.6, bars above and below.
        val fit = OverlayCoords.fit(700f, 500f, 1366, 768)
        assertPoint(0f to 0f, OverlayCoords.toWindow(fit.left, fit.top, 700f, 500f, 1366, 768))
        assertPoint(1f to 1f, OverlayCoords.toWindow(fit.right, fit.bottom, 700f, 500f, 1366, 768))
        assertPoint(0.5f to 0.5f, OverlayCoords.toWindow(350f, 250f, 700f, 500f, 1366, 768))
    }

    @Test
    fun `a touch on a bar is not on the window`() {
        assertNull(OverlayCoords.toWindow(350f, 10f, 700f, 500f, 1366, 768))
        assertNull(OverlayCoords.toWindow(350f, 495f, 700f, 500f, 1366, 768))
        assertNull(OverlayCoords.toWindow(-1f, 250f, 700f, 500f, 1366, 768))
    }

    @Test
    fun `a drag past the picture is held to its edge`() {
        assertPoint(1f to 0f, OverlayCoords.toWindowClamped(900f, -50f, 700f, 500f, 1366, 768))
        assertPoint(0f to 1f, OverlayCoords.toWindowClamped(-30f, 900f, 700f, 500f, 1366, 768))
    }

    @Test
    fun `the same touch maps the same at any overlay size`() {
        // The window's center is the window's center, small or large.
        for (size in listOf(240f to 135f, 700f to 394f, 1000f to 900f)) {
            assertPoint(0.5f to 0.5f, OverlayCoords.toWindow(size.first / 2, size.second / 2, size.first, size.second, 1280, 720))
        }
    }

    @Test
    fun `a turned picture is fitted by its turned shape`() {
        // A 1600x800 window turned a quarter shows 800 wide and 1600 high: in a 400x400 view it is 200 x 400.
        val fit = OverlayCoords.fit(400f, 400f, 1600, 800, quarterTurns = 1)
        assertEquals(200f, fit.width, eps)
        assertEquals(400f, fit.height, eps)
        assertEquals(100f, fit.left, eps)
        assertEquals(0.5f, OverlayCoords.shownAspect(1600, 800, 1), eps)
        assertEquals(2f, OverlayCoords.shownAspect(1600, 800, 2), eps)
    }

    @Test
    fun `a quarter turn clockwise moves the window's top left to the top right of the picture`() {
        val fit = OverlayCoords.fit(400f, 400f, 1600, 800, quarterTurns = 1)
        // The turned picture's top right is the window's top left.
        assertPoint(0f to 0f, OverlayCoords.toWindow(fit.right, fit.top, 400f, 400f, 1600, 800, 1))
        // Its bottom right is the window's bottom left (clockwise: left edge goes to the top, bottom to the left).
        assertPoint(0f to 1f, OverlayCoords.toWindow(fit.left, fit.top, 400f, 400f, 1600, 800, 1))
        assertPoint(1f to 0f, OverlayCoords.toWindow(fit.right, fit.bottom, 400f, 400f, 1600, 800, 1))
        assertPoint(1f to 1f, OverlayCoords.toWindow(fit.left, fit.bottom, 400f, 400f, 1600, 800, 1))
    }

    @Test
    fun `a half turn flips both ways and three quarters turns back`() {
        val fit = OverlayCoords.fit(800f, 400f, 1600, 800, quarterTurns = 2)
        assertPoint(1f to 1f, OverlayCoords.toWindow(fit.left, fit.top, 800f, 400f, 1600, 800, 2))
        assertPoint(0f to 0f, OverlayCoords.toWindow(fit.right, fit.bottom, 800f, 400f, 1600, 800, 2))
        val fit3 = OverlayCoords.fit(400f, 400f, 1600, 800, quarterTurns = 3)
        assertPoint(0f to 1f, OverlayCoords.toWindow(fit3.right, fit3.bottom, 400f, 400f, 1600, 800, 3))
        assertPoint(1f to 0f, OverlayCoords.toWindow(fit3.left, fit3.top, 400f, 400f, 1600, 800, 3))
    }

    @Test
    fun `four quarter turns and zero are the same, and so is a turn backwards`() {
        val zero = OverlayCoords.toWindow(123f, 77f, 700f, 500f, 1366, 768, 0)
        assertEquals(zero, OverlayCoords.toWindow(123f, 77f, 700f, 500f, 1366, 768, 4))
        val back = OverlayCoords.toWindow(300f, 100f, 400f, 400f, 1600, 800, -1)
        assertEquals(back, OverlayCoords.toWindow(300f, 100f, 400f, 400f, 1600, 800, 3))
    }

    @Test
    fun `a finger moving up scrolls down`() {
        assertTrue(OverlayCoords.scrollNotches(-80f, 40f) > 0f)
        assertEquals(2f, OverlayCoords.scrollNotches(-80f, 40f), eps)
        assertEquals(0f, OverlayCoords.scrollNotches(10f, 0f), eps)
    }
}
