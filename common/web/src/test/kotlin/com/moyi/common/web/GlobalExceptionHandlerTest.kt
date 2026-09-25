package com.moyi.common.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * The error contract, tested through a throwaway controller rather than a
 * real one. What is on trial is the *shape* every endpoint will produce, and
 * binding that to one endpoint's request model would make the next endpoint's
 * author assume it only applies there.
 */
class GlobalExceptionHandlerTest {
    data class Body(
        @field:NotBlank
        @field:Size(min = 12, message = "must be at least 12 characters")
        val secret: String,
    )

    @RestController
    class TestController {
        @PostMapping("/things")
        fun create(
            @Valid @RequestBody body: Body,
        ): String = body.secret

        @PostMapping("/boom")
        fun boom(): Nothing = error("connection string: postgres://user:hunter2@db/moyi")

        @PostMapping("/teapot")
        fun teapot(): Nothing = throw Teapot()
    }

    private class Teapot :
        ApiException(
            status = HttpStatus.I_AM_A_TEAPOT,
            errorCode = ErrorCode.INTERNAL_ERROR,
            detail = "Short and stout.",
        )

    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(TestController())
            .setControllerAdvice(GlobalExceptionHandler(ProblemDetails(ProblemProperties())))
            .setMessageConverters(
                JacksonJsonHttpMessageConverter(
                    JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                ),
            ).setValidator(LocalValidatorFactoryBean().apply { afterPropertiesSet() })
            .build()

    @Test
    fun `a field that fails validation is 422 with a per-field error`() {
        // 422, not the conventional 400: doc 06 §2 reserves 400 for a body
        // that could not be read. A client can act on the difference — 400
        // means the client is wrong, 422 means the user is.
        mockMvc
            .post("/things") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"secret":"short"}"""
            }.andReturn()
            .response
            .let { response ->
                response.status shouldBe 422
                response.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
                response.contentAsString shouldContain "\"field\":\"secret\""
                response.contentAsString shouldContain "\"code\":\"SIZE\""
                response.contentAsString shouldContain "must be at least 12 characters"
            }
    }

    @Test
    fun `a rejected value is never echoed back`() {
        // The failure this prevents: Bean Validation carries the rejected
        // value on FieldError, and the obvious implementation includes it.
        // On a password field that puts the plaintext in the response body,
        // the browser's network tab, and every proxy log between the two.
        val response =
            mockMvc
                .post("/things") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"secret":"hunter2"}"""
                }.andReturn()
                .response

        response.contentAsString shouldNotContain "hunter2"
    }

    @Test
    fun `an unreadable body is 400, and says nothing about why`() {
        val response =
            mockMvc
                .post("/things") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"secret":"""
                }.andReturn()
                .response

        response.status shouldBe 400
        response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
        // Jackson's own message names the parser class and the offset.
        response.contentAsString shouldNotContain "JsonParseException"
    }

    @Test
    fun `an unexpected exception is 500 and leaks nothing`() {
        val response =
            mockMvc
                .post("/boom") { contentType = MediaType.APPLICATION_JSON }
                .andReturn()
                .response

        response.status shouldBe 500
        response.contentAsString shouldContain "\"code\":\"INTERNAL_ERROR\""
        // The thrown message was a credential-shaped string on purpose.
        response.contentAsString shouldNotContain "hunter2"
        response.contentAsString shouldNotContain "postgres://"
    }

    @Test
    fun `an ApiException keeps its own status and code`() {
        val response =
            mockMvc
                .post("/teapot") { contentType = MediaType.APPLICATION_JSON }
                .andReturn()
                .response

        response.status shouldBe 418
        response.contentAsString shouldContain "Short and stout."
    }

    @Test
    fun `an error Spring raised on its own still carries a code`() {
        // The framework's own problem details arrive correctly shaped and,
        // before this, without `code` — the one field doc 06 §2 says a client
        // switches on, and which it generates into an exhaustive sealed
        // class. Found by asking a running application for a 405, not by
        // reading the handler.
        val response =
            mockMvc
                .get("/things")
                .andReturn()
                .response

        response.status shouldBe 405
        response.contentAsString shouldContain "\"code\":\"METHOD_NOT_ALLOWED\""
    }

    @Test
    fun `an error Spring raised on its own carries the problem type and our title`() {
        // ADR-0024's contract lists `type` as required on every problem body,
        // and Spring's own ProblemDetail arrives without one — with its own
        // title-cased title, too. Found by asking the running jar for a 404
        // during the Phase 1 smoke test (2026-09-24), not by reading the
        // handler, which stamped `code` and looked complete.
        val response =
            mockMvc
                .get("/things")
                .andReturn()
                .response

        response.status shouldBe 405
        response.contentAsString shouldContain "https://api.moyi.app/problems/method-not-allowed"
        response.contentAsString shouldContain "\"title\":\"Method not allowed\""
        response.contentAsString shouldContain "\"instance\":\"/things\""
        // Spring's headers on its own errors survive the rebuild: `Allow` is
        // the one a client can act on.
        response.getHeader("Allow") shouldContain "POST"
    }

    @Test
    fun `an unknown route is 404 and says nothing about static resources`() {
        // The running application answers `/api/v1/me/` with Spring's
        // "No static resource api/v1/me." — an implementation detail with
        // the word "static" in it, on an API that serves none.
        val response =
            mockMvc
                .get("/nothing-here")
                .andReturn()
                .response

        response.status shouldBe 404
        response.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        response.contentAsString shouldContain "https://api.moyi.app/problems/not-found"
        response.contentAsString shouldContain "\"detail\":\"No such resource.\""
        response.contentAsString shouldNotContain "static resource"
        response.contentAsString shouldNotContain "endpoint"
    }

    @Test
    fun `our own handlers keep their code, which the new stamping must not reach`() {
        // Honest about what this covers: our handlers build their response
        // directly and never pass through `handleExceptionInternal`, so the
        // stamping cannot touch them today. The `containsKey` guard there is
        // defensive and currently unreachable — this test is the regression
        // that notices if a future change routes our handlers through it,
        // because 418 falls in the 4xx fallback range and would be relabelled
        // MALFORMED_REQUEST.
        val response =
            mockMvc
                .post("/teapot") { contentType = MediaType.APPLICATION_JSON }
                .andReturn()
                .response

        response.contentAsString shouldContain "\"code\":\"INTERNAL_ERROR\""
        response.contentAsString shouldNotContain "MALFORMED_REQUEST"
    }

    @Test
    fun `the problem type resolves to a documentation URI and the title reads as prose`() {
        val response =
            mockMvc
                .post("/things") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"secret":"short"}"""
                }.andReturn()
                .response

        response.contentAsString shouldContain "https://api.moyi.app/problems/validation-failed"
        response.contentAsString shouldContain "\"title\":\"Validation failed\""
        response.contentAsString shouldContain "\"instance\":\"/things\""
    }
}
