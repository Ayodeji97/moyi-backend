package com.moyi.identity.api

import java.time.Instant
import java.util.UUID

/**
 * What identity holds about a person, for their own data export (FR-009, ADR-0059): their profile
 * and the consents they gave (FR-011, ADR-0056). The one place outside identity an address is
 * handed over, and only for the person it belongs to, to put in an archive only they can fetch.
 */
interface AccountExports {
    /** `null` for an id that names nobody, or an account that has been deleted. */
    fun accountOf(userId: UUID): ExportedAccount?
}

/** A person's profile. [toString] prints the id only: the rest is personal data. */
@Suppress("LongParameterList") // The profile's fields, each named where it is built.
class ExportedAccount(
    val id: UUID,
    val email: String,
    val emailVerifiedAt: Instant?,
    val displayName: String,
    val locale: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val consents: List<ExportedConsent>,
) {
    override fun toString(): String = "ExportedAccount(id=$id)"
}

/**
 * One consent the person gave: which document (`TERMS`, `PRIVACY`, `AGE_18`), which version, when.
 * The keyed hashes of the address and user agent it was given from are not carried: they identify
 * nothing to the person, and are a lookup key to nobody else.
 */
class ExportedConsent(
    val document: String,
    val version: String,
    val acceptedAt: Instant,
)
