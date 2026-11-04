package com.moyi.gratitude.web

import com.moyi.gratitude.service.DayView

/**
 * `200` from `GET /bonds/{bondId}/remember` (spec §3.6): the days of the caller's archive most
 * relevant to `q`, most relevant first, each the archive's [DayResponse]. No cursor: it is a
 * top-k answer, not a feed. Nothing says which entry matched (ADR-0039 decision 4), and no text
 * is generated.
 */
internal data class RememberResponse(
    val items: List<DayResponse>,
) {
    companion object {
        fun from(
            days: List<DayView>,
            partnerDisplayName: String?,
        ): RememberResponse = RememberResponse(days.map { DayResponse.from(it, partnerDisplayName) })
    }
}
