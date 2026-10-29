package com.moyi.gratitude.web

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.service.DayView
import com.moyi.gratitude.service.DaysPage
import java.time.LocalDate

/**
 * One day of the archive (FR-090): the day `GET /today` shows while it is
 * today, without the streak, and with the partner's name.
 *
 * [status] is the day's, as its erasures will leave it, shared deliberately
 * as it is on `today` (`TodayResponse` has the reasoning). [myEntry] and
 * [partnerEntry] are the only places content can appear, and each is rendered
 * from BR-1's answer by the same two functions `today` uses, so an entry looks
 * the same on both routes: in full, a tombstone, or for a partner's entry the
 * caller has not been shown, an author and a status and nothing else; once
 * such an entry is erased, `null`, as if it had never been written
 * (`service.shownTo`, ADR-0032 question 2).
 *
 * **Nothing here is about the other person beyond that.** No `deletedAt` or
 * `updatedAt` (when somebody erased or changed something is not the reader's
 * to know), and `favourited` is the caller's own mark: the partner's marks
 * are in no field, count or flag of any response (FR-093, FR-064).
 *
 * **One addition, [partnerDisplayName], and it discloses nothing new**: the
 * name the other member goes by, which the caller's own `GET /bonds/{bondId}`
 * already gives both members. The archive's author label needs it (ADR-0036
 * question 5, ruled 2026-10-21), and `bond` owes it
 * (`BondAccess.partnerDisplayNameOf`, which has its rules: `null` only before
 * a second member ever joined, the name of one who left or blocked, and
 * `"Former member"` for an account identity no longer knows). It is on each
 * day and not on the page, so that `GET /days/{date}` stays the feed's
 * element byte for byte; it is the same on every day of a bond, looked up once
 * a request. Read as it is now, so a rename moves the archive's `ETag`, which
 * is right: the tag is the body. `GET /today` does not carry it (the ruling
 * names the archive).
 */
internal data class DayResponse(
    val date: LocalDate,
    val status: BondDayStatus,
    val myEntry: EntryResponse?,
    val partnerEntry: PartnerEntryResponse?,
    val partnerDisplayName: String?,
) {
    companion object {
        /** [partnerDisplayName] is the request's one answer from `BondAccess.partnerDisplayNameOf`, the same for every day of it. */
        fun from(
            view: DayView,
            partnerDisplayName: String?,
        ): DayResponse =
            DayResponse(
                date = view.date,
                status = view.status,
                myEntry =
                    view.myEntry?.let {
                        EntryResponse.of(
                            it,
                            view.date,
                            it.disclosed?.id in view.marked,
                            view.reactions[it.disclosed?.id],
                            view.media[it.disclosed?.id],
                        )
                    },
                partnerEntry =
                    view.partnerEntry?.let {
                        val id = it.disclosed?.id
                        PartnerEntryResponse.of(it, view.date, id in view.marked, view.reactions[id], view.media[id])
                    },
                partnerDisplayName = partnerDisplayName,
            )
    }
}

/**
 * `200` from `GET /bonds/{bondId}/days`: a page of days, newest first.
 *
 * [nextCursor] is `null` on the last page. Otherwise it is handed back,
 * unchanged, as `cursor` to get the days before these. **A page may hold
 * fewer days than `limit`, or none, and still have a [nextCursor]**: a page
 * is also bounded by its size, and with `favourites=true` a day can be left
 * out after it was counted. Only a `null` cursor means the end.
 */
internal data class DaysResponse(
    val items: List<DayResponse>,
    val nextCursor: String?,
) {
    companion object {
        fun from(
            page: DaysPage,
            partnerDisplayName: String?,
        ): DaysResponse =
            DaysResponse(
                items = page.days.map { DayResponse.from(it, partnerDisplayName) },
                nextCursor = page.next?.let { DayCursor(it).encode() },
            )
    }
}
