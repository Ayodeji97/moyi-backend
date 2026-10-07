package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
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
 * before any of this is reached. Not transactional itself: each statement
 * joins the caller's transaction when there is one ([removeAllOf] always has
 * the erasure's) and commits by itself when there is not.
 */
@Component
internal class Favourites(
    private val jdbc: JdbcTemplate,
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
     * day, entry).
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
    ): Boolean =
        jdbc.queryForObject(
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
            """.trimIndent(),
            Int::class.java,
            entryId.value,
            memberId,
            Timestamp.from(now.truncatedTo(ChronoUnit.MICROS)),
        ) == 1

    /** Removes the member's mark if there is one. No mark is not an error: absent is what was asked for. */
    fun unmark(
        entryId: EntryId,
        memberId: UUID,
    ) {
        jdbc.update("DELETE FROM entry_favourites WHERE entry_id = ? AND member_id = ?", entryId.value, memberId)
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
}
