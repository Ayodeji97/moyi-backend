package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryId
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/**
 * What the two kinds of row a member may hang on an entry share: a bookmark
 * ([Favourites]) and a reaction ([Reactions]). Each is written by one
 * statement that reads the entry's row under a lock and writes only if the
 * entry still qualifies, and removed by one that never waits and never
 * reports a removal it cannot promise. The idiom was
 * reached in three steps for bookmarks, each forced by a failure that was
 * run (ADR-0036 decision 12), so there is one copy of it and not two that
 * can drift.
 *
 * Nothing here knows either table's rule. The pieces are:
 *
 * - [REVEALED_AND_NOT_ERASED]: which entries can carry such a row at all.
 * - [waitingForALockAtMost]: the bound on how long the write waits.
 * - [deleteOrBeRefused] and [removedUnlessHeld]: the removal that is refused
 *   at once, and says so, when somebody else holds the row.
 */
internal object EntryRowGuard {
    /**
     * How long a guarded write waits for a lock before it answers that the
     * entry does not qualify. Nothing that leaves a revealed entry whole
     * holds its row, so the length decides one thing: how soon a request
     * stuck behind a withdrawal gives its connection back. Two seconds,
     * against a delivery that can run to sixty.
     */
    val LOCK_WAIT: Duration = Duration.ofSeconds(2)

    /**
     * An entry both members can read and nobody has taken back, as a
     * condition on `entries e`. Both marks of an erasure are asked, as
     * `Entry.isErased` asks both: an erasure that has set one and not yet
     * the other is already an erasure.
     */
    const val REVEALED_AND_NOT_ERASED = "e.revealed_at IS NOT NULL AND e.deleted_at IS NULL AND e.status <> 'DELETED'"

    /**
     * The statement that removes one member's row from [table], **or is
     * refused at once when somebody else holds that row** (`FOR UPDATE
     * NOWAIT`). Four parameters: the entry and the member, twice. Run through
     * [removedUnlessHeld], which turns the refusal into an answer.
     *
     * A row that cannot be locked is one somebody else is deleting: the
     * member's own second tap, or an erasure, which removes every such row
     * on its entry. Waiting for it would hold a connection until that
     * transaction ends, which for the withdrawal consumer is the whole
     * delivery. Skipping it (`SKIP LOCKED`, as first built) answered
     * "removed" for a deletion that had not committed, and an erasure that
     * then rolled back left the row there after a removal reported
     * successful. So it does neither: it is refused, and the caller retries.
     *
     * [table] is a name written in this module's source, never a value from
     * a request.
     */
    fun deleteOrBeRefused(table: String): String =
        """
        DELETE FROM $table
        WHERE entry_id = ? AND member_id = ?
          AND (entry_id, member_id) IN (
              SELECT entry_id, member_id FROM $table
              WHERE entry_id = ? AND member_id = ?
              FOR UPDATE NOWAIT
          )
        """.trimIndent()
}

/** Postgres's `lock_not_available`: a lock that outlasted `lock_timeout`, or one asked for with `NOWAIT` and not to be had. */
private const val LOCK_NOT_AVAILABLE = "55P03"

/** Whether this is Postgres refusing a lock, at once or after the timeout, however Spring has wrapped it. */
private fun DataAccessException.isLockRefusal(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == LOCK_NOT_AVAILABLE }

/**
 * Runs [statement], one made by [EntryRowGuard.deleteOrBeRefused], for one
 * member's row on one entry. **True when the row is absent or was removed;
 * false when another transaction holds it**, and then nothing was removed
 * and nothing is promised: the caller is to be told to try again.
 *
 * On the request path the statement runs in autocommit, so a refusal is
 * already rolled back when it is caught. The refusal is an answer and is
 * not logged; its message names a row. Every other failure is thrown as it
 * came.
 */
internal fun JdbcTemplate.removedUnlessHeld(
    statement: String,
    entryId: EntryId,
    memberId: UUID,
): Boolean =
    try {
        update(statement, entryId.value, memberId, entryId.value, memberId)
        true
    } catch (failure: DataAccessException) {
        if (!failure.isLockRefusal()) throw failure
        false
    }

/**
 * Runs [statement] in a transaction of its own in which no lock is waited
 * for longer than [lockTimeout], and returns what it returned; **`null` when
 * a lock was not had in time**, and then nothing [statement] did stands.
 *
 * The timeout is set with `set_config(…, true)`, which is `SET LOCAL` with a
 * parameter (the idiom of `ConsumerRegistry.register`): it lasts for this
 * transaction and is gone from the session when it ends, so a pooled
 * connection goes back as it came. That is why there is a transaction here
 * at all: set outside one, it would end with its own statement and bound
 * nothing. The timeout is Postgres's `lock_not_available`, which leaves the
 * transaction aborted; it is caught outside the transaction, after the
 * rollback, and is **not logged**: it is an answer, not a failure, and its
 * message names a row. Every other failure is thrown as it came.
 *
 * **Not to be called inside a caller's transaction**: joined to one, a
 * timeout would abort that transaction too.
 *
 * [jdbc] is the template [statement] runs on, so that the setting and the
 * statement are made through the same one (a test's stand-in included).
 */
internal fun <T : Any> TransactionTemplate.waitingForALockAtMost(
    lockTimeout: Duration,
    jdbc: JdbcTemplate,
    statement: () -> T,
): T? =
    try {
        execute {
            jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String::class.java, "${lockTimeout.toMillis()}ms")
            statement()
        }
    } catch (failure: DataAccessException) {
        if (!failure.isLockRefusal()) throw failure
        null
    }
