package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingPort
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * A cancelled call stops (review of Task 7). The search cancels its query embed at its timeout
 * by interrupting the thread the call runs on, a virtual thread as in `QueryEmbedder`. The call
 * must then end without sending the words again: a retry after the cancel would resend what
 * someone typed, for nobody, for as long as the client's retries last.
 *
 * Each provider is wired as production wires it ([AiConfiguration]) against a stub that never
 * answers. The test waits for the call's thread to end, which is after any retry it would make,
 * and then counts what the stub received. The client timeout is short, so that a client that
 * ignored the interrupt would still end, and be counted, within the test.
 */
internal class CancelledCallTest {
    @Test
    fun `an interrupted OpenAI call is sent once and not retried`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            val port =
                port(
                    stub,
                    AiProperties(
                        provider = AiProperties.Provider.OPENAI,
                        openai = AiProperties.OpenAi(apiKey = "sk-test", baseUrl = "${stub.baseUrl}/v1"),
                        timeout = CLIENT_TIMEOUT,
                    ),
                )

            interruptedOnceArrived(stub, port)

            stub.requests.size shouldBe 1
        }
    }

    @Test
    fun `an interrupted Voyage call is sent once and not retried`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            val port =
                port(
                    stub,
                    AiProperties(
                        provider = AiProperties.Provider.VOYAGE,
                        voyage = AiProperties.Voyage(apiKey = "pa-test", baseUrl = stub.baseUrl),
                        timeout = CLIENT_TIMEOUT,
                    ),
                )

            interruptedOnceArrived(stub, port)

            stub.requests.size shouldBe 1
        }
    }

    /** Voyage retries a 429 or 5xx after a back-off: an interrupt during the back-off must end the call there. */
    @Test
    fun `a Voyage call interrupted in its back-off after a 503 is not sent again`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            repeat(2) { stub.responses += 503 to "{}" }
            val port =
                AiConfiguration().embeddingPort(
                    AiProperties(
                        provider = AiProperties.Provider.VOYAGE,
                        voyage = AiProperties.Voyage(apiKey = "pa-test", baseUrl = stub.baseUrl),
                        timeout = CLIENT_TIMEOUT,
                    ),
                    SimpleMeterRegistry(),
                    RestClient.builder(),
                )

            interruptedOnceArrived(stub, port)

            stub.requests.size shouldBe 1
        }
    }

    private fun port(
        stub: StubHttpServer,
        properties: AiProperties,
    ): EmbeddingPort {
        stub.stall = true
        return AiConfiguration().embeddingPort(properties, SimpleMeterRegistry(), RestClient.builder())
    }

    /** Starts a call, interrupts its thread once the request has arrived, and waits for the thread to end. */
    private fun interruptedOnceArrived(
        stub: StubHttpServer,
        port: EmbeddingPort,
    ) {
        val call = Thread.ofVirtual().start { runCatching { port.embed(listOf("the quiet lamp")) } }
        stub.awaitRequest(ARRIVAL) shouldBe true

        call.interrupt()

        call.join(ENDED) shouldBe true
    }

    private companion object {
        val CLIENT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val ARRIVAL: Duration = Duration.ofSeconds(5)

        /** Longer than every attempt and back-off the OpenAI client's two retries could take at [CLIENT_TIMEOUT]. */
        val ENDED: Duration = Duration.ofSeconds(30)
    }
}
