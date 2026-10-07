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
 * [next] is the date of the last day this page took from the archive, or
 * `null` when there is nothing older: the next page is the days strictly
 * before it.
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
 * [PAGE_TEXT_OCTETS] of entry text: two full entries a day are 16 KB, and
 * the largest page by count would be several times the 256 KB a response may
 * be (NFR-008). Days are taken in order while the text **this caller will be
 * sent** stays within the bound; a tombstone and a locked entry send none.
 * The first day is always taken, so a page is never empty for its size. Text
 * is measured as JSON will carry it ([octetsAsJson]), because that is what
 * the limit is on.
 *
 * **`favouritesOnly`** asks the query for days holding an entry the caller
 * has marked, and then **drops a day on which nothing marked can be read in
 * full now**. The query sees rows; a marked entry whose author has withdrawn
 * is still a whole row until the erasure reaches it, and to this caller it is
 * already a tombstone that says `favourited: false`. Such a day is not a
 * favourite. Dropping it after the gate means a page can hold fewer days
 * than were asked for, or none, and still have a [DaysPage.next]: the cursor
 * is the last day **taken from the archive**, kept or dropped, so the walk
 * goes on past it and ends when the archive does.
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
     * it smaller than one day: no entry can be large enough to reach the
     * real one alone, and the rule that the first day is always taken would
     * otherwise be a line nothing could show to be needed.
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
        // One more than the page: its presence is how "there is more" is known without a second query.
        val candidates = archive.candidates(membership.bondId, membership.memberId, before, until, favouritesOnly, limit + 1)
        val read = read(membership, candidates.take(limit))
        return read.paged(marksOf(membership, read), favouritesOnly, textOctets, more = candidates.size > read.size)
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
     * Takes days in order until the text bound is reached, and says where
     * the next page begins. [more] is whether the archive has a day older
     * than this window.
     */
    private fun List<Read>.paged(
        marked: Set<EntryId>,
        favouritesOnly: Boolean,
        textOctets: Int,
        more: Boolean,
    ): DaysPage {
        val days = mutableListOf<DayView>()
        var octets = 0
        var taken = 0
        var full = false
        for (day in this) {
            val kept = day.keptOf(marked)
            // With favourites only, a day on which nothing marked can be read any more is taken from the archive and not shown.
            val shown = !favouritesOnly || kept.isNotEmpty()
            // The first day shown is always taken. Once a day does not fit, nothing after it is taken either.
            full = full || (shown && days.isNotEmpty() && octets + day.octets > textOctets)
            if (!full) {
                taken++
                if (shown) {
                    days += day.shown(kept)
                    octets += day.octets
                }
            }
        }
        val last = this.getOrNull(taken - 1)?.day?.date
        return DaysPage(days, next = last.takeIf { more || taken < size })
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
         * 192 KiB of entry text in a page. With the most a page can carry
         * besides text (fifty days of two entries is about 35 KB of ids,
         * dates and names) that leaves a response under 256 KB.
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
         * It matters because an entry is bounded by octets as sent to us and
         * by 500 characters, not by octets as we send it: five hundred
         * control characters are 500 octets in and 3,000 out, and a page of
         * such entries counted raw would be past the limit while looking a
         * sixth of the way to it.
         */
        fun octetsAsJson(text: String): Int =
            text.toByteArray(Charsets.UTF_8).size +
                text.count { it < FIRST_UNESCAPED } * ESCAPED_CONTROL_EXTRA +
                text.count { it == '"' || it == '\\' }
    }
}
