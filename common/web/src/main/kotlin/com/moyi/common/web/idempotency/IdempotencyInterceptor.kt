package com.moyi.common.web.idempotency

import com.moyi.common.core.IdGenerator
import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Doc 06 §1's `Idempotency-Key`, built when `POST /entries` made it required.
 *
 * **The record is written before the handler runs, not after.** A row
 * inserted on the way in, under [IdempotencyKeyStore.reserve]'s unique
 * constraint, is what makes a concurrent retry a `409` rather than a second
 * execution — the reserve-then-complete shape. Writing it afterwards would
 * leave the window the header exists to close: two identical requests would
 * both find nothing reserved, both run the handler, and the second `INSERT`
 * would only ever race the *first request's own write of its result*, which
 * is too late to stop anything. Deliberately proved rather than assumed —
 * see the PR body for what moving the reservation after the handler does to
 * this suite.
 *
 * The body is hashed, never stored: doc 18 §9, and an entry's text is the one
 * thing this system exists not to leak. (V11's `response_body` column does
 * hold the rendered response verbatim, bounded to the 24h window and
 * cleared once slice C3's reaper lands — see that migration's comment.)
 *
 * **Requires the request's body to be re-readable and the response to be
 * buffered.** [preHandle] reads the body to hash it, before the handler's
 * own `@RequestBody` reads it again, and `afterCompletion` reads back what
 * the handler wrote to store as the stored response — an ordinary
 * `HttpServletRequest`/`HttpServletResponse` supports neither, so a request
 * must already be wrapped by [IdempotencyRequestCachingFilter] before it
 * reaches this interceptor. Both halves of that requirement are checked
 * rather than assumed (review round 1, Important #4): an unwrapped request
 * fails loudly in [preHandle], before anything is written; an unwrapped
 * response is logged loudly and the reservation is discarded in
 * [afterCompletion], rather than left permanently "in flight" for every
 * later request under the same key. See [IdempotencyKeyStore]'s KDoc for
 * why neither this class nor that filter is `@Component`-scanned, so the
 * filter has to be registered by hand in the first place.
 */
class IdempotencyInterceptor(
    private val store: IdempotencyKeyStore,
    private val clock: Clock,
    private val ids: IdGenerator,
) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

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

        val endpoint = "${request.method} ${request.requestURI}"
        val requestHash = sha256(request.inputStream.readBytes())
        val now = clock.instant()
        val reservation =
            IdempotencyRecord(
                id = ids.timeOrdered(),
                userId = callerId(request),
                endpoint = endpoint,
                idempotencyKey = key,
                requestHash = requestHash,
                responseStatus = null,
                responseBody = null,
                responseEtag = null,
                responseLocation = null,
                createdAt = now,
                expiresAt = now.plus(TTL),
            )

        val existing = store.reserve(reservation)
        return if (existing == null) {
            request.setAttribute(RECORD_ID_ATTRIBUTE, reservation.id)
            true
        } else {
            replay(existing, endpoint, requestHash, response)
        }
    }

    /**
     * The three ways an already-reserved key can answer a second request —
     * see the class KDoc. A mismatch on *either* [endpoint] or [requestHash]
     * is [IdempotencyKeyReusedException] (Ruling A, review round 1): doc 06
     * §1 keys a reservation on `userId + endpoint + key`, so the same key
     * against a different endpoint is a reuse of the key, not a fresh row —
     * V11's unique constraint no longer includes `endpoint`, which is what
     * makes that comparison this function's job instead of the database's.
     */
    private fun replay(
        existing: IdempotencyRecord,
        endpoint: String,
        requestHash: String,
        response: HttpServletResponse,
    ): Boolean =
        when {
            existing.endpoint != endpoint || existing.requestHash != requestHash -> {
                throw IdempotencyKeyReusedException()
            }

            existing.responseStatus == null -> {
                throw IdempotencyKeyInFlightException()
            }

            else -> {
                response.status = existing.responseStatus.toInt()
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.setHeader(REPLAYED_HEADER, "true")
                existing.responseEtag?.let { response.setHeader(HttpHeaders.ETAG, it) }
                existing.responseLocation?.let { response.setHeader(HttpHeaders.LOCATION, it) }
                existing.responseBody?.let { response.writer.write(it) }
                false
            }
        }

    /**
     * Not called for a replay: [preHandle] returning `false` stops the chain
     * before Spring MVC would call this for the interceptor that stopped it.
     */
    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val recordId = request.getAttribute(RECORD_ID_ATTRIBUTE) as? UUID ?: return
        val cached = response as? ContentCachingResponseWrapper
        if (cached == null) {
            log.error(
                "IdempotencyRequestCachingFilter is not registered for {} {} — the reservation for this " +
                    "Idempotency-Key cannot be completed and would otherwise stay 'in flight' forever. " +
                    "Discarding it instead of leaving every later request under this key a permanent 409.",
                request.method,
                request.requestURI,
            )
            store.discard(recordId)
            return
        }
        store.complete(
            id = recordId,
            captured =
                CapturedResponse(
                    status = cached.status,
                    body = String(cached.contentAsByteArray, Charsets.UTF_8),
                    etag = cached.getHeader(HttpHeaders.ETAG),
                    location = cached.getHeader(HttpHeaders.LOCATION),
                ),
            ex = ex,
        )
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val HEADER = "Idempotency-Key"
        const val REPLAYED_HEADER = "Idempotency-Replayed"

        /** Doc 06 §1. */
        private val TTL = Duration.ofHours(24)
        private const val RECORD_ID_ATTRIBUTE = "com.moyi.common.web.idempotency.recordId"
    }
}

/**
 * Wraps every request so its body can be read twice: once by
 * [IdempotencyInterceptor], to hash it and reserve a row before the handler
 * runs, and once by the handler's own `@RequestBody`. Also wraps the
 * response in a [ContentCachingResponseWrapper], so `afterCompletion` can
 * read back what the handler wrote.
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
 * **Only applies to `POST`/`PUT`/`PATCH`, and never to a multipart body**
 * (review round 1, Important #3). A bare `Filter` bean is registered by
 * Spring Boot for every path, in every module that depends on
 * `common:web` — and draining every body eagerly and buffering every
 * response is wrong for a `GET` (idempotency is meaningless on a method
 * HTTP already defines as idempotent, so `@Idempotent` is never put on one)
 * and wasteful for `actuator`/the OpenAPI document. Scoped by method and
 * content type instead of a `FilterRegistrationBean` with a URL allowlist:
 * `common:web` cannot know in advance which path in which module ends up
 * `@Idempotent`, so a path-based allowlist here would have to be guessed and
 * kept in sync by hand; a rule about *what kind of request this filter's
 * work could ever be needed for* does not.
 */
class IdempotencyRequestCachingFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val isMultipart = request.contentType?.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE) == true
        return runCatching { HttpMethod.valueOf(request.method) }.getOrNull() !in APPLICABLE_METHODS || isMultipart
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val cachedResponse = ContentCachingResponseWrapper(response)
        try {
            filterChain.doFilter(ReplayableHttpServletRequest(request), cachedResponse)
        } finally {
            cachedResponse.copyBodyToResponse()
        }
    }

    private companion object {
        val APPLICABLE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
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

/** 422: the same key was already sent against a different endpoint, or with a body that hashes differently. */
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
