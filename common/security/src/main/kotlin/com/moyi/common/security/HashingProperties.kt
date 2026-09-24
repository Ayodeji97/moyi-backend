package com.moyi.common.security

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * The secret behind [PersonalDataHasher] (ADR-0023).
 *
 * Same posture as the signing key (ADR-0019): [secretSource] defaults to
 * [SecretSource.CONFIGURED], which refuses to start without [secret] from the
 * environment, and [SecretSource.EPHEMERAL] has to be asked for by name.
 * The consequence of an ephemeral secret is milder than an ephemeral key —
 * `consent_records.ip_hash` values written by one process could not be
 * matched by the next, and rate-limit buckets restart empty — but it is
 * still a silent loss of an audit property that a deploy should not be able
 * to cause by forgetting a variable.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.security.hashing")
data class HashingProperties(
    @field:NotNull
    val secretSource: SecretSource = SecretSource.CONFIGURED,
    /** At least [MIN_SECRET_LENGTH] characters; `openssl rand -base64 48` is one way to make one. */
    val secret: String? = null,
) {
    enum class SecretSource {
        /** [secret] supplied. The only source production may use. */
        CONFIGURED,

        /** Thirty-two random bytes at startup, logged at WARN. For the `local` profile and test contexts only. */
        EPHEMERAL,
    }

    companion object {
        /** 256 bits of key material, the block that HMAC-SHA256 uses in full. */
        const val MIN_SECRET_LENGTH = 32
    }
}
