package com.moyi.common.web.idempotency

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
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
@ExtendWith(OutputCaptureExtension::class)
@Import(IdempotencyInterceptorTest.TimeConfiguration::class, IdempotencyInterceptorTest.BrokenBodyConfiguration::class)
class IdempotencyInterceptorTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val controller: IdempotencyProbeController,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcTemplate,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val pool = Executors.newFixedThreadPool(2)

    @AfterEach
    fun reset() {
        // The pool first, bounded: a request still parked must not race the truncate.
        pool.shutdownNow()
        check(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "a worker thread outlived the test" }
        controller.reset()
        jdbc.execute("TRUNCATE TABLE idempotency_keys, probe_results")
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
    fun `a replay returns the first status and the re-read resource, without running the write twice`() {
        val key = UUID.randomUUID().toString()
        val first = post(ada, key, """{"text":"thank you"}""")
        first.status shouldBe 201
        val second = post(ada, key, """{"text":"thank you"}""")

        second.status shouldBe 201
        first.contentAsString shouldContain "thank you"
        // Re-read by result id from the probe's own table: V11 holds no copy.
        second.contentAsString shouldContain "thank you"
        second.getHeader("Idempotency-Replayed") shouldBe "true"
        first.getHeader("Idempotency-Replayed").shouldBeNull()
        handlerRuns shouldBe 1
    }

    @Test
    fun `a replay after the resource was erased renders what is there now, never the first response`() {
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"words that were erased"}""").status shouldBe 201
        jdbc.update("UPDATE probe_results SET label = NULL")

        val replay = post(ada, key, """{"text":"words that were erased"}""")

        replay.status shouldBe 201
        replay.contentAsString shouldNotContain "erased"
        handlerRuns shouldBe 1
    }

    @Test
    fun `a refused request leaves no key, so the corrected retry under the same key runs`() {
        // The probe refuses after its write, inside the transaction — a 4xx
        // thrown from the block. Keep the reservation in a transaction of its
        // own and the retry below is 422 IDEMPOTENCY_KEY_REUSED (the body
        // differs) for a request the server never accepted.
        val key = UUID.randomUUID().toString()
        controller.refuseNext = true
        post(ada, key, """{"text":"first try"}""").status shouldBe 422

        val corrected = post(ada, key, """{"text":"second try"}""")

        corrected.status shouldBe 201
        corrected.getHeader("Idempotency-Replayed").shouldBeNull()
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Int::class.java) shouldBe 1
    }

    @Test
    fun `a replay carries back ETag and Location`() {
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
    fun `the same key against a different path is 422, not a fresh reservation`() {
        // Ruling A: V11's unique constraint is `(user_id, idempotency_key)`
        // alone, so only the comparison in IdempotentExecution.replay can
        // refuse a different method or concrete path.
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"thank you"}""").status shouldBe 201

        val reused = post(ada, key, """{"text":"thank you"}""", path = OTHER_ENTRIES_PATH)

        reused.status shouldBe 422
        reused.contentAsString shouldContain "\"code\":\"IDEMPOTENCY_KEY_REUSED\""
    }

    @Test
    fun `the same key on another concrete path of the same route is 422, not a replay`() {
        // The path is compared concretely: a route template would make two
        // bonds one target. Both paths below match the one mapping.
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"thank you"}""", path = "/api/v1/probe/bonds/a/entries").status shouldBe 201

        val reused = post(ada, key, """{"text":"thank you"}""", path = "/api/v1/probe/bonds/b/entries")

        reused.status shouldBe 422
        reused.contentAsString shouldContain "\"code\":\"IDEMPOTENCY_KEY_REUSED\""
        handlerRuns shouldBe 1
    }

    @Test
    fun `a key is scoped to its user, so two people may pick the same one`() {
        val key = UUID.randomUUID().toString()
        post(ada, key, """{"text":"thank you"}""").status shouldBe 201
        post(bea, key, """{"text":"thank you"}""").status shouldBe 201
        handlerRuns shouldBe 2
    }

    /**
     * `VALIDATION_FAILED` is "always accompanied by `errors`" (`ErrorCode`),
     * and this one was not: a bare `422` with the header named only in prose.
     */
    @Test
    fun `a missing key on a required endpoint is 422 with an errors entry naming the header`() {
        for (absent in listOf(null, "", "   ")) {
            val response = post(ada, key = absent, body = """{"text":"thank you"}""")

            response.status shouldBe 422
            response.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
            response.contentAsString shouldContain "\"errors\":[{\"field\":\"Idempotency-Key\",\"code\":\"NOT_BLANK\""
        }
        handlerRuns shouldBe 0
    }

    /**
     * A6: 1 to 255 characters. V11's column is unbounded `text` otherwise,
     * and a key is stored for 24 hours per request — the bound is the
     * difference between a key and a place to park a megabyte.
     */
    @Test
    fun `a key of 255 characters is accepted, and one of 256 is 422 naming the header`() {
        post(ada, "k".repeat(255), """{"text":"thank you"}""").status shouldBe 201

        val tooLong = post(ada, "k".repeat(256), """{"text":"thank you"}""")

        tooLong.status shouldBe 422
        tooLong.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        tooLong.contentAsString shouldContain "\"errors\":[{\"field\":\"Idempotency-Key\",\"code\":\"SIZE\""
        handlerRuns shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Int::class.java) shouldBe 1
    }

    @Test
    fun `a key with anything but visible ASCII in it is 422 naming the header`() {
        // A space inside, a control character, a non-ASCII letter, DEL.
        for (key in listOf("two words", "bell\u0007", "cl\u00e9", "del\u007f")) {
            val response = post(ada, key, """{"text":"thank you"}""")

            response.status shouldBe 422
            response.contentAsString shouldContain "\"errors\":[{\"field\":\"Idempotency-Key\",\"code\":\"PATTERN\""
        }
        // Every visible ASCII character is allowed, the punctuation included.
        post(ada, "!\"#$%&'()*+,-./09:;<=>?@AZ[\\]^_`az{|}~", """{"text":"thank you"}""").status shouldBe 201
        handlerRuns shouldBe 1
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

        val firstCall = pool.submit<Int> { post(ada, key, """{"text":"thank you"}""").status }
        entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

        // The first request holds the key's advisory lock and is parked
        // inside its own transaction: the second is expected to be answered
        // — 409, without ever running the write — well before the first is
        // let go. (IdempotentExecutionTest proves the same thing through
        // pg_blocking_pids, without a timeout standing in for "queued".)
        val secondCall = pool.submit<Int> { post(ada, key, """{"text":"thank you"}""").status }
        val secondStatus = secondCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        release.countDown()
        val statuses = listOf(firstCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), secondStatus)

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

    // ---- a body the caching filter does not hand over ready-made (A5) ------

    /**
     * MockMvc gives a request with no content a declared length of `-1` — the
     * same thing a chunked request reports. With nothing to read the body is
     * empty, and an empty body is what Spring already answers for a required
     * `@RequestBody`: `400 MALFORMED_REQUEST`. It used to be a `500`, raised
     * before the handler was reached. (The chunked request *with* a body is
     * `IdempotencyRealServerTest`'s: MockMvc cannot send one.)
     */
    @Test
    fun `a POST with no body and no declared length is 400, not a 500`() {
        val response =
            mockMvc
                .post(ENTRIES_PATH) {
                    with { request -> request.apply { userPrincipal = Principal { ada.toString() } } }
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                }.andReturn()
                .response

        response.status shouldBe 400
        response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
        handlerRuns shouldBe 0
    }

    @Test
    fun `a body over one mebibyte is 413, not a 500, and is never run`() {
        val response = post(ada, UUID.randomUUID().toString(), """{"text":"${"a".repeat(1024 * 1024)}"}""")

        response.status shouldBe 413
        response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
        handlerRuns shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Int::class.java) shouldBe 0
    }

    @Test
    fun `a multipart body is Spring's own 415, not a 500`() {
        val response =
            mockMvc
                .post(ENTRIES_PATH) {
                    with { request -> request.apply { userPrincipal = Principal { ada.toString() } } }
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.parseMediaType("multipart/form-data; boundary=x")
                    content = "--x\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\nthank you\r\n--x--\r\n"
                }.andReturn()
                .response

        response.status shouldBe 415
        response.contentAsString shouldContain "\"code\":\"UNSUPPORTED_MEDIA_TYPE\""
        handlerRuns shouldBe 0
    }

    /**
     * A client that disconnects part-way through its body. The body is read
     * in `preHandle` now, so the container's `IOException` is raised inside
     * MVC — and, until it was caught where it is read, reached
     * `handleUnexpected`: an ERROR-logged `500` with a stack trace, for
     * something no server did wrong. Observed 2026-10-04, here and against a
     * real Tomcat (`IdempotencyRealServerTest`). There is no client left to
     * read the answer; the point is what the server counts and logs it as.
     */
    @Test
    fun `a body whose stream fails part-way is 400, logged as a client error and never run`(output: CapturedOutput) {
        for (failure in listOf("io", "eof")) {
            val response =
                mockMvc
                    .post(ENTRIES_PATH) {
                        with { request -> request.apply { userPrincipal = Principal { ada.toString() } } }
                        header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                        header(BREAK_HEADER, failure)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"text":"thank you"}"""
                    }.andReturn()
                    .response

            response.status shouldBe 400
            response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
        }
        handlerRuns shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Int::class.java) shouldBe 0
        output.all shouldNotContain "Unhandled exception"
        output.all shouldNotContain "Exception:" // no stack trace, and no exception's own message
        output.all shouldContain "MALFORMED_REQUEST -> 400"
    }

    /**
     * Stands in for a client that goes away mid-body: with [BREAK_HEADER] set,
     * the request's stream yields [BREAK_AFTER] bytes and then throws — an
     * `IOException` (what Tomcat's `ClientAbortException` and a socket
     * timeout are) or an `EOFException` (a body shorter than it declared).
     * Outermost, so it sits *inside* [ReplayableHttpServletRequest] and is
     * what `buffered` reads from.
     */
    @TestConfiguration
    class BrokenBodyConfiguration {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        fun brokenBodyFilter(): OncePerRequestFilter =
            object : OncePerRequestFilter() {
                override fun doFilterInternal(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    filterChain: FilterChain,
                ) {
                    val failure = request.getHeader(BREAK_HEADER)
                    filterChain.doFilter(if (failure == null) request else BrokenBodyRequest(request, failure), response)
                }
            }
    }

    private class BrokenBodyRequest(
        request: HttpServletRequest,
        private val failure: String,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val real: InputStream = super.getInputStream()
            return object : ServletInputStream() {
                private var served = 0

                override fun read(): Int {
                    if (served++ < BREAK_AFTER) return real.read()
                    if (failure == "eof") throw EOFException("Unexpected EOF read on the socket")
                    throw IOException("Connection reset by peer")
                }

                override fun isFinished(): Boolean = false

                override fun isReady(): Boolean = true

                override fun setReadListener(listener: ReadListener?) = Unit
            }
        }
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
        const val BREAK_HEADER = "X-Probe-Break-Body"
        const val BREAK_AFTER = 8
    }
}

/**
 * Counts its own invocations so a test can assert the handler's write ran
 * once, not "the response looked like a replay" — a fact the headers could
 * in principle get wrong.
 *
 * Shaped like a real idempotent handler (`EntriesController`/`SubmitEntry`):
 * its write runs through [IdempotentExecution.once] inside its own
 * transaction, into `probe_results` (test-only, `V11_1`), and a replay is
 * answered by **re-reading** that row by the returned id — never from a
 * stored copy. A `NULL` label is this probe's tombstone.
 *
 * [enteredGate] and [releaseGate] exist only for the concurrency test: when
 * set, the write signals the first (after the key's lock and reservation are
 * taken) and waits on the second, so a test can hold a winning request
 * inside its transaction for exactly as long as it needs to.
 */
@RestController
class IdempotencyProbeController(
    private val execution: IdempotentExecution,
    private val transactions: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) {
    val handlerRuns = AtomicInteger(0)

    @Volatile
    var enteredGate: CountDownLatch? = null

    @Volatile
    var releaseGate: CountDownLatch? = null

    /** When set, the next write throws a `422` after inserting its row, and clears this. */
    @Volatile
    var refuseNext: Boolean = false

    fun reset() {
        handlerRuns.set(0)
        enteredGate = null
        releaseGate = null
        refuseNext = false
    }

    @PostMapping("/api/v1/probe/entries")
    @Idempotent
    fun create(
        @RequestBody body: Map<String, String>,
        http: HttpServletRequest,
    ): ResponseEntity<Map<String, String?>> = respond(http, body, etag = "\"7\"", location = "/api/v1/probe/entries/7")

    @PostMapping("/api/v1/probe/bonds/{bond}/entries")
    @Idempotent
    fun createUnderBond(
        @RequestBody body: Map<String, String>,
        http: HttpServletRequest,
    ): ResponseEntity<Map<String, String?>> = respond(http, body)

    @PostMapping("/api/v1/probe/other-entries")
    @Idempotent
    fun createOther(
        @RequestBody body: Map<String, String>,
        http: HttpServletRequest,
    ): ResponseEntity<Map<String, String?>> = respond(http, body)

    private fun respond(
        http: HttpServletRequest,
        body: Map<String, String>,
        etag: String? = null,
        location: String? = null,
    ): ResponseEntity<Map<String, String?>> {
        val outcome =
            checkNotNull(
                transactions.execute {
                    execution.once(IdempotencyInterceptor.requestOf(http)) {
                        handlerRuns.incrementAndGet()
                        enteredGate?.countDown()
                        releaseGate?.await(GATE_SECONDS, TimeUnit.SECONDS)
                        val id = UUID.randomUUID()
                        jdbc.update("INSERT INTO probe_results (id, label) VALUES (?, ?)", id, body["text"])
                        if (refuseNext) {
                            refuseNext = false
                            throw ProbeRefusedException()
                        }
                        IdempotentResult(body, id, ResultKind.ENTRY, HttpStatus.CREATED.value(), etag, location)
                    }
                },
            )
        val rendered: Map<String, String?> =
            outcome.value ?: mapOf(
                "text" to jdbc.queryForObject("SELECT label FROM probe_results WHERE id = ?", String::class.java, outcome.resultId),
            )
        val response = ResponseEntity.status(outcome.status)
        outcome.etag?.let { response.eTag(it) }
        outcome.location?.let { response.location(URI.create(it)) }
        if (outcome.wasReplayed) response.header(IdempotencyInterceptor.REPLAYED_HEADER, "true")
        return response.body(rendered)
    }

    private companion object {
        const val GATE_SECONDS = 10L
    }
}

/** A refusal thrown from inside the probe's write — any `ApiException` a real handler's block might throw. */
class ProbeRefusedException : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_FAILED, "The probe refused this request.")
