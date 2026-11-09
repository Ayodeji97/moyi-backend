package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.gratitude.api.ArchiveExports
import com.moyi.gratitude.api.ExportedDay
import com.moyi.gratitude.api.ExportedDays
import com.moyi.gratitude.api.ExportedEntry
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.Readability
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * [ArchiveExports], answered by [GetDays.page], the archive route's own reading (ADR-0059 decision
 * 3). Nothing here decides what a member may read: the guard says whether they are a member, the
 * gate says what each entry is to them, and this only maps what the route would have sent. A
 * favourite, a reaction and media are carried only on an entry read in full, as `EntryResponse`
 * carries them, so a tombstone carries none of them.
 */
@Service
internal class ExportMemberArchive(
    private val access: BondAccess,
    private val days: GetDays,
) : ArchiveExports {
    override fun page(
        userId: UUID,
        bondId: UUID,
        before: LocalDate?,
    ): ExportedDays {
        val membership = access.membershipOf(userId, bondId)
        val page = days.page(membership, before, until = null, limit = GetDays.MAX_LIMIT, favouritesOnly = false)
        return ExportedDays(
            days =
                page.days
                    .map { day ->
                        ExportedDay(
                            date = day.date,
                            status = day.status.name,
                            entries =
                                listOfNotNull(
                                    day.myEntry?.let { entry(it, mine = true, day) },
                                    day.partnerEntry?.let { entry(it, mine = false, day) },
                                ),
                        )
                    }.filter { it.entries.isNotEmpty() },
            next = page.next,
        )
    }

    private fun entry(
        reading: EntryReading,
        mine: Boolean,
        day: DayView,
    ): ExportedEntry? {
        val entry = reading.disclosed ?: return null
        val inFull = reading.readability == Readability.FULL
        val media = day.media[entry.id]?.takeIf { inFull }
        return ExportedEntry(
            id = entry.id.value,
            mine = mine,
            status = entry.status.name,
            text = entry.text?.value,
            createdAt = entry.createdAt,
            intendedAt = entry.intendedAt,
            favourited = inFull && entry.id in day.marked,
            reaction = day.reactions[entry.id]?.name?.takeIf { inFull },
            // Only what the app would give a URL for: never one still processing, nor one held back from the partner.
            imageId = media?.image?.takeIf { it.url != null }?.id,
            voiceId = media?.voice?.takeIf { it.url != null }?.id,
        )
    }
}
