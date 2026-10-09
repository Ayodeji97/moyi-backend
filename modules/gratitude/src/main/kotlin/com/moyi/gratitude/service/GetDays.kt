package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.infra.database.ArchiveDay
import com.moyi.gratitude.infra.database.ArchiveDays
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.Favourites
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
 * gate, that says so. Which row of a day is "mine" is [onEachSideOf]'s, the
 * rule `GET /today` uses.
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
 * has marked, and then **drops a day on which nothing marked can be read in
 * full now**. The query sees rows; a marked entry whose author has withdrawn
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
     * **The bound: at most [MAX_WINDOWS] windows.** It is reached only when
     * a request meets more dropped days in a row than the windows hold
     * (about a thousand: `limit + 1`, then nineteen of [REFILL_WINDOW]):
     * a member who bookmarked that many days of a partner's entries, the
     * partner withdrew, and the erasure has not run. The request then
     * returns what it has, with the date of the last day it **examined** as
     * the cursor, so the walk goes on from there and skips nothing. Such a
     * page can be short or empty and still carry a cursor, which is what
     * every page with a dropped day looked like before, and is the one case
     * left where a withdrawal that nothing has erased can be told from a
     * deletion by hand. Without a bound, one request could read a bond's
     * whole history.
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
        val page = Filling(limit, textOctets)
        var examined = before
        var older = true
        var windows = 0
        while (older && !page.more && windows < MAX_WINDOWS) {
            // The first window is one more than the page: that day's presence is how "there is more" is known.
            val size = if (windows == 0) limit + 1 else REFILL_WINDOW
            val candidates = archive.candidates(membership.bondId, membership.memberId, examined, until, favouritesOnly, size)
            val read = read(membership, candidates)
            val marked = marksOf(membership, read)
            for (day in read) {
                val kept = day.keptOf(marked)
                // With favourites only, a day on which nothing marked can be read any more is passed over.
                if (!favouritesOnly || kept.isNotEmpty()) page.offer(day, kept)
            }
            windows++
            older = candidates.size == size
            examined = candidates.lastOrNull()?.date ?: examined
        }
        val next =
            when {
                page.more -> page.days.last().date

                // The bound was reached with candidates left: go on from the last one examined, shown or not.
                older -> examined

                else -> null
            }
        return DaysPage(page.days, next)
    }

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
        return read.single().let { it.shown(it.keptOf(marked)) }
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
        return days.map { day ->
            val sides = written[day.id].orEmpty().onEachSideOf(membership.memberId)
            Read(day, sides.mine?.readBy(reader), sides.partners?.readBy(reader))
        }
    }

    /** The caller's own marks among [read]: asked after the gate, and only of what it answered in full. */
    private fun marksOf(
        membership: BondMembership,
        read: List<Read>,
    ): Set<EntryId> = favourites.markedBy(membership.memberId, read.flatMap { it.readInFull })

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
        ) {
            more = more || taken.size == limit || (taken.isNotEmpty() && octets + day.octets > textOctets)
            if (!more) {
                taken += day.shown(kept)
                octets += day.octets
            }
        }
    }

    /** A candidate day after the gate: what the caller may see of each side. */
    private class Read(
        val day: ArchiveDay,
        val mine: EntryReading?,
        val partners: EntryReading?,
    ) {
        private val readings = listOfNotNull(mine, partners)

        val readInFull: List<EntryId> = readings.filter { it.readability == Readability.FULL }.mapNotNull { it.disclosed?.id }

        /** Which of [marked] are entries of this day read in full: the only ones that may say they are kept. */
        fun keptOf(marked: Set<EntryId>): Set<EntryId> = readInFull.filter { it in marked }.toSet()

        /** This day as a caller is given it, in the feed and alone. */
        fun shown(kept: Set<EntryId>): DayView = DayView(day.date, day.status, mine, partners, kept)

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
         * carry besides text (fifty days of two entries are about 32.5 KB
         * of ids, dates and field names) the largest response there can be
         * is about 229 KB, under the 262,144 a response may be.
         * `DaysFeedTest` builds that page and measures it: 229,063 octets.
         *
         * The margin is 33 KB and is what a new field on an entry spends:
         * a hundred entries a page, so every thirty octets added to one are
         * 3 KB off it.
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
