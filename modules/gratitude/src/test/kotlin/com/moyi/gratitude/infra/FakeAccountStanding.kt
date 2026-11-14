package com.moyi.gratitude.infra

import com.moyi.identity.api.AccountStanding
import com.moyi.identity.api.SuspensionSpan
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * identity's standing port, in memory, as [FakeUserDirectory] is its directory's: this module's
 * context does not boot identity. A test says when somebody was suspended and lifted; a span is
 * open until [lift]. [reset] forgets every span, for an `@AfterEach`.
 */
internal class FakeAccountStanding : AccountStanding {
    private val spans = CopyOnWriteArrayList<SuspensionSpan>()

    fun suspend(
        userId: UUID,
        at: Instant,
    ) {
        spans += SuspensionSpan(userId, at, null)
    }

    /** Closes [userId]'s open span at [at]. */
    fun lift(
        userId: UUID,
        at: Instant,
    ) {
        val open = spans.single { it.userId == userId && it.to == null }
        spans[spans.indexOf(open)] = open.copy(to = at)
    }

    fun reset() = spans.clear()

    override fun suspendedAmong(userIds: Collection<UUID>): Set<UUID> =
        spans.filter { it.userId in userIds && it.to == null }.map { it.userId }.toSet()

    override fun suspensionsOf(userIds: Collection<UUID>): List<SuspensionSpan> = spans.filter { it.userId in userIds }
}
