package com.moyi.common.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.common.web.idempotency.IdempotencyTestApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A query parameter whose bytes are not UTF-8 is a malformed request: `400 MALFORMED_REQUEST`, and a log line
 * that does not quote it (slice C6b, task 2 review I1).
 *
 * Tomcat decodes the query string the first time a parameter is asked for, which is while the handler's
 * arguments are resolved. On bytes it cannot decode it throws `InvalidParameterException`, whose message quotes
 * the parameter's value as far as it could read it, and the catch-all logged that message at ERROR with a
 * `500`. For a search the value is somebody's words.
 *
 * **Over a real socket**, not MockMvc: the decoding that fails is Tomcat's, and MockMvc has no Tomcat. Every
 * parameter of the query string is decoded together, so an unknown parameter fails the request as a known one
 * does.
 */
@SpringBootTest(classes = [IdempotencyTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
@Import(UndecodableParameterTest.ProbeConfiguration::class)
class UndecodableParameterTest(
    @LocalServerPort private val port: Int,
    @Autowired private val logging: LoggingSystem,
) : PostgresIntegrationTest() {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `a parameter the handler reads, not UTF-8, is 400 MALFORMED_REQUEST and its value is in no log line`(output: CapturedOutput) {
        assertMalformed(get("/api/v1/probe/params?cursor=$WATCHED%20x%FF"), output)
    }

    @Test
    fun `a parameter the handler does not know, not UTF-8, is the same 400`(output: CapturedOutput) {
        assertMalformed(get("/api/v1/probe/params?cursor=fine&unknown=$WATCHED%20x%FF"), output)
    }

    @Test
    fun `a half of a surrogate pair is not UTF-8 either`(output: CapturedOutput) {
        assertMalformed(get("/api/v1/probe/params?cursor=$WATCHED%ED%A0%80"), output)
    }

    /**
     * The two loggers that read or print a request's parameters before any handler, set **by their exact names**,
     * which outranks a pin in `application.yml`. Below INFO the dispatcher servlet reads every parameter for its
     * request line before a handler is chosen, so without [UndecodableParameterFilter] Tomcat's exception escapes
     * the exception handler and Tomcat logs it at ERROR, the value quoted, under Boot's own error body.
     */
    @Test
    fun `with the dispatcher servlet at TRACE and the filter chain at DEBUG, set by name, it is the same 400`(output: CapturedOutput) {
        try {
            logging.setLogLevel(DISPATCHER_SERVLET, LogLevel.TRACE)
            logging.setLogLevel(FILTER_CHAIN_PROXY, LogLevel.DEBUG)
            assertMalformed(get("/api/v1/probe/params?cursor=$WATCHED%20x%FF"), output)
        } finally {
            logging.setLogLevel(DISPATCHER_SERVLET, null)
            logging.setLogLevel(FILTER_CHAIN_PROXY, null)
        }
    }

    /**
     * The filter makes Tomcat parse the parameters early, and for a form POST that reads the body, which Tomcat
     * does for no other kind. A JSON body is untouched. A form body without a query string is rebuilt by Spring
     * from the parameters (`ServletServerHttpRequest.getBody`); **with a query string it is not, and a handler
     * reading the raw body is given none**, a 400. No route of this API takes a form: each reads a JSON object
     * into a class, and a form is a 415 for it whether or not the body was read first.
     */
    @Test
    fun `a JSON body is untouched, and a form is the 415 it was, with or without a query string`() {
        post("/api/v1/probe/body?cursor=x", "application/json", """{"text":"thank you"}""").body() shouldBe """{"text":"thank you"}"""
        post("/api/v1/probe/body", "application/x-www-form-urlencoded", "text=thank+you").body() shouldBe "text=thank+you"
        // The one change, held so that it is known: before the filter this was the body, echoed.
        post("/api/v1/probe/body?cursor=x", "application/x-www-form-urlencoded", "text=thank+you").statusCode() shouldBe 400
        for (path in listOf("/api/v1/probe/object", "/api/v1/probe/object?cursor=x")) {
            val form = post(path, "application/x-www-form-urlencoded", "text=thank+you")
            form.statusCode() shouldBe 415
            form.body() shouldContain "\"code\":\"UNSUPPORTED_MEDIA_TYPE\""
        }
        post("/api/v1/probe/object?cursor=x", "application/json", """{"text":"thank you"}""").body() shouldBe "thank you"
    }

    @Test
    fun `the same parameter, decodable, reaches the handler`() {
        val answer = get("/api/v1/probe/params?cursor=$WATCHED%20x%C3%A9")
        answer.statusCode() shouldBe 200
        answer.body() shouldBe "$WATCHED xé"
    }

    private fun assertMalformed(
        answer: HttpResponse<String>,
        output: CapturedOutput,
    ) {
        answer.statusCode() shouldBe 400
        answer.body() shouldContain "\"code\":\"MALFORMED_REQUEST\""
        answer.body() shouldNotContain WATCHED
        output.all shouldContain "MALFORMED_REQUEST -> 400 a request parameter could not be decoded"
        output.all shouldNotContain WATCHED
        output.all shouldNotContain "Unhandled exception"
    }

    private fun post(
        pathAndQuery: String,
        contentType: String,
        body: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$pathAndQuery"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(pathAndQuery: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$pathAndQuery")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @RestController
    class ProbeController {
        @GetMapping("/api/v1/probe/params")
        fun params(
            @RequestParam cursor: String,
        ): String = cursor

        @PostMapping("/api/v1/probe/body")
        fun body(
            @RequestBody body: String,
        ): String = body

        @PostMapping("/api/v1/probe/object")
        fun text(
            @RequestBody body: Text,
        ): String = body.text
    }

    data class Text(
        val text: String,
    )

    @TestConfiguration
    class ProbeConfiguration {
        @Bean
        fun parameterProbeController(): ProbeController = ProbeController()
    }

    private companion object {
        /** In no request but these, and in no SQL, class name or log line the application writes. */
        const val WATCHED = "zqundecodablewords"
        const val DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet"
        const val FILTER_CHAIN_PROXY = "org.springframework.security.web.FilterChainProxy"
    }
}
