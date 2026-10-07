package com.moyi.common.events

import java.time.Duration

/**
 * How long a failed delivery waits before it is offered again (plan C5a,
 * decision 5). There is no last attempt, so the count this is asked about has
 * no upper bound; the wait does.
 */
internal object Backoff {
    private val FIRST: Duration = Duration.ofSeconds(2)
    private const val CAP_MINUTES = 15L
    private val CAP: Duration = Duration.ofMinutes(CAP_MINUTES)

    // 2 s doubled nine times is already past the cap, so nothing above this is
    // ever computed: the shift below cannot overflow however large the count.
    private const val DOUBLINGS_TO_CAP = 9

    /** 2 s, 4 s, 8 s … capped at 15 min. [attempts] is the count after this failure, from 1. */
    fun delayAfter(attempts: Int): Duration {
        // Below one is answered as one: this runs while a failure is being
        // recorded, and a second failure there would lose the first.
        val doublings = (attempts.coerceAtLeast(1) - 1).coerceAtMost(DOUBLINGS_TO_CAP)
        return minOf(CAP, FIRST.multipliedBy(1L shl doublings))
    }

    /**
     * The same schedule as a list: the wait after the first failure, the
     * second, and so on up to the first that reaches the cap. Every failure
     * past the end waits as long as the last entry. It exists so that the
     * statement recording a failure can pick the wait from the count it finds
     * in the row, without the doubling being written a second time in SQL.
     */
    fun steps(): List<Duration> = (1..DOUBLINGS_TO_CAP + 1).map(::delayAfter)
}
