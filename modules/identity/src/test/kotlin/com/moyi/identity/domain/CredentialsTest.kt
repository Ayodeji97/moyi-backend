package com.moyi.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class CredentialsTest {
    @Test
    fun `a password hash never prints itself`() {
        // The failure this prevents is not exotic: one `log.debug("loaded {}",
        // credentials)` and the hash is in a log file forever (doc 18 §9).
        val hash = PasswordHash(ARGON2ID_HASH)

        "$hash" shouldBe "PasswordHash(redacted)"
        hash.toString() shouldNotContain "argon2id"
    }

    @Test
    fun `a credentials record does not print the hash either`() {
        val printed = credentials().toString()

        printed shouldNotContain ARGON2ID_HASH
        printed shouldNotContain "argon2id"
    }

    @Test
    fun `a blank hash is rejected`() {
        // An empty hash would make every password comparison fail closed,
        // which is safe, or fail open, which is not — depending entirely on
        // the encoder. Not storing it is the only answer that does not depend.
        shouldThrow<IllegalArgumentException> { PasswordHash("") }
        shouldThrow<IllegalArgumentException> { PasswordHash("   ") }
    }

    @Test
    fun `negative failed attempts are rejected`() {
        shouldThrow<IllegalArgumentException> { credentials(failedAttempts = -1) }
    }

    @Test
    fun `a lockout that has expired is not a lockout`() {
        val locked = credentials(lockedUntil = NOW.plusSeconds(60))
        val expired = credentials(lockedUntil = NOW.minusSeconds(1))

        locked.isLockedAt(NOW) shouldBe true
        // Exactly at the boundary the lock is over: `locked_until` is the
        // moment access returns, not the last moment it is denied.
        locked.isLockedAt(NOW.plusSeconds(60)) shouldBe false
        expired.isLockedAt(NOW) shouldBe false
        credentials(lockedUntil = null).isLockedAt(NOW) shouldBe false
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T10:15:30Z")
        const val ARGON2ID_HASH = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaA"

        fun credentials(
            failedAttempts: Int = 0,
            lockedUntil: Instant? = null,
        ) = Credentials(
            userId = UserId(UUID.fromString("00000000-0000-7000-8000-000000000001")),
            passwordHash = PasswordHash(ARGON2ID_HASH),
            algorithm = PasswordHashAlgorithm.ARGON2ID,
            passwordUpdatedAt = NOW,
            failedAttempts = failedAttempts,
            lockedUntil = lockedUntil,
        )
    }
}
