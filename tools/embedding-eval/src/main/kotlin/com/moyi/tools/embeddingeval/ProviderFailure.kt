package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingUnavailableException

/**
 * What a provider's failure may print: the provider's name, the class of each exception in the
 * chain, and an HTTP status where one was read without reading a message body. **Never a
 * message or a stack trace**: a provider's error body can quote its input, and the OpenAI SDK's
 * messages carry that body.
 *
 * A failure through the port is an [EmbeddingUnavailableException], which carries no cause (its
 * KDoc): the provider's classes and status are its own [EmbeddingUnavailableException.failure]
 * and [EmbeddingUnavailableException.status], read by `modules/ai`'s adapter. Anything else is
 * told by its own chain's classes.
 */
internal object ProviderFailure {
    fun describe(
        provider: String,
        failure: Throwable,
    ): String {
        val classes = generateSequence(failure) { it.cause }.map { it.javaClass.simpleName }.toList()
        val unavailable = failure as? EmbeddingUnavailableException
        val chain = classes + listOfNotNull(unavailable?.failure)
        return "$provider failed: ${chain.joinToString(" < ")}" + (unavailable?.status?.let { ", HTTP $it" } ?: "")
    }
}
