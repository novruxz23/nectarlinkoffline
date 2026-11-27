// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlacementTest {
    private val density = 2f

    @Test
    fun `what is remembered is read back`() {
        val p = OverlayPlacement(40, 120, 600, 340, collapsed = true)
        assertEquals(p, OverlayPlacement.decode(p.encode()))
        assertEquals(OverlayPlacement(0, 0, 10, 10, false), OverlayPlacement.decode("0,0,10,10,0"))
    }

    @Test
    fun `nonsense is not a placement`() {
        assertNull(OverlayPlacement.decode(null))
        assertNull(OverlayPlacement.decode(""))
        assertNull(OverlayPlacement.decode("1,2,3"))
        assertNull(OverlayPlacement.decode("a,b,c,d,e"))
        assertNull(OverlayPlacement.decode("1,2,0,10,0"))
    }

    @Test
    fun `a first overlay keeps the window's shape and stays on the screen`() {
        val p = OverlayPlacement.initial(1080, 2400, 1366, 768, 0, density)
        assertEquals(1366f / 768f, p.width.toFloat() / p.height, 0.02f)
        assertTrue(p.x >= 0 && p.y >= 0)
        assertTrue(p.x + p.width <= 1080 && p.y + p.height <= 2400)
    }

    @Test
    fun `later overlays are placed a little lower than the first`() {
        val first = OverlayPlacement.initial(1080, 2400, 1366, 768, 0, density)
        val second = OverlayPlacement.initial(1080, 2400, 1366, 768, 1, density)
        assertTrue(second.y > first.y)
    }

    @Test
    fun `a very tall window is still big enough to use`() {
        val p = OverlayPlacement.initial(1080, 2400, 300, 3000, 0, density)
        assertTrue(minOf(p.width, p.height) >= (OverlayPlacement.MIN_DP * density).toInt() - 1)
    }

    @Test
    fun `an overlay is kept on the screen and no smaller than the least`() {
        val p = OverlayPlacement.clamp(OverlayPlacement(-50, 5000, 20, 20), 1080, 2400, density)
        assertEquals(0, p.x)
        assertEquals((OverlayPlacement.MIN_DP * density).toInt(), p.width)
        assertTrue(p.y + p.height <= 2400)
    }

    @Test
    fun `a pinch scales around the center`() {
        val start = OverlayPlacement(300, 600, 400, 300)
        val bigger = OverlayPlacement.scaled(start, 1.5f, 1080, 2400, density)
        assertEquals(600, bigger.width)
        assertEquals(450, bigger.height)
        assertEquals(500, bigger.x + bigger.width / 2)
        assertEquals(750, bigger.y + bigger.height / 2)
        val tiny = OverlayPlacement.scaled(start, 0.01f, 1080, 2400, density)
        assertEquals((OverlayPlacement.MIN_DP * density).toInt(), tiny.width)
    }
}
