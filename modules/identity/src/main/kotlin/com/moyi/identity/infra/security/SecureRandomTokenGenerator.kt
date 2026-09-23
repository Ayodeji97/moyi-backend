package com.moyi.identity.infra.security

import com.moyi.identity.domain.TokenGenerator
import com.moyi.identity.domain.VerificationSecret
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * 256 bits from [SecureRandom], base64url without padding: 43 characters
 * from an alphabet that survives a URL query string, an email client's
 * link-wrapping and a copy-paste without escaping.
 *
 * 256 bits is doc 09 §3's figure for refresh tokens and there is no reason
 * to be weaker here: at that size the space cannot be enumerated, which is
 * what lets the stored hash be unsalted (see `VerificationSecret.hash`) and
 * what makes "not recognised" a safe answer to a guess.
 *
 * One `SecureRandom` for the bean's lifetime. It is thread-safe, and
 * constructing one per call would re-seed from the OS each time, which is
 * slower and buys nothing.
 */
@Component
internal class SecureRandomTokenGenerator : TokenGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun verificationSecret(): VerificationSecret {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return VerificationSecret(encoder.encodeToString(bytes))
    }

    internal companion object {
        const val SECRET_BYTES = 32

        /** 32 bytes in base64 is 44 characters with padding, 43 without. */
        const val ENCODED_LENGTH = 43
    }
}
