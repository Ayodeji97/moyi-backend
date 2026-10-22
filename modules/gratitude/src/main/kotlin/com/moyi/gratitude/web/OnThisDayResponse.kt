package com.moyi.gratitude.web

import com.moyi.gratitude.service.DayView

/**
 * `200` from `GET /bonds/{bondId}/on-this-day`: the days of earlier years on today's date,
 * newest first, each the archive's [DayResponse]. No cursor: it is every such day there is,
 * within a response's size (ADR-0039 decision 13). Empty on a bond that takes no writes.
 */
internal data class OnThisDayResponse(
    val items: List<DayResponse>,
) {
    companion object {
        fun from(days: List<DayView>): OnThisDayResponse = OnThisDayResponse(days.map(DayResponse::from))
    }
}
