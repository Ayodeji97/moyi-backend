package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.ReactionType
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * `reactions` (V23): the one acknowledgement a member has left on their
 * partner's entry (FR-048).
 *
 * **The opposite of a bookmark in one respect, and built like it in every
 * other.** A bookmark is private to the member who made it, so every
 * question `Favourites` answers names a member. A reaction is made to be
 * seen by the entry's author (the owner's ruling), so [on] names only
 * entries: both members are shown the same value on the same entry. What
 * keeps that from being more than was ruled is that there is **no time** in
 * the row, and nothing else of the reactor's (V23 has why).
 *
 * **An entry carries at most one.** Only the member who did not write an
 * entry may react to it, a bond seats two, and the key is the pair, so there
 * is one row an entry can have. [set] is what holds the first of those, in
 * its own statement: whatever the service decided, the author's member id
 * gets no row.
 *
 * Plain SQL on [JdbcTemplate], as [Favourites] is and for its reasons: a row
 * is two ids and a name, and it stays out of Hibernate's persistence
 * context, so the withdrawal's `EntryStore.forgetLoaded` between erasures
 * has nothing of this to forget.
 *
 * **It decides nothing about who may ask.** [set]'s `memberId` is the
 * caller's member id in the entry's bond, resolved by `BondAccess`; whether
 * that member may see the entry at all, and whether the bond still takes a
 * reaction, are `ReactToEntry`'s questions, asked before any of this is
 * reached. [remove], [removeAllOn] and [on] are not transactional
 * themselves: each statement joins the caller's transaction when there is
 * one ([removeAllOn] always has the erasure's) and commits by itself when
 * there is not. [set] opens a transaction of its own, for its lock timeout.
 *
 * **Neither write waits out an erasure**, for the reason `Favourites` gives
 * (ADR-0036 decision 12): the withdrawal consumer holds every row it has
 * touched until its whole delivery commits, and a request that waited on one
 * held a pooled connection while it did. [remove] is refused a row it cannot
 * lock at once; [set] gives up after `EntryRowGuard.LOCK_WAIT`. Both idioms are
 * `EntryRowGuard`'s, shared with `Favourites`.
 */
@Component
internal class Reactions(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    /**
     * True when the entry is one this member can react to, in which case
     * their reaction on it is [type] when this returns; false when it is
     * not: not revealed, erased, **written by this member**, or not there.
     *
     * **One statement decides, writes and answers**, and the answer is
     * whether the entry **qualified**, not whether a row changed
     * (`Favourites.mark` has the double tap that taught this). Setting the
     * type already set is a success; setting another replaces it, because
     * the key is the pair and the conflict updates.
     *
     * **`author_member_id <> ?` is the store's own line, not the
     * service's.** The service refuses the caller's own entry first, with
     * its own code. This is for everything that is not that service: a
     * reaction on one's own entry would be a row the author made and the
     * author is shown, which means nothing, and a later caller of this
     * function that forgot to ask could not make one.
     *
     * **`FOR SHARE` is what makes the read true of a change still in
     * flight**: without it the statement would read the last committed row,
     * whole, while an erasure of it was open, and leave a reaction the
     * erasure had already finished removing reactions for. With it the
     * statement waits for that transaction, and Postgres then evaluates the
     * `WHERE` again on the row as it was left. `FOR SHARE` and not the
     * weaker `FOR KEY SHARE`, so that this does not rest on the eraser
     * having taken `FOR UPDATE` first (`Favourites.mark` has the run that
     * proved each).
     *
     * The lock is held for this statement alone and nothing else is held
     * with it, so it adds no edge to the application's lock order (bond,
     * day, entry).
     *
     * **It waits [lockTimeout] for a lock and no longer, and then answers
     * `false`.** A revealed entry's row is changed by one thing only, an
     * erasure. The one other holder is an author's `PATCH` on it, which locks
     * the row before it refuses and lets go within milliseconds. So a row
     * held past the wait is being erased, and "not an entry that can be
     * reacted to" is the answer waiting would have given, sooner. If that
     * erasure rolls back, the member was refused on an entry that is still
     * whole and may ask again. `waitingForALockAtMost` has how
     * the timeout is set and why nothing of it is logged. **Not to be called
     * inside a caller's transaction.**
     *
     * [lockTimeout] is `EntryRowGuard.LOCK_WAIT` for every caller there is.
     * It is a parameter so that a test can wait less than two seconds to see
     * the refusal, as `Favourites.mark`'s is.
     *
     * It does **not** know about a withdrawal that has erased nothing yet,
     * or about a bond that has ended: those rows are whole. The service
     * does, and asks first.
     */
    fun set(
        entryId: EntryId,
        memberId: UUID,
        type: ReactionType,
        lockTimeout: Duration = EntryRowGuard.LOCK_WAIT,
    ): Boolean =
        transactions.waitingForALockAtMost(lockTimeout, jdbc) {
            jdbc.queryForObject(SET, Int::class.java, entryId.value, memberId, memberId, type.name) == 1
        } ?: false

    /**
     * Removes the member's reaction if there is one. None is not an error:
     * absent is what was asked for.
     *
     * **It never waits for a row lock, and never guesses that a deletion
     * will commit** (`EntryRowGuard.deleteOrBeRefused`, as
     * `Favourites.unmark`). True means absent or removed. False means
     * another transaction holds the row, usually an erasure that is deleting
     * it: nothing was removed, and what the caller is told is the service's to
     * say (`ReactToEntry.unreact`: try again, unless the entry is already a
     * tombstone to them). Were it answered as a removal on an entry read in
     * full, an erasure that then rolled back would leave the reaction
     * there, shown to both, after the member was told it was gone.
     */
    fun remove(
        entryId: EntryId,
        memberId: UUID,
    ): Boolean = jdbc.removedUnlessHeld(REMOVE, entryId, memberId)

    /**
     * Every reaction on one entry, for `EraseEntry` and nobody else: an
     * acknowledgement of words is not kept once the words are taken back.
     * The foreign key's cascade cannot do this, because an erasure keeps the
     * row.
     */
    fun removeAllOn(entryId: EntryId) {
        jdbc.update("DELETE FROM reactions WHERE entry_id = ?", entryId.value)
    }

    /**
     * The one reaction on each of [entryIds] that has one. One query, and
     * none at all for an empty collection.
     *
     * **It names no member, on purpose**: the reaction on an entry is shown
     * to both people, so there is no "whose" to ask. It says nothing about
     * whether an entry may be read; the caller asks only about entries the
     * gate answered in full, and renders a reaction on nothing else.
     *
     * Ordered, so that if a row were ever put beside another on one entry
     * by hand, which no request can do, both members would still be shown
     * the same one.
     *
     * **A stored type this version does not know is skipped, not thrown on**
     * (ADR-0037, Owed by the amendment of 2026-10-28). The `CHECK` lets no
     * request store one, but a rollback can leave one behind: an image that
     * predates a migration adding a type reads a database that already holds
     * it. `valueOf` made every read of that day a `500`; skipped, the entry
     * reads as carrying no reaction, which is what this version can say about
     * it, and one `WARN` line names the entry's id and the stored name, and
     * nothing of anyone's words. It is an expand step: it makes a sixth type
     * survivable for an image built after this, not `ME_TOO` for one built
     * before V27.
     */
    fun on(entryIds: Collection<EntryId>): Map<EntryId, ReactionType> {
        if (entryIds.isEmpty()) return emptyMap()
        val placeholders = entryIds.joinToString { "?" }
        val found = LinkedHashMap<EntryId, ReactionType>()
        jdbc
            .query(
                "SELECT entry_id, type FROM reactions WHERE entry_id IN ($placeholders) ORDER BY entry_id, member_id",
                PreparedStatementSetter { statement -> entryIds.forEachIndexed { index, id -> statement.setObject(index + 1, id.value) } },
                RowMapper { row, _ -> EntryId(row.getObject("entry_id", UUID::class.java)) to row.getString("type") },
            ).forEach { (entry, stored) ->
                val type = ReactionType.named(stored)
                if (type == null) {
                    log.warn("Skipped a reaction of a type this version does not know: entry {}, type {}", entry.value, stored)
                } else {
                    found.putIfAbsent(entry, type)
                }
            }
        return found
    }

    private companion object {
        private val log = LoggerFactory.getLogger(Reactions::class.java)

        val SET =
            """
            WITH reactable AS (
                SELECT e.id FROM entries e
                WHERE e.id = ? AND ${EntryRowGuard.REVEALED_AND_NOT_ERASED} AND e.author_member_id <> ?
                FOR SHARE
            ), reacted AS (
                INSERT INTO reactions (entry_id, member_id, type)
                SELECT id, ?, ? FROM reactable
                ON CONFLICT (entry_id, member_id) DO UPDATE SET type = excluded.type
            )
            SELECT count(*) FROM reactable
            """.trimIndent()

        val REMOVE = EntryRowGuard.deleteOrBeRefused("reactions")
    }
}
