package com.moyi.common.security.ratelimit

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import org.springframework.http.HttpStatus
import java.time.Duration

/**
 * A bucket was empty. Thrown by the interceptor for a per-address or
 * per-user bucket and by a service for a per-email one; either way
 * `SecurityExceptionHandler` turns it into the 429 doc 06 §2 specifies.
 *
 * The detail follows `states.md` §1b — "States when to try again. Never an
 * accusation" — so it names the wait and nothing else: not the bucket, not
 * the count, not the word "attempts".
 */
class RateLimitExceededException(
    val decision: RateLimitDecision.Rejected,
) : ApiException(
        status = HttpStatus.TOO_MANY_REQUESTS,
        errorCode = ErrorCode.RATE_LIMITED,
        detail = "Please wait ${describe(decision.retryAfter)} before trying again.",
    ) {
    /** RFC 9110 §10.2.3: `Retry-After` in whole seconds. Rounded up, so it is never a zero that means "now". */
    val retryAfterSeconds: Long = wholeSecondsUp(decision.retryAfter)

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val MINUTES_PER_HOUR = 60L

        fun wholeSecondsUp(wait: Duration): Long = (wait.toNanos() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND

        const val NANOS_PER_SECOND = 1_000_000_000L

        fun describe(wait: Duration): String {
            val seconds = wholeSecondsUp(wait)
            val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
            val hours = (minutes + MINUTES_PER_HOUR - 1) / MINUTES_PER_HOUR
            return when {
                seconds <= SECONDS_PER_MINUTE -> "a minute"
                minutes < MINUTES_PER_HOUR -> "$minutes minutes"
                hours == 1L -> "an hour"
                else -> "$hours hours"
            }
        }
    }
}
