package com.moyi.admin.infra

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP, SHA-1, 6 digits, 30 s (what every authenticator app speaks).
 * java-otp makes the codes; the window, the step number and the constant-time
 * compare are ours. [matchingStep] returns the step that matched so the caller
 * can refuse to accept the same step twice (replay).
 */
internal class Totp(
    private val clock: Clock,
) {
    private val generator = TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(PERIOD_SECONDS), DIGITS)
    private val random = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun matchingStep(
        secret: ByteArray,
        code: String,
    ): Long? {
        if (code.length != DIGITS || !code.all { it in '0'..'9' }) return null
        val now = clock.instant().epochSecond / PERIOD_SECONDS
        val key = SecretKeySpec(secret, ALGORITHM)
        val given = code.toByteArray(Charsets.US_ASCII)
        var matched: Long? = null
        // Every candidate is compared, so timing does not reveal which step (if any) hit.
        for (step in now - 1..now + 1) {
            val expected = generator.generateOneTimePasswordString(key, Instant.ofEpochSecond(step * PERIOD_SECONDS))
            if (MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), given)) matched = step
        }
        return matched
    }

    fun otpauthUri(
        secret: ByteArray,
        account: String,
        issuer: String,
    ): String {
        val label = "${encode(issuer)}:${encode(account)}"
        return "otpauth://totp/$label?secret=${base32(secret)}&issuer=${encode(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl BYTE_BITS) or (b.toInt() and BYTE_MASK)
            bits += BYTE_BITS
            while (bits >= BASE32_BITS) {
                out.append(BASE32[(buffer shr (bits - BASE32_BITS)) and BASE32_MASK])
                bits -= BASE32_BITS
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(BASE32[(buffer shl (BASE32_BITS - bits)) and BASE32_MASK])
        return out.toString()
    }

    private companion object {
        const val PERIOD_SECONDS = 30L
        const val DIGITS = 6
        const val SECRET_BYTES = 20
        const val ALGORITHM = "HmacSHA1"
        const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFF
        const val BASE32_BITS = 5
        const val BASE32_MASK = 0x1F
    }
}
