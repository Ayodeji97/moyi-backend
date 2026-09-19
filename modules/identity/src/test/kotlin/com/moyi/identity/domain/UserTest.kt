package com.moyi.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class UserTest {
    @Test
    fun `a blank display name is rejected`() {
        shouldThrow<IllegalArgumentException> { user(displayName = "   ") }
    }

    @Test
    fun `a display name longer than the column is rejected`() {
        // The database CHECK in V2 carries the same number. If these two ever
        // disagree, the domain accepts a value the insert then rejects — a
        // 500 on a path that should have returned a validation error.
        shouldThrow<IllegalArgumentException> { user(displayName = "a".repeat(User.MAX_DISPLAY_NAME_LENGTH + 1)) }
        user(displayName = "a".repeat(User.MAX_DISPLAY_NAME_LENGTH)).displayName.length shouldBe
            User.MAX_DISPLAY_NAME_LENGTH
    }

    @Test
    fun `a deleted user must carry the time it was deleted`() {
        shouldThrow<IllegalArgumentException> { user(status = UserStatus.DELETED, deletedAt = null) }
        user(status = UserStatus.DELETED, deletedAt = NOW).status shouldBe UserStatus.DELETED
    }

    @Test
    fun `copy re-runs the invariants`() {
        // The reason a data class is safe here: `copy()` goes through the
        // constructor, so an invariant cannot be bypassed by copying around it.
        shouldThrow<IllegalArgumentException> { user().copy(displayName = "") }
    }

    @Test
    fun `email verification is a time, not a flag`() {
        user(emailVerifiedAt = null).isEmailVerified shouldBe false
        user(emailVerifiedAt = NOW).isEmailVerified shouldBe true
    }

    @Test
    fun `an email must have a local part, an at sign and a dotted domain`() {
        listOf("", "ada", "ada@example", "ada example@test.com", "a@b.c ", "@example.com", "ada@@example.com")
            .forEach { candidate ->
                shouldThrow<IllegalArgumentException> { Email(candidate) }
            }
    }

    @Test
    fun `an email that is plausibly deliverable is accepted, including the awkward ones`() {
        // Deliberately permissive: plus-addressing, subdomains and long TLDs
        // are all real, and rejecting them is a support ticket from a user who
        // is right and cannot sign up.
        listOf("ada@example.com", "ada+moyi@example.co.uk", "a.b-c_d@mail.sub.example.technology")
            .forEach { candidate -> Email(candidate).value shouldBe candidate }
    }

    @Test
    fun `an email longer than RFC 5321 allows is rejected`() {
        val tooLong = "a".repeat(250) + "@example.com"
        shouldThrow<IllegalArgumentException> { Email(tooLong) }
    }

    @Test
    fun `a rejected email is not echoed in the failure message`() {
        // Doc 18 §5: the failure must not echo input. Exception messages reach
        // logs, and an address is personal data even when it is malformed.
        val failure = shouldThrow<IllegalArgumentException> { Email("not-an-address") }

        failure.message.orEmpty() shouldNotContain "not-an-address"
    }

    @Test
    fun `case is preserved, because the database is what compares them`() {
        // Lower-casing here would throw away what the user typed *and* leave
        // the uniqueness question to application code. citext owns it.
        Email("Ada.Lovelace@Example.COM").value shouldBe "Ada.Lovelace@Example.COM"
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T10:15:30Z")

        fun user(
            displayName: String = "Ada",
            status: UserStatus = UserStatus.ACTIVE,
            emailVerifiedAt: Instant? = NOW,
            deletedAt: Instant? = null,
        ) = User(
            id = UserId(UUID.fromString("00000000-0000-7000-8000-000000000001")),
            email = Email("ada@example.com"),
            emailVerifiedAt = emailVerifiedAt,
            displayName = displayName,
            avatarMediaId = null,
            locale = "en",
            status = status,
            createdAt = NOW,
            updatedAt = null,
            deletedAt = deletedAt,
        )
    }
}
