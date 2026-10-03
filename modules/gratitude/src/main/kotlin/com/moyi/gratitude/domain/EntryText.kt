package com.moyi.gratitude.domain

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * An entry's body, validated once at the boundary so every later reader
 * trusts it without re-checking (FR-041).
 *
 * **Stored exactly as sent** (ruling P12; spec §3.2 and doc 04 §7: "stored
 * raw and unmodified"). [value] is the string the member's client sent — not
 * normalised, not trimmed, nothing folded, collapsed or stripped. NFKC and
 * `trim` are used below only to **decide** whether the text is acceptable,
 * on a copy that is then thrown away.
 *
 * An earlier version stored `NFKC(raw).trim()` and said normalisation
 * "changes nothing a person wrote to mean". That was false. NFKC is a lossy,
 * one-way compatibility mapping: `…` becomes `...`, `²` becomes `2`, `™`
 * becomes `TM`, `½` becomes `1⁄2`, `ﬁ` becomes `fi`. Raw text can always be
 * normalised later; normalised text can never be restored — so the couple's
 * words are kept, and anything that wants a normal form derives one.
 *
 * FR-041's limits, and what each is measured on:
 *
 * - **No U+0000.** Postgres `text` cannot hold it, so such a text could never
 *   be stored; refused here it is a `422`, where at the insert it was a `500`.
 *
 * - **Octet cap, on the stored bytes.** A grapheme has no upper bound on its
 *   byte length, so the grapheme limit alone caps *count*, not *size*.
 *   [MAX_OCTETS] bounds what flows into the row, the future search column,
 *   the outbox payload and the 256 KB response cap. It is measured on [value]
 *   — the raw UTF-8 — because that is what V12's `entries_text_octets_check`
 *   measures: the two must agree, or a text this accepts is a `500` at the
 *   insert. The NFKC form can be *larger* than the raw (`ﷺ`, three octets,
 *   expands to eighteen characters); that does not matter to storage, since
 *   the NFKC form is never stored.
 *
 * - **Blank, on the normalised and trimmed form.** `isNotBlank()` rather
 *   than Java's `String.isBlank`, because ADR-0029 §13 found the two
 *   disagree: `U+00A0` (a non-breaking space) is not blank to Java and is to
 *   Kotlin. An entry that is one non-breaking space reads as nothing at all.
 *
 * - **Grapheme count, on the NFKC form, trimmed** — spec §3.2's rule:
 *   "NFKC-normalised before counting". `500` is what a person means by
 *   "characters": a ZWJ family emoji, a flag (two regional indicators) and a
 *   base letter with combining marks are each one. [graphemes] walks the text
 *   with a `BreakIterator` so the count is of what was typed, not of how it
 *   is encoded. **Counting on the NFKC form has a cost the spec accepted and
 *   the owner has been asked about** (ADR-0031, P12): a typographic ellipsis
 *   counts as three, so a client's own counter must normalise the same way
 *   or it will show "fits" for a text this refuses.
 *
 * **Order matters.** The two cheap checks on the raw string run first, so a
 * megabyte of text is refused by one `String.toByteArray` call rather than
 * after being normalised and walked boundary by boundary.
 *
 * **Reading a row back goes through [of] too** (`GratitudeMappers.toDomain`).
 * Every check is a function of the raw string alone, so a text that was
 * accepted is accepted again, unchanged. Rows written before P12 hold the
 * NFKC-trimmed form; they pass for the same reason and are returned as they
 * were stored — what NFKC removed from them cannot be put back.
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

        /** U+0000 — the one code point a Postgres `text` column cannot store. */
        private const val NUL = '\u0000'

        /**
         * [raw], kept as it is, if it passes — see the class KDoc for what
         * each limit is measured on.
         *
         * @throws IllegalArgumentException naming whichever limit was crossed
         *   first — NUL, octets, blank, then graphemes, in that order.
         */
        fun of(raw: String): EntryText {
            require(NUL !in raw) { "an entry cannot contain the NUL character" }
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) { "an entry is at most $MAX_OCTETS bytes" }
            // Only to decide. What is returned, and stored, is `raw`.
            val counted = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            require(counted.isNotBlank()) { "an entry must say something" }
            require(graphemes(counted) <= MAX_GRAPHEMES) { "an entry is at most $MAX_GRAPHEMES characters" }
            return EntryText(raw)
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
