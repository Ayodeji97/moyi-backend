package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayAssignment
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * What [GetToday.today] hands the web layer: the date "today" resolves to in
 * the bond's own zone, the status that date's row carries (or [BondDayStatus.OPEN]
 * when no row exists yet — see [GetToday]'s own KDoc), the caller's own entry
 * in full, and their partner's entry exactly as BR-1 says the caller may see
 * it.
 *
 * [partnerEntry] and [partnerEntryVisible] travel separately rather than as
 * one nullable-or-not field, because "absent" and "present but locked" are
 * different facts the web layer has to tell apart to choose between
 * [com.moyi.gratitude.web.LockedEntryResponse] and nothing at all —
 * collapsing them here would make the controller re-derive [Entry.canBeReadBy]
 * a second time, or trust a `null` that could mean either.
 */
internal data class TodayView(
    val date: LocalDate,
    val status: BondDayStatus,
    val myEntry: Entry?,
    val partnerEntry: Entry?,
    val partnerEntryVisible: Boolean,
)

/**
 * `GET /bonds/{bondId}/today` (spec §6.1): today's Bond-day, and each side's
 * entry, exactly as BR-1 gates it.
 *
 * **A read never writes.** [DayAssignment.dateFor] resolves which date
 * "today" is, the same function [SubmitEntry] uses — `null` for `intendedAt`
 * (nobody backdates a read) and `{ false }` for `isClosed` (today is never a
 * closed day; the function needs the lambda to resolve an offline draft's
 * date, not this one). [BondDayStore.findByBondAndDate] then **reads** the
 * row rather than opening it: [BondDayStore.openOrGet] is `SubmitEntry`'s
 * own call, made once a write is actually happening, and calling it here
 * instead would `INSERT` a row for every bond anyone merely opened the app
 * on. A day nobody has written to yet has no row at all, and is reported as
 * [BondDayStatus.OPEN] — the status it would open under — without one ever
 * being created.
 *
 * **BR-1 is asked, never restated.** [Entry.canBeReadBy] already states the
 * rule this method has to apply to both entries; [today] calls it once per
 * entry and keeps nothing of its own logic about who may read what. The
 * caller's own entry is included in [entryList] the same as their partner's,
 * and run through the identical check — it is not special-cased as "always
 * visible", because [Entry.canBeReadBy]'s own first clause
 * (`memberId == authorMemberId`) already makes that true without this method
 * having to know why.
 *
 * **Nothing here is cached.** Every call re-reads the row and the entries
 * fresh; the day this returns is only ever as current as the transaction
 * that reads it. A cache in front of this method would have to be reasoned
 * about as a reveal-gate bypass in its own right (doc 12) — deliberately not
 * this slice's problem, and `RevealGateTest`'s own "priming today as one
 * member does not serve it to the other" is what would catch it if one were
 * added carelessly.
 */
@Service
internal class GetToday(
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val clock: Clock,
) {
    fun today(membership: BondMembership): TodayView {
        val zone = ZoneId.of(membership.anchorTimezone)
        val date = DayAssignment.dateFor(clock.instant(), null, zone) { false }
        val day = days.findByBondAndDate(membership.bondId, date)

        if (day == null) {
            return TodayView(date = date, status = BondDayStatus.OPEN, myEntry = null, partnerEntry = null, partnerEntryVisible = false)
        }

        val entryList = entries.findForDay(day.id)
        val myEntry = entryList.firstOrNull { it.authorMemberId == membership.memberId }
        val partnerEntry = entryList.firstOrNull { it.authorMemberId != membership.memberId }
        val partnerEntryVisible = partnerEntry != null && partnerEntry.canBeReadBy(membership.memberId, day)

        return TodayView(
            date = date,
            status = day.status,
            myEntry = myEntry,
            partnerEntry = partnerEntry,
            partnerEntryVisible = partnerEntryVisible,
        )
    }
}
