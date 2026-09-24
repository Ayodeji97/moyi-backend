package com.moyi.common.security

import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 over a configured secret, for personal identifiers that have to
 * be stored or used as a key without being recoverable from what is stored:
 * an address in `consent_records.ip_hash`, a `User-Agent`, an email in a
 * Redis rate-limit key (doc 07 §5, NFR-044).
 *
 * **Keyed, not plain.** A plain SHA-256 of an IPv4 address is a lookup table
 * away from the address: there are 2^32 of them and hashing all of them
 * takes minutes. With a secret in the MAC, the table cannot be built without
 * it, which turns "we store hashes" from a courtesy into a control.
 *
 * Output is base64url without padding — forty-three characters, safe in a
 * text column and in a Redis key. A [Mac] is not thread-safe and is
 * initialised per call; it costs microseconds.
 */
class PersonalDataHasher private constructor(
    private val key: SecretKeySpec,
) {
    fun hash(value: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private val log = LoggerFactory.getLogger(PersonalDataHasher::class.java)

        fun from(properties: HashingProperties): PersonalDataHasher =
            when (properties.secretSource) {
                HashingProperties.SecretSource.CONFIGURED -> configured(properties.secret)
                HashingProperties.SecretSource.EPHEMERAL -> ephemeral()
            }

        private fun configured(secret: String?): PersonalDataHasher {
            check(!secret.isNullOrBlank()) {
                "moyi.security.hashing.secret-source is CONFIGURED (the default) but moyi.security.hashing.secret is " +
                    "not set. Supply at least ${HashingProperties.MIN_SECRET_LENGTH} characters through the environment " +
                    "(`openssl rand -base64 48`), or set the source to EPHEMERAL for local development. Refusing to " +
                    "start rather than hash addresses with a secret nobody chose."
            }
            check(secret.length >= HashingProperties.MIN_SECRET_LENGTH) {
                "moyi.security.hashing.secret is ${secret.length} characters; at least " +
                    "${HashingProperties.MIN_SECRET_LENGTH} are required. A short secret is a short search."
            }
            return PersonalDataHasher(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        }

        private fun ephemeral(): PersonalDataHasher {
            val bytes = ByteArray(HashingProperties.MIN_SECRET_LENGTH).also(SecureRandom()::nextBytes)
            log.warn(
                "The personal-data hashing secret is EPHEMERAL: generated at startup, so address and user-agent " +
                    "hashes written by this process cannot be matched by the next one. Correct for local development; " +
                    "wrong anywhere else.",
            )
            return PersonalDataHasher(SecretKeySpec(bytes, ALGORITHM))
        }
    }
}
