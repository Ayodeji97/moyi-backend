package com.moyi.common.web.idempotency

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.FieldViolation
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
import org.springframework.web.util.WebUtils
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
 * refuses a missing or malformed key (`422`), resolves the verified caller (`401` if
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
 * which an ordinary `HttpServletRequest` does not support, so
 * [IdempotencyRequestCachingFilter] wraps the request and [preHandle] asks
 * that wrapper to buffer the body — **here, and only for an `@Idempotent`
 * handler**, bounded by [MAX_BODY_BYTES].
 *
 * **What a request the body cannot be taken from is answered with** (A5,
 * whole-branch review — every one of these used to be a `500`, because an
 * unwrapped request was treated as a wiring bug whatever the reason):
 *
 * - **No declared length — `Transfer-Encoding: chunked`** — works. It is what
 *   a streaming client sends when it does not know the size up front, and the
 *   KMP client's engines do. The body is read up to the bound and no further.
 * - **Longer than [MAX_BODY_BYTES]** — `413`, whether the length was declared
 *   (refused unread) or discovered while reading a chunked body.
 * - **Multipart** — not wrapped, not prepared; the handler's `@RequestBody`
 *   has no converter for it and Spring answers `415` itself.
 * - **The filter never ran at all** — the one case that *is* a wiring bug,
 *   and still fails loudly: [IdempotencyRequestCachingFilter] marks every
 *   request it sees, so its absence is told apart from its declining.
 *
 * A handler whose request never passed through this interceptor — the import
 * forgotten — fails loudly in [requestOf] rather than running unprotected.
 */
class IdempotencyInterceptor(
    private val fingerprint: RequestFingerprint,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val isIdempotent = (handler as? HandlerMethod)?.getMethodAnnotation(Idempotent::class.java) != null
        // `null` for a request the filter declined — a multipart body. Nothing
        // is prepared for it; the handler's @RequestBody cannot read
        // multipart, and Spring's own 415 is the answer.
        val replayable = if (isIdempotent) replayableOf(request) else null
        if (replayable != null) prepare(replayable, request)
        return true
    }

    /**
     * The wrapper [IdempotencyRequestCachingFilter] put on [request], found
     * through any wrapper another filter put around it since (Spring
     * Security's, for one) rather than by the request's own type. `null`
     * when the filter saw the request and declined it; a loud failure when
     * the filter never saw it at all, which is the wiring bug.
     */
    private fun replayableOf(request: HttpServletRequest): ReplayableHttpServletRequest? {
        val replayable = WebUtils.getNativeRequest(request, ReplayableHttpServletRequest::class.java)
        check(replayable != null || request.getAttribute(IdempotencyRequestCachingFilter.SEEN_ATTRIBUTE) != null) {
            "@Idempotent on ${request.method} ${request.requestURI}, but IdempotencyRequestCachingFilter is not " +
                "registered for this request. Without it the body can be read only once, and the handler's own " +
                "@RequestBody would read an exhausted stream. Register the filter wherever this interceptor is wired."
        }
        return replayable
    }

    private fun prepare(
        replayable: ReplayableHttpServletRequest,
        request: HttpServletRequest,
    ) {
        val key = request.getHeader(HEADER)?.trim().orEmpty()
        violationOf(key)?.let { throw IdempotencyKeyNotValidException(it) }

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
                // After the caller is known (`userId` above is evaluated first):
                // an unauthenticated request's body is never read. 413 from here.
                fingerprint = fingerprint.of(method, path, replayable.buffered(MAX_BODY_BYTES)),
            ),
        )
    }

    /**
     * What is wrong with [key] as an `Idempotency-Key`, or `null` (A6): it
     * must be 1 to [MAX_KEY_LENGTH] characters of visible ASCII (`!` to `~`).
     * V11's `idempotency_keys_key_check` states the same bound for the row.
     * A UUID or a ULID is well inside it. The codes are the ones Bean
     * Validation's constraints of the same meaning produce, so a client sees
     * one vocabulary.
     */
    private fun violationOf(key: String): FieldViolation? =
        when {
            key.isEmpty() -> FieldViolation(HEADER, "NOT_BLANK", "is required")
            key.length > MAX_KEY_LENGTH -> FieldViolation(HEADER, "SIZE", "must be at most $MAX_KEY_LENGTH characters")
            !key.all { it in VISIBLE_ASCII } -> FieldViolation(HEADER, "PATTERN", "must contain only visible ASCII characters")
            else -> null
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

        /** The longest an `Idempotency-Key` may be — V11's `idempotency_keys_key_check`, and the contract's `maxLength`. */
        const val MAX_KEY_LENGTH = 255

        /** `!` (0x21) to `~` (0x7E): printable, no space, nothing a log line or a header could mangle. */
        private val VISIBLE_ASCII = '!'..'~'

        private const val REQUEST_ATTRIBUTE = "com.moyi.common.web.idempotency.request"

        /**
         * 1 MiB: the most an `@Idempotent` request's body may be. Every body
         * such a route takes today is small — `EntryText.MAX_OCTETS` bounds
         * an entry's text at 8,192 octets (FR-041) — so this is generous
         * headroom, not a tuned limit. Past it the request is `413`. Raise
         * it only alongside a concrete need for a larger `@Idempotent` body.
         */
        const val MAX_BODY_BYTES = 1024 * 1024

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
 * Makes a request's body readable twice: once by [IdempotencyInterceptor], to
 * fingerprint it before the handler runs, and once by the handler's own
 * `@RequestBody`. The response is not wrapped: nothing reads it back since
 * the record moved into the handler's transaction ([IdempotentExecution]).
 *
 * **This filter reads nothing.** It only puts a [ReplayableHttpServletRequest]
 * around the request. The body is buffered later, by [IdempotencyInterceptor]
 * — once handler mapping has said the route is `@Idempotent`, once the caller
 * is known, and never past [IdempotencyInterceptor.MAX_BODY_BYTES]. Until
 * then, and on every route that is not `@Idempotent`, the wrapper passes the
 * container's own stream straight through.
 *
 * It used to buffer eagerly, here: every `POST`/`PUT`/`PATCH` body, on every
 * path, before anything knew whether the route needed it. That had to be
 * bounded by the declared `Content-Length` (whole-branch review, F3: this
 * filter runs on public paths too, and an unauthenticated multi-megabyte
 * `POST /auth/login` was read into memory whole) — and the bound in turn
 * refused every request with *no* declared length, which is what a chunked
 * body is. Those then reached an `@Idempotent` handler unwrapped and were a
 * `500` (A5). Buffering where the need is known removes both problems: a
 * route that is not `@Idempotent` is never buffered at all, and one that is
 * can take a chunked body, read no further than the bound.
 *
 * **Not `org.springframework.web.util.ContentCachingRequestWrapper`.** That
 * class only *records* what something else reads through it — its own
 * `getInputStream()` is created once and memoised, so a second reader (the
 * handler, after the interceptor read to hash the body) gets an exhausted
 * stream and an empty body, which breaks `@RequestBody` on every
 * `@Idempotent` endpoint. [ReplayableHttpServletRequest] serves every
 * [HttpServletRequest.getInputStream] call after buffering from its own
 * in-memory copy — genuinely repeatable, not merely recorded.
 *
 * **Wraps `POST`/`PUT`/`PATCH`, never a multipart body.** Scoped by what kind
 * of request this could ever be needed for, not by a URL allowlist:
 * `common:web` cannot know which path in which module ends up `@Idempotent`.
 * A `GET` is not wrapped (HTTP already defines it idempotent, so
 * `@Idempotent` is never put on one). A multipart body is not wrapped
 * because it is not read through the input stream by anything this
 * interceptor could fingerprint — see [IdempotencyInterceptor] for what an
 * `@Idempotent` route answers one with.
 *
 * **Every request it sees is marked** ([SEEN_ATTRIBUTE]), wrapped or not, so
 * the interceptor can tell "declined" from "not registered" — only the
 * second is a wiring bug.
 *
 * **Where it runs.** A plain `Filter` bean, registered by Spring Boot at the
 * lowest precedence — so *after* the Spring Security chain (order `-100`)
 * in the real application, not before it as this KDoc once said. Nothing
 * here depends on which: the interceptor finds the wrapper through whatever
 * else wraps the request.
 */
class IdempotencyRequestCachingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        request.setAttribute(SEEN_ATTRIBUTE, true)
        filterChain.doFilter(if (wraps(request)) ReplayableHttpServletRequest(request) else request, response)
    }

    /** Whether [request] is one whose body an `@Idempotent` handler could need twice — a pure function of method and type. */
    fun wraps(request: HttpServletRequest): Boolean {
        val isMultipart = request.contentType?.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE) == true
        return runCatching { HttpMethod.valueOf(request.method) }.getOrNull() in APPLICABLE_METHODS && !isMultipart
    }

    companion object {
        /** Set on every request this filter has seen, wrapped or not. */
        const val SEEN_ATTRIBUTE = "com.moyi.common.web.idempotency.filter-seen"

        private val APPLICABLE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
    }
}

/**
 * A request whose body can be read again **once [buffered] has been called**;
 * until then it is the request it wraps, stream and all.
 */
internal class ReplayableHttpServletRequest(
    request: HttpServletRequest,
) : HttpServletRequestWrapper(request) {
    private var body: ByteArray? = null

    /**
     * The whole body, read once and kept, or [RequestBodyTooLargeException]
     * if it is longer than [maxBytes].
     *
     * A declared length over the bound is refused without reading a byte. An
     * undeclared one (chunked) is read to at most one byte past the bound —
     * that byte is how "too long" is known — so what this holds in memory is
     * bounded whatever the client sends and however it frames it.
     */
    fun buffered(maxBytes: Int): ByteArray {
        body?.let { return it }
        if (request.contentLengthLong > maxBytes) throw RequestBodyTooLargeException()
        val read = request.inputStream.readNBytes(maxBytes + 1)
        if (read.size > maxBytes) throw RequestBodyTooLargeException()
        body = read
        return read
    }

    override fun getInputStream(): ServletInputStream = body?.let(::ReplayableServletInputStream) ?: super.getInputStream()

    override fun getReader(): BufferedReader =
        body?.let { BufferedReader(InputStreamReader(ByteArrayInputStream(it), Charset.forName(characterEncoding ?: "UTF-8"))) }
            ?: super.getReader()
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

/**
 * 422: doc 06 §1 requires `Idempotency-Key` on this endpoint, and it was not
 * sent, or what was sent is not a key — longer than 255 characters, or not
 * visible ASCII (A6). `VALIDATION_FAILED`, with the `errors` entry that code
 * is always accompanied by: [violation] names the header, never its value.
 */
class IdempotencyKeyNotValidException(
    violation: FieldViolation,
) : ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ErrorCode.VALIDATION_FAILED,
        "The Idempotency-Key header is missing or not valid.",
        errors = listOf(violation),
    )

/**
 * 413: an `@Idempotent` request's body is longer than
 * [IdempotencyInterceptor.MAX_BODY_BYTES]. `MALFORMED_REQUEST`, the code
 * every other body this application cannot take in carries: a client has
 * nothing more specific to do with it than not to send it.
 */
class RequestBodyTooLargeException :
    ApiException(
        HttpStatus.CONTENT_TOO_LARGE,
        ErrorCode.MALFORMED_REQUEST,
        "The request body is too large.",
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
