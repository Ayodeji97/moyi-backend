package com.moyi.gratitude.domain

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * An entry's body, validated once at the boundary so every later reader
 * trusts it without re-checking (FR-041).
 *
 * FR-041 asks for three limits, and each exists for a different failure:
 *
 * - **Blank.** `require(text.isNotBlank())` rather than Java's own
 *   `String.isBlank`, because ADR-0029 §13 found the two disagree: `U+00A0`
 *   (a non-breaking space) is not blank to `String.isBlank` and Kotlin's
 *   `isBlank` — which delegates to `Character.isWhitespace` — agrees it is.
 *   An entry that is one non-breaking space would otherwise slip past a
 *   naive check and read as nothing at all.
 *
 * - **Grapheme count.** `500` is what a person means by "characters" — a
 *   family emoji built from a Zero-Width Joiner sequence is one grapheme and
 *   as many as ten Java `char`s, and a limit on `String.length` would refuse
 *   legitimately typed text while a code-point count would let ten times the
 *   intended amount through. [graphemes] walks the text with a
 *   `BreakIterator` so the count matches what was typed rather than how it
 *   is encoded.
 *
 * - **Octet cap.** A grapheme has no upper bound on its byte length, so the
 *   grapheme limit alone caps *count*, not *size* — a small number of
 *   pathological graphemes could still reach megabytes. That is what flows
 *   into `text_search`, the stored `tsvector`, the outbox payload and the
 *   256 KB response cap, so [MAX_OCTETS] is the backstop that keeps all four
 *   bounded regardless of what the grapheme count alone would allow.
 *
 * **Order matters.** The octet check runs before [graphemes] walks the
 * string with a `BreakIterator`, so a megabyte of text is refused in one
 * cheap `String.toByteArray` call rather than after an expensive walk of
 * every grapheme boundary in it.
 *
 * Stored raw once accepted — doc 04 §7. Normalisation to NFKC and trimming
 * happen here because they change nothing a person wrote to mean, but no
 * further rewriting does: no case folding, no collapsing of internal
 * whitespace, no stripping of emoji. What is typed is what is kept.
 */
@JvmInline
internal value class EntryText private constructor(
    val value: String,
) {
    /**
     * Never the raw text — doc 18 §5/§9 forbids exactly the leak an unguarded
     * `toString` would cause the first time an `EntryText` is logged or
     * interpolated into an exception message. `identity`'s `Password` is the
     * precedent this follows, not `RegionZone`: `RegionZone` wraps a
     * `ZoneId`, nobody's words, and has nothing to redact.
     */
    override fun toString(): String = "EntryText(redacted)"

    companion object {
        const val MAX_GRAPHEMES = 500
        const val MAX_OCTETS = 8192

        /**
         * @throws IllegalArgumentException naming whichever limit was crossed
         *   first — blank, octets, then graphemes, in that order.
         */
        fun of(raw: String): EntryText {
            val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            require(text.isNotBlank()) { "an entry must say something" }
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) { "an entry is at most $MAX_OCTETS bytes" }
            require(graphemes(text) <= MAX_GRAPHEMES) { "an entry is at most $MAX_GRAPHEMES characters" }
            return EntryText(text)
        }

        /**
         * User-perceived characters, per Unicode text segmentation
         * ([UAX #29](https://unicode.org/reports/tr29/)) rather than
         * `String.length`'s UTF-16 code units — a ZWJ emoji sequence is one
         * of these and several of those.
         */
        private fun graphemes(text: String): Int {
            val boundary = BreakIterator.getCharacterInstance(Locale.ROOT)
            boundary.setText(text)
            var count = 0
            while (boundary.next() != BreakIterator.DONE) count++
            return count
        }
    }
}
