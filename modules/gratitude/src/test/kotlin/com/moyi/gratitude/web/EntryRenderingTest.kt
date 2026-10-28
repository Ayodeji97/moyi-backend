package com.moyi.gratitude.web

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.domain.PromptId
import com.moyi.gratitude.domain.ReactionType
import com.moyi.gratitude.domain.Reader
import com.moyi.gratitude.service.EntryImage
import com.moyi.gratitude.service.EntryImageStatus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI
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
    private val author = Reader(memberId = UUID.randomUUID(), bondId = bondId, withdrawnAuthors = emptySet())
    private val partner = Reader(memberId = UUID.randomUUID(), bondId = bondId, withdrawnAuthors = emptySet())
    private val stranger = Reader(memberId = UUID.randomUUID(), bondId = UUID.randomUUID(), withdrawnAuthors = emptySet())
    private val now = Instant.parse("2026-09-15T08:00:00Z")
    private val date = LocalDate.of(2026, 9, 15)

    private val entry =
        Entry.submit(
            id = EntryId(UUID.randomUUID()),
            bondDayId = BondDayId(UUID.randomUUID()),
            bondId = bondId,
            authorMemberId = author.memberId,
            text = EntryText.of("thank you for the coffee"),
            promptId = null,
            intendedAt = now,
            now = now,
        )

    @Test
    fun `a locked entry renders as BR-8's shape, and is never the full shape with its words left out`() {
        val locked = entry.readBy(partner)

        PartnerEntryResponse.of(locked, date, favourited = false, reaction = null, image = null) shouldBe
            LockedEntryResponse(author.memberId)
        // The full shape carries an id, a createdAt and an intendedAt: BR-8
        // forbids every one of them on a locked entry, text or no text.
        EntryResponse.of(locked, date, favourited = false, reaction = null, image = null).shouldBeNull()
    }

    @Test
    fun `a non-member's reading renders as nothing at all`() {
        val revealed = entry.copy(status = EntryStatus.REVEALED, revealedAt = now)

        PartnerEntryResponse.of(revealed.readBy(stranger), date, favourited = false, reaction = null, image = null).shouldBeNull()
        EntryResponse.of(revealed.readBy(stranger), date, favourited = false, reaction = null, image = null).shouldBeNull()
    }

    @Test
    fun `a full reading renders with its words, for its author and for a partner once revealed`() {
        EntryResponse.of(entry.readBy(author), date, favourited = false, reaction = null, image = null).shouldNotBeNull().text shouldBe
            "thank you for the coffee"

        val revealed = entry.copy(status = EntryStatus.REVEALED, revealedAt = now)
        val rendered = PartnerEntryResponse.of(revealed.readBy(partner), date, favourited = false, reaction = null, image = null)
        (rendered as EntryResponse).text shouldBe "thank you for the coffee"
        rendered.status shouldBe EntryStatus.REVEALED
    }

    @Test
    @Suppress("LongMethod") // A table of readings; each call names every argument the renderer takes, the photo's since D2.
    fun `the caller's mark is rendered on a full reading and on nothing else, whatever the caller of the renderer says`() {
        val revealed = entry.copy(status = EntryStatus.REVEALED, revealedAt = now)
        EntryResponse
            .of(
                revealed.readBy(author),
                date,
                favourited = true,
                reaction = null,
                image = null,
            ).shouldNotBeNull()
            .favourited shouldBe
            true
        EntryResponse
            .of(
                revealed.readBy(author),
                date,
                favourited = false,
                reaction = null,
                image = null,
            ).shouldNotBeNull()
            .favourited shouldBe
            false
        (
            PartnerEntryResponse.of(
                revealed.readBy(partner),
                date,
                favourited = true,
                reaction = null,
                image = null,
            ) as EntryResponse
        ).favourited shouldBe
            true

        // A tombstone says false: an erased row, and a withdrawn author's row that is still whole.
        val erased = revealed.copy(deletedAt = now)
        EntryResponse
            .of(
                erased.readBy(author),
                date,
                favourited = true,
                reaction = null,
                image = null,
            ).shouldNotBeNull()
            .favourited shouldBe
            false
        (
            PartnerEntryResponse.of(
                erased.readBy(partner),
                date,
                favourited = true,
                reaction = null,
                image = null,
            ) as EntryResponse
        ).favourited shouldBe
            false
        val afterWithdrawal = Reader(memberId = partner.memberId, bondId = bondId, withdrawnAuthors = setOf(author.memberId))
        val hidden =
            PartnerEntryResponse.of(
                revealed.readBy(afterWithdrawal),
                date,
                favourited = true,
                reaction = null,
                image = null,
            ) as EntryResponse
        hidden.text.shouldBeNull()
        hidden.favourited shouldBe false

        // The narrow shapes have nowhere to put one.
        PartnerEntryResponse.of(entry.readBy(partner), date, favourited = true, reaction = null, image = null) shouldBe
            LockedEntryResponse(author.memberId)
        PartnerEntryResponse.of(
            entry.copy(deletedAt = now).readBy(partner),
            date,
            favourited = true,
            reaction = null,
            image = null,
        ) shouldBe
            ErasedEntryResponse(author.memberId, ErasedEntryStatus.REMOVED)
    }

    @Test
    @Suppress("LongMethod") // As the mark's: a table of readings, every argument named.
    fun `the reaction is rendered on a full reading, the same to both members, and on nothing else whatever the caller says`() {
        val revealed = entry.copy(status = EntryStatus.REVEALED, revealedAt = now)
        val moved = ReactionType.MOVED
        // To the author and to the member who left it alike: it is the entry's, not the caller's.
        EntryResponse
            .of(
                revealed.readBy(author),
                date,
                favourited = false,
                reaction = moved,
                image = null,
            ).shouldNotBeNull()
            .reaction shouldBe
            moved
        (
            PartnerEntryResponse.of(
                revealed.readBy(partner),
                date,
                favourited = false,
                reaction = moved,
                image = null,
            ) as EntryResponse
        ).reaction shouldBe
            moved
        EntryResponse
            .of(revealed.readBy(author), date, favourited = false, reaction = null, image = null)
            .shouldNotBeNull()
            .reaction
            .shouldBeNull()

        // A tombstone says null: an erased row, and a withdrawn author's row that is still whole and still has one.
        val erased = revealed.copy(deletedAt = now)
        EntryResponse
            .of(erased.readBy(author), date, favourited = false, reaction = moved, image = null)
            .shouldNotBeNull()
            .reaction
            .shouldBeNull()
        (
            PartnerEntryResponse.of(
                erased.readBy(partner),
                date,
                favourited = false,
                reaction = moved,
                image = null,
            ) as EntryResponse
        ).reaction.shouldBeNull()
        for (reader in listOf(author, partner)) {
            val afterWithdrawal = Reader(memberId = reader.memberId, bondId = bondId, withdrawnAuthors = setOf(author.memberId))
            val hidden =
                PartnerEntryResponse.of(
                    revealed.readBy(afterWithdrawal),
                    date,
                    favourited = false,
                    reaction = moved,
                    image = null,
                ) as EntryResponse
            hidden.status shouldBe EntryStatus.DELETED
            hidden.reaction.shouldBeNull()
        }

        // The narrow shapes have nowhere to put one.
        PartnerEntryResponse.of(entry.readBy(partner), date, favourited = false, reaction = moved, image = null) shouldBe
            LockedEntryResponse(author.memberId)
        PartnerEntryResponse.of(
            entry.copy(deletedAt = now).readBy(partner),
            date,
            favourited = false,
            reaction = moved,
            image = null,
        ) shouldBe
            ErasedEntryResponse(author.memberId, ErasedEntryStatus.REMOVED)
    }

    @Test
    fun `the photo is rendered on a full reading, whatever the caller of the renderer passes on anything else`() {
        val photo = UUID.randomUUID()
        val image =
            EntryImage(photo, EntryImageStatus.READY, URI.create("https://storage.example.test/v/x/full.webp?X-Amz-Signature=fake"), 4, 3)
        val revealed = entry.withImage(photo, now).copy(status = EntryStatus.REVEALED, revealedAt = now)

        EntryResponse.of(revealed.readBy(author), date, favourited = false, reaction = null, image = image).shouldNotBeNull().image shouldBe
            EntryImageResponse(photo, EntryImageStatus.READY, image.url.toString(), 4, 3)
        // A tombstone has no photo, even one its caller passes.
        EntryResponse
            .of(revealed.copy(deletedAt = now).readBy(partner), date, favourited = false, reaction = null, image = image)
            .shouldNotBeNull()
            .image
            .shouldBeNull()
        // And a response prints no URL.
        EntryImageResponse.from(image).toString() shouldNotContain "X-Amz-Signature"
        EntryResponse.of(revealed.readBy(author), date, favourited = false, reaction = null, image = image).toString() shouldNotContain
            "X-Amz-Signature"
    }

    @Test
    fun `a reaction request prints a type it knows and nothing else that was sent`() {
        ReactionRequest(type = "HEART").toString() shouldBe "ReactionRequest(type=HEART)"
        ReactionRequest(type = "zq-anything-a-caller-typed").toString() shouldBe "ReactionRequest(type=null)"
        ReactionRequest().toString() shouldBe "ReactionRequest(type=null)"
    }

    @Test
    fun `the author's tombstone is the wide shape - no text, DELETED - while the row still holds its words`() {
        val rendered =
            PartnerEntryResponse.of(
                entry.copy(deletedAt = now).readBy(author),
                date,
                favourited = false,
                reaction = null,
                image = null,
            ) as EntryResponse

        rendered.text.shouldBeNull()
        rendered.status shouldBe EntryStatus.DELETED
        rendered.id shouldBe entry.id.value
    }

    @Test
    fun `a partner who had read the entry gets the wide tombstone too`() {
        val revealedThenErased = entry.copy(status = EntryStatus.REVEALED, revealedAt = now, deletedAt = now)

        val rendered =
            PartnerEntryResponse.of(
                revealedThenErased.readBy(partner),
                date,
                favourited = false,
                reaction = null,
                image = null,
            ) as EntryResponse

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

        PartnerEntryResponse.of(unseen, date, favourited = false, reaction = null, image = null) shouldBe
            ErasedEntryResponse(author.memberId, ErasedEntryStatus.REMOVED)
        // A view literal: distinct from the wide tombstone's DELETED, so `status` discriminates.
        ErasedEntryStatus.entries.map { it.name } shouldBe listOf("REMOVED")
        EntryResponse.of(unseen, date, favourited = false, reaction = null, image = null).shouldBeNull()
    }

    @Test
    fun `a withdrawn author's entry renders as the tombstone its reader is owed, while the row still holds its words`() {
        // No renderer asks about a withdrawal: each shape follows from the gate's answer, as it does for a delete.
        val withdrawn = setOf(author.memberId)
        val authorNow = Reader(author.memberId, bondId, withdrawn)
        val partnerNow = Reader(partner.memberId, bondId, withdrawn)
        val revealed = entry.copy(status = EntryStatus.REVEALED, revealedAt = now)

        val own = EntryResponse.of(entry.readBy(authorNow), date, favourited = false, reaction = null, image = null).shouldNotBeNull()
        own.text.shouldBeNull()
        own.status shouldBe EntryStatus.DELETED
        val read =
            PartnerEntryResponse.of(
                revealed.readBy(partnerNow),
                date,
                favourited = false,
                reaction = null,
                image = null,
            ) as EntryResponse
        read.text.shouldBeNull()
        read.status shouldBe EntryStatus.DELETED
        PartnerEntryResponse.of(entry.readBy(partnerNow), date, favourited = false, reaction = null, image = null) shouldBe
            ErasedEntryResponse(author.memberId, ErasedEntryStatus.REMOVED)
        EntryResponse.of(entry.readBy(partnerNow), date, favourited = false, reaction = null, image = null).shouldBeNull()
        entry.text.shouldNotBeNull()
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
        val response =
            EntryResponse
                .of(
                    entry.copy(text = EntryText.of(words)).readBy(author),
                    date,
                    favourited = false,
                    reaction = null,
                    image = null,
                ).shouldNotBeNull()
        val printed = listOf(request, request.toDraft(), response).map { it.toString() }

        printed.forEach { it shouldNotContain words }
        // What is useful in a log is still there: which entry, and when.
        printed[0] shouldBe "SubmitEntryRequest(text=(redacted), imageMediaId=null, voiceMediaId=null, intendedAt=$now, promptId=null)"
        printed[1] shouldBe "EntryDraft(text=(redacted), imageMediaId=null, voiceMediaId=null, intendedAt=$now, promptId=null)"
        // A prompt id is unchecked client text, erased with the words (BR-10a, FR-046): its presence, never its value.
        val raw = "0199e8a0-6a00-7000-8000-000000000001\nforged log line"
        SubmitEntryRequest(text = words, intendedAt = now, promptId = raw).let { named ->
            listOf(named.toString(), named.toDraft().toString()).forEach {
                it shouldContain "promptId=(given)"
                it shouldNotContain "0199e8a0"
            }
        }
        // The entry itself, once parsed, keeps the same silence: an erased entry forgets which prompt
        // it answered, and a log line that printed it would not. Everything else prints as before.
        val answering = entry.copy(promptId = PromptId(UUID.fromString("0199e8a0-6a00-7000-8000-000000000001")))
        answering.toString() shouldBe
            "Entry(id=${entry.id}, bondDayId=${entry.bondDayId}, bondId=$bondId, authorMemberId=${author.memberId}, " +
            "text=EntryText(redacted), imageMediaId=null, voiceMediaId=null, voiceDurationMs=null, promptId=(given), " +
            "status=SUBMITTED, authorDeletedAccount=false, createdAt=$now, intendedAt=$now, updatedAt=$now, " +
            "revealedAt=null, deletedAt=null)"
        answering.toString() shouldNotContain "0199e8a0"
        entry.toString() shouldContain "promptId=null,"
        printed[2] shouldContain "id=${entry.id.value}"
        printed[2] shouldContain "text=(redacted)"
        // A tombstone has no words, and says so rather than claiming to hide some.
        EntryResponse
            .of(
                entry.copy(deletedAt = now).readBy(author),
                date,
                favourited = false,
                reaction = null,
                image = null,
            ).toString() shouldContain
            "text=null"
    }
}
