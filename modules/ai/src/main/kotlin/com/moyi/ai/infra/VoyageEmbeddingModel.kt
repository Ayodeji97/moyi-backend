package com.moyi.ai.infra

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.web.client.RestClient

/**
 * Voyage's `/v1/embeddings` as a Spring AI [EmbeddingModel], so the port treats both providers alike.
 * One retry on 429 or 5xx after 500 ms. Errors carry the status, never the input.
 */
internal class VoyageEmbeddingModel(
    private val client: RestClient,
    private val model: String,
) : EmbeddingModel {
    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val body = VoyageRequest(request.instructions, model, "document")
        val reply = send(body) ?: send(body) ?: error("Voyage gave no answer after a retry")
        val data = checkNotNull(reply.data) { "Voyage answered without data" }
        return EmbeddingResponse(data.map { Embedding(it.embedding.toFloatArray(), it.index) })
    }

    override fun embed(document: Document): FloatArray = call(EmbeddingRequest(listOf(document.text.orEmpty()), null)).result.output

    /** null = retryable (429/5xx); throws on any other failure. */
    private fun send(body: VoyageRequest): VoyageResponse? =
        client.post().uri("/v1/embeddings").body(body).exchange { _, res ->
            when {
                res.statusCode.value() == TOO_MANY_REQUESTS || res.statusCode.is5xxServerError -> null.also { Thread.sleep(RETRY_MS) }
                res.statusCode.is2xxSuccessful -> res.bodyTo(VoyageResponse::class.java)
                else -> error("Voyage refused the request: ${res.statusCode.value()}")
            }
        }

    private data class VoyageRequest(
        val input: List<String>,
        val model: String,
        @get:JsonProperty("input_type") val inputType: String,
    )

    private data class VoyageResponse(
        val data: List<Item>?,
    ) {
        data class Item(
            val embedding: List<Float>,
            val index: Int,
        )
    }

    private companion object {
        const val RETRY_MS = 500L
        const val TOO_MANY_REQUESTS = 429
    }
}
