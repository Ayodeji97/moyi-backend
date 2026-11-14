package com.moyi.identity.api

import java.time.Instant
import java.util.UUID

/**
 * Suspending an account and lifting the suspension (admin console A3, FR-113; spec rulings
 * 4–6). Admin-only: `ArchitectureTest` lets no other module import it, and within `admin` the
 * console service alone.
 *
 * Both methods join the caller's transaction and refuse to run outside one
 * (`propagation = MANDATORY`): the act and the admin's audit row commit together or not at
 * all. Each takes the user's sessions lock and then the user's row, in that order (as sign-in,
 * refresh, reset and the deletion job take them), so two acts on one account, or an act and a
 * deletion being carried out, are serialised and the second sees the first's result.
 *
 * Nothing is written for any outcome but [SuspensionOutcome.SUSPENDED] and
 * [SuspensionOutcome.LIFTED].
 */
interface AccountSuspension {
    /**
     * `ACTIVE` or `PENDING_VERIFICATION`: the account becomes `SUSPENDED`, every access token
     * issued up to the act's next whole second dies, every refresh family is revoked, every
     * push token forgotten, and a span opened at the act.
     */
    fun suspend(userId: UUID): SuspensionResult

    /**
     * `SUSPENDED`: the account returns to the status it has earned, `ACTIVE` if its address
     * is verified and `PENDING_VERIFICATION` if not, and its span is closed at the act. No
     * session comes back: the person signs in afresh.
     */
    fun lift(userId: UUID): SuspensionResult
}

/** What an act found and did. Closed: the console renders each case. */
enum class SuspensionOutcome {
    /** The account was suspended by this act. */
    SUSPENDED,

    /** The account was already suspended; nothing changed. */
    ALREADY_SUSPENDED,

    /** The suspension was lifted by this act. */
    LIFTED,

    /** The account was not suspended; nothing changed. */
    NOT_SUSPENDED,

    /** The account is deleted (or `PENDING_DELETION`, unused since ADR-0058 decision 2); nothing changed. */
    GONE,

    /** No account has the id. */
    NO_SUCH_ACCOUNT,
}

/** An account's lifecycle as the admin console sees it, mapped one to one from identity's own. */
enum class AccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    PENDING_DELETION,
    DELETED,
}

/**
 * [statusBefore] is the account's status as the act found it, under its locks; `null` only for
 * [SuspensionOutcome.NO_SUCH_ACCOUNT]. [deletionDueAt] is when a pending deletion request will
 * be carried out (the end of its cancellation window, the instant the job runs it at: plan
 * ruling 1), `null` when none is pending.
 *
 * [toString] prints the outcome and the status only.
 */
data class SuspensionResult(
    val outcome: SuspensionOutcome,
    val statusBefore: AccountStatus?,
    val deletionDueAt: Instant?,
) {
    override fun toString(): String = "SuspensionResult(outcome=$outcome, statusBefore=$statusBefore)"
}
