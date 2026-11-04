package com.moyi.ai.api

/**
 * The model a vector was made by, and so the space it lives in (ADR-0054). Vectors from two
 * spaces are never compared: a stored vector is used only while its space is the port's.
 */
@Suppress("MagicNumber") // Each model's published output size: the table is the constant.
enum class EmbeddingSpace(
    val dimensions: Int,
) {
    OPENAI_TEXT_EMBEDDING_3_SMALL(1536),
    VOYAGE_3_5(1024),
}

/**
 * Text in, vectors out. The one way any module reaches an embedding provider; which provider,
 * if any, is configuration (`moyi.ai.embedding.provider`).
 */
interface EmbeddingPort {
    /** The space vectors are made in now, or null when no provider is configured. */
    val space: EmbeddingSpace?

    /** One vector per text, in order. @throws EmbeddingUnavailableException on no provider, timeout or provider error. */
    fun embed(texts: List<String>): List<FloatArray>
}

/**
 * No vector could be made. The message is a fixed reason ("timeout", "provider error",
 * "no provider"), **never the text**: a provider's own message may quote its input, which is
 * entry or query text.
 *
 * **It never carries the provider's exception as its `cause`** (final review M4): a later
 * `log.warn(..., e)` would print that exception's message, and the SDK's quote the request.
 * What may be told of it is kept instead: [failure], the simple class names of its chain,
 * outermost first and joined by `" < "`, and [status], an HTTP status where one could be read
 * without reading a message body. Neither holds a word of the input.
 */
class EmbeddingUnavailableException(
    reason: String,
    val failure: String? = null,
    val status: Int? = null,
) : RuntimeException(reason)
