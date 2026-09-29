package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayAssignment
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
 * whichever status [BondDayStore.openOrGet] *would* open it under —
 * [BondDayStatus.SUSPENDED] while [BondMembership.awaitingSecondMember]
 * (doc 04 §8.3a, the same condition [SubmitEntry] tests before its own
 * `openOrGet` call), [BondDayStatus.OPEN] otherwise — without a row ever
 * being created. Getting this wrong the other way (always reporting `OPEN`)
 * would let the status travel backwards through an edge the state machine
 * does not have: the creator of a still-solo bond writes (J1), `SubmitEntry`
 * opens the row `SUSPENDED`, and a caller who had just been told `OPEN`
 * would watch it become `SUSPENDED` on their own write rather than staying
 * put — fix round 1, C1.
 *
 * **BR-1 is asked, never restated, for *both* entries — including the
 * caller's own.** [Entry.canBeReadBy]'s own first clause
 * (`memberId == authorMemberId`) already makes [myEntry] readable without
 * this method special-casing it — but [today] still calls [Entry.canBeReadBy]
 * on it rather than assuming that clause is checked elsewhere, so a caller's
 * own entry stays exempt only because BR-1 currently exempts it. If that
 * clause is ever narrowed (a blocked partner, an archived bond), this method
 * follows without needing to change — fix round 1, I1: an earlier version of
 * this KDoc claimed this already and the code did not yet do it; asserted
 * now by `authorMemberId` selecting *which* entry is "mine" (a routing
 * decision, not a security one) and [Entry.canBeReadBy] alone deciding
 * whether it is rendered.
 *
 * **Nothing here is cached.** Every call re-reads the row and the entries
 * fresh; the day this returns is only ever as current as the transaction
 * that reads it. A cache in front of this method would have to be reasoned
 * about as a reveal-gate bypass in its own right (doc 12) — deliberately not
 * this slice's problem, and `RevealGateTest`'s own "priming today as one
 * member does not serve it to the other" is what would catch it if one were
 * added carelessly.
 *
 * **Neither `membership.hasLeft` nor `membership.isOpen` is checked.**
 * [BondMembership]'s own KDoc says, in bold, that a caller must check one of
 * them — but that instruction is aimed at a *write* path (`states.md` §9:
 * an ended bond stays a readable archive for both members, so it is the
 * write paths, not the reads, that have to refuse a member who has left or
 * a bond that has ended). This is a read, and BR-1 governs what it may show
 * regardless of bond status — the same reason `GetBond`'s own `view` takes
 * no such check either.
 */
@Service
internal class GetToday(
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun today(membership: BondMembership): TodayView {
        val zone = ZoneId.of(membership.anchorTimezone)
        val date = DayAssignment.dateFor(clock.instant(), null, zone) { false }
        val day = days.findByBondAndDate(membership.bondId, date)

        if (day == null) {
            val status = if (membership.awaitingSecondMember) BondDayStatus.SUSPENDED else BondDayStatus.OPEN
            return TodayView(date = date, status = status, myEntry = null, partnerEntry = null, partnerEntryVisible = false)
        }

        val entryList = entries.findForDay(day.id)
        val myEntry =
            entryList
                .firstOrNull { it.authorMemberId == membership.memberId }
                ?.takeIf { it.canBeReadBy(membership.memberId, day) }
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
