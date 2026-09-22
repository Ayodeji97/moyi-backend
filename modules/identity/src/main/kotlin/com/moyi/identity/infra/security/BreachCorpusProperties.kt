package com.moyi.identity.infra.security

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Where the breached-password corpus is and how old it is allowed to get.
 *
 * **There is deliberately no `enabled` flag.** The single strongest argument
 * in ADR-0012 for an offline filter over the HIBP range API is that it
 * *removes* the fail-open/fail-closed question — there is no outage to
 * degrade gracefully around. A toggle would put that question straight back,
 * and it would be answered in the worst possible place: a properties file on
 * whichever environment somebody was debugging. A service with no corpus does
 * not start. See [BloomFilterBreachedPasswordCorpus].
 */
@Validated
@ConfigurationProperties(prefix = "moyi.security.breach-corpus")
internal data class BreachCorpusProperties(
    /**
     * A Spring resource location. The default is the classpath entry the
     * Gradle build downloads, verifies by SHA-256 and packages — see
     * `modules/identity/build.gradle.kts` and ADR-0016.
     */
    @field:NotBlank
    val resource: String = DEFAULT_RESOURCE,
    /**
     * ADR-0012 revisits the corpus when it is "more than a year stale". A
     * corpus older than this is logged at WARN on every startup rather than
     * refused: the quarterly rebuild is what keeps it fresh, so staleness here
     * means that job has been failing, and taking production down for a
     * *documentation* trigger would be a worse outcome than the staleness.
     */
    @field:Min(1)
    val warnAfterDays: Long = DEFAULT_WARN_AFTER_DAYS,
) {
    private companion object {
        const val DEFAULT_RESOURCE = "classpath:security/pwned-passwords.bloom"
        const val DEFAULT_WARN_AFTER_DAYS = 365L
    }
}
