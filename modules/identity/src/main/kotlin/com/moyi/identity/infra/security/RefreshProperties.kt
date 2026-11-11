package com.moyi.identity.infra.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `moyi.identity.refresh.grace-window`: how long after a refresh token is
 * rotated a retry of it is still answered with a new pair rather than treated
 * as a reuse (ADR-0021, amended 2026-11-08 for issue #136). Thirty seconds,
 * by the owner's ruling, and this class is the one place that number is
 * written.
 *
 * `0` turns the window off, which is the strict rotation of ADR-0021's first
 * version. The upper bound is a guard against a typo, not a product limit:
 * the window is how long a thief who copied a just-rotated token can replay it
 * without an immediate alarm, so a value of minutes would be a different
 * decision, and would need a different ADR rather than an environment variable.
 * Checked at binding, so a value outside it is a failed start, naming the
 * property.
 */
@ConfigurationProperties(prefix = "moyi.identity.refresh")
internal class RefreshProperties(
    graceWindow: Duration?,
) {
    val graceWindow: Duration =
        (graceWindow ?: DEFAULT_GRACE_WINDOW).also {
            require(!it.isNegative && it <= MAX_GRACE_WINDOW) {
                "moyi.identity.refresh.grace-window must be between 0s and $MAX_GRACE_WINDOW, was $it"
            }
        }

    companion object {
        val DEFAULT_GRACE_WINDOW: Duration = Duration.ofSeconds(30)
        val MAX_GRACE_WINDOW: Duration = Duration.ofMinutes(2)
    }
}
