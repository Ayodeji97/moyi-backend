package com.moyi.tools.embeddingeval

/**
 * What a provider's failure may print: the provider's name, the class of each exception in the
 * chain, and an HTTP status where one can be read without reading a message body. **Never a
 * message or a stack trace**: a provider's error body can quote its input, and the OpenAI SDK's
 * messages carry that body.
 *
 * The status comes from a `statusCode()` method (the OpenAI SDK's service exceptions), or from
 * `VoyageEmbeddingModel`'s own "refused the request: <status>" message, matched whole.
 */
internal object ProviderFailure {
    private val VOYAGE_REFUSAL = Regex("""Voyage refused the request: (\d{3})""")

    fun describe(
        provider: String,
        failure: Throwable,
    ): String {
        val chain = generateSequence(failure) { it.cause }.toList()
        val status = chain.firstNotNullOfOrNull(::statusOf)
        return "$provider failed: ${chain.joinToString(" < ") { it.javaClass.simpleName }}" + (status?.let { ", HTTP $it" } ?: "")
    }

    private fun statusOf(failure: Throwable): Int? =
        failure.javaClass.methods
            .firstOrNull { it.name == "statusCode" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(failure) as? Int }.getOrNull() }
            ?: failure.message
                ?.let { VOYAGE_REFUSAL.matchEntire(it) }
                ?.groupValues
                ?.get(1)
                ?.toInt()
}
