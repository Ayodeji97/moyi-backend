package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema

internal const val REMEMBER_OPERATION = "remember"
private const val REMEMBER_RESPONSE = "RememberResponse"

/** Mirrors `gratitude.service.GetDays.REMEMBER_MAX_LIMIT`, internal to that module, as the search limits are mirrored. */
private const val REMEMBER_MAX_LIMIT = 20

/** Mirrors `gratitude.service.GetDays.REMEMBER_DEFAULT_LIMIT`; see [REMEMBER_MAX_LIMIT]. */
private const val REMEMBER_DEFAULT_LIMIT = 10

/**
 * `GET /bonds/{bondId}/remember` (ADR-0054, spec §3.6). The handler takes `q` and `limit` as
 * optional text, read after the membership guard, as search does, so springdoc alone would
 * document two optional strings. This states what the route enforces: `q` required and read by
 * search's own rules, `limit` a whole number in its range. And it says what the one field cannot:
 * the days come in order of relevance, there is no cursor, and fewer than `limit` is usual.
 */
internal fun documentRemember(api: OpenAPI) {
    val operations =
        api.paths.values
            .flatMap { it.readOperations() }
            .filter { it.operationId == REMEMBER_OPERATION }
    operations.forEach { it.description = REMEMBER_DESCRIPTION }
    operations
        .flatMap { it.parameters.orEmpty() }
        .filter { it.`in` == "query" }
        .forEach { parameter ->
            when (parameter.name) {
                "q" -> {
                    parameter.required = true
                    parameter.schema = StringSchema().minLength(1).maxLength(SEARCH_MAX_OCTETS)
                    parameter.description = "The question, in plain words, read as the search box reads `q`: $SEARCH_TERMS_RULES"
                }

                "limit" -> {
                    parameter.schema =
                        IntegerSchema()
                            .minimum(1.toBigDecimal())
                            .maximum(REMEMBER_MAX_LIMIT.toBigDecimal())
                            ._default(REMEMBER_DEFAULT_LIMIT)
                    parameter.description =
                        "The most days to return, 1 to $REMEMBER_MAX_LIMIT. Anything else is 422 VALIDATION_FAILED on `limit`."
                }
            }
        }
    api.components.schemas[REMEMBER_RESPONSE]?.let { response ->
        response.properties.keys
            .filter { it !in response.required.orEmpty() }
            .forEach(response::addRequiredItem)
    }
}

private const val REMEMBER_DESCRIPTION =
    "\"Remember when\": the days of the bond most relevant to a question, most relevant first, each day whole and in " +
        "the feed's day shape. Two rankings of the entries the caller can read in full are fused: by the question's " +
        "words, and by its meaning, when the bond's semantic search is on and the meaning can be read in time. When it " +
        "cannot (the setting is off, no provider is configured, the provider fails or is slow), the answer is the " +
        "words' ranking alone, still `200`: the client renders one state. Only entries a person wrote are returned, " +
        "never generated text, and nothing says which entry of a day was relevant or why.\n\n" +
        "There is no cursor: it is one answer, not a feed. It may hold fewer days than `limit`, or none, because a day " +
        "is shown only when the caller can read the relevant entry in full, and the answer is bounded by its size. " +
        "The caller's own entry is found before it is revealed; the partner's only once it has been.\n\n" +
        "`q` is never echoed in a refusal, and the response is never to be stored. Thirty questions an hour per account: " +
        "each may be sent to the embedding provider."
