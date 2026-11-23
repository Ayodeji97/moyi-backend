package com.moyi.notification.domain

import java.time.Duration

/**
 * How a coalescing push settles (ADR-0069 decision 7, spec §3 B17). Which categories coalesce is
 * `PushCategory.coalesces`; `domain` imports nothing (ArchitectureTest), so the merge takes its
 * values, and the value that means "several", as parameters.
 */
internal object Coalescing {
    /** How long a coalescing push waits for the changes after it before it may go. */
    val SETTLE: Duration = Duration.ofMinutes(2)

    /**
     * The change a push that called off [cancelled] carries: its [own] when everything it called
     * off was about the same change, otherwise [several] (the generic words).
     */
    fun <C> merged(
        own: C,
        cancelled: Collection<C>,
        several: C,
    ): C = if (cancelled.all { it == own }) own else several
}
