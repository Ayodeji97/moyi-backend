package com.moyi.gratitude.infra.database

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException

/**
 * The database constraint names this module enforces BR-2 through, and a
 * way to ask which one a failure was — `identity.infra.database.IdentityConstraints`'
 * own precedent, copied rather than shared for the reason `FakeUserDirectory`'s
 * own KDoc gives across every module boundary in this codebase: it is
 * `internal` there, and unreachable from here.
 *
 * BR-2 ("enforced by a unique index, not by application logic alone") is the
 * whole reason this exists: `SubmitEntry` attempts the insert and handles the
 * conflict, rather than checking first and racing its own check.
 */
internal object GratitudeConstraints {
    /** `entries_one_per_member_per_day` (V12) — one entry per member, per Bond-day. */
    const val ENTRY_ONE_PER_MEMBER_PER_DAY = "entries_one_per_member_per_day"
}

/**
 * True when this failure was [constraint] and not some other one.
 *
 * Walks the cause chain rather than checking `cause` directly: Spring wraps
 * Hibernate, which wraps the driver, and the depth is not something to
 * hard-code. Treating *any* integrity violation as BR-2 would silently
 * swallow a genuine bug — a NOT NULL breach, a bad foreign key — as a
 * polite `409` instead of the `500` that would surface it.
 */
internal fun DataIntegrityViolationException.violates(constraint: String): Boolean =
    generateSequence(cause) { it.cause }
        .any { it is ConstraintViolationException && it.constraintName.equals(constraint, ignoreCase = true) }
