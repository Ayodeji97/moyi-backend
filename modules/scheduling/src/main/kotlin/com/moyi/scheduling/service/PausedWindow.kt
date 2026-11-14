package com.moyi.scheduling.service

import java.time.Duration
import java.time.Instant

/**
 * Where a looking-back job's window starts: [lookback] before [upTo], but never before the moment
 * its flag was last switched on ([onSince]; `null`: never switched, so no bound). What fell due while
 * the flag was off is not asked about, so switching a send back on does not send, late and at once,
 * what the pause held back (ADR-0067 decision 7). Never after [upTo]: a flag switched on since the
 * last boundary asks for an empty window, not a reversed one.
 */
internal fun windowStart(
    upTo: Instant,
    lookback: Duration,
    onSince: Instant?,
): Instant {
    val start = upTo.minus(lookback)
    return if (onSince == null || !onSince.isAfter(start)) start else minOf(onSince, upTo)
}
