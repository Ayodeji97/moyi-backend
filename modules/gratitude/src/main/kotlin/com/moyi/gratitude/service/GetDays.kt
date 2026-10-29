package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.ReactionType
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.infra.database.ArchiveDay
import com.moyi.gratitude.infra.database.ArchiveDays
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.Favourites
import com.moyi.gratitude.infra.database.Reactions
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * One day of the archive, for one member: its date, the status its row
 * carries, and each side's entry **as BR-1 answered for this caller**. The
 * same thing `TodayView` is for today, without the streak.
 *
 * Both entries are [EntryReading]s: the gate has run by the time this exists.
 * [status] is the day's own and is there to be shown; nothing was decided
 * from it.
 */
internal data class DayView(
    val date: LocalDate,
    val status: BondDayStatus,
    val myEntry: EntryReading?,
    val partnerEntry: EntryReading?,
    /** Which of this day's entries **the caller** has bookmarked. Only ever ids of entries read in full, and nobody else's marks. */
    val marked: Set<EntryId>,
    /** The reaction on each of this day's entries that has one, which both members are shown. Only ever of entries read in full. */
    val reactions: Map<EntryId, ReactionType>,
)

/**
 * A page of the archive, newest day first, and where the next one begins.
 * [next] is the date of the last day of [days], or `null` when there is
 * nothing older to show: the next page is the days strictly before it.
 *
 * One exception, which [GetDays.page] explains: a request that stopped at
 * its bound on reading gives the date of the last day it examined, and
 * [days] may then be short or empty.
 */
internal data class DaysPage(
    val days: List<DayView>,
    val next: LocalDate?,
)

/**
 * `GET /bonds/{bondId}/days` (FR-090, spec §6.6): the days of a bond a member
 * has something to see on, a page at a time.
 *
 * **The query lists; the gate decides what is shown.** [ArchiveDays] picks
 * the days (its KDoc has the rule). Every entry of a picked day then leaves
 * here as [Entry.readBy]'s answer, exactly as on `GET /today`, and nothing
 * else chooses a shape: not the day's status, not a column the query read. A
 * closed `SOLO` day whose entry was never revealed is its author's alone
 * (ADR-0033 decision 9), and it is the entry's `revealedAt`, asked by the
 * gate, that says so. How a picked day is shown is [shownTo]'s, the rule
 * `GET /today` uses: which row is "mine", an entry erased before the caller
 * was ever shown it left out, and the status as the day's erasures will
 * leave it.
 *
 * **So the query must not list a day for that entry alone**, or the day would
 * come back empty where a day the partner never wrote on is not listed at
 * all. It does not: an entry erased before its reveal was never revealed, and
 * [ArchiveDays]'s rule lists a day for its author or for a revealed row. Search
 * and on this day ask for a live row besides. `UnseenErasureTwinTest` holds the
 * five routes to the twin who never wrote.
 *
 * **Who has withdrawn is asked after the entries are loaded, not before**
 * ([readerNow], which has the reasoning). The [BondMembership] this is handed
 * was resolved by the controller before the joining-day reconcile, which can
 * wait on the bond's lock while an ending commits. It still says whose
 * archive this is and which member is "me", neither of which can change. It
 * does not say who has withdrawn: that is read again, last, so a withdrawal
 * committed at any point before then hides its author's words in every day
 * of the page, whatever the rows still hold.
 *
 * **A page has two bounds.** The count the caller asked for, and
 * [PAGE_TEXT_OCTETS] of entry text **as it will be sent**. An entry is at
 * most 8,192 octets as it arrives and can be six times that as JSON: the
 * largest there can be is 49,147 octets on the wire ([octetsAsJson] has
 * how). So one day can be 98 KB, and the largest page by count would be
 * nearly nineteen times the 256 KB a response may be (NFR-008). Days are
 * taken in order while the text **this caller will be sent** stays within
 * the bound; a tombstone and a locked entry send none. The first day is
 * always taken, so a page is never empty for its size. Text is measured as
 * JSON will carry it, because that is what the limit is on: measured as it
 * arrived, two of the largest days would count as 32 KB and be sent as
 * 197 KB.
 *
 * **`favouritesOnly`** asks the query for days holding an entry the caller
 * has marked, excluding known withdrawn authors before the limit, and then
 * **drops a day on which nothing marked can be read in full now**. The query sees rows; a marked entry whose author has withdrawn
 * is still a whole row until the erasure reaches it, and to this caller it is
 * already a tombstone that says `favourited: false`. Such a day is not a
 * favourite.
 *
 * **A dropped day leaves no trace in the page: the request reads on past
 * it.** The page is the days that are shown, and its cursor is the last of
 * them, exactly as if the dropped days had not been candidates. That is not
 * tidiness. A member who deletes an entry by hand takes every bookmark on it
 * away in the same transaction, so their partner's favourites never hold a
 * gap. A member who withdraws leaves the bookmarks until the erasure runs.
 * As first built, a dropped day still moved the cursor, so the partner could
 * be sent `{"items": [], "nextCursor": "…"}`, which a deletion by hand can
 * never produce: for as long as the consumer was behind, one old bookmark
 * told a withdrawal from a deletion (ADR-0028 decision 8 says nothing may;
 * found by review, with a twin bond; ADR-0036 decision 7).
 * `WithdrawalTwinArchiveTest` holds the two to one answer.
 *
 * **`favourited` is the caller's own**, asked once for the page and only of
 * entries the gate answered in full. No query here can return the other
 * person's marks.
 *
 * **Read-only, no lock, nothing cached.** Like [GetToday], this never
 * writes; the request it serves may, before it, when the joining day is still
 * `SUSPENDED` (`DaysController`). And like it, **neither `hasLeft` nor
 * `isOpen` is checked**: an ended bond stays a readable archive for both
 * members (ADR-0028, `states.md` §9), and this is that archive.
 */
@Service
internal class GetDays(
    private val archive: ArchiveDays,
    private val entries: EntryStore,
    private val access: BondAccess,
    private val favourites: Favourites,
    private val reactions: Reactions,
    private val clock: Clock,
) {
    /**
     * At most [limit] days, newest first, strictly before [before] and on or
     * before [until] where those are given.
     *
     * @throws IllegalArgumentException [limit] is outside 1..[MAX_LIMIT]. The
     * web layer has already refused such a request; this is the same bound,
     * asked here, so that no other caller can read a bond's history in one go.
     *
     * [textOctets] is the page's bound on text and is [PAGE_TEXT_OCTETS] for
     * every caller there is. It is a parameter only so that a test can make
     * it smaller than one day: no day can be large enough to reach the
     * real one alone (the largest is 98,294 octets of text, half of it), and
     * the rule that the first day is always taken would otherwise be a line
     * nothing could show to be needed.
     *
     * **It reads in windows until the page is decided.** A window is a run
     * of candidates from [ArchiveDays], read through the gate. The page is
     * decided when a day that would be shown is met after the page is
     * complete (there is more, and [DaysPage.next] is the last day shown),
     * or when the candidates run out (`null`). Without `favouritesOnly`
     * every candidate is shown and the first window, one more than [limit],
     * always decides. With it, a window can be all dropped days, and the
     * request reads the next one from where that ended: [REFILL_WINDOW]
     * candidates at a time, whatever [limit] is, so that a page of one
     * does not go back to the database for every two days.
     *
     * **Who has withdrawn is asked once per window, after that window's
     * entries are loaded** ([read]). Not once after the last window: whether
     * to read another window depends on what the gate dropped from this one,
     * so each window needs the answer before the next is fetched. And each
     * entry is then judged by an answer taken after its own row was read,
     * which is the whole of ADR-0035 decision 12's guarantee: a member who
     * was absent from the marker when asked had not committed their ending
     * when the row was read. Two windows can hold two answers if an ending
     * commits between them. The later days are then tombstones and the
     * earlier ones are not, each true when it was read; it is what two
     * requests a moment apart would have been sent.
     *
     * **The bound: at most [MAX_WINDOWS] windows.** This bounds work when
     * concurrent deletions or unmarks keep invalidating candidates. Known
     * withdrawn authors are excluded BEFORE LIMIT, freshly for each window,
     * so a static withdrawn history cannot consume the bound. A withdrawal
     * racing a window can invalidate that window, but the next query excludes
     * that author; a bond has only two members and markers are insert-only.
     * At the bound, look ahead before returning the last examined date. This
     * fallback is for concurrent changes, not an observable erasure backlog.
     */
    @Suppress("LongParameterList") // The page's own bounds, each a separate fact of the request.
    @Transactional(readOnly = true)
    fun page(
        membership: BondMembership,
        before: LocalDate?,
        until: LocalDate?,
        limit: Int,
        favouritesOnly: Boolean,
        textOctets: Int = PAGE_TEXT_OCTETS,
    ): DaysPage {
        require(limit in 1..MAX_LIMIT) { "a page of the archive is 1 to $MAX_LIMIT days" }
        return fill(
            membership,
            before,
            limit,
            textOctets,
            select = { from, size -> candidates(membership, from, until, favouritesOnly, size) },
            // With favourites only, a day on which nothing marked can be read any more is passed over.
            shows = { _, kept -> !favouritesOnly || kept.isNotEmpty() },
        )
    }

    /**
     * `GET /bonds/{bondId}/search` (FR-050, FR-091; ADR-0039): the days of the archive on which
     * one entry the caller can read in full holds every word of [terms], a page at a time.
     *
     * **[page]'s reading with another query.** The candidates are [ArchiveDays.matching]'s,
     * which excludes the known withdrawn before its `LIMIT`, asked afresh for every window
     * as the favourites feed asks. Every day is read through the gate, the marker last, and
     * **shown only if an entry that matched was read in full**: a withdrawal that commits
     * after the candidates were read makes that entry a tombstone, and the day is passed over
     * as `favourites=true` passes one over, leaving no trace in the page or its cursor
     * (ADR-0036 decision 7). A shown day is the whole day: both entries as the gate renders
     * them, and nothing to say which matched.
     *
     * **No `hasLeft` or `isOpen` check**: an ended bond is its archive, and is searched as it
     * is read (the controller's default 3).
     *
     * @throws IllegalArgumentException [limit] is outside 1..[MAX_LIMIT], as for [page].
     */
    @Transactional(readOnly = true)
    fun search(
        membership: BondMembership,
        terms: SearchTerms,
        before: LocalDate?,
        limit: Int,
        textOctets: Int = PAGE_TEXT_OCTETS,
    ): DaysPage {
        require(limit in 1..MAX_LIMIT) { "a page of the archive is 1 to $MAX_LIMIT days" }
        return fill(
            membership,
            before,
            limit,
            textOctets,
            select = { from, size ->
                // Asked again for every window: see `candidates`.
                val withdrawn = access.readerNow(membership).withdrawnAuthors
                archive.matching(membership.bondId, membership.memberId, terms, from, size, withdrawn)
            },
            shows = { day, _ -> day.matchedInFull },
        )
    }

    /**
     * `GET /bonds/{bondId}/on-this-day` (FR-092; ADR-0039): the days of earlier years dated the
     * bond's today, newest first, as the archive shows each.
     *
     * **Nothing, before anything is read, when the bond takes no writes from the caller**:
     * ended, left, blocked or counting down to deletion (the owner's Q-O3, decision 14). One
     * answer for every cause, so a block is not told from a leave. Asked of the membership
     * **resolved again here** ([membershipNow]), after the joining-day reconcile and before any
     * entry is read, not of the guard's: an ending can commit while the reconcile waits on the
     * bond's lock, and the guard's membership would still take writes (final review m2). An
     * ending that commits after this resolution is an answer from before the ending (ADR-0035
     * decision 12). The same resolution gives the withdrawn the candidates exclude.
     *
     * **Today is the bond's** (BR-3): [todayOf], as `GET /today` asks it, so both members are given the
     * same date and a memory changes at the bond's midnight.
     *
     * The candidates exclude days of tombstones and the known withdrawn ([ArchiveDays.onThisDay]);
     * the gate then reads every day, the marker last, and **a day is shown only if something on
     * it was read in full**: a withdrawal that commits after the candidates were read leaves its
     * author's days as tombstones, and those are not memories. Unpaginated: at most
     * [ArchiveDays.ON_THIS_DAY_YEARS] days, taken newest first while the text stays within
     * [textOctets] (the archive's bound, the first day always taken). Days past it are left out,
     * and are in the archive (decision 13).
     *
     * No opt-in is read: the owner's Q-O2 puts the opt-in on the weekly push (Phase 4), and
     * nothing of the partner's settings could reach this answer.
     */
    @Transactional(readOnly = true)
    fun onThisDay(
        membership: BondMembership,
        textOctets: Int = PAGE_TEXT_OCTETS,
    ): List<DayView> {
        val now = access.membershipNow(membership)
        if (!now.takesWrites) return emptyList()
        val today = todayOf(membership, clock)
        val candidates = archive.onThisDay(membership.bondId, membership.memberId, today, now.asReader().withdrawnAuthors)
        val read = read(membership, candidates).filter { it.readInFull.isNotEmpty() }
        val marked = marksOf(membership, read)
        val reacted = reactionsOn(read)
        val memories = Filling(ArchiveDays.ON_THIS_DAY_YEARS, textOctets)
        read.forEach { memories.offer(it, it.keptOf(marked), reacted) }
        return memories.days
    }

    /**
     * The reading [page] and [search] share, which [page]'s KDoc describes: candidates from
     * [select] in windows, each window's entries read through the gate with the marker read
     * after them, the days [shows] keeps offered in order to a [Filling], and the look-ahead
     * at the bound. [select] is asked for the days strictly before its date (none: from the
     * newest), at most its count. [shows] is given each day after the gate and the caller's
     * own marks among what it read in full.
     */
    @Suppress("LongParameterList") // Each a separate fact of the reading.
    private fun fill(
        membership: BondMembership,
        before: LocalDate?,
        limit: Int,
        textOctets: Int,
        select: (LocalDate?, Int) -> List<ArchiveDay>,
        shows: (Read, Set<EntryId>) -> Boolean,
    ): DaysPage {
        val page = Filling(limit, textOctets)
        var examined = before
        var older = true
        var windows = 0
        while (older && !page.more && windows < MAX_WINDOWS) {
            // The first window is one more than the page: that day's presence is how "there is more" is known.
            // `addExact`, though `limit` is at most MAX_LIMIT by the callers' checks: a sum that cannot wrap, whoever calls.
            val size = if (windows == 0) Math.addExact(limit, 1) else REFILL_WINDOW
            val candidates = select(examined, size)
            val read = read(membership, candidates)
            val marked = marksOf(membership, read)
            val reacted = reactionsOn(read)
            for (day in read) {
                val kept = day.keptOf(marked)
                if (shows(day, kept)) page.offer(day, kept, reacted)
            }
            windows++
            older = candidates.size == size
            examined = candidates.lastOrNull()?.date ?: examined
        }
        val next =
            when {
                page.more -> page.days.last().date

                // The bound was reached and the last window came back full. Full is not the same as "there is
                // more": the archive may end exactly there, and a cursor would then promise a page that does
                // not exist. So look once more, for one day, before giving it; then go on from the last day
                // examined, shown or not.
                older -> examined.takeIf { select(it, 1).isNotEmpty() }

                else -> null
            }
        return DaysPage(page.days, next)
    }

    /**
     * Omit known withdrawals before applying LIMIT. Ask again for every window,
     * including look-ahead: a withdrawal that races one query cannot leave a
     * history's worth of invisible candidates in the next. The gate still
     * reads the marker AFTER loading entries, so this filter grants no access.
     * Bond facts come through BondAccess, never a cross-module table join.
     */
    private fun candidates(
        membership: BondMembership,
        before: LocalDate?,
        until: LocalDate?,
        favouritesOnly: Boolean,
        limit: Int,
    ): List<ArchiveDay> =
        archive.candidates(
            membership.bondId,
            membership.memberId,
            before,
            until,
            favouritesOnly,
            limit,
            if (favouritesOnly) access.readerNow(membership).withdrawnAuthors else emptySet(),
        )

    /**
     * `GET /bonds/{bondId}/days/{date}`: that one day as the feed would give
     * it, or `null` when the feed would not list it for this member.
     *
     * **Not a second reading of a day.** The day is found by the feed's own
     * rule ([ArchiveDays.on]), its entries are read by [read] and it becomes
     * a [DayView] by [Read.shown], which are what [page] uses: the marker is
     * read last here because it is read last there, and a day cannot look
     * one way in the feed and another alone.
     *
     * **`null` has one meaning to a caller and many causes**, deliberately
     * not told apart: no row for the date, a day nobody wrote on, a day
     * holding only an entry the other person wrote and this member has not
     * been shown. Above all the last: to answer it differently would be to
     * say that they wrote.
     */
    @Transactional(readOnly = true)
    fun day(
        membership: BondMembership,
        date: LocalDate,
    ): DayView? {
        val day = archive.on(membership.bondId, membership.memberId, date) ?: return null
        val read = read(membership, listOf(day))
        val marked = marksOf(membership, read)
        return read.single().let { it.shown(it.keptOf(marked), reactionsOn(read)) }
    }

    /**
     * Each of [days] after the gate, in order: its entries loaded in one
     * query, **then** who has withdrawn asked, then each side read.
     */
    private fun read(
        membership: BondMembership,
        days: List<ArchiveDay>,
    ): List<Read> {
        val written = entries.findForDays(days.map { it.id })
        // After the entries, never before: the marker is read last.
        val reader = access.readerNow(membership)
        return days.map { day -> Read(day, written[day.id].orEmpty().shownTo(reader, day.status, day.closedAt, day.entryCount)) }
    }

    /** The caller's own marks among [read]: asked after the gate, and only of what it answered in full. */
    private fun marksOf(
        membership: BondMembership,
        read: List<Read>,
    ): Set<EntryId> = favourites.markedBy(membership.memberId, read.flatMap { it.readInFull })

    /** The reactions on the entries of [read]: asked after the gate, of what it answered in full, and of no member. */
    private fun reactionsOn(read: List<Read>): Map<EntryId, ReactionType> = reactions.on(read.flatMap { it.readInFull })

    /**
     * A page being filled: days that will be shown are offered in order,
     * and it takes them until it has [limit] or the next would take the text
     * past [textOctets]. The first day offered is always taken.
     *
     * [more] becomes true at the first day offered that it does not take.
     * Nothing is taken after that, so the page is its first days and never
     * a later one that happened to fit.
     */
    private class Filling(
        private val limit: Int,
        private val textOctets: Int,
    ) {
        private val taken = mutableListOf<DayView>()
        private var octets = 0

        val days: List<DayView> get() = taken

        /** Whether a day that would be shown was met and not taken: the archive has more for this caller than this page. */
        var more = false
            private set

        fun offer(
            day: Read,
            kept: Set<EntryId>,
            reacted: Map<EntryId, ReactionType>,
        ) {
            more = more || taken.size == limit || (taken.isNotEmpty() && octets + day.octets > textOctets)
            if (!more) {
                taken += day.shown(kept, reacted)
                octets += day.octets
            }
        }
    }

    /** A candidate day after the gate: what the caller may see of each side, and the status they are shown ([shownTo]). */
    private class Read(
        val day: ArchiveDay,
        private val shown: ShownDay,
    ) {
        private val readings = listOfNotNull(shown.mine, shown.partners)

        val readInFull: List<EntryId> = readings.filter { it.readability == Readability.FULL }.mapNotNull { it.disclosed?.id }

        /**
         * Whether an entry of this day that matched a search ([ArchiveDay.matched]) was read in full:
         * the only reason a search shows a day.
         */
        val matchedInFull: Boolean get() = readInFull.any { it in day.matched }

        /** Which of [marked] are entries of this day read in full: the only ones that may say they are kept. */
        fun keptOf(marked: Set<EntryId>): Set<EntryId> = readInFull.filter { it in marked }.toSet()

        /**
         * This day as a caller is given it, in the feed and alone. [reacted]
         * may hold other days' entries; only this day's, read in full, are
         * taken from it.
         */
        fun shown(
            kept: Set<EntryId>,
            reacted: Map<EntryId, ReactionType>,
        ): DayView = DayView(day.date, shown.status, shown.mine, shown.partners, kept, reacted.filterKeys { it in readInFull })

        /** The octets of text this day will send to this caller. A reading that discloses no text sends none. */
        val octets: Int =
            readings.sumOf { reading ->
                reading.disclosed
                    ?.text
                    ?.value
                    ?.let(::octetsAsJson) ?: 0
            }
    }

    internal companion object {
        /** The most days a page may be asked for, and what it is when nothing is asked (doc 06 §3). */
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 20

        /**
         * How many candidates a request reads at a time once its first
         * window has not decided the page, and how many windows it reads at
         * most. `page` says when either matters. Together they bound one
         * request to about a thousand days and, at about eight statements a
         * window, to about 160 statements.
         */
        const val REFILL_WINDOW = MAX_LIMIT + 1
        const val MAX_WINDOWS = 20

        /**
         * 192 KiB (196,608 octets) of entry text in a page, counted as
         * JSON carries it. Two of the largest days there can be fit in it
         * (196,588 octets) and a third does not. With the most a page can
         * carry besides text (fifty days of two entries are about 34 KB
         * of ids, dates and field names) the largest response there can be
         * is about 231 KB, under the 262,144 a response may be.
         * `DaysFeedTest` builds that page and measures it: 230,663 octets,
         * with the test clock's whole-second timestamps and no reactions.
         * An instant in production carries microseconds, seven octets more
         * in each of two fields of a hundred entries: about 1.4 KB. A
         * reaction's longest name is seven octets more than `null` on each
         * of them: 0.7 KB.
         *
         * So the margin is about 29 KB, and it is what a new field on an
         * entry spends: a hundred entries a page, so every thirty octets
         * added to one are 3 KB off it. `reaction` (slice C5c) spent 2.3.
         * `partnerDisplayName` (ADR-0036 question 5) is on the day, so fifty
         * a page, not a hundred, but a name of 80 characters can be written
         * as 470 octets: at its largest it spends about 25 KB, and
         * `DaysFeedTest` measures the largest page with it. What is left is
         * about 4 KB, and a field on a day or an entry now needs this bound
         * looked at again. The name it measures with is
         * `ArchivePageBudget.MEASURED_DISPLAY_NAME_LENGTH`, and
         * `OpenApiContractTest` ("the longest display name identity takes is
         * no longer than the archive's largest page was measured with") fails
         * if identity ever takes a longer one.
         */
        const val PAGE_TEXT_OCTETS = 192 * 1024

        private const val ESCAPED_CONTROL_EXTRA = 5
        private const val FIRST_UNESCAPED = ' '

        /**
         * How many octets [text] can take up inside a JSON string: its UTF-8
         * length, and what escaping adds. A control character is written as
         * up to six octets where it was one (`\u0001`), a quote or a
         * backslash as two. An upper bound and not the serializer's exact
         * count (it has shorter forms for a few), which is the safe side: a
         * page may be a little smaller than it had to be, never larger.
         *
         * It matters because an entry is bounded by octets as sent to us
         * (8,192), not by octets as we send it, and its limit of 500
         * characters does not make up the difference. That limit is counted
         * on the text trimmed, and U+001C to U+001F are trimmed as space
         * and escaped as controls. So `a` followed by 8,191 of them is
         * accepted as one character, is 8,192 octets in and 49,147 out, and
         * a page of such entries counted raw would be past the limit while
         * looking a sixth of the way to it.
         */
        fun octetsAsJson(text: String): Int =
            text.toByteArray(Charsets.UTF_8).size +
                text.count { it < FIRST_UNESCAPED } * ESCAPED_CONTROL_EXTRA +
                text.count { it == '"' || it == '\\' }
    }
}
