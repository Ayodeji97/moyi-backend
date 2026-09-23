package com.moyi.identity.domain

import java.util.UUID

/**
 * Something happened to an account that its owner must be told about, by
 * email, after the transaction that did it commits. Raised inside that
 * transaction and consumed by an `AFTER_COMMIT` listener, exactly like
 * [VerificationRequested] — the same non-critical-event reasoning under
 * ADR-0008 applies: if the process dies between commit and send, the account
 * is still safe (the revocation already happened); only the notice is lost.
 *
 * Carries the address and display name so the listener reads nothing back.
 * No secret rides in these events, so the generated `toString` is fine.
 */
internal sealed interface SecurityNotice {
    val userId: UserId
    val email: Email
    val displayName: String

    /**
     * Doc 09 §3: "presenting an already-rotated token means it was stolen …
     * The entire token family is revoked immediately, and the user is emailed."
     * The person may be the thief's victim, or may be the one holding the
     * stale copy; the email has to make sense to both.
     */
    data class SessionReuseDetected(
        override val userId: UserId,
        override val email: Email,
        override val displayName: String,
        val familyId: UUID,
    ) : SecurityNotice

    /**
     * T-17: "reset revokes all sessions; notification email to the old
     * address". If the reset was not the account owner's doing, this email is
     * how they find out.
     */
    data class PasswordChanged(
        override val userId: UserId,
        override val email: Email,
        override val displayName: String,
    ) : SecurityNotice
}
