package com.moyi.common.security.ratelimit

/**
 * Marks a handler method as consuming one token from each named bucket on
 * every call. Read by [RateLimitInterceptor] before the handler runs.
 *
 * **Several buckets are allowed, and one endpoint genuinely needs them.**
 * `GET /invites/{code}` carries a per-user allowance above the per-IP bucket
 * it shares with `POST /invites/{code}/accept` (doc 06 §4, ADR-0027): the
 * per-IP one is the T-06 brute-force bound, and the per-user one stops a
 * single signed-in account spending it. Both are consumed; the headers
 * describe whichever has fewest tokens left, which is the one the caller will
 * hit first.
 *
 * **What may be named here, and what may not.** A bucket whose subject is
 * [RateLimitBucket.Subject.IP] or [RateLimitBucket.Subject.USER] — both are
 * known before the handler runs, from the socket and from the verified token.
 * A [RateLimitBucket.Subject.EMAIL] bucket may not: the address is in the
 * request body, which the interceptor has not read, and those are consumed by
 * the service that does read it. Naming one is refused at the first request
 * with an `IllegalStateException`, so a wiring mistake is a failure rather
 * than a limit that silently never applies — the endpoint's own test turns
 * that into a build failure.
 *
 * The per-user `authenticated:user` bucket is not named here by anyone: it
 * applies to every authenticated request without being asked.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimited(
    vararg val buckets: RateLimitBucket,
)
