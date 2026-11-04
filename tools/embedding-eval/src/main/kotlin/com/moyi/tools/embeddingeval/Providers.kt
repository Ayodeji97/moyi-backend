package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.web.client.RestClient
import java.util.function.Supplier

/**
 * The providers whose key is in the environment, each built by `modules:ai`'s own configuration
 * (`AiConfiguration`, found by scanning its package, since it is `internal` to that module) in a
 * small Spring context of its own, with the key as `moyi.ai.embedding.<provider>.api-key`. So the
 * vectors are made by the adapters production uses, with production's defaults (model, base URL,
 * timeout), and this tool holds no HTTP code of its own. [close] closes every context.
 */
internal class Providers private constructor(
    private val all: List<Provider>,
) : List<Provider> by all,
    AutoCloseable {
    override fun close() = all.forEach { it.close() }

    companion object {
        const val OPENAI_KEY = "MOYI_AI_EMBEDDING_OPENAI_API_KEY"
        const val VOYAGE_KEY = "MOYI_AI_EMBEDDING_VOYAGE_API_KEY"
        private const val AI_PACKAGE = "com.moyi.ai.infra"

        /** In this order: the provider name, as `moyi.ai.embedding.provider` takes it, and its key's variable. */
        private val KEYS = listOf("openai" to OPENAI_KEY, "voyage" to VOYAGE_KEY)

        /** [overrides] are further `moyi.ai.embedding.*` properties, for a test's stub base URL. */
        fun from(
            environment: Map<String, String>,
            overrides: Map<String, String> = emptyMap(),
        ): Providers =
            Providers(
                KEYS.mapNotNull { (name, variable) ->
                    environment[variable]?.takeIf { it.isNotBlank() }?.let { key -> Provider(name, context(name, key, overrides)) }
                },
            )

        private fun context(
            name: String,
            key: String,
            overrides: Map<String, String>,
        ): AnnotationConfigApplicationContext =
            AnnotationConfigApplicationContext().apply {
                val properties = mapOf("moyi.ai.embedding.provider" to name, "moyi.ai.embedding.$name.api-key" to key) + overrides
                environment.propertySources.addFirst(MapPropertySource("embedding-eval", properties))
                registerBean(MeterRegistry::class.java, Supplier<MeterRegistry> { SimpleMeterRegistry() })
                registerBean(RestClient.Builder::class.java, Supplier { RestClient.builder() })
                scan(AI_PACKAGE)
                refresh()
            }
    }
}

/** One provider's context and the port it built. Prints its name alone, never its key. */
internal class Provider(
    val name: String,
    private val context: AnnotationConfigApplicationContext,
) {
    val port: EmbeddingPort = context.getBean(EmbeddingPort::class.java)

    fun close() = context.close()

    override fun toString() = "Provider($name)"
}
