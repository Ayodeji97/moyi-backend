package com.moyi.gratitude.service

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.domain.Reader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [shownTo], the one rule every day renderer inherits, with no HTTP in the way: an entry
 * erased before its reader was ever shown it is not there, and the day's status is the one
 * its erasures will leave (ADR-0032 question 2, ADR-0036 question 6, as ruled 2026-10-21).
 * `UnseenErasureTwinTest` holds the five routes to it.
 */
internal class ShownDayTest {
    private val bondId = UUID.randomUUID()
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val day = BondDayId(UUID.randomUUID())
    private val now = Instant.parse("2026-09-15T08:00:00Z")

    private fun reader(
        member: UUID,
        withdrawn: Set<UUID> = emptySet(),
    ) = Reader(member, bondId, withdrawn)

    private fun written(
        author: UUID,
        at: Instant = now,
    ): Entry =
        Entry.submit(
            id = EntryId(UUID.randomUUID()),
            bondDayId = day,
            bondId = bondId,
            authorMemberId = author,
            text = EntryText.of("zq-words-of-$author"),
            promptId = null,
            intendedAt = at,
            now = at,
        )

    @Test
    fun `a partner's entry deleted before it was revealed is no entry, as if it had never been written`() {
        val erased = written(ada).copy(deletedAt = now, status = EntryStatus.DELETED)
        // The gate's own answer is unchanged: favourites and reactions still hear it.
        erased.canBeReadBy(reader(bea)) shouldBe Readability.TOMBSTONE_UNSEEN

        val shown = listOf(erased).shownTo(reader(bea), BondDayStatus.OPEN, closedAt = null)

        shown shouldBe emptyList<Entry>().shownTo(reader(bea), BondDayStatus.OPEN, closedAt = null)
        shown.partners.shouldBeNull()
        shown.mine.shouldBeNull()
    }

    @Test
    fun `beside the caller's own entry it is no entry too, and the caller's own is read as before`() {
        val erased = written(ada).copy(deletedAt = now, status = EntryStatus.DELETED)
        val beas = written(bea, now.plusSeconds(60))

        val shown = listOf(erased, beas).shownTo(reader(bea), BondDayStatus.PARTIAL, closedAt = null)

        shown.partners.shouldBeNull()
        shown.mine?.readability shouldBe Readability.FULL
        shown.status shouldBe BondDayStatus.PARTIAL
    }

    @Test
    fun `a tombstone the reader had read, and their own, are still shown`() {
        val revealed = written(ada).copy(status = EntryStatus.REVEALED, revealedAt = now)
        val seen = revealed.copy(deletedAt = now, status = EntryStatus.DELETED)
        listOf(seen).shownTo(reader(bea), BondDayStatus.REVEALED, now).partners?.readability shouldBe Readability.TOMBSTONE

        val own = written(ada).copy(deletedAt = now, status = EntryStatus.DELETED)
        listOf(own).shownTo(reader(ada), BondDayStatus.OPEN, closedAt = null).mine?.readability shouldBe Readability.TOMBSTONE
    }

    @Test
    fun `a withdrawn entry nothing has erased yet is no entry to a partner it was never revealed to`() {
        val adas = written(ada)

        val shown = listOf(adas).shownTo(reader(bea, withdrawn = setOf(ada)), BondDayStatus.PARTIAL, closedAt = null)

        shown.partners.shouldBeNull()
        // And the day is shown as the erasure will leave it, which is as a delete by hand leaves it.
        shown.status shouldBe BondDayStatus.OPEN
        // To its author, the tombstone, on the same day.
        val hers = listOf(adas).shownTo(reader(ada, withdrawn = setOf(ada)), BondDayStatus.PARTIAL, closedAt = null)
        hers.mine?.readability shouldBe Readability.TOMBSTONE
        hers.status shouldBe BondDayStatus.OPEN
    }

    @Test
    fun `a day waiting on its reveal with a withdrawn entry in it is shown with one entry left`() {
        val adas = written(ada)
        val beas = written(bea)

        val shown = listOf(adas, beas).shownTo(reader(bea, withdrawn = setOf(ada)), BondDayStatus.PENDING_REVEAL, closedAt = null)

        shown.status shouldBe BondDayStatus.PARTIAL
        shown.partners.shouldBeNull()
        shown.mine?.readability shouldBe Readability.FULL
    }

    @Test
    fun `the status steps back only as an erasure would step it back`() {
        val adas = written(ada)
        val withdrawn = reader(bea, withdrawn = setOf(ada))

        // Nobody withdrawn: the row's status, whatever it is.
        listOf(adas).shownTo(reader(bea), BondDayStatus.PARTIAL, closedAt = null).status shouldBe BondDayStatus.PARTIAL
        // Already erased: the row has been stepped back by the erasure itself, and is not stepped again.
        val erased = adas.copy(deletedAt = now, status = EntryStatus.DELETED)
        listOf(erased).shownTo(withdrawn, BondDayStatus.OPEN, closedAt = null).status shouldBe BondDayStatus.OPEN
        // Settled: history, as `BondDay.withoutEntry` leaves it (BR-10), a withheld PENDING_REVEAL among it.
        listOf(adas).shownTo(withdrawn, BondDayStatus.PENDING_REVEAL, closedAt = now).status shouldBe BondDayStatus.PENDING_REVEAL
        listOf(adas).shownTo(withdrawn, BondDayStatus.SOLO, closedAt = now).status shouldBe BondDayStatus.SOLO
        // A joining day stays SUSPENDED, as it does when erased.
        listOf(adas).shownTo(withdrawn, BondDayStatus.SUSPENDED, closedAt = null).status shouldBe BondDayStatus.SUSPENDED
    }
}
