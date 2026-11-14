package com.moyi.scheduling.service

import java.time.Duration
import java.time.Instant

/**
 * Where a looking-back job's window starts: [lookback] before [upTo], but never before the moment
 * its flag was last switched on ([onSince]; `null`: never switched, so no bound). What fell due while
 * the flag was off is not asked about, so switching a send back on does not send, late and at once,
 * what the pause held back (ADR-0067 decision 7).
 *
 * `null` when the window is empty: the flag was switched on at or after [upTo], the run's last
 * boundary. The job then asks about nothing (a window `(upTo, upTo]` is refused by the roster's own
 * check, `ReminderTime`'s "a window ends after it begins") and records an empty run.
 */
internal fun windowStart(
    upTo: Instant,
    lookback: Duration,
    onSince: Instant?,
): Instant? {
    val start = upTo.minus(lookback)
    val bounded = if (onSince != null && onSince.isAfter(start)) onSince else start
    return bounded.takeIf { it.isBefore(upTo) }
}
