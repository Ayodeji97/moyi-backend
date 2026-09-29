package com.moyi.common.web.idempotency

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * [IdempotencyRequestCachingFilter.shouldNotFilter] alone — no Spring context
 * needed, since the method is a pure function of the request's method,
 * content type and declared length. F3 (whole-branch review): before this
 * fix, [IdempotencyRequestCachingFilter] eagerly read the entire body of
 * every `POST`/`PUT`/`PATCH`, non-multipart request into memory —
 * including every public, unauthenticated one (`/auth/login`,
 * `/auth/register`, `/auth/refresh`) — before Spring Security, handler
 * mapping or `RateLimitInterceptor` (an MVC interceptor, which runs after
 * every servlet filter) ever saw the request. These three tests are the size
 * bound that closes that: an oversized or size-unknown body is left
 * unwrapped rather than buffered.
 */
internal class IdempotencyRequestCachingFilterTest {
    private val filter = IdempotencyRequestCachingFilter()

    @Test
    fun `an ordinary JSON POST within the bound is wrapped`() {
        val request = MockHttpServletRequest("POST", "/api/v1/probe/entries")
        request.contentType = "application/json"
        request.setContent("""{"text":"thank you"}""".toByteArray())

        filter.shouldNotFilter(request) shouldBe false
    }

    @Test
    fun `a body over the size bound is left unwrapped, not buffered`() {
        val request = MockHttpServletRequest("POST", "/api/v1/probe/entries")
        request.contentType = "application/json"
        // One byte over the 1 MiB bound — MockHttpServletRequest derives its
        // declared content length from the array it was given, exactly as a
        // real Content-Length header would.
        request.setContent(ByteArray(1024 * 1024 + 1))

        filter.shouldNotFilter(request) shouldBe true
    }

    @Test
    fun `a body with no declared length at all is left unwrapped too`() {
        // The attack F3 closes is exactly a body whose size is not known in
        // advance — an absent length must not be trusted as "small".
        val request = MockHttpServletRequest("POST", "/api/v1/probe/entries")
        request.contentType = "application/json"

        filter.shouldNotFilter(request) shouldBe true
    }

    @Test
    fun `a GET is left unwrapped regardless of size, as before this fix`() {
        val request = MockHttpServletRequest("GET", "/api/v1/probe/entries")

        filter.shouldNotFilter(request) shouldBe true
    }
}
