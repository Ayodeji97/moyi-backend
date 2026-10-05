package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayAssignment
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * What [GetToday.today] hands the web layer: the date "today" resolves to in
 * the bond's own calendar, the status that date's row carries (or the status
 * it would open under when no row exists yet — see [GetToday]'s own KDoc),
 * and each side's entry **as BR-1 answered for this caller**.
 *
 * Both entries are [EntryReading]s, never [Entry]s: the gate has already run
 * by the time this object exists, and its answer travels with the entry it
 * was given for. The web layer chooses a shape from
 * [EntryReading.readability]; it has no entry to ask about, and no words to
 * render that the gate did not grant.
 */
internal data class TodayView(
    val date: LocalDate,
    val status: BondDayStatus,
    val myEntry: EntryReading?,
    val partnerEntry: EntryReading?,
)

/**
 * `GET /bonds/{bondId}/today` (spec §5.1): today's Bond-day, and each side's
 * entry, exactly as BR-1 gates it.
 *
 * **This query never writes; the request it serves sometimes does, before
 * it.** [today] is a read-only transaction and stays one.
 * `EntriesController.today` calls [ReconcileJoiningDay.beforeRead] first,
 * and that is where the one write is: while the couple's joining day is still
 * `SUSPENDED`, it takes the bond's row lock in a transaction of its own —
 * committed before this one opens — resumes that day, moves its `endsAt`
 * later if the calendar now says so, and puts it through the reveal rule. In
 * every other case `GET /today` writes nothing and takes no lock.
 *
 * [DayAssignment.dateFor] resolves which date "today" is, the same function [SubmitEntry] uses — `null` for `intendedAt`
 * (nobody backdates a read) and `{ false }` for `isSettled` (the lambda only
 * matters for an offline draft's claimed date, and a read makes no claim),
 * against the same effective-zone calendar ([asCalendar]) [SubmitEntry]
 * resolves its writes on. [BondDayStore.findByBondAndDate] then **reads** the
 * row rather than opening it: [BondDayStore.openOrGet] is `SubmitEntry`'s
 * own call, made once a write is actually happening, and calling it here
 * instead would `INSERT` a row for every bond anyone merely opened the app
 * on. A day nobody has written to yet has no row at all, and is reported as
 * whichever status [BondDayStore.openOrGet] *would* open it under —
 * [BondDayStatus.SUSPENDED] while [BondMembership.awaitingSecondMember]
 * (doc 04 §8.3a — the bond [SubmitEntry] opens today's row `SUSPENDED`
 * for), [BondDayStatus.OPEN] otherwise — without a row ever
 * being created. Getting this wrong the other way (always reporting `OPEN`)
 * would let the status travel backwards through an edge the state machine
 * does not have: the creator of a still-solo bond writes (J1), `SubmitEntry`
 * opens the row `SUSPENDED`, and a caller who had just been told `OPEN`
 * would watch it become `SUSPENDED` on their own write rather than staying
 * put — fix round 1, C1.
 *
 * **BR-1 is asked, never restated, for *both* entries — including the
 * caller's own.** `authorMemberId` selects *which* entry is "mine" (a routing
 * decision, not a security one); [Entry.readBy] alone decides what of either
 * is rendered. So the caller's own entry is exempt only because BR-1 exempts
 * it, and is a tombstone the moment BR-1 says an erased entry is one for its
 * author too. **The day's status plays no part**: BR-1 keys on the entry's
 * own `revealedAt` (spec §4), so [today] hands the gate no day to consult.
 *
 * **Nothing here is cached.** Every call re-reads the row and the entries
 * fresh; the day this returns is only ever as current as the transaction
 * that reads it. A cache in front of this method would have to be reasoned
 * about as a reveal-gate bypass in its own right (doc 12). There is none,
 * and `RevealGateTest`'s own "priming today as one member does not serve it
 * to the other" is what would catch it if one were added carelessly.
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
        // The same calendar SubmitEntry files against — the bond's effective
        // zone over time, not the zone it currently requests (BR-6). Reading
        // `anchorTimezone` here would, for the rest of the day a confirmed
        // change is deferred, report a date the caller's own write would not
        // land on.
        val date = DayAssignment.dateFor(clock.instant(), null, membership.anchorTimeline.asCalendar()) { false }
        val day = days.findByBondAndDate(membership.bondId, date)

        if (day == null) {
            val status = if (membership.awaitingSecondMember) BondDayStatus.SUSPENDED else BondDayStatus.OPEN
            return TodayView(date = date, status = status, myEntry = null, partnerEntry = null)
        }

        val reader = membership.asReader()
        val entryList =
            entries.findForDay(day.id).sortedWith(
                compareBy<Entry> { it.isErased }.thenByDescending { it.createdAt }.thenBy { it.id.value },
            )
        return TodayView(
            date = date,
            status = day.status,
            myEntry = entryList.firstOrNull { it.authorMemberId == membership.memberId }?.readBy(reader),
            partnerEntry = entryList.firstOrNull { it.authorMemberId != membership.memberId }?.readBy(reader),
        )
    }
}
