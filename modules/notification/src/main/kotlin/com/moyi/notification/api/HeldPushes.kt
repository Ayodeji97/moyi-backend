package com.moyi.notification.api

import java.time.Instant

/**
 * Pushes held back by a member's quiet hours, and their release when the
 * hours end (Q7, ADR-0045).
 *
 * A push that arrives inside quiet hours is not sent and not dropped: the
 * `notification.push` consumer records it `HELD` until the instant the hours
 * end and lets its outbox delivery go. Nothing in the outbox waits on it, so
 * nothing there looks stuck for a night. Something with a clock has to ask
 * for it again; that is this, called by `scheduling` every minute.
 */
interface HeldPushes {
    /**
     * Hands every push whose hold has ended by [now], at most [limit] of them,
     * back to the consumer, which decides again (the push may have gone stale
     * in the meantime, and is then `EXPIRED`). Safe to run on two instances at
     * once: a push is released by one of them.
     *
     * @return how many were released; [limit] means more may be waiting.
     */
    fun releaseDue(
        now: Instant,
        limit: Int,
    ): Int
}
