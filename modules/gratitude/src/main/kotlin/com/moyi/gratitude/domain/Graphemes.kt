package com.moyi.gratitude.domain

import java.text.BreakIterator
import java.util.Locale

/**
 * User-perceived characters, per Unicode text segmentation
 * ([UAX #29](https://unicode.org/reports/tr29/)) rather than `String.length`'s UTF-16 code
 * units: a ZWJ emoji sequence is one of these and several of those. What "characters" means
 * wherever this module limits a person's text: an entry ([EntryText]) and a search
 * ([SearchTerms]). One count, so the two cannot come to disagree.
 */
internal object Graphemes {
    fun count(text: String): Int {
        val boundary = BreakIterator.getCharacterInstance(Locale.ROOT)
        boundary.setText(text)
        var count = 0
        while (boundary.next() != BreakIterator.DONE) count++
        return count
    }
}
