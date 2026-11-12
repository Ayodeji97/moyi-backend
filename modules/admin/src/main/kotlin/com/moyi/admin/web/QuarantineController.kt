package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.ModerationConsole
import com.moyi.admin.service.QuarantinePage
import com.moyi.common.security.ClientAddressResolver
import com.moyi.media.api.BlockOutcome
import com.moyi.media.api.DeleteOutcome
import com.moyi.media.api.QuarantineRow
import com.moyi.media.api.ReleaseOutcome
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Clock
import java.util.UUID

/**
 * The quarantine queue (A2 spec §5.2): the list, one held-back asset's page, and its three
 * decisions. "Block as evidence" is listed first, with the runbook's words. No form is offered
 * while an open report holds the asset (it is decided in the report), nor for blocked evidence
 * (deleting it is a server step); a forged POST is refused by the port under the row lock all the
 * same. Every decision redirects to the list, which shows each asset's new state: a released or
 * deleted asset has no page of its own any more.
 */
@Controller
@ConditionalOnAdminConsole
@PreAuthorize("hasRole('MODERATOR')")
@Suppress("TooManyFunctions") // One handler per route of spec §5.2, and the three forms they render.
internal class QuarantineController(
    private val console: ModerationConsole,
    private val secondFactor: SecondFactor,
    private val addresses: ClientAddressResolver,
    private val clock: Clock,
) {
    @GetMapping("/admin/quarantine")
    fun queue(
        authentication: Authentication,
        request: HttpServletRequest,
    ): ModelAndView {
        val now = clock.instant()
        val rows = console.quarantineQueue(ModerationPages.adminId(authentication), addresses.orNull(request))
        return ModelAndView(
            "admin/quarantine",
            mapOf("rows" to rows.map { QuarantineItem(it, ModerationPages.ageInDays(it.moderatedAt, now)) }),
        )
    }

    @GetMapping("/admin/quarantine/{mediaId}")
    fun asset(
        @PathVariable mediaId: String,
        @RequestParam(required = false) view: String?,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(mediaId)
        val adminId = ModerationPages.adminId(authentication)
        val page = id?.let { console.quarantined(it, adminId, addresses.orNull(request)) } ?: return ModerationPages.notFound()
        val decidable = decidable(page)
        val model =
            mapOf(
                "page" to page,
                "decidable" to decidable,
                "viewable" to (decidable && !page.asset.purged),
                "embed" to (view != null && decidable && !page.asset.purged),
                "audio" to (page.asset.kind == ReportsController.VOICE),
                "forms" to (if (decidable) forms(page.asset.mediaId, secondFactor.asksForCode(request.getSession(false))) else emptyList()),
            )
        return ModelAndView("admin/quarantined", model)
    }

    @PostMapping("/admin/quarantine/{mediaId}/release")
    fun release(
        @PathVariable mediaId: String,
        @RequestParam(defaultValue = "") note: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(mediaId) ?: return ModerationPages.notFound()
        return Decisions.decide(releaseForm(id), note, redirect) {
            when (console.release(id, note, ModerationPages.adminId(authentication), addresses.orNull(request)).outcome) {
                ReleaseOutcome.RELEASED -> "Released."
                ReleaseOutcome.REMODERATED -> "Released, and sent to moderation again."
                ReleaseOutcome.NEVER_RELEASED -> NEVER_RELEASED
                ReleaseOutcome.REPORT_HELD -> REPORT_HELD
                ReleaseOutcome.NOT_QUARANTINED -> ALREADY_DECIDED
                ReleaseOutcome.NOT_HELD_BACK -> ALREADY_DECIDED
            }
        }
    }

    @PostMapping("/admin/quarantine/{mediaId}/block")
    @Suppress("LongParameterList") // The form's two fields and the code, and the request Spring hands a handler.
    fun block(
        @PathVariable mediaId: String,
        @RequestParam(defaultValue = "") note: String,
        @RequestParam(defaultValue = "") code: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(mediaId) ?: return ModerationPages.notFound()
        val adminId = ModerationPages.adminId(authentication)
        val from = addresses.orNull(request)
        return Decisions.decideGated(blockForm(id), note, redirect, { secondFactor.prove(request.session, adminId, code, from) }) { proof ->
            when (console.block(id, note, proof, adminId, from).outcome) {
                BlockOutcome.BLOCKED -> "Blocked as evidence. Now follow runbook §2."
                BlockOutcome.ALREADY_BLOCKED -> "Already blocked as evidence."
                BlockOutcome.GONE -> "Already deleted, and its objects are gone. Nothing was blocked."
                BlockOutcome.REPORT_HELD -> REPORT_HELD
            }
        }
    }

    @PostMapping("/admin/quarantine/{mediaId}/delete")
    @Suppress("LongParameterList") // The form's two fields and the code, and the request Spring hands a handler.
    fun delete(
        @PathVariable mediaId: String,
        @RequestParam(defaultValue = "") note: String,
        @RequestParam(defaultValue = "") code: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(mediaId) ?: return ModerationPages.notFound()
        val adminId = ModerationPages.adminId(authentication)
        val from = addresses.orNull(request)
        return Decisions.decideGated(
            deleteForm(id),
            note,
            redirect,
            { secondFactor.prove(request.session, adminId, code, from) },
        ) { proof ->
            when (console.delete(id, note, proof, adminId, from).outcome) {
                DeleteOutcome.DELETED -> "Deleted."
                DeleteOutcome.NOT_QUARANTINED -> ALREADY_DECIDED
                DeleteOutcome.EVIDENCE_SERVER_ONLY -> "Blocked evidence is deleted only on the server: runbook §2 step 4."
                DeleteOutcome.REPORT_HELD -> REPORT_HELD
            }
        }
    }

    /** Spec §5.2: decided here only while it is quarantined by moderation, not blocked, and no open report holds it. */
    private fun decidable(page: QuarantinePage): Boolean =
        page.asset.verdict == QUARANTINED && page.asset.status == QUARANTINED && !page.asset.evidenceHeld && !page.asset.heldByReport

    /** Block first (spec §5.2), with the runbook's words; then release; then delete. */
    private fun forms(
        id: UUID,
        askCode: Boolean,
    ): List<DecisionForm> =
        listOf(
            blockForm(id).again(note = "", askCode = askCode, error = null),
            releaseForm(id),
            deleteForm(id).again(note = "", askCode = askCode, error = null),
        )

    private fun releaseForm(id: UUID) = DecisionForm("release", BACK, "${pageOf(id)}/release", "Release")

    private fun blockForm(id: UUID) =
        DecisionForm(
            "block",
            BACK,
            "${pageOf(id)}/block",
            "Block as evidence",
            gated = true,
            warning = "If this is child sexual abuse material: block it, do not delete it, then runbook §2.",
        )

    private fun deleteForm(id: UUID) = DecisionForm("delete", BACK, "${pageOf(id)}/delete", "Delete", gated = true)

    private fun pageOf(id: UUID) = "$BACK/$id"

    /** A list row and its age in days, from when it was held back. */
    class QuarantineItem(
        val row: QuarantineRow,
        val ageDays: Long?,
    ) {
        val blocked: Boolean = row.verdict == ReportsController.BLOCKED

        override fun toString(): String = "QuarantineItem(${row.mediaId})"
    }

    private companion object {
        const val BACK = "/admin/quarantine"
        const val QUARANTINED = "QUARANTINED"
        const val ALREADY_DECIDED = "No longer held back: already decided by someone else."
        const val REPORT_HELD = "An open report holds this. It is decided in the report."
        const val NEVER_RELEASED = "Blocked evidence is never released."
    }
}
