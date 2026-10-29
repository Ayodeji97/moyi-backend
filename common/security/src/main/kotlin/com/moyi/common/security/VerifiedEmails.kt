package com.moyi.common.security

import java.util.UUID

/**
 * Has this user confirmed their email address (FR-002, ADR-0018)?
 *
 * A port, defined here and implemented by `identity`, as [TokenRevocations] is,
 * for `media`: a leaf that may depend on no domain module (spec §2.1, ADR-0047),
 * and that refuses every photo and voice note to an unverified account (the
 * owner's ruling, 2026-10-29, ADR-0049). `bond` asks the same question through
 * `identity.api.UserDirectory`, which it may depend on; both are answered from
 * one rule, `User.isEmailVerified`.
 */
fun interface VerifiedEmails {
    /** `false` for an id that names nobody: an erased account is not a verified one. */
    fun isVerified(userId: UUID): Boolean
}
