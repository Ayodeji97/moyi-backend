package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.SupportConsole
import com.moyi.common.security.ClientAddressResolver
import com.moyi.support.api.TicketCloseOutcome
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Clock
import java.util.UUID

/**
 * The support inbox (A4 spec §4, ADR-0057, ADR-0067 decisions 12 and 13), for every role. The list
 * shows ids, lengths and ages, never the words; a ticket's page shows its words, its reference and
 * the address to answer at. Closing empties the words, so it is A2's gated decision: the same form,
 * the same flow (`Decisions.decideGated`), the same answers.
 *
 * Named `adminSupportController`: `support` has a `SupportController` of its own (the member's).
 */
@Controller("adminSupportController")
@ConditionalOnAdminConsole
@PreAuthorize("hasRole('SUPPORT')")
internal class SupportInboxController(
    private val console: SupportConsole,
    private val secondFactor: SecondFactor,
    private val addresses: ClientAddressResolver,
    private val clock: Clock,
) {
    @GetMapping(INBOX)
    fun inbox(
        authentication: Authentication,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val now = clock.instant()
        val rows = console.inbox(ModerationPages.adminId(authentication), addresses.orNull(request))
        model.addAttribute("rows", rows.map { TicketItem(it, ModerationPages.ageInDays(it.receivedAt, now)) })
        model.addAttribute("moderator", secondFactor.moderates(authentication))
        return "admin/support"
    }

    @GetMapping("$INBOX/{ticketId}")
    @Suppress("LongParameterList", "ReturnCount") // The path, who asked from where, the session's window, the page; a return per 404.
    fun ticket(
        @PathVariable ticketId: String,
        authentication: Authentication,
        request: HttpServletRequest,
        session: HttpSession?,
        model: Model,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(ticketId) ?: return ModerationPages.notFound()
        val case =
            console.ticket(id, ModerationPages.adminId(authentication), addresses.orNull(request)) ?: return ModerationPages.notFound()
        model.addAttribute("case", case)
        model.addAttribute("moderator", secondFactor.moderates(authentication))
        if (case.open) model.addAttribute("closeForm", closeForm(id).again("", secondFactor.asksForCode(session), null))
        return ModelAndView("admin/ticket", model.asMap())
    }

    @PostMapping("$INBOX/{ticketId}/close")
    @Suppress("LongParameterList") // The path, the form's two fields, who asked from where, and the session.
    fun close(
        @PathVariable ticketId: String,
        @RequestParam(defaultValue = "") note: String,
        @RequestParam(defaultValue = "") code: String,
        authentication: Authentication,
        request: HttpServletRequest,
        session: HttpSession,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(ticketId) ?: return ModerationPages.notFound()
        val adminId = ModerationPages.adminId(authentication)
        val from = addresses.orNull(request)
        return Decisions.decideGated(
            closeForm(id),
            note,
            redirect,
            prove = { secondFactor.prove(session, adminId, code, from) },
        ) { secondFactorAt -> lineOf(console.close(id, note, secondFactorAt, adminId, from)) }
    }

    /** One open ticket as the list shows it. [toString] names the ticket only. */
    class TicketItem(
        val row: com.moyi.support.api.TicketRow,
        val ageDays: Long?,
    ) {
        override fun toString(): String = "TicketItem(${row.ticketId})"
    }

    companion object {
        const val INBOX = "/admin/support"
        const val CLOSE_LABEL = "Close this ticket"
        const val CLOSED = "Closed. Its words are gone."
        const val NOT_OPEN = "Already closed. Nothing changed."

        fun pageOf(id: UUID): String = "$INBOX/$id"

        fun lineOf(outcome: TicketCloseOutcome): String =
            when (outcome) {
                TicketCloseOutcome.CLOSED -> CLOSED
                TicketCloseOutcome.NOT_OPEN -> NOT_OPEN
            }

        private fun closeForm(id: UUID) =
            DecisionForm(
                id = "close",
                back = pageOf(id),
                action = "${pageOf(id)}/close",
                label = CLOSE_LABEL,
                gated = true,
                warning = "Closing deletes the ticket's words and reference. Answer the person first.",
            )
    }
}
