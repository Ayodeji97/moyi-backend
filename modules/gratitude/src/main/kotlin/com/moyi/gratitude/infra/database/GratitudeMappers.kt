package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
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
 * entity it came from. [Entry] has no update path in this task — nothing
 * here ever changes a submitted entry — so it has no `applyTo`.
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
 * `id`, `bondId`, `date`, `anchorTimezone`, `startsAt`, `endsAt` and
 * `createdAt` are not copied: none of them is a bond-day's to change after it
 * opens — the span above all, per [BondDay]'s own KDoc on why the interval it
 * was resolved against never moves. Neither is `version`, which is Hibernate's to increment — assigning
 * it here would fight the optimistic lock rather than use it.
 */
internal fun BondDay.applyTo(entity: BondDayEntity) {
    require(entity.getId() == id.value) { "cannot apply a bond-day onto a different bond-day's row" }
    entity.status = status
    entity.entryCount = entryCount.toShort()
    entity.revealedAt = revealedAt
    entity.closedAt = closedAt
}

/**
 * `EntryText.of` re-validates on the way out exactly as `ZoneId.of` does
 * above, including the 500-grapheme cap — which V12's `CHECK` does not
 * restate; the schema bounds `text` by octets alone. A row the database
 * would accept but this function would refuse to read cannot exist today
 * because every write already goes through `EntryText.of` first (`Entry.submit`),
 * so the two bounds never actually diverge — but they are two separate
 * statements of the limit, not one, and only the schema's is enforced at
 * the boundary a second writer could someday bypass.
 */
internal fun EntryEntity.toDomain(): Entry =
    Entry(
        id = EntryId(getId()),
        bondDayId = BondDayId(bondDayId),
        bondId = bondId,
        authorMemberId = authorMemberId,
        text = requireNotNull(text?.let(EntryText::of)) { "a stored entry always has text in this slice" },
        imageMediaId = imageMediaId,
        voiceMediaId = voiceMediaId,
        voiceDurationMs = voiceDurationMs,
        promptId = promptId,
        status = status,
        authorDeletedAccount = authorDeletedAccount,
        createdAt = createdAt,
        intendedAt = intendedAt,
        updatedAt = updatedAt,
        revealedAt = revealedAt,
        deletedAt = deletedAt,
    )

/** Insert-only — see the header comment on why [BondDay] has no equivalent. */
internal fun Entry.toEntity(): EntryEntity =
    EntryEntity(
        id = id.value,
        bondDayId = bondDayId.value,
        bondId = bondId,
        authorMemberId = authorMemberId,
        text = text.value,
        imageMediaId = imageMediaId,
        voiceMediaId = voiceMediaId,
        voiceDurationMs = voiceDurationMs,
        promptId = promptId,
        status = status,
        authorDeletedAccount = authorDeletedAccount,
        createdAt = createdAt,
        intendedAt = intendedAt,
        updatedAt = updatedAt,
        revealedAt = revealedAt,
        deletedAt = deletedAt,
    )
