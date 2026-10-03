package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
    fun `an erased entry is a tombstone for its author always, and for a partner it had been revealed to`() {
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
        }
        revealed.copy(deletedAt = now).canBeReadBy(partner) shouldBe Readability.TOMBSTONE
        revealed.copy(status = EntryStatus.DELETED, deletedAt = now, text = null).canBeReadBy(partner) shouldBe Readability.TOMBSTONE
    }

    @Test
    fun `an entry erased before it was ever revealed is an unseen tombstone for the partner`() {
        // The partner was only ever entitled to BR-8's locked shape; the
        // erasure must not widen that. Decided here, in the gate.
        unrevealed.copy(deletedAt = now).canBeReadBy(partner) shouldBe Readability.TOMBSTONE_UNSEEN
        unrevealed.copy(status = EntryStatus.DELETED).canBeReadBy(partner) shouldBe Readability.TOMBSTONE_UNSEEN
        unrevealed.copy(status = EntryStatus.DELETED, deletedAt = now, text = null).canBeReadBy(partner) shouldBe
            Readability.TOMBSTONE_UNSEEN
    }

    @Test
    fun `an erased row whose status was never flipped still loads, and is a tombstone`() {
        // `deleted_at` set and the text nulled, status still SUBMITTED: the
        // constructor must admit it, or the mapper answers a 500.
        val halfErased = unrevealed.copy(deletedAt = now, text = null)

        halfErased.canBeReadBy(author) shouldBe Readability.TOMBSTONE
        halfErased.canBeReadBy(partner) shouldBe Readability.TOMBSTONE_UNSEEN
        shouldThrow<IllegalArgumentException> { unrevealed.copy(text = null) }
    }

    @Test
    fun `a member of another bond reads nothing, whatever the entry's state`() {
        // Membership is asked FIRST: a revealed entry is not public, and an
        // erased one is not even acknowledged to exist.
        unrevealed.canBeReadBy(stranger) shouldBe Readability.NOT_A_MEMBER
        revealed.canBeReadBy(stranger) shouldBe Readability.NOT_A_MEMBER
        revealed.copy(deletedAt = now).canBeReadBy(stranger) shouldBe Readability.NOT_A_MEMBER
        // Even with the author's own member id, if the membership is of another bond.
        revealed.canBeReadBy(Reader(memberId = author.memberId, bondId = stranger.bondId)) shouldBe Readability.NOT_A_MEMBER
    }

    @Test
    fun `a Reader cannot be copied into another bond's`() {
        // `ArchitectureTest` pins the one place a Reader is constructed. A
        // `data class` would hand every holder a second constructor the rule
        // cannot see: `asReader().copy(bondId = …)`. A plain class has none.
        val generated =
            Reader::class.java.declaredMethods
                .map { it.name }
                .filter { it.startsWith("copy") || it.startsWith("component") }

        generated shouldBe emptyList()
    }

    @Test
    fun `a reading discloses the words only when the gate answered FULL`() {
        unrevealed
            .readBy(author)
            .disclosed
            .shouldNotBeNull()
            .text shouldBe unrevealed.text
        revealed
            .readBy(partner)
            .disclosed
            .shouldNotBeNull()
            .text shouldBe unrevealed.text

        // The row still holds its words; a tombstone does not hand them on.
        val authorsTombstone =
            unrevealed
                .copy(deletedAt = now)
                .readBy(author)
                .disclosed
                .shouldNotBeNull()
        authorsTombstone.text.shouldBeNull()
        val partnersTombstone =
            revealed
                .copy(deletedAt = now)
                .readBy(partner)
                .disclosed
                .shouldNotBeNull()
        partnersTombstone.text.shouldBeNull()
    }

    @Test
    fun `a wide tombstone reads as DELETED whichever mark the erasure left`() {
        val byTimestamp =
            unrevealed
                .copy(deletedAt = now)
                .readBy(author)
                .disclosed
                .shouldNotBeNull()
        byTimestamp.status shouldBe EntryStatus.DELETED
        byTimestamp.id shouldBe unrevealed.id
        unrevealed
            .readBy(author)
            .disclosed
            .shouldNotBeNull()
            .status shouldBe EntryStatus.SUBMITTED
    }

    @Test
    fun `a locked, unseen-erased or non-member reading discloses nothing but, at most, the author`() {
        // BR-8, held by the reading itself rather than by each renderer
        // remembering: there is no id, timestamp or text to reach for.
        val locked = unrevealed.readBy(partner)
        locked.readability shouldBe Readability.LOCKED
        locked.disclosed.shouldBeNull()
        locked.authorMemberId shouldBe author.memberId

        val unseen = unrevealed.copy(deletedAt = now).readBy(partner)
        unseen.readability shouldBe Readability.TOMBSTONE_UNSEEN
        unseen.disclosed.shouldBeNull()
        unseen.authorMemberId shouldBe author.memberId

        val outsider = revealed.readBy(stranger)
        outsider.disclosed.shouldBeNull()
        outsider.authorMemberId.shouldBeNull()
    }

    @Test
    fun `a reading never prints the entry it wraps`() {
        val printed = "${unrevealed.readBy(author)}"

        printed shouldNotContain "thank you for the coffee"
        printed shouldNotContain "EntryText"
        printed shouldNotContain unrevealed.id.value.toString()
    }
}
