package com.moyi.identity.domain

import java.time.Instant
import java.util.UUID

/**
 * What a person agreed to, when, and to which version of it (FR-011).
 *
 * The 18+ confirmation is a consent row like the other two rather than a
 * boolean column on `users`. FR-011 settled that: four documents required
 * the confirmation to be "recorded at registration" and no storage for it
 * existed anywhere, and modelling it as a row means it needs no new column
 * **and** appears in the data export (FR-009) for free, which a column would
 * not have done without someone remembering to add it.
 */
internal data class ConsentRecord(
    val id: UUID,
    val userId: UserId,
    val document: ConsentDocument,
    val version: String,
    val acceptedAt: Instant,
    /**
     * Corroborating evidence, and **null until the trusted-proxy resolver
     * exists**. Behind a reverse proxy the client address is whatever
     * `X-Forwarded-For` says, which an attacker sets freely unless the proxy
     * is trusted and the header validated. Recording an unvalidated value
     * would produce an audit trail that looks like evidence and is not —
     * worse than an empty column. The resolver arrives with per-IP rate
     * limiting (FR-012), which needs exactly the same thing.
     */
    val ipHash: String?,
    val userAgentHash: String?,
) {
    init {
        require(version.isNotBlank()) { "consent version must not be blank" }
    }
}

/** The documents a person consents to at registration. */
internal enum class ConsentDocument {
    TERMS,
    PRIVACY,

    /** FR-011's 18+ self-declaration. Whether self-declaration suffices is OQ-20, still open. */
    AGE_18,
}
