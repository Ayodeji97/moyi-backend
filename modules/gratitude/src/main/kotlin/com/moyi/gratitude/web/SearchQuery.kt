package com.moyi.gratitude.web

import com.moyi.common.web.FieldViolation
import com.moyi.gratitude.domain.SearchTerms
import java.time.LocalDate

/**
 * The three query parameters of `GET /bonds/{bondId}/search`, read after the guard, as
 * [DaysQuery] reads the feed's and for its reasons: a stranger is told the bond's `404` before
 * anything about `q`, and a refusal is this application's `422`, naming each parameter and
 * never its value.
 *
 * `limit` and `cursor` are [DaysQuery]'s own reading (one reading of each, wherever they are
 * taken). `q` is [SearchTerms.of]'s. Every problem is reported at once.
 */
internal data class SearchQuery(
    val terms: SearchTerms,
    val limit: Int,
    /** From the cursor: days strictly before this date. */
    val before: LocalDate?,
) {
    companion object {
        /** @throws DaysQueryNotValidException naming each parameter that is missing or cannot be read. */
        fun parse(
            q: String?,
            limit: String?,
            cursor: String?,
        ): SearchQuery {
            val violations = mutableListOf<FieldViolation>()
            val paging =
                try {
                    DaysQuery.parse(limit = limit, cursor = cursor, until = null, favourites = null)
                } catch (refused: DaysQueryNotValidException) {
                    violations += refused.errors.orEmpty()
                    null
                }
            val terms =
                when (q) {
                    null -> null.also { violations += Q_MISSING }
                    else -> SearchTerms.of(q) ?: null.also { violations += Q_NOT_SEARCHABLE }
                }
            if (paging == null || terms == null) throw DaysQueryNotValidException(violations)
            return SearchQuery(terms, paging.limit, paging.before)
        }

        // About the parameter, never its value: what somebody searched for is their words.
        private val Q_MISSING = FieldViolation("q", "REQUIRED", "is required: the words to look for")
        private val Q_NOT_SEARCHABLE =
            FieldViolation(
                "q",
                "VALID_SEARCH",
                "must be 1 to ${SearchTerms.MAX_GRAPHEMES} characters, not only space, " +
                    "at most ${SearchTerms.MAX_OCTETS} bytes, with no NUL",
            )
    }
}
