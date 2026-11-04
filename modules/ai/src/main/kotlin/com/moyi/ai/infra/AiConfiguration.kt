package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * Chooses the [EmbeddingPort] from configuration: one `@Bean` with an exhaustive `when`, as
 * `PushSenderConfiguration` does and for its reason (ADR-0017 decision 3). No Spring AI starter
 * and so no autoconfiguration: the models are built here, where their keys and timeouts are seen.
 */
@Configuration
@EnableConfigurationProperties(AiProperties::class)
internal class AiConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun embeddingPort(
        properties: AiProperties,
        meters: MeterRegistry,
        rest: RestClient.Builder,
    ): EmbeddingPort =
        when (properties.provider) {
            AiProperties.Provider.OPENAI -> {
                val key =
                    checkNotNull(properties.openai.apiKey?.takeIf { it.isNotBlank() }) {
                        "moyi.ai.embedding.openai.api-key is required while the provider is OPENAI (MOYI_AI_EMBEDDING_OPENAI_API_KEY)"
                    }
                val options =
                    OpenAiEmbeddingOptions
                        .builder()
                        .apiKey(key)
                        .baseUrl(properties.openai.baseUrl)
                        .model(properties.openai.model)
                        .timeout(properties.timeout)
                        .maxRetries(OPENAI_MAX_RETRIES)
                        .build()
                // The builder, not the constructor taking options, which 2.0 deprecates; with no
                // client given it builds the OpenAI SDK client from the options, as that did.
                val model = OpenAiEmbeddingModel.builder().options(options).build()
                SpringAiEmbeddingAdapter(model, EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL, meters, "openai")
            }

            AiProperties.Provider.VOYAGE -> {
                val key =
                    checkNotNull(properties.voyage.apiKey?.takeIf { it.isNotBlank() }) {
                        "moyi.ai.embedding.voyage.api-key is required while the provider is VOYAGE (MOYI_AI_EMBEDDING_VOYAGE_API_KEY)"
                    }
                val client =
                    rest
                        .clone()
                        .baseUrl(properties.voyage.baseUrl)
                        .requestFactory(
                            SimpleClientHttpRequestFactory().apply {
                                setConnectTimeout(properties.timeout)
                                setReadTimeout(properties.timeout)
                            },
                        ).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $key")
                        .build()
                SpringAiEmbeddingAdapter(VoyageEmbeddingModel(client, properties.voyage.model), EmbeddingSpace.VOYAGE_3_5, meters, "voyage")
            }

            AiProperties.Provider.NONE -> {
                NoEmbeddings
            }
        }.also { log.info("Embedding provider: {}", properties.provider) }

    private companion object {
        const val OPENAI_MAX_RETRIES = 2
    }
}
