package com.moyi.common.web.idempotency

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.InputStream

/**
 * [IdempotencyRequestCachingFilter] and the wrapper it applies, with no
 * Spring context: what gets wrapped, that wrapping reads nothing, and that
 * buffering — which only [IdempotencyInterceptor] asks for — is bounded.
 *
 * Two findings meet here. F3 (first whole-branch review): the filter used to
 * read every `POST`/`PUT`/`PATCH` body into memory on every path, public
 * ones included, before anything could refuse the request. A5 (final
 * whole-branch review): the `Content-Length` bound that closed F3 left a
 * chunked request unwrapped, and an `@Idempotent` route answered it `500`.
 * The filter now reads nothing at all; these tests hold both ends.
 */
internal class IdempotencyRequestCachingFilterTest {
    private val filter = IdempotencyRequestCachingFilter()

    @Test
    fun `a JSON POST is wrapped, marked as seen, and not a byte of it is read`() {
        val stream = CountingStream("""{"text":"thank you"}""".toByteArray())
        val request = requestReading(stream)

        val passedOn = passedDownTheChain(request)

        passedOn.shouldBeInstanceOf<ReplayableHttpServletRequest>()
        request.getAttribute(IdempotencyRequestCachingFilter.SEEN_ATTRIBUTE).shouldNotBeNull()
        stream.bytesRead shouldBe 0
    }

    @Test
    fun `a wrapped request nobody buffers hands on the container's own stream`() {
        // Every route that is not @Idempotent: the wrapper must be invisible.
        val stream = CountingStream("""{"text":"thank you"}""".toByteArray())
        val wrapped = ReplayableHttpServletRequest(requestReading(stream))

        String(wrapped.inputStream.readAllBytes()) shouldBe """{"text":"thank you"}"""
    }

    @Test
    fun `a buffered body is read once and can be read again, as many times as asked`() {
        val stream = CountingStream("""{"text":"thank you"}""".toByteArray())
        val wrapped = ReplayableHttpServletRequest(requestReading(stream))

        String(wrapped.buffered(MAX)) shouldBe """{"text":"thank you"}"""
        String(wrapped.inputStream.readAllBytes()) shouldBe """{"text":"thank you"}"""
        wrapped.reader.readText() shouldBe """{"text":"thank you"}"""
        wrapped.buffered(MAX) shouldBeSameInstanceAs wrapped.buffered(MAX)
        stream.bytesRead shouldBe 20
    }

    @Test
    fun `a declared length over the bound is refused without reading a byte`() {
        val stream = CountingStream(ByteArray(MAX + 1))
        val wrapped = ReplayableHttpServletRequest(requestReading(stream, declaredLength = MAX + 1L))

        shouldThrow<RequestBodyTooLargeException> { wrapped.buffered(MAX) }
        stream.bytesRead shouldBe 0
    }

    @Test
    fun `a body with no declared length is read to the bound and one byte past it, never further`() {
        // Chunked transfer: the length is -1 and the size is whatever arrives.
        val atTheBound = ReplayableHttpServletRequest(requestReading(CountingStream(ByteArray(MAX)), declaredLength = -1))
        atTheBound.buffered(MAX).size shouldBe MAX

        val endless = CountingStream(ByteArray(MAX * 4))
        val tooLong = ReplayableHttpServletRequest(requestReading(endless, declaredLength = -1))
        shouldThrow<RequestBodyTooLargeException> { tooLong.buffered(MAX) }
        endless.bytesRead shouldBe MAX + 1
    }

    @Test
    fun `a declared length that understates the body does not get past the bound either`() {
        val lying = CountingStream(ByteArray(MAX * 4))
        val wrapped = ReplayableHttpServletRequest(requestReading(lying, declaredLength = 10))

        shouldThrow<RequestBodyTooLargeException> { wrapped.buffered(MAX) }
        lying.bytesRead shouldBe MAX + 1
    }

    @Test
    fun `a multipart POST and a GET are passed on unwrapped, but still marked as seen`() {
        val multipart = MockHttpServletRequest("POST", "/api/v1/probe/entries")
        multipart.contentType = "multipart/form-data; boundary=x"
        multipart.setContent("--x--".toByteArray())
        val get = MockHttpServletRequest("GET", "/api/v1/probe/entries")

        for (request in listOf(multipart, get)) {
            passedDownTheChain(request) shouldBeSameInstanceAs request
            request.getAttribute(IdempotencyRequestCachingFilter.SEEN_ATTRIBUTE).shouldNotBeNull()
        }
    }

    private fun passedDownTheChain(request: HttpServletRequest): HttpServletRequest {
        val chain = MockFilterChain()
        filter.doFilter(request, MockHttpServletResponse(), chain)
        return chain.request as HttpServletRequest
    }

    /** A JSON `POST` whose body comes from [stream], declaring [declaredLength] (`-1`: none, as a chunked request does). */
    private fun requestReading(
        stream: CountingStream,
        declaredLength: Long = stream.size.toLong(),
    ): MockHttpServletRequest =
        object : MockHttpServletRequest("POST", "/api/v1/probe/entries") {
            override fun getInputStream(): ServletInputStream = stream.asServletStream()

            override fun getContentLengthLong(): Long = declaredLength

            override fun getContentLength(): Int = declaredLength.toInt()
        }.apply { contentType = "application/json" }

    /** Counts what is actually pulled from it — "bounded" is a claim about this number. */
    private class CountingStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        var bytesRead = 0
            private set
        val size get() = bytes.size

        override fun read(): Int = if (bytesRead < bytes.size) bytes[bytesRead++].toInt() and BYTE_MASK else -1

        fun asServletStream(): ServletInputStream =
            object : ServletInputStream() {
                override fun read(): Int = this@CountingStream.read()

                override fun isFinished(): Boolean = bytesRead >= bytes.size

                override fun isReady(): Boolean = true

                override fun setReadListener(listener: ReadListener?) = Unit
            }
    }

    private companion object {
        const val MAX = 4096
        const val BYTE_MASK = 0xff
    }
}
