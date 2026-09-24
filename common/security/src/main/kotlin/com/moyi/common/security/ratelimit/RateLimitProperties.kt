package com.moyi.common.security.ratelimit

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * The limiter's operational knobs. The buckets themselves are not here —
 * they are doc 06 §4's table and live in [RateLimitBucket], because a limit
 * that can be quietly loosened in an environment file is not the limit the
 * threat model was written against.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.security.rate-limit")
data class RateLimitProperties(
    /**
     * `false` replaces the limiter with one that allows everything, and says
     * so at WARN on every start. It exists for a test context whose subject
     * is something else, and as an operator's lever if the limiter itself
     * misbehaves; it is not a deployment option.
     */
    val enabled: Boolean = true,
    /**
     * How long one Redis round-trip may take before the limiter gives up and
     * lets the request through. Short on purpose: this sits in front of every
     * login, and a slow Redis must degrade to "no limit", never to "slow login".
     */
    @field:NotNull
    val requestTimeout: Duration = Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MILLIS),
    /**
     * After a failure, how long the limiter stops trying Redis and answers
     * "unavailable" at once, so an outage costs one timeout per interval
     * rather than one per request.
     */
    @field:NotNull
    val retryInterval: Duration = Duration.ofSeconds(DEFAULT_RETRY_INTERVAL_SECONDS),
) {
    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 250L
        const val DEFAULT_RETRY_INTERVAL_SECONDS = 5L
    }
}
