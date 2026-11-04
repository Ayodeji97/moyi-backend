package com.moyi.gratitude.web

import com.moyi.common.web.FieldViolation
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.service.GetDays

/**
 * The two query parameters of `GET /bonds/{bondId}/remember` (spec §3.6), read after the guard,
 * as [SearchQuery] reads search's and for its reasons: a stranger is told the bond's `404` before
 * anything about `q`, and a refusal is this application's `422`, naming each parameter and never
 * its value.
 *
 * `q` is [SearchQuery.termsOf]'s reading, the search box's rules and refusals (ADR-0039 decision 9).
 * `limit` is [DaysQuery.wholeNumberIn]'s, within 1..[GetDays.REMEMBER_MAX_LIMIT], and
 * [GetDays.REMEMBER_DEFAULT_LIMIT] when absent. Every problem is reported at once.
 */
internal data class RememberQuery(
    val terms: SearchTerms,
    val limit: Int,
) {
    companion object {
        /** @throws DaysQueryNotValidException naming each parameter that is missing or cannot be read. */
        fun parse(
            q: String?,
            limit: String?,
        ): RememberQuery {
            val violations = mutableListOf<FieldViolation>()
            val size =
                when (limit) {
                    null -> GetDays.REMEMBER_DEFAULT_LIMIT
                    else -> DaysQuery.wholeNumberIn(limit, 1..GetDays.REMEMBER_MAX_LIMIT) ?: null.also { violations += LIMIT }
                }
            val terms = SearchQuery.termsOf(q, violations)
            if (size == null || terms == null) throw DaysQueryNotValidException(violations)
            return RememberQuery(terms, size)
        }

        // About the parameter, never its value.
        private val LIMIT = FieldViolation("limit", "RANGE", "must be a whole number from 1 to ${GetDays.REMEMBER_MAX_LIMIT}")
    }
}
