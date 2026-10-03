package com.moyi.gratitude.web

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.domain.Reader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * BR-1's four answers, as the wire shapes they become — the mapping in
 * [EntryResponse.of] and [PartnerEntryResponse.of], with no HTTP in the way.
 *
 * It exists for the cases `RevealGateTest` cannot reach through the API: the
 * controller's own membership guard answers a non-member before any entry is
 * read, and a caller's own entry is never `LOCKED`. Those branches are still
 * the difference between BR-8's shape and a full one, so they are pinned here.
 */
internal class EntryRenderingTest {
    private val bondId = UUID.randomUUID()
    private val author = Reader(memberId = UUID.randomUUID(), bondId = bondId)
    private val partner = Reader(memberId = UUID.randomUUID(), bondId = bondId)
    private val stranger = Reader(memberId = UUID.randomUUID(), bondId = UUID.randomUUID())
    private val now = Instant.parse("2026-09-15T08:00:00Z")
    private val date = LocalDate.of(2026, 9, 15)

    private val entry =
        Entry.submit(
            id = EntryId(UUID.randomUUID()),
            bondDayId = BondDayId(UUID.randomUUID()),
            bondId = bondId,
            authorMemberId = author.memberId,
            text = EntryText.of("thank you for the coffee"),
            intendedAt = now,
            now = now,
        )

    @Test
    fun `a locked entry renders as BR-8's shape, and is never the full shape with its words left out`() {
        val locked = entry.readBy(partner)

        PartnerEntryResponse.of(locked, date) shouldBe LockedEntryResponse(author.memberId)
        // The full shape carries an id, a createdAt and an intendedAt: BR-8
        // forbids every one of them on a locked entry, text or no text.
        EntryResponse.of(locked, date).shouldBeNull()
    }

    @Test
    fun `a non-member's reading renders as nothing at all`() {
        val revealed = entry.copy(status = EntryStatus.REVEALED, revealedAt = now)

        PartnerEntryResponse.of(revealed.readBy(stranger), date).shouldBeNull()
        EntryResponse.of(revealed.readBy(stranger), date).shouldBeNull()
    }

    @Test
    fun `a full reading renders with its words, for its author and for a partner once revealed`() {
        EntryResponse.of(entry.readBy(author), date).shouldNotBeNull().text shouldBe "thank you for the coffee"

        val revealed = entry.copy(status = EntryStatus.REVEALED, revealedAt = now)
        val rendered = PartnerEntryResponse.of(revealed.readBy(partner), date)
        (rendered as EntryResponse).text shouldBe "thank you for the coffee"
        rendered.status shouldBe EntryStatus.REVEALED
    }

    @Test
    fun `the author's tombstone is the wide shape - no text, DELETED - while the row still holds its words`() {
        val rendered = PartnerEntryResponse.of(entry.copy(deletedAt = now).readBy(author), date) as EntryResponse

        rendered.text.shouldBeNull()
        rendered.status shouldBe EntryStatus.DELETED
        rendered.id shouldBe entry.id.value
    }

    @Test
    fun `a partner who had read the entry gets the wide tombstone too`() {
        val revealedThenErased = entry.copy(status = EntryStatus.REVEALED, revealedAt = now, deletedAt = now)

        val rendered = PartnerEntryResponse.of(revealedThenErased.readBy(partner), date) as EntryResponse

        rendered.text.shouldBeNull()
        rendered.status shouldBe EntryStatus.DELETED
        rendered.id shouldBe entry.id.value
        rendered.createdAt shouldBe entry.createdAt
    }

    @Test
    fun `a partner who never could read the entry gets author and REMOVED, and no wide shape exists for them`() {
        // BR-8: the wide tombstone carries an id and two timestamps this
        // reader was never entitled to. The narrow type has nowhere to put
        // them, and the wide one refuses to be built.
        val unseen = entry.copy(deletedAt = now).readBy(partner)

        PartnerEntryResponse.of(unseen, date) shouldBe ErasedEntryResponse(author.memberId, ErasedEntryStatus.REMOVED)
        // A view literal: distinct from the wide tombstone's DELETED, so `status` discriminates.
        ErasedEntryStatus.entries.map { it.name } shouldBe listOf("REMOVED")
        EntryResponse.of(unseen, date).shouldBeNull()
    }

    /**
     * C1 (final whole-branch review). Doc 18 §5/§9: an entry's words never
     * reach a log. `EntryText` and `EntryReading` already refuse to print
     * themselves; these three carried the same words as a plain `String` in a
     * data class, whose generated `toString` prints every property — one
     * `log.debug("{}", request)` or one exception message away from a leak.
     */
    @Test
    fun `the request, the draft and the response never print an entry's words`() {
        val words = "a secret between two people"
        val request = SubmitEntryRequest(text = words, intendedAt = now)
        val response = EntryResponse.of(entry.copy(text = EntryText.of(words)).readBy(author), date).shouldNotBeNull()
        val printed = listOf(request, request.toDraft(), response).map { it.toString() }

        printed.forEach { it shouldNotContain words }
        // What is useful in a log is still there: which entry, and when.
        printed[0] shouldBe "SubmitEntryRequest(text=(redacted), imageMediaId=null, voiceMediaId=null, intendedAt=$now)"
        printed[1] shouldBe "EntryDraft(text=(redacted), imageMediaId=null, voiceMediaId=null, intendedAt=$now)"
        printed[2] shouldContain "id=${entry.id.value}"
        printed[2] shouldContain "text=(redacted)"
        // A tombstone has no words, and says so rather than claiming to hide some.
        EntryResponse.of(entry.copy(deletedAt = now).readBy(author), date).toString() shouldContain "text=null"
    }
}
