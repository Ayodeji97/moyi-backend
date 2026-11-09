package com.moyi.support.service

import com.moyi.support.api.ExportedTicket
import com.moyi.support.api.SupportExports
import com.moyi.support.infra.database.SupportTickets
import org.springframework.stereotype.Service
import java.util.UUID

/** [SupportExports]: the person's own tickets, read as they are stored. */
@Service
internal class ExportSupportTickets(
    private val tickets: SupportTickets,
) : SupportExports {
    override fun ticketsOf(userId: UUID): List<ExportedTicket> = tickets.allOf(userId)
}
