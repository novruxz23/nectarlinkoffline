// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class TypingDiffTest {
    @Test
    fun `typing adds the new letters`() {
        assertEquals(TypingDiff(0, "o"), TypingDiff.between("hell", "hello"))
        assertEquals(TypingDiff(0, "hi"), TypingDiff.between("", "hi"))
    }

    @Test
    fun `deleting is backspaces`() {
        assertEquals(TypingDiff(2, ""), TypingDiff.between("hello", "hel"))
        assertEquals(TypingDiff(5, ""), TypingDiff.between("hello", ""))
    }

    @Test
    fun `a correction is backspaces then the new text`() {
        assertEquals(TypingDiff(2, "p!"), TypingDiff.between("help", "hep!"))
        assertEquals(TypingDiff(3, "ay"), TypingDiff.between("play", "pay"))
    }

    @Test
    fun `nothing changed is nothing`() {
        assertEquals(TypingDiff(0, ""), TypingDiff.between("same", "same"))
    }
}
