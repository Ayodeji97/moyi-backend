package com.moyi.common.security.ratelimit

import com.moyi.common.security.ClientAddressResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Consumes the buckets that can be decided before the controller runs —
 * the per-user one for every authenticated request, and the per-address one
 * a handler asks for with [RateLimited] — and writes the `X-RateLimit-*`
 * headers doc 06 §4 promises on every limited response.
 *
 * **Before the handler, on purpose.** The headers go on in `preHandle`,
 * because by `afterCompletion` a `ResponseEntity` has been written and the
 * response is committed; a header added then is a header nobody receives.
 * An empty bucket is a thrown [RateLimitExceededException], which MVC routes
 * through the same `@RestControllerAdvice` chain as everything else, so the
 * 429 has the problem shape without a second writer.
 *
 * When two buckets apply, the headers describe the one with fewer tokens
 * left — the one the client will hit first. When Redis could not answer,
 * there are no headers: better none than invented ones.
 *
 * A [HandlerInterceptor] rather than a filter because the annotation is on
 * the handler method, and only an interceptor is handed the [HandlerMethod].
 * Chirp does the same, and then ends the request with `sendError(429)`,
 * which loses the `Retry-After` FR-012 requires; throwing keeps it.
 */
@Component
class RateLimitInterceptor(
    private val limiter: RateLimiter,
    private val addresses: ClientAddressResolver,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val method = handler as? HandlerMethod ?: return true

        val caller = authenticatedUserId()
        val global = caller?.let { userId -> consume(RateLimitBucket.AUTHENTICATED, userId) }
        val named =
            method.getMethodAnnotation(RateLimited::class.java)?.buckets.orEmpty().map { bucket ->
                consume(bucket, keyFor(bucket, method, request, caller))
            }

        (listOfNotNull(global) + named)
            .filterIsInstance<RateLimitDecision.Allowed>()
            .minByOrNull { it.remaining }
            ?.let { response.writeRateLimit(it.limit, it.remaining, it.resetAt.epochSecond) }
        return true
    }

    /**
     * What this bucket is keyed on, and the refusal for the one kind that
     * cannot be. The `check`s fail the request rather than skipping the limit:
     * a bucket that quietly does not apply is worse than a 500, because
     * nothing ever notices.
     */
    private fun keyFor(
        bucket: RateLimitBucket,
        method: HandlerMethod,
        request: HttpServletRequest,
        caller: String?,
    ): String =
        when (bucket.subject) {
            RateLimitBucket.Subject.IP -> {
                addresses.resolve(request).rateLimitKey
            }

            RateLimitBucket.Subject.USER -> {
                checkNotNull(caller) {
                    "@RateLimited on ${method.shortLogMessage} names $bucket, which is keyed on the caller, " +
                        "but the request carries no verified token. A per-user bucket belongs on an authenticated endpoint."
                }
            }

            RateLimitBucket.Subject.EMAIL -> {
                error(
                    "@RateLimited on ${method.shortLogMessage} names $bucket, whose subject is an email address. " +
                        "That is in the request body, which this interceptor has not read; a per-email bucket is " +
                        "consumed by the service that reads it.",
                )
            }
        }

    private fun consume(
        bucket: RateLimitBucket,
        key: String,
    ): RateLimitDecision {
        val decision = limiter.tryConsume(bucket, key)
        if (decision is RateLimitDecision.Rejected) throw RateLimitExceededException(decision)
        return decision
    }

    /** The subject of a verified bearer token, or `null` — an anonymous principal is a `String`, not a [Jwt]. */
    private fun authenticatedUserId(): String? = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject
}

/** The header names doc 06 §4 fixes; `X-RateLimit-Reset` is Unix seconds, GitHub's convention. */
object RateLimitHeaders {
    const val LIMIT = "X-RateLimit-Limit"
    const val REMAINING = "X-RateLimit-Remaining"
    const val RESET = "X-RateLimit-Reset"
}

fun HttpServletResponse.writeRateLimit(
    limit: Long,
    remaining: Long,
    resetEpochSeconds: Long,
) {
    setHeader(RateLimitHeaders.LIMIT, limit.toString())
    setHeader(RateLimitHeaders.REMAINING, remaining.toString())
    setHeader(RateLimitHeaders.RESET, resetEpochSeconds.toString())
}

fun HttpHeaders.writeRateLimit(exceeded: RateLimitExceededException) {
    set(HttpHeaders.RETRY_AFTER, exceeded.retryAfterSeconds.toString())
    set(RateLimitHeaders.LIMIT, exceeded.decision.limit.toString())
    set(RateLimitHeaders.REMAINING, "0")
    set(
        RateLimitHeaders.RESET,
        exceeded.decision.resetAt.epochSecond
            .toString(),
    )
}
