package com.moyi.common.web.idempotency

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.security.Principal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
@Import(IdempotencyInterceptorTest.TimeConfiguration::class)
class IdempotencyInterceptorTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val controller: IdempotencyProbeController,
    @Autowired private val clock: MutableClock,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

    @AfterEach
    fun reset() {
        controller.reset()
    }

    private val handlerRuns get() = controller.handlerRuns.get()

    private fun post(
        caller: UUID,
        key: String?,
        body: String,
        path: String = ENTRIES_PATH,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
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
    fun `a replay carries back ETag and Location, not just the body`() {
        val key = UUID.randomUUID().toString()
        val first = post(ada, key, """{"text":"thank you"}""")
        first.status shouldBe 201
        first.getHeader("ETag") shouldBe CREATED_ETAG
        first.getHeader("Location") shouldBe CREATED_LOCATION

        val second = post(ada, key, """{"text":"thank you"}""")

        second.getHeader("ETag") shouldBe CREATED_ETAG
        second.getHeader("Location") shouldBe CREATED_LOCATION
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
    fun `the same key against a different endpoint is 422, not a fresh reservation`() {
        // Ruling A, review round 1: doc 06 §1 keys a reservation on `userId +
        // endpoint + key`, so a different endpoint under the same key is a
        // reuse of the key, not an unrelated row — V11's unique constraint no
        // longer enforces this itself (it can't, without making "different
        // endpoint" impossible to detect at all), so this is the comparison
        // that has to catch it instead.
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"thank you"}""").status shouldBe 201

        val reused = post(ada, key, """{"text":"thank you"}""", path = OTHER_ENTRIES_PATH)

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
    fun `an expired reservation does not replay`() {
        // Ruling B, review round 1: the 24h window is enforced at read. No
        // reaper exists yet (slice C3), so this also proves the row Ruling B
        // leaves behind does not turn a legitimate reuse of the same key,
        // a day later, into a 500 under the unique constraint.
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"thank you"}""").status shouldBe 201

        clock.advance(Duration.ofHours(24).plusSeconds(1))
        val second = post(ada, key, """{"text":"thank you"}""")

        second.status shouldBe 201
        second.getHeader("Idempotency-Replayed").shouldBeNull()
        handlerRuns shouldBe 2
    }

    @Test
    fun `a concurrent request while the first is still in flight is 409, and only the first runs the handler`() {
        // Hardened after review round 1, Critical #2. Two things the earlier
        // version of this test did not actually prove:
        // - `handlerRuns` is an AtomicInteger now, not a `@Volatile Int`
        //   incremented with `++`, which is a read-modify-write and could
        //   read 1 even if both requests had run the handler and interleaved.
        // - the first request is held *inside* the handler, released only
        //   after the second has already been answered, so the second is
        //   deterministically forced into the in-flight branch rather than
        //   racing a schedule where it happens to see a completed row
        //   instead — a schedule under which this test used to pass even
        //   with the reservation moved after the handler (see the PR body).
        //
        // Review round 2, N2: the second call now runs on the pool and is
        // bounded by its own `.get(TIMEOUT_SECONDS, ...)`, not called
        // synchronously with no timeout of its own. Under round 1's version
        // of this test, a genuine future regression of the reserve-before-
        // handler ordering would have hung this call — and the whole test
        // run — indefinitely, since only `firstCall` had a bound. Now both
        // sides fail within TIMEOUT_SECONDS instead.
        val key = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        controller.enteredGate = entered
        controller.releaseGate = release

        val pool = Executors.newFixedThreadPool(2)
        val firstCall = pool.submit<Int> { post(ada, key, """{"text":"thank you"}""").status }
        entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

        // The first request has reserved the key and is now blocked inside
        // the handler (response_status still null): the second is expected
        // to be answered — 409, without ever entering the handler — well
        // before the first is let go.
        val secondCall = pool.submit<Int> { post(ada, key, """{"text":"thank you"}""").status }
        val secondStatus = secondCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        release.countDown()
        val statuses = listOf(firstCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), secondStatus)
        pool.shutdown()

        statuses shouldBe listOf(201, 409)
        handlerRuns shouldBe 1
    }

    @Test
    fun `a missing principal is 401 UNAUTHENTICATED, not a 500`() {
        // Important #5, review round 1, tested directly per review round 2's
        // N3: every other test in this file applies the `userPrincipal`
        // post-processor inside `post()` — this is the same request with
        // that one thing omitted, no new fixture needed.
        val response =
            mockMvc
                .post(ENTRIES_PATH) {
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"thank you"}"""
                }.andReturn()
                .response

        response.status shouldBe 401
        response.contentAsString shouldContain "\"code\":\"UNAUTHENTICATED\""
        handlerRuns shouldBe 0
    }

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }

    private companion object {
        const val ENTRIES_PATH = "/api/v1/probe/entries"
        const val OTHER_ENTRIES_PATH = "/api/v1/probe/other-entries"
        const val CREATED_ETAG = "\"7\""
        const val CREATED_LOCATION = "/api/v1/probe/entries/7"
        const val TIMEOUT_SECONDS = 10L
    }
}

/**
 * Counts its own invocations so a test can assert the handler ran once, not
 * "the response looked like a replay" — a fact the interceptor's own headers
 * could in principle get wrong.
 *
 * [enteredGate] and [releaseGate] exist only for the concurrency test: when
 * set, the handler signals the first and waits on the second, so a test can
 * hold a winning request inside the handler for exactly as long as it needs
 * to. `null` by default, so every other test runs unblocked.
 */
@RestController
final class IdempotencyProbeController {
    val handlerRuns = AtomicInteger(0)

    @Volatile
    var enteredGate: CountDownLatch? = null

    @Volatile
    var releaseGate: CountDownLatch? = null

    fun reset() {
        handlerRuns.set(0)
        enteredGate = null
        releaseGate = null
    }

    @PostMapping("/api/v1/probe/entries")
    @Idempotent
    fun create(
        @RequestBody body: Map<String, String>,
    ): ResponseEntity<Map<String, String>> {
        handlerRuns.incrementAndGet()
        enteredGate?.countDown()
        releaseGate?.await()
        return ResponseEntity
            .created(URI.create("/api/v1/probe/entries/7"))
            .eTag("\"7\"")
            .body(body)
    }

    @PostMapping("/api/v1/probe/other-entries")
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    fun createOther(
        @RequestBody body: Map<String, String>,
    ): Map<String, String> {
        handlerRuns.incrementAndGet()
        return body
    }
}
