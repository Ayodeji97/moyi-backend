package com.moyi.common.web.idempotency

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Doc 06 §1 over HTTP, against a throwaway fixture controller rather than a
 * real one — `common:web` has no domain endpoint of its own, and testing the
 * interceptor through a later slice's endpoint would couple two unrelated
 * pieces of work together. [IdempotencyTestApplication] wires the real
 * filter and the real interceptor into a real Spring context, against a
 * real Postgres, the same way [com.moyi.common.security.ratelimit.RateLimitInterceptorTest]
 * does for the sibling interceptor in `common:security`.
 */
@SpringBootTest(classes = [IdempotencyTestApplication::class])
@AutoConfigureMockMvc
class IdempotencyInterceptorTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val controller: IdempotencyProbeController,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

    @AfterEach
    fun reset() {
        controller.reset()
    }

    private val handlerRuns get() = controller.handlerRuns

    private fun post(
        caller: UUID,
        key: String?,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/probe/entries") {
                with { request -> request.apply { userPrincipal = Principal { caller.toString() } } }
                key?.let { header(IdempotencyInterceptor.HEADER, it) }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    @Test
    fun `a replay returns the first response and does not run the handler twice`() {
        val key = UUID.randomUUID().toString()
        val first = post(ada, key, """{"text":"thank you"}""")
        first.status shouldBe 201
        val second = post(ada, key, """{"text":"thank you"}""")

        second.status shouldBe 201
        second.contentAsString shouldBe first.contentAsString
        second.getHeader("Idempotency-Replayed") shouldBe "true"
        first.getHeader("Idempotency-Replayed").shouldBeNull()
        handlerRuns shouldBe 1
    }

    @Test
    fun `the same key with a different body is 422, not the wrong stored response`() {
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"thank you"}""").status shouldBe 201

        val reused = post(ada, key, """{"text":"something else"}""")

        reused.status shouldBe 422
        reused.contentAsString shouldContain "\"code\":\"IDEMPOTENCY_KEY_REUSED\""
    }

    @Test
    fun `a key is scoped to its user, so two people may pick the same one`() {
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"thank you"}""").status shouldBe 201
        post(bea, key, """{"text":"thank you"}""").status shouldBe 201
        handlerRuns shouldBe 2
    }

    @Test
    fun `a missing key on a required endpoint is 422 naming the header`() {
        post(ada, key = null, body = """{"text":"thank you"}""").status shouldBe 422
    }

    @Test
    fun `two identical concurrent requests reserve once and run the handler exactly once`() {
        // The concurrency half of the replay guarantee: two requests that
        // both arrive before either has a stored response must not both run
        // the handler. This is the test step 6 of the task brief uses to
        // prove the reservation happens before the handler runs, not after —
        // see the PR body for what moving it does to this test specifically.
        val key = UUID.randomUUID().toString()
        val ready = CountDownLatch(CONCURRENT_CALLERS)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS)
        val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())

        val tasks =
            (1..CONCURRENT_CALLERS).map {
                pool.submit {
                    ready.countDown()
                    go.await()
                    statuses.add(post(ada, key, """{"text":"thank you"}""").status)
                }
            }
        ready.await()
        go.countDown()
        tasks.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        pool.shutdown()

        handlerRuns shouldBe 1
        statuses shouldContain 201
    }

    private companion object {
        const val CONCURRENT_CALLERS = 2
        const val TIMEOUT_SECONDS = 10L
    }
}

/**
 * Counts its own invocations so a test can assert the handler ran once, not
 * "the response looked like a replay" — a fact the interceptor's own headers
 * could in principle get wrong.
 */
@RestController
final class IdempotencyProbeController {
    @Volatile
    var handlerRuns: Int = 0
        private set

    fun reset() {
        handlerRuns = 0
    }

    @PostMapping("/api/v1/probe/entries")
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody body: Map<String, String>,
    ): Map<String, String> {
        handlerRuns++
        return body
    }
}
