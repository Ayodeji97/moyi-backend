package com.moyi.ai.infra

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Which embedding provider is wired, and how to reach it. The posture is push's
 * (`PushProperties`, ADR-0044): **the default is the real provider, and it refuses to start
 * without its key.** `NONE` has to be asked for by name; the `local` profile, the test
 * contexts and, until a key exists, `config/deploy.yml` ask (ADR-0054).
 *
 * Every value arrives from the environment, as `MOYI_AI_EMBEDDING_…`.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.ai.embedding")
internal data class AiProperties(
    val provider: Provider = Provider.OPENAI,
    @field:Valid
    val openai: OpenAi = OpenAi(),
    @field:Valid
    val voyage: Voyage = Voyage(),
    /** Per request, connect and read alike: the sweep and the search box both wait on it. */
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }

    internal enum class Provider {
        /** OpenAI's `text-embedding-3-small`, through Spring AI. Requires [OpenAi.apiKey]. */
        OPENAI,

        /** Voyage's `voyage-3.5`, through our own Spring AI model. Requires [Voyage.apiKey]. */
        VOYAGE,

        /** NONE: no vector is made; search and remember answer from keywords. Never the default. */
        NONE,
    }

    /**
     * The base URL **includes `/v1`**: the OpenAI SDK under Spring AI uses a configured value
     * verbatim and appends only `/embeddings` (it adds `/v1` itself only when the value is blank).
     * Without it every request is a 404, which the port reports as "provider error" and search
     * silently falls back to keywords; `OpenAiWiringTest` pins the default. [Voyage]'s base URL is
     * the bare host, because [VoyageEmbeddingModel] adds the whole path.
     */
    internal data class OpenAi(
        val apiKey: String? = null,
        val model: String = "text-embedding-3-small",
        @field:NotBlank
        val baseUrl: String = "https://api.openai.com/v1",
    ) {
        /** The generated one would print the key. */
        override fun toString() = "OpenAi(model=$model, baseUrl=$baseUrl, apiKey=${apiKey?.let { "<redacted>" }})"
    }

    internal data class Voyage(
        val apiKey: String? = null,
        val model: String = "voyage-3.5",
        @field:NotBlank
        val baseUrl: String = "https://api.voyageai.com",
    ) {
        /** The generated one would print the key. */
        override fun toString() = "Voyage(model=$model, baseUrl=$baseUrl, apiKey=${apiKey?.let { "<redacted>" }})"
    }
}
