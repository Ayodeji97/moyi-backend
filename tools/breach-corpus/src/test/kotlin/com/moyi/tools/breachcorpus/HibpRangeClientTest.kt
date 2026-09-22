package com.moyi.tools.breachcorpus

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.util.zip.GZIPOutputStream

/**
 * The retry policy, which is security-relevant rather than merely robust.
 *
 * A range that fails and is skipped is ~10 breached passwords the filter will
 * wave through for as long as that corpus is deployed, and nothing downstream
 * can detect it — the file is well-formed, the count looks plausible, and the
 * missing members are exactly the ones nobody will type in a test. So the
 * rules being pinned here are: transient failures are retried, permanent ones
 * are not retried pointlessly, and **exhaustion throws** rather than returning
 * an empty body that would parse into zero digests.
 */
internal class HibpRangeClientTest {
    private val http = mockk<HttpClient>()
    private val slept = mutableListOf<Long>()
    private val client = HibpRangeClient(http) { slept += it }

    @Test
    fun `a successful response is returned without sleeping`() {
        respondWith(ok(BODY))

        client.fetch(PREFIX) shouldBe BODY
        slept shouldBe emptyList()
    }

    @Test
    fun `a transient failure is retried and the backoff doubles`() {
        respondWith(status(SERVICE_UNAVAILABLE), status(TOO_MANY_REQUESTS), ok(BODY))

        client.fetch(PREFIX) shouldBe BODY
        slept shouldBe listOf(500L, 1000L)
    }

    @Test
    fun `an IO failure is transient too`() {
        every {
            http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } throws IOException("connection reset") andThen ok(BODY)

        client.fetch(PREFIX) shouldBe BODY
    }

    @Test
    fun `a gzipped body is decompressed`() {
        // HIBP serves gzip and Java's HttpClient neither asks for it nor
        // decodes it, so this is ours to do. Getting it wrong does not throw:
        // the compressed bytes read as text, every line looks malformed, and
        // the corpus comes out empty while the job reports success.
        respondWith(gzipped(BODY))

        client.fetch(PREFIX) shouldBe BODY
    }

    @Test
    fun `a body served as plain text is read as text, even though gzip was offered`() {
        // Keyed on the response header rather than on having asked. A proxy
        // that ignores Accept-Encoding would otherwise produce a ZipException
        // per range, retried four times, a million times over.
        respondWith(ok(BODY))

        client.fetch(PREFIX) shouldBe BODY
    }

    @Test
    fun `exhausting the retries throws rather than returning nothing`() {
        // The failure mode this prevents: an empty body parses cleanly into
        // zero qualifying digests, so a range that never loaded would look
        // exactly like a range with no prevalent passwords in it.
        respondWith(*Array(ATTEMPTS) { status(SERVICE_UNAVAILABLE) })

        val failure = shouldThrow<IOException> { client.fetch(PREFIX) }

        failure.message shouldContain "failed after at most $ATTEMPTS attempts"
        slept.size shouldBe ATTEMPTS - 1
    }

    @Test
    fun `a permanent failure stops immediately instead of backing off five times`() {
        // Over a million ranges, retrying a 403 is the difference between
        // finding out in seconds that the User-Agent was rejected and finding
        // out an hour later.
        respondWith(status(FORBIDDEN))

        shouldThrow<IOException> { client.fetch(PREFIX) }

        slept shouldBe emptyList()
    }

    private fun respondWith(vararg responses: HttpResponse<ByteArray>) {
        val remaining = responses.toMutableList()
        every { http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } answers { remaining.removeFirst() }
    }

    private fun ok(body: String) = response(OK, body.toByteArray(), gzip = false)

    private fun gzipped(body: String): HttpResponse<ByteArray> {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(body.toByteArray()) }
        return response(OK, compressed.toByteArray(), gzip = true)
    }

    private fun status(code: Int) = response(code, ByteArray(0), gzip = false)

    private fun response(
        code: Int,
        body: ByteArray,
        gzip: Boolean,
    ): HttpResponse<ByteArray> =
        mockk<HttpResponse<ByteArray>>(relaxed = true).also {
            every { it.statusCode() } returns code
            every { it.body() } returns body
            every { it.headers() } returns
                HttpHeaders.of(if (gzip) mapOf("content-encoding" to listOf("gzip")) else emptyMap()) { _, _ -> true }
        }

    private companion object {
        const val PREFIX = "00000"
        const val BODY = "0005AD76BD555C1D6D771DE417A4B87E4B4:58"
        const val ATTEMPTS = 5
        const val OK = 200
        const val FORBIDDEN = 403
        const val TOO_MANY_REQUESTS = 429
        const val SERVICE_UNAVAILABLE = 503
    }
}
