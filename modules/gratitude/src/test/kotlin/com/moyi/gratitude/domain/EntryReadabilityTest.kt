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
    private val author = Reader(memberId = UUID.randomUUID(), bondId = bondId, withdrawnAuthors = emptySet())
    private val partner = Reader(memberId = UUID.randomUUID(), bondId = bondId, withdrawnAuthors = emptySet())
    private val stranger = Reader(memberId = UUID.randomUUID(), bondId = UUID.randomUUID(), withdrawnAuthors = emptySet())

    /** The same two members, asking once the author has withdrawn: the bond's fact, so both readers carry it. */
    private val authorAfterWithdrawing = Reader(author.memberId, bondId, withdrawnAuthors = setOf(author.memberId))
    private val partnerAfterWithdrawal = Reader(partner.memberId, bondId, withdrawnAuthors = setOf(author.memberId))
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

    /**
     * FR-093, as a table over the same readers: the gate's answer and one
     * thing more, whether the entry has been revealed. `FavouritesTest` holds
     * what each answer becomes over HTTP.
     */
    @Test
    fun `who may bookmark an entry is the gate's answer for that reader, and a full reading must also be revealed`() {
        // Either member, of a revealed entry: the author's own included.
        revealed.canBeFavouritedBy(author) shouldBe Favouriting.ALLOWED
        revealed.canBeFavouritedBy(partner) shouldBe Favouriting.ALLOWED
        // The author's own, still waiting: told so. The partner asking of the same entry was never shown it.
        unrevealed.canBeFavouritedBy(author) shouldBe Favouriting.NOT_YET_REVEALED
        unrevealed.canBeFavouritedBy(partner) shouldBe Favouriting.NEVER_SHOWN
        // A status that says REVEALED with no timestamp reveals nothing here either.
        unrevealed.copy(status = EntryStatus.REVEALED).canBeFavouritedBy(author) shouldBe Favouriting.NOT_YET_REVEALED

        // A tombstone to a reader who could read it; nothing to one who never could.
        for (erased in listOf(revealed.copy(deletedAt = now), revealed.copy(status = EntryStatus.DELETED))) {
            erased.canBeFavouritedBy(author) shouldBe Favouriting.ERASED
            erased.canBeFavouritedBy(partner) shouldBe Favouriting.ERASED
        }
        unrevealed.copy(deletedAt = now).canBeFavouritedBy(author) shouldBe Favouriting.ERASED
        unrevealed.copy(deletedAt = now).canBeFavouritedBy(partner) shouldBe Favouriting.NEVER_SHOWN

        // A withdrawal counts from its commit, while the row is still whole.
        revealed.canBeFavouritedBy(authorAfterWithdrawing) shouldBe Favouriting.ERASED
        revealed.canBeFavouritedBy(partnerAfterWithdrawal) shouldBe Favouriting.ERASED
        unrevealed.canBeFavouritedBy(partnerAfterWithdrawal) shouldBe Favouriting.NEVER_SHOWN

        // Another bond's member: nothing, revealed or not, erased or not.
        revealed.canBeFavouritedBy(stranger) shouldBe Favouriting.NEVER_SHOWN
        revealed.copy(deletedAt = now).canBeFavouritedBy(stranger) shouldBe Favouriting.NEVER_SHOWN
    }

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
        val authorsIdInAnotherBond = Reader(memberId = author.memberId, bondId = stranger.bondId, withdrawnAuthors = emptySet())
        revealed.canBeReadBy(authorsIdInAnotherBond) shouldBe Readability.NOT_A_MEMBER
    }

    @Test
    fun `a withdrawn author's live entry is a tombstone to its author and to a partner it was revealed to`() {
        // Nothing has been erased: the rows are whole, and stay so until the
        // outbox's consumer gets to them. The gate answers as if it had.
        unrevealed.isErased shouldBe false
        revealed.isErased shouldBe false

        unrevealed.canBeReadBy(authorAfterWithdrawing) shouldBe Readability.TOMBSTONE
        revealed.canBeReadBy(authorAfterWithdrawing) shouldBe Readability.TOMBSTONE
        revealed.canBeReadBy(partnerAfterWithdrawal) shouldBe Readability.TOMBSTONE
    }

    @Test
    fun `a withdrawn author's entry that was never revealed is an unseen tombstone to the partner`() {
        // The withdrawal must not hand the partner the id and timestamps BR-8 withheld while the entry was live.
        unrevealed.canBeReadBy(partnerAfterWithdrawal) shouldBe Readability.TOMBSTONE_UNSEEN

        val unseen = unrevealed.readBy(partnerAfterWithdrawal)
        unseen.disclosed.shouldBeNull()
        unseen.authorMemberId shouldBe author.memberId
    }

    @Test
    fun `a withdrawal leaves the other member's entries as they were`() {
        // A withdrawal is its author's, of their own words: the set names
        // authors, and an entry whose author is not in it is not touched.
        val partnersUnrevealed = unrevealed.copy(id = EntryId(UUID.randomUUID()), authorMemberId = partner.memberId)
        val partnersRevealed = revealed.copy(id = EntryId(UUID.randomUUID()), authorMemberId = partner.memberId)

        partnersUnrevealed.canBeReadBy(partnerAfterWithdrawal) shouldBe Readability.FULL
        partnersRevealed.canBeReadBy(partnerAfterWithdrawal) shouldBe Readability.FULL
        partnersRevealed.canBeReadBy(authorAfterWithdrawing) shouldBe Readability.FULL
        partnersUnrevealed.canBeReadBy(authorAfterWithdrawing) shouldBe Readability.LOCKED
        partnersRevealed
            .readBy(authorAfterWithdrawing)
            .disclosed
            .shouldNotBeNull()
            .text shouldBe revealed.text
    }

    @Test
    fun `membership is still asked before a withdrawal is`() {
        // A reader of another bond learns nothing, not even that something was
        // withdrawn: neither tombstone is theirs. The set travels with a
        // membership of ITS bond, so it could only name this author by accident.
        val outsider = Reader(memberId = UUID.randomUUID(), bondId = UUID.randomUUID(), withdrawnAuthors = setOf(author.memberId))
        val authorsIdElsewhere = Reader(memberId = author.memberId, bondId = outsider.bondId, withdrawnAuthors = setOf(author.memberId))

        unrevealed.canBeReadBy(outsider) shouldBe Readability.NOT_A_MEMBER
        revealed.canBeReadBy(outsider) shouldBe Readability.NOT_A_MEMBER
        revealed.canBeReadBy(authorsIdElsewhere) shouldBe Readability.NOT_A_MEMBER
        revealed.readBy(outsider).authorMemberId.shouldBeNull()
    }

    @Test
    fun `a withdrawn entry's reading hands on no words, though the entry still has them`() {
        val authors = unrevealed.readBy(authorAfterWithdrawing).disclosed.shouldNotBeNull()
        authors.text.shouldBeNull()
        authors.status shouldBe EntryStatus.DELETED
        // The author could always see when they wrote it; only the words are gone.
        authors.id shouldBe unrevealed.id

        val partners = revealed.readBy(partnerAfterWithdrawal).disclosed.shouldNotBeNull()
        partners.text.shouldBeNull()
        partners.status shouldBe EntryStatus.DELETED
        revealed.text.shouldNotBeNull()
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
