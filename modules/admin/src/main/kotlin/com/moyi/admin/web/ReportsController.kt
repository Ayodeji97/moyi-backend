package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.InvalidModerationNote
import com.moyi.admin.service.ModerationConsole
import com.moyi.admin.service.ReportPage
import com.moyi.admin.service.StepUpRequired
import com.moyi.common.security.ClientAddressResolver
import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.ReclassifyOutcome
import com.moyi.gratitude.api.ReportCase
import com.moyi.gratitude.api.ReportModeration
import com.moyi.gratitude.api.ReportRow
import com.moyi.gratitude.api.Takedown
import com.moyi.gratitude.api.UpholdResult
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
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
import java.time.Instant
import java.util.UUID

/**
 * The report queue (A2 spec §5.1): the list, one report's page with its decision forms, and the
 * three decisions, each a POST that redirects back to the report with a one-line result chosen
 * from the outcome. The rules are the console's and the functions'; this parses, passes the
 * session's step-up proof through as it is, and renders.
 *
 * `MODERATOR` here too, not only in the service, so that a `SUPPORT` session meets the admin
 * `403` page on every route before any id is parsed (spec §4).
 *
 * The reclassify form's choice is [ReportModeration.reasons]: gratitude's domain's own list, read
 * here and never restated, which writes nothing and needs no transaction.
 *
 * Named as a bean: gratitude's member-facing `ReportsController` (the report route) has the
 * default name, and two beans may not share one.
 */
@Controller("adminReportsController")
@ConditionalOnAdminConsole
@PreAuthorize("hasRole('MODERATOR')")
@Suppress("TooManyFunctions") // One handler per route of spec §5.1, and the forms they render.
internal class ReportsController(
    private val console: ModerationConsole,
    private val reports: ReportModeration,
    private val secondFactor: SecondFactor,
    private val addresses: ClientAddressResolver,
    private val clock: Clock,
) {
    @GetMapping("/admin/reports")
    fun reports(
        authentication: Authentication,
        request: HttpServletRequest,
    ): ModelAndView {
        val now = clock.instant()
        val rows = console.reportQueue(ModerationPages.adminId(authentication), addresses.orNull(request))
        return ModelAndView("admin/reports", mapOf("rows" to rows.map { ReportItem(it, ModerationPages.ageInDays(it.receivedAt, now)) }))
    }

    @GetMapping("/admin/reports/{reportId}")
    fun report(
        @PathVariable reportId: String,
        @RequestParam(required = false) view: String?,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(reportId)
        val adminId = ModerationPages.adminId(authentication)
        val page = id?.let { console.report(it, adminId, addresses.orNull(request)) } ?: return ModerationPages.notFound()
        val held = page.case.media.map { HeldItem(it.mediaId, it.kind, it.status, it.verdict, barred(page.case, it.verdict)) }
        val model =
            mapOf(
                "page" to page,
                "held" to held,
                "embed" to held.firstOrNull { it.barred == null && it.mediaId.toString() == view },
                "forms" to forms(page, secondFactor.asksForCode(request.getSession(false))),
            )
        return ModelAndView("admin/report", model)
    }

    @PostMapping("/admin/reports/{reportId}/dismiss")
    @Suppress("LongParameterList") // The form's two fields, and the request Spring hands a handler.
    fun dismiss(
        @PathVariable reportId: String,
        @RequestParam(defaultValue = "") expectedReason: String,
        @RequestParam(defaultValue = "") note: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(reportId) ?: return ModerationPages.notFound()
        return Decisions.decide(dismissForm(id, expectedReason), note, redirect) {
            when (console.dismiss(id, expectedReason, note, ModerationPages.adminId(authentication), addresses.orNull(request))) {
                DismissOutcome.DISMISSED -> "Dismissed."
                DismissOutcome.NOT_OPEN -> ALREADY_DECIDED
                DismissOutcome.STALE -> STALE
            }
        }
    }

    @PostMapping("/admin/reports/{reportId}/reclassify")
    @Suppress("LongParameterList") // The form's three fields, and who asked from where.
    fun reclassify(
        @PathVariable reportId: String,
        @RequestParam(defaultValue = "") expectedReason: String,
        @RequestParam(defaultValue = "") newReason: String,
        @RequestParam(defaultValue = "") note: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(reportId) ?: return ModerationPages.notFound()
        return Decisions.decide(reclassifyForm(id, expectedReason), note, redirect) {
            val outcome =
                console.reclassify(id, expectedReason, newReason, note, ModerationPages.adminId(authentication), addresses.orNull(request))
            when (outcome) {
                ReclassifyOutcome.RECLASSIFIED -> "Reclassified."
                ReclassifyOutcome.SAME_REASON -> "That is already the report's reason. Nothing changed."
                ReclassifyOutcome.NOT_OPEN -> ALREADY_DECIDED
                ReclassifyOutcome.STALE -> STALE
                ReclassifyOutcome.UNKNOWN_REASON -> "That is not one of the five reasons. Nothing changed."
            }
        }
    }

    /** Behind the step-up (spec §10.2): a typed code is proven first, and only then is the uphold asked for. */
    @PostMapping("/admin/reports/{reportId}/uphold")
    @Suppress("LongParameterList") // The form's five fields, and who asked from where.
    fun uphold(
        @PathVariable reportId: String,
        @RequestParam(defaultValue = "") expectedReason: String,
        @RequestParam(defaultValue = "") takedown: String,
        @RequestParam(defaultValue = "") note: String,
        @RequestParam(defaultValue = "") code: String,
        @RequestParam(name = UpholdLabel.FIELD, defaultValue = "") shown: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(reportId)
        val how = Takedown.entries.firstOrNull { it.name == takedown }
        if (id == null || how == null) return ModerationPages.notFound()
        val adminId = ModerationPages.adminId(authentication)
        val from = addresses.orNull(request)
        val form = upholdForm(id, expectedReason, how, UpholdLabel.posted(shown, how))
        return Decisions.decideGated(form, note, redirect, { secondFactor.prove(request.session, adminId, code, from) }) { proof ->
            when (console.uphold(id, expectedReason, how, note, proof, adminId, from)) {
                is UpholdResult.Done -> if (how == Takedown.BLOCK) "Upheld: blocked as evidence. Now follow runbook §2." else "Upheld."
                UpholdResult.NotOpen -> ALREADY_DECIDED
                UpholdResult.ReclassifyFirst -> "A report of sexual content involving a minor is upheld by blocking, never by deleting."
                UpholdResult.Stale -> STALE
            }
        }
    }

    /** The forms a report's page offers (spec §5.1): none once it is decided. */
    private fun forms(
        page: ReportPage,
        askCode: Boolean,
    ): List<DecisionForm> {
        val case = page.case
        if (case.status != OPEN) return emptyList()
        val upholds =
            when {
                // A report of words or of a member holds nothing: one Uphold records the decision,
                // as runbook §3.3 tells the operator to type it.
                case.media.isEmpty() -> {
                    val takedown = if (case.priority) Takedown.BLOCK else Takedown.DELETE
                    listOf(upholdForm(case.reportId, case.reason, takedown, UpholdLabel.UPHOLD))
                }

                // `entry_report_uphold` answers RECLASSIFY_FIRST to a delete of a priority report.
                case.priority -> {
                    listOf(upholdForm(case.reportId, case.reason, Takedown.BLOCK, UpholdLabel.EVIDENCE))
                }

                else -> {
                    listOf(
                        upholdForm(case.reportId, case.reason, Takedown.DELETE, UpholdLabel.DELETE),
                        upholdForm(case.reportId, case.reason, Takedown.BLOCK, UpholdLabel.BLOCK),
                    )
                }
            }.map { it.again(note = "", askCode = askCode, error = null) }
        return listOf(dismissForm(case.reportId, case.reason), reclassifyForm(case.reportId, case.reason)) + upholds
    }

    private fun dismissForm(
        id: UUID,
        expectedReason: String,
    ) = DecisionForm("dismiss", pageOf(id), "${pageOf(id)}/dismiss", "Dismiss", mapOf(EXPECTED_REASON to expectedReason))

    private fun reclassifyForm(
        id: UUID,
        expectedReason: String,
    ) = DecisionForm(
        "reclassify",
        pageOf(id),
        "${pageOf(id)}/reclassify",
        "Reclassify",
        mapOf(EXPECTED_REASON to expectedReason),
        reasons = reports.reasons() - expectedReason,
    )

    private fun upholdForm(
        id: UUID,
        expectedReason: String,
        takedown: Takedown,
        label: UpholdLabel,
    ) = DecisionForm(
        id = "uphold-${takedown.name.lowercase()}",
        back = pageOf(id),
        action = "${pageOf(id)}/uphold",
        label = label.text,
        hidden = mapOf(EXPECTED_REASON to expectedReason, "takedown" to takedown.name, UpholdLabel.FIELD to label.name),
        gated = true,
    )

    private fun pageOf(id: UUID) = "/admin/reports/$id"

    /** Why a held asset has no "View" link, or null when it has one: the report's half of spec §7.4, and a block. */
    private fun barred(
        case: ReportCase,
        verdict: String?,
    ): String? =
        when {
            case.priority || verdict == BLOCKED -> DO_NOT_VIEW
            !case.mediaReviewable -> "Not shown: decided on the reporter's word (runbook §3.2)."
            else -> null
        }

    /** A list row and its age in days. */
    class ReportItem(
        val row: ReportRow,
        val ageDays: Long?,
    ) {
        override fun toString(): String = "ReportItem(${row.reportId})"
    }

    /** A held asset as the report page shows it, with why it has no "View" link, if it has none. */
    class HeldItem(
        val mediaId: UUID,
        val kind: String,
        val status: String,
        val verdict: String?,
        val barred: String?,
    ) {
        val audio: Boolean = kind == VOICE
    }

    companion object {
        const val EXPECTED_REASON = "expectedReason"
        const val OPEN = "OPEN"
        const val BLOCKED = "BLOCKED"
        const val VOICE = "VOICE"
        const val DO_NOT_VIEW = "DO NOT VIEW — runbook §2"
        const val ALREADY_DECIDED = "Already decided by someone else."
        const val STALE = "The report changed after this page was shown. Read it again before deciding."
    }
}

/**
 * An uphold form's label (spec §5.1), which the report's page chose from the report's priority and
 * whether it holds media. The form carries its key in a hidden field, so that the form re-rendered
 * after "code needed", "wrong code" or a refused note keeps the label the moderator was shown,
 * without a second read of the report (which `entry_report_read` would log as a `READ`).
 *
 * The key decides the label and nothing else: the uphold is the function's, by the posted
 * `takedown`, against the report as it stands. A key that is unknown, or that does not fit the
 * posted takedown, falls back to that takedown's plain label, never to text from the request.
 */
internal enum class UpholdLabel(
    val text: String,
    private val takedowns: Set<Takedown>,
) {
    /** A report that holds nothing: one form records the decision (runbook §3.3). */
    UPHOLD("Uphold", setOf(Takedown.DELETE, Takedown.BLOCK)),
    DELETE("Uphold, delete", setOf(Takedown.DELETE)),
    BLOCK("Uphold, block", setOf(Takedown.BLOCK)),

    /** A priority report's one uphold. */
    EVIDENCE("Block as evidence, then runbook §2", setOf(Takedown.BLOCK)),
    ;

    companion object {
        const val FIELD = "label"

        fun posted(
            key: String,
            takedown: Takedown,
        ): UpholdLabel =
            entries.firstOrNull { it.name == key && takedown in it.takedowns }
                ?: if (takedown == Takedown.DELETE) DELETE else BLOCK
    }
}

/**
 * A decision's POST, shared by the report and quarantine pages (spec §5): a result in the flash
 * and a redirect back to [DecisionForm.back], or, when nothing was decided, the form again with
 * the note kept. Nothing here decides whether the step-up is fresh: the console does, and says so
 * by throwing.
 */
internal object Decisions {
    const val RESULT = "result"

    /** [attempt] answers the result line; a note the console refused is the form again, `400`. */
    fun decide(
        form: DecisionForm,
        note: String,
        redirect: RedirectAttributes,
        attempt: () -> String,
    ): ModelAndView =
        try {
            redirect.addFlashAttribute(RESULT, attempt())
            ModelAndView("redirect:${form.back}")
        } catch (_: InvalidModerationNote) {
            again(form.again(note, askCode = false, error = DecisionForm.NOTE_REFUSED), HttpStatus.BAD_REQUEST)
        }

    /**
     * The step-up's flow (spec §10.2): [prove] first (a typed code through `AdminSignIn.stepUp`); a
     * refused code is the form again, `401`, and nothing is attempted. Then [attempt] with the
     * proof as it now stands; the console's `StepUpRequired` is the form again with the code field.
     */
    fun decideGated(
        form: DecisionForm,
        note: String,
        redirect: RedirectAttributes,
        prove: () -> SecondFactor.Proof,
        attempt: (Instant?) -> String,
    ): ModelAndView =
        when (val proof = prove()) {
            SecondFactor.Proof.Refused -> {
                again(form.again(note, askCode = true, error = AdminAuthController.WRONG_CODE), HttpStatus.UNAUTHORIZED)
            }

            is SecondFactor.Proof.Held -> {
                try {
                    decide(form, note, redirect) { attempt(proof.secondFactorAt) }
                } catch (_: StepUpRequired) {
                    again(form.again(note, askCode = true, error = DecisionForm.CODE_NEEDED), HttpStatus.OK)
                }
            }
        }

    private fun again(
        form: DecisionForm,
        status: HttpStatus,
    ) = ModelAndView(DecisionForm.DECIDE_VIEW, mapOf("form" to form), status)
}
