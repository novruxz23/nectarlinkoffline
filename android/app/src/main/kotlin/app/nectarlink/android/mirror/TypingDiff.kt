// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

/** What typing did to a field: backspaces for what went, and the text that came. */
data class TypingDiff(val backspaces: Int, val added: String) {
    companion object {
        /** The change from `before` to `after`, by what they share at the start. */
        fun between(before: String, after: String): TypingDiff {
            var common = 0
            val limit = minOf(before.length, after.length)
            while (common < limit && before[common] == after[common]) common++
            // Don't split a character made of two chars (an emoji) down the middle.
            if (common > 0 && common < before.length && before[common - 1].isHighSurrogate()) common--
            return TypingDiff(before.length - common, after.substring(common))
        }
    }
}
