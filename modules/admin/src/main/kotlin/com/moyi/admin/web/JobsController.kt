package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.InvalidModerationNote
import com.moyi.admin.service.JobsConsole
import com.moyi.common.security.ClientAddressResolver
import com.moyi.gratitude.api.RebuildResult
import com.moyi.gratitude.api.StreakRebuild
import com.moyi.scheduling.api.CloseRun
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.view.RedirectView
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The jobs page (A4 spec §4, FR-114, FR-074): every role sees it; an `ADMIN` runs the close job by
 * hand and rebuilds a bond's streak. Each POST, in order: the role (`ADMIN`, before anything is
 * parsed); the bond id, for a rebuild (not a UUID: the form again, `422`); the justification
 * (`ModerationNote`'s rule, refused: the form again, `422`); the act. Answered with a `303` to the
 * page and one line in the flash, chosen from the outcome.
 *
 * Not `@Transactional`: the close run is many transactions of its own (`JobsConsole.runClose`),
 * and the rebuild is one, the console's. The justification is never in a flash, a `Location` or a
 * log line, only the form it was typed into.
 */
@Controller
@ConditionalOnAdminConsole
@PreAuthorize("hasRole('SUPPORT')")
internal class JobsController(
    private val console: JobsConsole,
    private val secondFactor: SecondFactor,
    private val addresses: ClientAddressResolver,
) {
    @GetMapping(PAGE)
    @Suppress("ReturnCount") // The page, the page with a refused date, the page with its check.
    fun page(
        @RequestParam(required = false) date: String?,
        authentication: Authentication,
        model: Model,
    ): ModelAndView {
        if (date.isNullOrBlank()) return rendered(model, authentication, HttpStatus.OK)
        model.addAttribute("date", date)
        val day = dateOrNull(date) ?: return rendered(model.addAttribute("dateError", NOT_A_DATE), authentication, HttpStatus.BAD_REQUEST)
        model.addAttribute("settlement", console.settlement(day))
        return rendered(model, authentication, HttpStatus.OK)
    }

    @PostMapping("$PAGE/close-days/run")
    @PreAuthorize("hasRole('ADMIN')")
    fun runClose(
        @RequestParam(defaultValue = "") justification: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
        model: Model,
    ): ModelAndView =
        try {
            val run = console.runClose(justification, ModerationPages.adminId(authentication), addresses.orNull(request))
            answered(lineOf(run), redirect)
        } catch (_: InvalidModerationNote) {
            model.addAttribute("closeForm", JobForm(justification, "", NOTE_REFUSED))
            rendered(model, authentication, HttpStatus.UNPROCESSABLE_CONTENT)
        }

    @PostMapping("$PAGE/streak/recalculate")
    @PreAuthorize("hasRole('ADMIN')")
    @Suppress("LongParameterList") // The form's two fields, who asked from where, and the page to show again.
    fun rebuild(
        @RequestParam(defaultValue = "") bondId: String,
        @RequestParam(defaultValue = "") justification: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
        model: Model,
    ): ModelAndView {
        val bond = ModerationPages.uuidOrNull(bondId.trim())
        if (bond == null) {
            model.addAttribute("rebuildForm", JobForm(justification, bondId, NOT_A_BOND))
            return rendered(model, authentication, HttpStatus.UNPROCESSABLE_CONTENT)
        }
        return try {
            val rebuilt = console.rebuild(bond, justification, ModerationPages.adminId(authentication), addresses.orNull(request))
            answered(lineOf(rebuilt), redirect)
        } catch (_: InvalidModerationNote) {
            model.addAttribute("rebuildForm", JobForm(justification, bondId, NOTE_REFUSED))
            rendered(model, authentication, HttpStatus.UNPROCESSABLE_CONTENT)
        }
    }

    /** The page, with whatever the handler put in [model] already (a date's check, a refused form). */
    private fun rendered(
        model: Model,
        authentication: Authentication,
        status: HttpStatus,
    ): ModelAndView {
        val page = console.page()
        model.addAttribute("jobs", page.jobs)
        model.addAttribute("timerOn", page.timerOn)
        model.addAttribute("moderator", secondFactor.moderates(authentication))
        val canAct = secondFactor.isAdmin(authentication)
        model.addAttribute("canAct", canAct)
        if (canAct) {
            if (!model.containsAttribute("closeForm")) model.addAttribute("closeForm", JobForm("", "", null))
            if (!model.containsAttribute("rebuildForm")) model.addAttribute("rebuildForm", JobForm("", "", null))
        }
        return ModelAndView(VIEW, model.asMap(), status)
    }

    private fun answered(
        line: String,
        redirect: RedirectAttributes,
    ): ModelAndView {
        redirect.addFlashAttribute(Decisions.RESULT, line)
        // A `RedirectView` of its own, for the spec's 303: a plain `redirect:` answers 302.
        return ModelAndView(
            RedirectView(PAGE, true).apply {
                setStatusCode(HttpStatus.SEE_OTHER)
                setExposeModelAttributes(false)
            },
        )
    }

    /** A form on the page as typed, with the line it was refused with. [toString] leaves the justification out. */
    class JobForm(
        val justification: String,
        val bondId: String,
        val error: String?,
    ) {
        val hint: String = DecisionForm.NOTE_HINT

        override fun toString(): String = "JobForm(error=$error)"
    }

    companion object {
        const val PAGE = "/admin/jobs"
        const val VIEW = "admin/jobs"
        const val NOT_A_DATE = "Not a date. Use YYYY-MM-DD."
        const val NOT_A_BOND = "Not a bond id."
        val NOTE_REFUSED = DecisionForm.Wording.JUSTIFICATION.noteRefused
        const val BUSY = "The close job is running now (the timer or another instance). Nothing was started; look again in a minute."
        const val BACKLOG = " More days are waiting: run it again."
        const val UNCHANGED = "Rebuilt: nothing had drifted."
        const val NO_DAYS = "That bond has no counted day: nothing to rebuild."

        fun lineOf(run: CloseRun): String =
            when (run) {
                CloseRun.Busy -> {
                    BUSY
                }

                is CloseRun.Ran -> {
                    "Ran: created ${run.created}, closed ${run.closed}, revealed ${run.revealed}, counted ${run.evaluated}, " +
                        "failed ${run.failed}." + (if (run.backlog) BACKLOG else "")
                }
            }

        fun lineOf(rebuilt: StreakRebuild): String =
            when (rebuilt.result) {
                RebuildResult.CHANGED -> {
                    "Rebuilt: the stored streak had drifted; it is now current ${rebuilt.after?.current}, " +
                        "longest ${rebuilt.after?.longest}."
                }

                RebuildResult.UNCHANGED -> {
                    UNCHANGED
                }

                RebuildResult.NO_DAYS -> {
                    NO_DAYS
                }
            }

        private fun dateOrNull(raw: String): LocalDate? =
            try {
                LocalDate.parse(raw.trim())
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
