package com.moyi.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class ConsentTest {
    @Test
    fun `a consent record without a version is meaningless and rejected`() {
        // "They agreed" is not a record. Which text they agreed to is the
        // whole evidentiary value, and a blank version is a row that looks
        // like evidence and proves nothing.
        shouldThrow<IllegalArgumentException> { consent(version = "  ") }
    }

    @Test
    fun `the 18+ confirmation is a document, not a flag`() {
        // FR-011: recording it as a row rather than a boolean column is what
        // puts it in the data export (FR-009) without anyone remembering to
        // add it there.
        ConsentDocument.entries shouldBe listOf(ConsentDocument.TERMS, ConsentDocument.PRIVACY, ConsentDocument.AGE_18)
    }

    @Test
    fun `evidence fields are absent rather than untrustworthy`() {
        // Null until there is a trusted-proxy resolver. An unvalidated
        // X-Forwarded-For is attacker-controlled, and a column full of
        // attacker-controlled values is worse than an empty one because it
        // reads as an audit trail.
        consent().ipHash shouldBe null
        consent().userAgentHash shouldBe null
    }

    private fun consent(version: String = "2026-09-01") =
        ConsentRecord(
            id = UUID.fromString("00000000-0000-7000-8000-000000000001"),
            userId = UserId(UUID.fromString("00000000-0000-7000-8000-000000000002")),
            document = ConsentDocument.TERMS,
            version = version,
            acceptedAt = Instant.parse("2026-09-19T10:15:30Z"),
            ipHash = null,
            userAgentHash = null,
        )
}
