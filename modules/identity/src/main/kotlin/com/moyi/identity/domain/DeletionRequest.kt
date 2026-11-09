package com.moyi.identity.domain

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A person's request that their account be deleted (FR-008, ADR-0058): pending
 * from [requestedAt], cancellable until [cancellableUntil], carried out from
 * then on and never later than [deleteBy].
 *
 * The two windows are the rule, and live here only: the store keeps
 * [cancellableUntil] as `execute_after` because the job's index needs it, and
 * [deleteBy] is derived, never stored, because nothing may move it.
 */
internal data class DeletionRequest(
    val id: UUID,
    val userId: UserId,
    val requestedAt: Instant,
) {
    /** FR-008: "a 14-day cancellation window". After it the request is carried out. */
    val cancellableUntil: Instant get() = requestedAt.plus(CANCELLATION_WINDOW)

    /** FR-008: "honoured within 30 days". The job runs within a day of [cancellableUntil], so this is a bound, not a schedule. */
    val deleteBy: Instant get() = requestedAt.plus(DEADLINE)

    /**
     * Strictly before the end of the window. From [cancellableUntil] on the request is due, which
     * the job asks of the stored `execute_after` (`execute_after <= now`, `DeletionRequestStore.dueBy`):
     * the exact complement, so no instant is both cancellable and due, or neither.
     */
    fun canBeCancelledAt(now: Instant): Boolean = now.isBefore(cancellableUntil)

    companion object {
        val CANCELLATION_WINDOW: Duration = Duration.ofDays(14)
        val DEADLINE: Duration = Duration.ofDays(30)

        /** Cut to microseconds: Postgres rounds a finer instant, and the idempotent 202 must repeat the dates exactly. */
        fun open(
            id: UUID,
            userId: UserId,
            now: Instant,
        ): DeletionRequest = DeletionRequest(id, userId, now.truncatedTo(ChronoUnit.MICROS))
    }
}
