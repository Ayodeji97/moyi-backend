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
     * refused: the twice-yearly rebuild is what keeps it fresh, so staleness
     * here means that job has been failing, and taking production down for a
     * *documentation* trigger would be a worse outcome than the staleness.
     *
     * **A year, not six months, deliberately.** It is tempting to tie this to
     * the rebuild cadence so the warning fires the moment one run is missed.
     * It stays at ADR-0012's number instead, because a threshold pinned to the
     * schedule has to be edited every time the schedule moves — and a warning
     * that has been re-tuned twice is a warning nobody reads. One whole missed
     * run of slack is the cost, and it buys a number that means something on
     * its own.
     */
    @field:Min(1)
    val warnAfterDays: Long = DEFAULT_WARN_AFTER_DAYS,
    /**
     * The fewest members a file may hold and still be treated as a corpus.
     *
     * **This is the layer that survives a mistake upstream.** The corpus
     * builder supports a range limit for smoke tests, and a range-limited run
     * produces a file that is valid in every observable respect — right magic,
     * right format, plausible digest — while covering a fraction of the
     * 1,048,576 prefix ranges and therefore waving through nearly every
     * breached password. The workflow refuses to publish one, but a guard that
     * only lives in the workflow is bypassed by editing the workflow. This one
     * lives next to the thing being protected.
     *
     * A million separates the two cases by three orders of magnitude in both
     * directions: a real corpus holds ~10.5M (ADR-0016), and a few hundred
     * ranges hold a few thousand. It also catches a *full* run that went wrong
     * — a parser change that silently dropped most entries would land here
     * too, which no check on the build inputs could see.
     */
    @field:Min(1)
    val minimumDigests: Long = DEFAULT_MINIMUM_DIGESTS,
) {
    private companion object {
        const val DEFAULT_RESOURCE = "classpath:security/pwned-passwords.bloom"
        const val DEFAULT_WARN_AFTER_DAYS = 365L

        /**
         * An order of magnitude below the ~10.5M a real corpus holds, and three
         * above what a smoke build produces. Deliberately not tuned close to
         * the real figure: this is a sanity bound, and one that tracked the
         * corpus size would need re-tuning every time the threshold moved.
         */
        const val DEFAULT_MINIMUM_DIGESTS = 1_000_000L
    }
}
