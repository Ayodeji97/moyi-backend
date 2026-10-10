package com.moyi.gratitude.web

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.service.DayView
import com.moyi.gratitude.service.DaysPage
import java.time.LocalDate

/**
 * One day of the archive (FR-090): the day `GET /today` shows while it is
 * today, without the streak.
 *
 * [status] is the day's own, shared deliberately as it is on `today`
 * (`TodayResponse` has the reasoning). [myEntry] and [partnerEntry] are the
 * only places content can appear, and each is rendered from BR-1's answer by
 * the same two functions `today` uses, so an entry looks the same on both
 * routes: in full, a tombstone, or for a partner's entry the caller was
 * never shown, an author and a status and nothing else.
 *
 * **Nothing here is about the other person beyond that.** No `deletedAt` or
 * `updatedAt` (when somebody erased or changed something is not the reader's
 * to know), and `favourited` is the caller's own mark: the partner's marks
 * are in no field, count or flag of any response (FR-093, FR-064).
 */
internal data class DayResponse(
    val date: LocalDate,
    val status: BondDayStatus,
    val myEntry: EntryResponse?,
    val partnerEntry: PartnerEntryResponse?,
) {
    companion object {
        fun from(view: DayView): DayResponse =
            DayResponse(
                date = view.date,
                status = view.status,
                myEntry = view.myEntry?.let { EntryResponse.of(it, view.date, it.disclosed?.id in view.marked) },
                partnerEntry = view.partnerEntry?.let { PartnerEntryResponse.of(it, view.date, it.disclosed?.id in view.marked) },
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
        fun from(page: DaysPage): DaysResponse =
            DaysResponse(items = page.days.map(DayResponse::from), nextCursor = page.next?.let { DayCursor(it).encode() })
    }
}
