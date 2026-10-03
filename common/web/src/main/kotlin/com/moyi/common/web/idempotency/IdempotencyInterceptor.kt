package com.moyi.common.web.idempotency

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.UUID

/**
 * Doc 06 §1's `Idempotency-Key` — the **header contract** only. The record
 * itself is [IdempotentExecution]'s, inside the handler's own transaction.
 *
 * **What this does, before the handler runs:** for an `@Idempotent` handler,
 * refuses a missing key (`422`), resolves the verified caller (`401` if
 * there is none), fingerprints the request through [RequestFingerprint] —
 * method, raw path and body — and leaves the result on the request as an
 * [IdempotentRequest], which the handler fetches with [requestOf] and hands to
 * [IdempotentExecution.once] together with its own transaction.
 *
 * **What it no longer does, and why (plan task 7, spec §5.4):** it does not
 * touch `idempotency_keys`. It used to reserve the row in [preHandle] and
 * complete it in `afterCompletion` — three transactions where the spec asks
 * for one — so a crash between them left either a committed reservation with
 * no result (a permanent `409` for a request that never happened) or a
 * committed result under a key that would run it again. Lock, reserve,
 * mutate and complete now share the handler's transaction; this class keeps
 * only what has to happen at the HTTP edge.
 *
 * **Who sets `Idempotency-Replayed: true`.** The handler does, from
 * [IdempotentOutcome.wasReplayed]: whether a request is a replay is decided
 * inside the handler's transaction, after this interceptor has already
 * returned, and by the time `postHandle` runs a `@ResponseBody` handler's
 * headers are written.
 *
 * The body is fingerprinted, never stored: doc 18 §9, and an entry's text is
 * the one thing this system exists not to leak. The fingerprint is keyed
 * (ruling P8) — see [RequestFingerprint] for why a plain hash would not do.
 *
 * **Requires the request's body to be re-readable.** [preHandle] reads it to
 * fingerprint it before the handler's own `@RequestBody` reads it again,
 * which an ordinary `HttpServletRequest` does not support, so a request must
 * already be wrapped by [IdempotencyRequestCachingFilter]. Checked rather
 * than assumed: an unwrapped request fails loudly in [preHandle], before
 * anything is written. And a handler whose request never passed through
 * this interceptor at all — the import forgotten — fails loudly in
 * [requestOf] rather than running unprotected.
 */
class IdempotencyInterceptor(
    private val fingerprint: RequestFingerprint,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        (handler as? HandlerMethod)?.getMethodAnnotation(Idempotent::class.java) ?: return true
        check(request is ReplayableHttpServletRequest) {
            "@Idempotent on ${request.method} ${request.requestURI}, but IdempotencyRequestCachingFilter is not " +
                "registered for this request. Without it the body can be read only once, and the handler's own " +
                "@RequestBody would read an exhausted stream. Register the filter wherever this interceptor is wired."
        }

        val key = request.getHeader(HEADER)?.trim()
        if (key.isNullOrBlank()) throw IdempotencyKeyRequiredException()

        val method = request.method
        // The raw, concrete path — never the route template (two bonds are
        // two targets), and never canonicalised: see IdempotentRequest.path.
        val path = request.requestURI
        request.setAttribute(
            REQUEST_ATTRIBUTE,
            IdempotentRequest(
                userId = callerId(request),
                key = key,
                method = method,
                path = path,
                fingerprint = fingerprint.of(method, path, request.inputStream.readBytes()),
            ),
        )
        return true
    }

    /**
     * The verified caller, read from [HttpServletRequest.getUserPrincipal]
     * rather than `common:security`'s `CurrentUser` — `common:security`
     * depends on `common:web`, not the other way round, so this module
     * cannot name that type. In the real application,
     * `SecurityContextHolderAwareRequestFilter` (part of Spring Security's
     * default filter chain, never disabled in `SecurityConfiguration`) sets
     * `getUserPrincipal()` from the verified JWT before the request ever
     * reaches Spring MVC, and `JwtAuthenticationToken.getName()` returns the
     * token's `sub` claim — so this is the same id `CurrentUser.id` resolves
     * to, without a dependency in that direction.
     */
    private fun callerId(request: HttpServletRequest): UUID =
        request.userPrincipal?.name?.let(UUID::fromString) ?: throw IdempotencyRequiresCallerException()

    companion object {
        const val HEADER = "Idempotency-Key"
        const val REPLAYED_HEADER = "Idempotency-Replayed"

        private const val REQUEST_ATTRIBUTE = "com.moyi.common.web.idempotency.request"

        /**
         * The [IdempotentRequest] [preHandle] prepared for this request. Fails
         * loudly when there is none — the handler is not `@Idempotent`, or no
         * context imported [IdempotencyConfiguration] — because the
         * alternative is a handler that believes it is protected and is not.
         */
        fun requestOf(request: HttpServletRequest): IdempotentRequest =
            checkNotNull(request.getAttribute(REQUEST_ATTRIBUTE) as? IdempotentRequest) {
                "No Idempotency-Key was prepared for ${request.method} ${request.requestURI}: the handler is not " +
                    "@Idempotent, or IdempotencyInterceptor is not registered (@Import(IdempotencyConfiguration::class))."
            }
    }
}

/**
 * Wraps every request so its body can be read twice: once by
 * [IdempotencyInterceptor], to fingerprint it before the handler runs, and
 * once by the handler's own `@RequestBody`. The response is no longer
 * wrapped: nothing reads it back since the record moved into the handler's
 * transaction ([IdempotentExecution]).
 *
 * **Not `org.springframework.web.util.ContentCachingRequestWrapper`,
 * despite the brief naming it.** That class only *records* what something
 * else reads through it — its own `getInputStream()` is created once and
 * memoised, so a second caller reading from it (the handler, after the
 * interceptor already read to hash the body) gets an exhausted stream and an
 * empty body, which breaks `@RequestBody` on every `@Idempotent` endpoint.
 * [ReplayableHttpServletRequest] instead drains the body once, eagerly, in
 * its constructor, and serves every subsequent [getInputStream] call from
 * that in-memory copy — genuinely repeatable, not merely recorded. Found by
 * reasoning through Spring's own source before writing a test that would
 * have failed opaquely on every `@Idempotent` `POST`.
 *
 * **Only applies to `POST`/`PUT`/`PATCH`, never to a multipart body, and
 * never above [MAX_CACHEABLE_BYTES]** (review round 1, Important #3; the
 * size bound added by the whole-branch review, F3). A bare `Filter` bean is
 * registered by Spring Boot for every path, in every module that depends on
 * `common:web` — and draining every body eagerly is wrong for a `GET`
 * (idempotency is meaningless on a method HTTP already defines as
 * idempotent, so `@Idempotent` is never put on one)
 * and wasteful for `actuator`/the OpenAPI document. Scoped by method and
 * content type instead of a `FilterRegistrationBean` with a URL allowlist:
 * `common:web` cannot know in advance which path in which module ends up
 * `@Idempotent`, so a path-based allowlist here would have to be guessed and
 * kept in sync by hand; a rule about *what kind of request this filter's
 * work could ever be needed for* does not.
 *
 * **Size is the half that argument does not consider, and it matters for a
 * reason method/content-type scoping does not touch: this filter runs
 * before Spring Security and before handler mapping, on every path,
 * authenticated or not.** [ReplayableHttpServletRequest]'s constructor calls
 * `request.inputStream.readBytes()` eagerly — the whole body, materialised
 * in memory, before `@Idempotent` is even known to apply, before Jackson
 * could stream-fail on a malformed body, and before `RateLimitInterceptor`
 * (an MVC interceptor, which runs *after* every servlet filter) ever sees
 * the request. `/api/v1/auth/login`, `/auth/register` and `/auth/refresh`
 * are `POST`, public, and JSON — exactly the shape this filter buffers — so
 * an unauthenticated multi-gigabyte `POST` to any of them is read fully into
 * memory by this constructor with no `RateLimitInterceptor` or
 * `server.tomcat.*` body-size limit (none is configured) having had a chance
 * to refuse it first. [shouldNotFilter] adding a `Content-Length` cap closes
 * that: a request whose declared length is missing or exceeds
 * [MAX_CACHEABLE_BYTES] is not wrapped at all, so `@Idempotent` on an
 * endpoint that legitimately needs a larger body is the signal to raise the
 * bound deliberately, not a gap to be found by an oversized request first.
 */
class IdempotencyRequestCachingFilter : OncePerRequestFilter() {
    /**
     * `public`, widened from `OncePerRequestFilter`'s own `protected` — so
     * [IdempotencyRequestCachingFilterTest] can drive this pure decision
     * directly, without a full Spring context to prove F3's size bound.
     */
    public override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val isMultipart = request.contentType?.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE) == true
        val contentLength = request.contentLengthLong
        val isOversized = contentLength < 0 || contentLength > MAX_CACHEABLE_BYTES
        return runCatching { HttpMethod.valueOf(request.method) }.getOrNull() !in APPLICABLE_METHODS || isMultipart || isOversized
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        filterChain.doFilter(ReplayableHttpServletRequest(request), response)
    }

    private companion object {
        val APPLICABLE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)

        /**
         * 1 MiB (whole-branch review, F3): every body this filter's own
         * callers actually send is small — `EntryText.MAX_OCTETS` bounds an
         * entry's text at 8,192 octets (FR-041), and every other
         * `@Idempotent`/write body in this codebase is a handful of fields —
         * so this is generous headroom over the largest legitimate request
         * today, not a tuned-to-the-byte limit. A request with no declared
         * `Content-Length` (chunked transfer, or a client that simply omits
         * it) is treated as oversized too, deliberately: the attack this
         * bound exists to stop is exactly a body whose size is not known in
         * advance, so trusting an absent header would leave the same
         * unbounded read this fix closes, just reachable by omitting the
         * header instead of inflating it. Raise this only alongside a
         * concrete need for a larger `@Idempotent` body, not preemptively.
         */
        const val MAX_CACHEABLE_BYTES = 1024L * 1024L
    }
}

private class ReplayableHttpServletRequest(
    request: HttpServletRequest,
) : HttpServletRequestWrapper(request) {
    private val body: ByteArray = request.inputStream.readBytes()

    override fun getInputStream(): ServletInputStream = ReplayableServletInputStream(body)

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(ByteArrayInputStream(body), Charset.forName(characterEncoding ?: "UTF-8")))
}

private class ReplayableServletInputStream(
    body: ByteArray,
) : ServletInputStream() {
    private val delegate = ByteArrayInputStream(body)

    override fun read(): Int = delegate.read()

    override fun isFinished(): Boolean = delegate.available() == 0

    override fun isReady(): Boolean = true

    /** Never async: this filter always runs synchronously, so there is never a listener to notify. */
    override fun setReadListener(listener: ReadListener?) {
        // Intentionally empty.
    }
}

/** 422: doc 06 §1 requires `Idempotency-Key` on this endpoint, and it was not sent. */
class IdempotencyKeyRequiredException :
    ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ErrorCode.VALIDATION_FAILED,
        "A required header, Idempotency-Key, was not sent.",
    )

/** 422: the same key was already sent against a different method or path, or with a body that hashes differently. */
class IdempotencyKeyReusedException :
    ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ErrorCode.IDEMPOTENCY_KEY_REUSED,
        "This idempotency key was already used with a different request.",
    )

/** 409: the first request carrying this key has reserved it and has not finished yet. */
class IdempotencyKeyInFlightException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.IDEMPOTENCY_KEY_IN_FLIGHT,
        "A request with this idempotency key is already being processed.",
    )

/**
 * 401: `@Idempotent` needs an authenticated caller to scope the reservation
 * to, and this request carries none. Review round 1, Important #5: a wiring
 * mistake — `@Idempotent` on an endpoint the security chain treats as
 * public — surfaces as the same 401 [com.moyi.common.security.CurrentUser]'s
 * resolver produces for the equivalent mistake there, not as a 500 for an
 * ordinary unauthenticated request.
 */
class IdempotencyRequiresCallerException :
    ApiException(
        HttpStatus.UNAUTHORIZED,
        ErrorCode.UNAUTHENTICATED,
        "Sign in to continue.",
    )
