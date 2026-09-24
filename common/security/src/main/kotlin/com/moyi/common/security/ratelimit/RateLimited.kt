package com.moyi.common.security.ratelimit

/**
 * Marks a handler method as consuming one token from a per-address bucket
 * on every call. Read by [RateLimitInterceptor] before the handler runs.
 *
 * Only a bucket whose subject is [RateLimitBucket.Subject.IP] may be named
 * here — a per-email bucket needs the body, which the interceptor does not
 * have, and is consumed by the service instead; the per-user bucket applies
 * to every authenticated request without being asked. Naming the wrong kind
 * is refused at the first request with an `IllegalStateException`, which the
 * endpoint's own test turns into a build failure.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimited(
    val bucket: RateLimitBucket,
)
