package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema

private const val SEARCH_OPERATION = "search"

/** `gratitude.domain.SearchTerms.MAX_OCTETS`, internal to that module and mirrored here as the archive's bounds are. */
private const val SEARCH_MAX_OCTETS = 1024

/**
 * `GET /bonds/{bondId}/search` (ADR-0039). The handler takes `q`, `limit` and `cursor` as
 * optional text, read after the membership guard, so springdoc alone would document three
 * optional strings. This states what the route enforces: `q` required and bounded, and the
 * feed's own `limit` and `cursor`.
 *
 * In a file of its own: `OpenApiConfiguration.kt` sits at detekt's `TooManyFunctions` threshold.
 */
internal fun documentSearch(api: OpenAPI) {
    val operations =
        api.paths.values
            .flatMap { it.readOperations() }
            .filter { it.operationId == SEARCH_OPERATION }
    operations.forEach { it.description = SEARCH_DESCRIPTION }
    operations
        .flatMap { it.parameters.orEmpty() }
        .filter { it.`in` == "query" }
        .forEach { parameter ->
            when (parameter.name) {
                "q" -> {
                    parameter.required = true
                    parameter.schema = StringSchema().minLength(1).maxLength(SEARCH_MAX_OCTETS)
                    parameter.description =
                        "The words to look for: 1 to 100 characters (graphemes, counted on the NFKC-normalised, trimmed " +
                        "text), at most $SEARCH_MAX_OCTETS UTF-8 octets, not only space, no U+0000. `maxLength` states " +
                        "the octet cap in UTF-16 characters, a coarse hint. A value that cannot be searched is 422 " +
                        "VALIDATION_FAILED on `q`, and is never sent back. Words with no letter or digit in them " +
                        "(punctuation, emoji) match nothing."
                }

                "limit" -> {
                    parameter.schema =
                        IntegerSchema()
                            .minimum(DAYS_MIN_LIMIT.toBigDecimal())
                            .maximum(DAYS_MAX_LIMIT.toBigDecimal())
                            ._default(DAYS_DEFAULT_LIMIT)
                    parameter.description = "The most days to return, as on the feed. Only a null `nextCursor` means the end."
                }

                "cursor" -> {
                    parameter.schema = StringSchema().maxLength(DAYS_MAX_CURSOR_LENGTH)
                    parameter.description =
                        "The `nextCursor` of an earlier page of this search, unchanged. The feed's own cursor: a position, nothing else."
                }
            }
        }
}

private const val SEARCH_DESCRIPTION =
    "The days of the bond on which one entry the caller can read in full holds every word of `q`, newest first, each " +
        "day whole and in the feed's day shape. Whole words, compared without case or accents (`cafe` finds `Café`); " +
        "no prefixes, no stemming, no operators. The caller's own entry is found before it is revealed; the partner's " +
        "only once it has been.\n\n" +
        "**Only a null `nextCursor` means the end.** A page can hold fewer days than `limit` and still have a " +
        "`nextCursor`, because it is also bounded by its size. There is no count of matches, and nothing says which " +
        "entry of a day matched or where: a client that highlights does so itself.\n\n" +
        "`q` is never echoed in a refusal, and the response is never to be stored."

private const val ON_THIS_DAY_OPERATION = "onThisDay"

/** `GET /bonds/{bondId}/on-this-day` says what its one field cannot: what is in it and when it is empty. */
internal fun documentOnThisDay(api: OpenAPI) {
    api.paths.values
        .flatMap { it.readOperations() }
        .filter { it.operationId == ON_THIS_DAY_OPERATION }
        .forEach { it.description = ON_THIS_DAY_DESCRIPTION }
}

private const val ON_THIS_DAY_DESCRIPTION =
    "The days of the bond dated today's month and day in an earlier year, newest first, each in the feed's day " +
        "shape. Today is the bond's date. 29 February is found on 29 February only. A day is included when an entry on " +
        "it can be read in full by the caller; a day of tombstones is not.\n\n" +
        "Empty when the bond takes no writes from the caller: it has ended, the caller has left, or it is counting " +
        "down to deletion. There is no cursor: it is every such day, within the size of one response."
