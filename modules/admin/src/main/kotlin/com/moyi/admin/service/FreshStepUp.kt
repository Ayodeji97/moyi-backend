package com.moyi.admin.service

import java.time.Instant
import java.util.UUID

/**
 * A second factor proved **in this request** (A5 spec §5 step 3): a TOTP code that
 * `AdminSignIn.stepUp` accepted, and spent, for [adminId] at [at]. What `ContentAccess` takes
 * before it reads an entry's words; there is no window, so a code proved for an earlier act
 * opens nothing.
 *
 * Made in one place only, `SecondFactor.proveNow`, after the code is accepted; `ArchitectureTest`
 * holds that no other file of `admin`'s main code calls this constructor. It is not the session's
 * `admin.secondFactorAt`, which proving one neither reads nor writes.
 */
internal class FreshStepUp internal constructor(
    val adminId: UUID,
    val at: Instant,
) {
    override fun toString(): String = "FreshStepUp(adminId=$adminId, at=$at)"
}
