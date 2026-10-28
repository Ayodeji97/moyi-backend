package com.moyi.media.service

import org.springframework.dao.DataAccessException
import java.sql.SQLException

/** Postgres's `lock_not_available`: a lock that outlasted `lock_timeout` (10 s in production, application.yml). */
private const val LOCK_NOT_AVAILABLE = "55P03"

/**
 * Runs [work], a transaction that takes an asset's row lock, and answers
 * `409 MEDIA_BUSY` when Postgres gave up waiting for that lock, instead of the
 * unmapped `500` it would otherwise be (review of #106).
 *
 * 409 and not 503: the storage is not involved, and nothing is wrong with the
 * request; another change to the same asset is still running, and the same
 * request may simply be sent again, as `FAVOURITE_BUSY` and `REACTION_BUSY`
 * say for their rows. Since no storage call is made under the lock
 * (ADR-0047 decision 2), a wait this long means the database itself is in
 * trouble, so it should be rare.
 *
 * Caught around the whole transaction, never inside it: the failed statement
 * has already aborted it, and it rolls back before the answer is given.
 */
internal fun <T> busyIfLockRefused(work: () -> T): T =
    try {
        work()
    } catch (failure: DataAccessException) {
        if (failure.isLockRefusal()) throw MediaBusyException(failure) else throw failure
    }

private fun DataAccessException.isLockRefusal(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == LOCK_NOT_AVAILABLE }
