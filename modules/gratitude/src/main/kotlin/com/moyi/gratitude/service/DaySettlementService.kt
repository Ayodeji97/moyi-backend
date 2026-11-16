package com.moyi.gratitude.service

import com.moyi.gratitude.api.DateSettlement
import com.moyi.gratitude.api.DaySettlement
import com.moyi.gratitude.domain.LateEntryGrace
import com.moyi.gratitude.infra.database.DateSettlementQuery
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

/** [DaySettlement] at the close job's own threshold ([CloseElapsedDays.settledBy]), so the two cannot drift. */
@Service
internal class DaySettlementService(
    private val query: DateSettlementQuery,
    private val grace: LateEntryGrace,
) : DaySettlement {
    override fun on(
        date: LocalDate,
        now: Instant,
    ): DateSettlement = query.on(date, CloseElapsedDays.settledBy(grace, now))
}
