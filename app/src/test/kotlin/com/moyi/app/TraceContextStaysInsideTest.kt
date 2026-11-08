package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.tracing.Tracer
import io.opentelemetry.context.propagation.TextMapPropagator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The trace a request is served under stays inside this process (ADR-0024, amended 2026-11-08, review of #147).
 *
 * Tracing exists to give an error body and its log lines one id. It has no exporter, so nothing downstream has
 * any use for our trace context, and Boot's defaults would hand it out: a client's `baggage` and `traceparent`
 * read in, and every `RestClient` built from Boot's builder (Voyage, Resend, FCM) instrumented to inject them
 * into its calls to a third party. So:
 * - only W3C `traceparent`/`tracestate` is read from a request, and baggage not at all;
 * - an outbound call made inside a request's trace carries no trace header of any kind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceContextStaysInsideTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val propagator: TextMapPropagator,
    @Autowired private val tracer: Tracer,
    @Autowired private val restClients: RestClient.Builder,
    @Autowired private val json: JsonMapper,
) : IntegrationTest() {
    private val received = CopyOnWriteArrayList<Map<String, List<String>>>()
    private val stub =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                received += exchange.requestHeaders.mapKeys { it.key.lowercase() }
                exchange.sendResponseHeaders(NO_CONTENT, -1)
                exchange.close()
            }
            start()
        }

    @AfterAll
    fun stop() = stub.stop(0)

    @Test
    fun `a request's trace context is read as W3C trace context and nothing else`() {
        // No `baggage`, no `b3`: what the propagator reads is exactly what it would write.
        propagator.fields() shouldContainExactlyInAnyOrder listOf("traceparent", "tracestate")
    }

    @Test
    fun `a W3C traceparent is continued, and a B3 header is not`() {
        val w3c = "4bf92f3577b34da6a3ce929d0e0e4736"
        traceIdOf(mockMvc.get("/api/v1/me") { header("traceparent", "00-$w3c-00f067aa0ba902b7-01") }) shouldBe w3c

        val b3 = "80f198ee56343ba864fe8b2a57d3eff7"
        traceIdOf(mockMvc.get("/api/v1/me") { header("b3", "$b3-e457b5a2e4d86bd1-1") }) shouldNotBe b3
    }

    @Test
    fun `a call to a third party made inside a request's trace carries no trace header and no baggage`() {
        val client = restClients.clone().baseUrl("http://127.0.0.1:${stub.address.port}").build()
        val span = tracer.nextSpan().name("an inbound request").start()
        tracer.withSpan(span).use {
            tracer.createBaggageInScope("chosen-by-a-client", "forwarded?").use {
                client.get().uri("/embeddings").retrieve().toBodilessEntity()
            }
        }
        span.end()

        received.size shouldBe 1
        received.single().keys.filter { it in TRACE_HEADERS }.shouldBeEmpty()
    }

    private fun traceIdOf(request: org.springframework.test.web.servlet.ResultActionsDsl): String =
        json
            .readTree(request.andReturn().response.contentAsString)
            .path("traceId")
            .asString()

    private companion object {
        const val NO_CONTENT = 204
        val TRACE_HEADERS = setOf("traceparent", "tracestate", "baggage", "b3", "x-b3-traceid", "x-b3-spanid", "x-b3-sampled")
    }
}
