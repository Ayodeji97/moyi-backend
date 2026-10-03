package com.moyi.gratitude.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * BR-1 as a truth table (spec §4): [Entry.canBeReadBy] in the order the rule
 * is stated — membership, then erasure, then authorship or a reveal — and
 * [EntryReading], the only form an entry is rendered from.
 *
 * `RevealGateTest` proves the same rule end to end; this is where each clause
 * is pinned on its own, so that a mutation of one line of the gate turns one
 * named case red.
 */
internal class EntryReadabilityTest {
    private val bondId = UUID.randomUUID()
    private val author = Reader(memberId = UUID.randomUUID(), bondId = bondId)
    private val partner = Reader(memberId = UUID.randomUUID(), bondId = bondId)
    private val stranger = Reader(memberId = UUID.randomUUID(), bondId = UUID.randomUUID())
    private val now = Instant.parse("2026-09-15T08:00:00Z")

    private val unrevealed =
        Entry.submit(
            id = EntryId(UUID.randomUUID()),
            bondDayId = BondDayId(UUID.randomUUID()),
            bondId = bondId,
            authorMemberId = author.memberId,
            text = EntryText.of("thank you for the coffee"),
            intendedAt = now,
            now = now,
        )
    private val revealed = unrevealed.copy(status = EntryStatus.REVEALED, revealedAt = now)

    @Test
    fun `an author reads their own entry in full, revealed or not`() {
        unrevealed.canBeReadBy(author) shouldBe Readability.FULL
        revealed.canBeReadBy(author) shouldBe Readability.FULL
    }

    @Test
    fun `a partner's entry is locked until its own revealedAt is set, and in full after`() {
        unrevealed.canBeReadBy(partner) shouldBe Readability.LOCKED
        revealed.canBeReadBy(partner) shouldBe Readability.FULL
    }

    @Test
    fun `revealedAt alone reveals - the entry's status is not consulted`() {
        // The timestamp is the key (spec §4). A status that says REVEALED on a
        // row whose timestamp was never set reveals nothing, and a set
        // timestamp reveals whatever the status still says.
        unrevealed.copy(status = EntryStatus.REVEALED).canBeReadBy(partner) shouldBe Readability.LOCKED
        unrevealed.copy(revealedAt = now).canBeReadBy(partner) shouldBe Readability.FULL
    }

    @Test
    fun `an erased entry is a tombstone for its author and its partner, revealed or not`() {
        // Either mark of an erasure is one: `deletedAt`, or the status.
        val erasures =
            listOf(
                unrevealed.copy(deletedAt = now),
                unrevealed.copy(status = EntryStatus.DELETED),
                revealed.copy(deletedAt = now),
                revealed.copy(status = EntryStatus.DELETED, deletedAt = now, text = null),
            )

        for (erased in erasures) {
            erased.canBeReadBy(author) shouldBe Readability.TOMBSTONE
            erased.canBeReadBy(partner) shouldBe Readability.TOMBSTONE
        }
    }

    @Test
    fun `a member of another bond reads nothing, whatever the entry's state`() {
        // Membership is asked FIRST: a revealed entry is not public, and an
        // erased one is not even acknowledged to exist.
        unrevealed.canBeReadBy(stranger) shouldBe Readability.NOT_A_MEMBER
        revealed.canBeReadBy(stranger) shouldBe Readability.NOT_A_MEMBER
        revealed.copy(deletedAt = now).canBeReadBy(stranger) shouldBe Readability.NOT_A_MEMBER
        // Even with the author's own member id, if the membership is of another bond.
        revealed.canBeReadBy(author.copy(bondId = stranger.bondId)) shouldBe Readability.NOT_A_MEMBER
    }

    @Test
    fun `a reading carries the words only when the gate answered FULL`() {
        unrevealed.readBy(author).text shouldBe unrevealed.text
        revealed.readBy(partner).text shouldBe unrevealed.text

        unrevealed.readBy(partner).text.shouldBeNull()
        unrevealed.readBy(stranger).text.shouldBeNull()
        // The row still holds its words; the reading does not hand them on.
        unrevealed.copy(deletedAt = now).readBy(author).text.shouldBeNull()
        revealed.copy(deletedAt = now).readBy(partner).text.shouldBeNull()
    }

    @Test
    fun `a tombstone reads as DELETED whichever mark the erasure left`() {
        unrevealed.copy(deletedAt = now).readBy(author).status shouldBe EntryStatus.DELETED
        revealed.copy(deletedAt = now).readBy(partner).status shouldBe EntryStatus.DELETED
        unrevealed.readBy(author).status shouldBe EntryStatus.SUBMITTED
    }

    @Test
    fun `a reading never prints the entry it wraps`() {
        "${unrevealed.readBy(author)}" shouldNotContain "thank you for the coffee"
        "${unrevealed.readBy(author)}" shouldNotContain "EntryText"
    }
}
