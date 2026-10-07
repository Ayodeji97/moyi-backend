package com.moyi.gratitude.web

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.FieldViolation
import com.moyi.gratitude.service.GetDays
import org.springframework.http.HttpStatus
import java.time.LocalDate

/**
 * The four query parameters of `GET /bonds/{bondId}/days`, read.
 *
 * **Taken as text and read here, not bound by Spring**, for two reasons.
 * Bound to an `Int` or a `LocalDate`, a value that does not convert is
 * refused before the controller's method runs at all: before the membership
 * guard, so a stranger would be told about their `limit` on a bond they may
 * not know exists; and by Spring's own handler, as a `400` whose detail
 * quotes the value that was sent. Read here, after the guard, the refusal is
 * this application's `422 VALIDATION_FAILED` with an `errors` entry naming
 * the parameter and saying nothing of its value.
 *
 * **Every parameter that cannot be read is reported, not the first.** A
 * client that got two wrong learns both in one answer, as it does from a
 * request body.
 *
 * The bounds of [limit] are [GetDays]'s constants, not restated.
 */
internal data class DaysQuery(
    val limit: Int,
    /** From the cursor: days strictly before this date. */
    val before: LocalDate?,
    /** Days on or before this date. */
    val until: LocalDate?,
    val favouritesOnly: Boolean,
) {
    companion object {
        private val WHOLE_NUMBER = Regex("[0-9]{1,9}")

        /** @throws DaysQueryNotValidException naming each parameter that is present and cannot be read. */
        fun parse(
            limit: String?,
            cursor: String?,
            until: String?,
            favourites: String?,
        ): DaysQuery {
            val violations = mutableListOf<FieldViolation>()

            fun <T : Any> read(
                raw: String?,
                violation: FieldViolation,
                parse: (String) -> T?,
            ): T? = raw?.let { parse(it) ?: null.also { violations += violation } }

            val pageSize = read(limit, LIMIT) { it.takeIf(WHOLE_NUMBER::matches)?.toInt()?.takeIf { n -> n in 1..GetDays.MAX_LIMIT } }
            val before = read(cursor, CURSOR) { DayCursor.parse(it)?.before }
            val onOrBefore = read(until, UNTIL, ::strictIsoDate)
            val favouritesOnly = read(favourites, FAVOURITES) { it.toBooleanStrictOrNull() }
            if (violations.isNotEmpty()) throw DaysQueryNotValidException(violations)
            return DaysQuery(pageSize ?: GetDays.DEFAULT_LIMIT, before, onOrBefore, favouritesOnly ?: false)
        }

        // What each says is about the parameter, never the value: a refusal must not carry back what was sent.
        private val LIMIT = FieldViolation("limit", "RANGE", "must be a whole number from 1 to ${GetDays.MAX_LIMIT}")
        private val CURSOR = FieldViolation("cursor", "VALID_CURSOR", "must be a nextCursor from an earlier page, unchanged")
        private val UNTIL = FieldViolation("until", "VALID_DATE", "must be a calendar date written YYYY-MM-DD")
        private val FAVOURITES = FieldViolation("favourites", "VALID_BOOLEAN", "must be true or false")
    }
}

/**
 * 422: a query parameter of the archive feed is present and cannot be read.
 * `VALIDATION_FAILED`, with the `errors` that code is always accompanied by:
 * each names a parameter, never its value.
 */
internal class DaysQueryNotValidException(
    violations: List<FieldViolation>,
) : ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ErrorCode.VALIDATION_FAILED,
        "One or more query parameters are not valid.",
        errors = violations,
    )
