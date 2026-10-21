package com.moyi.gratitude.domain

import java.text.Normalizer

/**
 * What a member searched for (`q`, ADR-0039 decision 9), checked once at the edge.
 *
 * **Kept as sent.** Postgres folds case and accents on both sides of the match with one
 * function (`public.immutable_unaccent`, V25); nothing here normalises what is searched for,
 * as nothing normalises what was written ([EntryText]). NFKC and `trim` are used only to
 * **decide**, on a copy, exactly as an entry's limits are decided.
 *
 * - **No U+0000**: Postgres `text` cannot hold it, and the statement would fail as a `500`.
 * - **At most [MAX_OCTETS] UTF-8 octets**, on the raw text: a character has no upper bound on
 *   its length, and `q` travels in a URL.
 * - **Not blank** and **at most [MAX_GRAPHEMES] characters**, on the NFKC form trimmed, by
 *   [Graphemes.count]: FR-041's convention for an entry, so a client counts both one way.
 *
 * **Redacted.** What somebody looks for in what two people wrote is their words (T-19); it
 * reaches no log, no exception message and no `toString`.
 */
@JvmInline
internal value class SearchTerms private constructor(
    val value: String,
) {
    override fun toString(): String = "SearchTerms(redacted)"

    companion object {
        const val MAX_GRAPHEMES = 100
        const val MAX_OCTETS = 1024
        private const val NUL = '\u0000'

        /** [raw] as a search, or `null` when it cannot be one. Which limit it crossed is not said: the answer is one `422`. */
        fun of(raw: String): SearchTerms? {
            if (NUL in raw || raw.toByteArray(Charsets.UTF_8).size > MAX_OCTETS) return null
            val counted = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            if (counted.isBlank() || Graphemes.count(counted) > MAX_GRAPHEMES) return null
            return SearchTerms(raw)
        }
    }
}
