package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.domain.PromptId
import java.time.ZoneId

/**
 * The translation between the domain model and the persistence model, as
 * plain functions — `BondMappers`' precedent (`modules/bond`), whose KDoc
 * gives the reason: MapStruct does not see Kotlin's value classes, and a
 * reflective mapper turns a rename into a runtime surprise instead of a
 * compile error.
 *
 * The direction matters the same way it does there, with one difference:
 * [BondDay] has no `toEntity`. Every row [BondDayStore] ever creates is
 * opened through [BondDayRepository.insertIfAbsent] — a single native
 * `INSERT ... ON CONFLICT DO NOTHING` statement, not an entity `save()` — so
 * there is no insert path here that would ever call one; adding a `toEntity`
 * nothing calls would be exactly the speculative code doc 12's review
 * checklist asks not to carry. [Entry] keeps its own [toEntity]: `EntryStore.insert`
 * genuinely does `save()` a freshly built entity. The update path for a
 * [BondDay] is [applyTo], which carries a changed aggregate onto the managed
 * entity it came from. [Entry] has no `applyTo`: an edit, an erasure and a
 * reveal are all written by `EntryStore.update`, which copies the fields
 * that can change onto the managed entity itself.
 *
 * `ZoneId.of` on the way **out** of the database re-validates the stored
 * id, the same call `RegionZone.of` makes in `bond`. A zone the JDK's tzdb
 * has since dropped fails here, loudly, rather than silently filing
 * somebody's day under the wrong date (doc 04 §6).
 */
internal fun BondDayEntity.toDomain(): BondDay =
    BondDay(
        id = BondDayId(getId()),
        bondId = bondId,
        date = date,
        status = status,
        anchorTimezone = ZoneId.of(anchorTimezone),
        startsAt = startsAt,
        endsAt = endsAt,
        entryCount = entryCount.toInt(),
        revealedAt = revealedAt,
        closedAt = closedAt,
        createdAt = createdAt,
        version = version,
    )

/**
 * Carries a changed [BondDay] onto the managed entity it came from — the
 * update path, for [BondDayStore.update].
 *
 * `id`, `bondId`, `date`, `anchorTimezone`, `startsAt` and `createdAt` are not
 * copied: none of them is a bond-day's to change after it opens. Neither is
 * `version`, which is Hibernate's to increment — assigning it here would
 * fight the optimistic lock rather than use it.
 *
 * **`endsAt` is copied, and may only move later** (ruling P10): the one
 * change to a day's span is [BondDay.extendedTo]'s, for the day a westward
 * anchor change merged into its successor's start. The `require` below is the
 * same extend-only rule, restated at the last point before the row is
 * written, so no other path to this function can shorten a day.
 */
internal fun BondDay.applyTo(entity: BondDayEntity) {
    require(entity.getId() == id.value) { "cannot apply a bond-day onto a different bond-day's row" }
    require(!endsAt.isBefore(entity.endsAt)) { "a bond-day's end only ever moves later: ${entity.endsAt}, not $endsAt" }
    entity.status = status
    entity.endsAt = endsAt
    entity.entryCount = entryCount.toShort()
    entity.revealedAt = revealedAt
    entity.closedAt = closedAt
}

/**
 * `EntryText.fromStorage` on the way out, **not** `EntryText.of`. `of` is the
 * write path's check and has tightened since rows were first written: the
 * owner's rulings of 2026-10-21 (ADR-0031) refuse a text of only zero-width
 * characters and count the 500 on the text as sent, trailing spaces
 * included. A row written under the old rule — and development and
 * production databases hold them — would fail `of`, and every read of its
 * day would be a `500`. `fromStorage` checks only what a stored text is and
 * a reader relies on (no NUL, no unpaired surrogate, within the octets, the
 * last also V12's `CHECK`); a row that fails it is broken, not old, and
 * fails here loudly as a zone the tzdb dropped does above. The grapheme cap
 * is not re-checked on read; the schema bounds `text` by octets alone, and
 * every write goes through `of` first (`Entry.submit`, an edit).
 *
 * A `NULL` text maps to `null`, not to a failure: that is a tombstone (see
 * [Entry]'s own KDoc), and [Entry]'s `init` is what refuses a missing text
 * on a row that is not one.
 */
internal fun EntryEntity.toDomain(): Entry =
    Entry(
        id = EntryId(getId()),
        bondDayId = BondDayId(bondDayId),
        bondId = bondId,
        authorMemberId = authorMemberId,
        text = text?.let(EntryText::fromStorage),
        imageMediaId = imageMediaId,
        voiceMediaId = voiceMediaId,
        voiceDurationMs = voiceDurationMs,
        promptId = promptId?.let(::PromptId),
        status = status,
        authorDeletedAccount = authorDeletedAccount,
        createdAt = createdAt,
        intendedAt = intendedAt,
        updatedAt = updatedAt,
        revealedAt = revealedAt,
        deletedAt = deletedAt,
    )

/** For the insert only (`EntryStore.insert`) — see the header comment on why [BondDay] has no equivalent. */
internal fun Entry.toEntity(): EntryEntity =
    EntryEntity(
        id = id.value,
        bondDayId = bondDayId.value,
        bondId = bondId,
        authorMemberId = authorMemberId,
        text = text?.value,
        textSearch = searchProjectionOf(text),
        imageMediaId = imageMediaId,
        voiceMediaId = voiceMediaId,
        voiceDurationMs = voiceDurationMs,
        promptId = promptId?.value,
        status = status,
        authorDeletedAccount = authorDeletedAccount,
        createdAt = createdAt,
        intendedAt = intendedAt,
        updatedAt = updatedAt,
        revealedAt = revealedAt,
        deletedAt = deletedAt,
    )
