package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryId
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `entry_favourites` (V22): which entries a member has bookmarked (FR-093).
 *
 * **Every question here names a member.** There is no "is this entry a
 * favourite" and no count: an answer about an entry alone would be an answer
 * about the other person, which no response may carry (spec §6.6, FR-064).
 * [removeAllOf] is the one statement that does not, and it returns nothing.
 *
 * Plain SQL on [JdbcTemplate], as `StreakCalendar` is: a row here is two ids
 * and a time, with nothing to map and no state to track. That also keeps it
 * out of Hibernate's persistence context, so the withdrawal's
 * `EntryStore.forgetLoaded` between erasures has nothing of this to forget.
 *
 * **It decides nothing about who may ask.** [memberId] is the caller's member
 * id in the entry's bond, resolved by `BondAccess`; whether that member may
 * see the entry at all is the read gate's question, asked by `FavouriteEntry`
 * before any of this is reached. [unmark], [removeAllOf] and [markedBy] are
 * not transactional themselves: each statement joins the caller's
 * transaction when there is one ([removeAllOf] always has the erasure's) and
 * commits by itself when there is not. [mark] opens a transaction of its
 * own, for one reason: its lock timeout has to be set on the connection the
 * statement runs on, for that statement and nothing after it.
 *
 * **Neither write waits out an erasure.** The withdrawal consumer erases a
 * member's entries one after another in one transaction and holds every row
 * it touched until the delivery commits: seconds for a long history, up to
 * its sixty-second limit. A request that waited on one of those rows held a
 * pooled connection while it did, and about as many such requests as the
 * pool has connections stalled every other request in the application,
 * other bonds' included (measured by review: six unmarks 3.6 s each, and a
 * member of another bond 3.3 s on `GET /today`). So [unmark] skips a row it
 * cannot lock at once, and [mark] gives up after [MARK_LOCK_TIMEOUT].
 */
@Component
internal class Favourites(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    /**
     * True when the entry is one that can be marked, in which case the
     * member's mark is on it when this returns; false when it is not:
     * not revealed, erased, or not there.
     *
     * **One statement decides, writes and answers.** The entry is read, the
     * row inserted and the answer given together, and the answer is whether
     * the entry **qualified**, not whether a row was inserted. A second mark
     * inserts nothing and is still a success. An earlier form asked
     * afterwards, in a second statement, whether the row was there; the
     * member's own unmark committing between the two made a repeat mark on a
     * whole entry look like a mark on an erased one.
     *
     * Both marks of an erasure are asked, as `Entry.isErased` asks both.
     *
     * **`FOR SHARE` is what makes the read true of a change still in
     * flight.** Without a locking clause the statement would read the last
     * committed row, whole, while an erasure of it was open, and insert a
     * mark the erasure had already finished removing marks for. With it the
     * statement waits for that transaction, and Postgres then evaluates the
     * `WHERE` again on the row as it was left: erased, no row, no insert,
     * `false`. In the other order the erasure waits for this statement and
     * removes what it inserted.
     *
     * `FOR SHARE` and not the weaker `FOR KEY SHARE`, which is all the
     * insert's foreign-key check takes: that one waits only for a
     * transaction that locked the row `FOR UPDATE` or changed its key.
     * `EraseEntry` does take `FOR UPDATE` first, but an erasure written as a
     * plain `UPDATE` would not be waited for, and the mark would land on the
     * erased entry. `FOR SHARE` conflicts with every update of the row, so
     * this does not rest on how the eraser happens to be written.
     *
     * The lock is held for this statement alone and nothing else is held
     * with it, so it adds no edge to the application's lock order (bond,
     * day, entry). The transaction opened here holds the timeout's setting
     * and this statement, and commits as the statement returns.
     *
     * **It waits [lockTimeout] for a lock and no longer, and then answers
     * `false`.** An entry is revealed before it can be marked, and a
     * revealed entry's row is changed by one thing only, an erasure. An
     * author's own delete holds the row for a moment. The withdrawal
     * consumer holds it until its whole delivery commits, and a mark that
     * waited for that would hold a connection for as long (the class's
     * note). So a row held past the timeout is being erased, and `false`,
     * "not an entry that can be marked", is the answer it would have had by
     * waiting, given sooner. If that erasure then rolls back, the member was
     * refused a mark on an entry that is still whole and may ask again.
     *
     * The timeout is set with `set_config(…, true)`, which is `SET LOCAL`
     * with a parameter (the idiom of `ConsumerRegistry.register`): it lasts
     * for this transaction and is gone from the session when it ends, so a
     * pooled connection goes back as it came. That is why there is a
     * transaction here at all: set outside one, it would end with its own
     * statement and bound nothing. The timeout is Postgres's
     * `lock_not_available`, which leaves the transaction aborted; it is
     * caught outside the transaction, after the rollback, and is not
     * logged. **Not to be called inside a caller's transaction**: joined to
     * one, a timeout would abort that transaction too.
     *
     * [lockTimeout] is [MARK_LOCK_TIMEOUT] for every caller there is. It is
     * a parameter so that a test can wait less than two seconds to see the
     * refusal.
     *
     * It does **not** know about a withdrawal that has erased nothing yet:
     * that row is whole. The gate does, and is asked first.
     *
     * A second mark changes nothing, including [now]: the row keeps the time
     * it was first made. [now] is cut to microseconds here, so `created_at`
     * reads back as written.
     */
    fun mark(
        entryId: EntryId,
        memberId: UUID,
        now: Instant,
        lockTimeout: Duration = MARK_LOCK_TIMEOUT,
    ): Boolean =
        try {
            transactions.execute {
                jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String::class.java, "${lockTimeout.toMillis()}ms")
                jdbc.queryForObject(
                    MARK,
                    Int::class.java,
                    entryId.value,
                    memberId,
                    Timestamp.from(now.truncatedTo(ChronoUnit.MICROS)),
                ) == 1
            } == true
        } catch (failure: DataAccessException) {
            if (!failure.isLockTimeout()) throw failure
            false
        }

    /**
     * Removes the member's mark if there is one. No mark is not an error:
     * absent is what was asked for.
     *
     * **It never waits.** It deletes the row only if it can lock it at once
     * (`FOR UPDATE SKIP LOCKED`), and returns either way. A row that cannot
     * be locked is one somebody else is deleting: the member's own second
     * tap, or an erasure, which removes every mark on its entry
     * ([removeAllOf]). Either way the mark is going, and waiting to delete
     * it again would only hold a connection until that transaction ends,
     * which for the withdrawal consumer is the whole delivery (the class's
     * note). The one case where the answer runs ahead of the fact: if that
     * erasure rolls back, the mark is still there after an unmark that
     * reported success. The entry is then whole again, the mark shows as
     * `favourited: true`, and the member can remove it.
     */
    fun unmark(
        entryId: EntryId,
        memberId: UUID,
    ) {
        jdbc.update(UNMARK, entryId.value, memberId, entryId.value, memberId)
    }

    /**
     * Every member's mark on one entry, for `EraseEntry` and nobody else: a
     * bookmark is never a reason to keep anything of an entry its author took
     * back (spec §6.6). The foreign key's cascade cannot do this, because an
     * erasure keeps the row.
     */
    fun removeAllOf(entryId: EntryId) {
        jdbc.update("DELETE FROM entry_favourites WHERE entry_id = ?", entryId.value)
    }

    /**
     * Which of [entryIds] this member has marked. One query, and none at all
     * for an empty collection.
     *
     * Only ever [memberId]'s own: the caller passes the member a response is
     * being rendered for. It says nothing about whether an entry may be read;
     * the caller renders a mark only on an entry the gate answered in full.
     */
    fun markedBy(
        memberId: UUID,
        entryIds: Collection<EntryId>,
    ): Set<EntryId> {
        if (entryIds.isEmpty()) return emptySet()
        val placeholders = entryIds.joinToString { "?" }
        return jdbc
            .query(
                "SELECT entry_id FROM entry_favourites WHERE member_id = ? AND entry_id IN ($placeholders)",
                PreparedStatementSetter { statement ->
                    statement.setObject(1, memberId)
                    entryIds.forEachIndexed { index, id -> statement.setObject(index + 2, id.value) }
                },
                RowMapper { row, _ -> EntryId(row.getObject("entry_id", UUID::class.java)) },
            ).toSet()
    }

    internal companion object {
        /**
         * How long a mark waits for a lock before it answers that the entry
         * cannot be marked. Nothing that leaves an entry markable holds its
         * row ([mark] says why), so the length decides one thing: how soon a
         * request stuck behind a withdrawal gives its connection back. Two
         * seconds, against a delivery that can run to sixty.
         */
        val MARK_LOCK_TIMEOUT: Duration = Duration.ofSeconds(2)

        /** Postgres's `lock_not_available`: a lock that outlasted `lock_timeout`. */
        private const val LOCK_NOT_AVAILABLE = "55P03"

        private val MARK =
            """
            WITH markable AS (
                SELECT e.id FROM entries e
                WHERE e.id = ? AND e.revealed_at IS NOT NULL AND e.deleted_at IS NULL AND e.status <> 'DELETED'
                FOR SHARE
            ), marked AS (
                INSERT INTO entry_favourites (entry_id, member_id, created_at)
                SELECT id, ?, ? FROM markable
                ON CONFLICT DO NOTHING
            )
            SELECT count(*) FROM markable
            """.trimIndent()

        private val UNMARK =
            """
            DELETE FROM entry_favourites
            WHERE entry_id = ? AND member_id = ?
              AND (entry_id, member_id) IN (
                  SELECT entry_id, member_id FROM entry_favourites
                  WHERE entry_id = ? AND member_id = ?
                  FOR UPDATE SKIP LOCKED
              )
            """.trimIndent()

        /** Whether this is Postgres saying a lock was not to be had in time, however Spring has wrapped it. */
        private fun DataAccessException.isLockTimeout(): Boolean =
            generateSequence<Throwable>(this) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == LOCK_NOT_AVAILABLE }
    }
}
