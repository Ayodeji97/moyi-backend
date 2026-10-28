package com.moyi.gratitude.domain

import java.text.BreakIterator
import java.util.Locale

/**
 * User-perceived characters, per Unicode text segmentation
 * ([UAX #29](https://unicode.org/reports/tr29/)) rather than `String.length`'s UTF-16 code
 * units: a ZWJ emoji sequence is one of these and several of those. What "characters" means
 * wherever this module limits a person's text: an entry ([EntryText]) and a search
 * ([SearchTerms]). One way of counting, so the two cannot come to count differently. What
 * each hands it is its own: an entry, the text as sent, untrimmed (the owner's ruling of
 * 2026-10-21, ADR-0031 ruling 1); a search, still its NFKC form trimmed, until ADR-0039's
 * question 11 says whether it follows.
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
