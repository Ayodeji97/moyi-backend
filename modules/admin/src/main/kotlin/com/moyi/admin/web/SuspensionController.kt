package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.InvalidModerationNote
import com.moyi.admin.service.ModerationNote
import com.moyi.admin.service.SuspensionConsole
import com.moyi.common.security.ClientAddressResolver
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.view.RedirectView
import java.util.UUID

/**
 * Suspending an account and lifting the suspension, from the lookup page (A3 spec §5, FR-113).
 * Both POSTs take the same steps, in the spec's order, each before the next:
 *
 * 1. the role: `ADMIN`, here at the class and again on the console, so that a `MODERATOR` or a
 *    `SUPPORT` session meets the `403` page before anything is parsed, proved or read;
 * 2. the id: anything but a UUID is the admin `404` page;
 * 3. the justification, by `ModerationNote`'s own rule: refused, the form again (`422`), and the
 *    code is never touched, so it is not spent;
 * 4. the code, proved in this request ([SecondFactor.proveNow]): none typed, the form asking for
 *    one; refused, the form again (`401`); accepted, its `ADMIN_STEP_UP` row commits on its own;
 * 5. the act, in `SuspensionConsole`'s transaction, with its audit row.
 *
 * Deliberately **not** `@Transactional` (A5's reason): the code's own transaction commits before
 * the act's opens, so an act that fails never un-spends the code.
 *
 * The answer is a `303` to `GET /admin/users?q=<id>` with one line in the flash, chosen from the
 * outcome: nothing secret goes in the URL, and a reload must not post the spent code again into
 * the five-failure lockout. An account that does not exist is the `404` page. The justification
 * is never in a flash, a `Location`, a log line or a page but the form it was typed into.
 */
@Controller
@ConditionalOnAdminConsole
@PreAuthorize("hasRole('ADMIN')")
internal class SuspensionController(
    private val console: SuspensionConsole,
    private val secondFactor: SecondFactor,
    private val addresses: ClientAddressResolver,
) {
    @PostMapping("/admin/users/{userId}/suspend")
    @Suppress("LongParameterList") // The form's two fields, and who asked from where.
    fun suspend(
        @PathVariable userId: String,
        @RequestParam(defaultValue = "") justification: String,
        @RequestParam(defaultValue = "") code: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView = act(Act.SUSPEND, userId, justification, code, authentication, request, redirect)

    @PostMapping("/admin/users/{userId}/unsuspend")
    @Suppress("LongParameterList") // The form's two fields, and who asked from where.
    fun lift(
        @PathVariable userId: String,
        @RequestParam(defaultValue = "") justification: String,
        @RequestParam(defaultValue = "") code: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView = act(Act.LIFT, userId, justification, code, authentication, request, redirect)

    @Suppress("LongParameterList") // Which act, the form's fields, and who asked from where.
    private fun act(
        act: Act,
        userId: String,
        justification: String,
        code: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val id = ModerationPages.uuidOrNull(userId) ?: return ModerationPages.notFound()
        val adminId = ModerationPages.adminId(authentication)
        val from = addresses.orNull(request)
        val form = formFor(act, id)
        return try {
            ModerationNote.require(justification)
            when (val fresh = secondFactor.proveNow(adminId, code, from)) {
                SecondFactor.Fresh.Missing -> {
                    again(form, justification, CODE_NEEDED_FOR_ACT, HttpStatus.OK)
                }

                SecondFactor.Fresh.Refused -> {
                    again(form, justification, AdminAuthController.WRONG_CODE, HttpStatus.UNAUTHORIZED)
                }

                is SecondFactor.Fresh.Proved -> {
                    val result =
                        when (act) {
                            Act.SUSPEND -> console.suspend(id, justification, fresh.stepUp, adminId, from)
                            Act.LIFT -> console.lift(id, justification, fresh.stepUp, adminId, from)
                        }
                    answered(id, result, redirect)
                }
            }
        } catch (_: InvalidModerationNote) {
            again(form, justification, DecisionForm.NOTE_REFUSED, HttpStatus.UNPROCESSABLE_CONTENT)
        }
    }

    /** The act's answer: the `404` page for an account that is not there, else the lookup with the outcome's line. */
    private fun answered(
        id: UUID,
        result: SuspensionResult,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val line = flashOf(result) ?: return ModerationPages.notFound()
        redirect.addFlashAttribute(Decisions.RESULT, line)
        // A `RedirectView` of its own, for the spec's 303: a plain `redirect:` answers 302.
        val view =
            RedirectView(lookupOf(id), true).apply {
                setStatusCode(HttpStatus.SEE_OTHER)
                setExposeModelAttributes(false)
            }
        return ModelAndView(view)
    }

    private fun again(
        form: DecisionForm,
        justification: String,
        error: String,
        status: HttpStatus,
    ) = ModelAndView(DecisionForm.DECIDE_VIEW, mapOf("form" to form.again(justification, askCode = true, error = error)), status)

    private enum class Act(
        val route: String,
        val label: String,
    ) {
        SUSPEND("suspend", SUSPEND_LABEL),
        LIFT("unsuspend", LIFT_LABEL),
    }

    companion object {
        const val SUSPEND_LABEL = "Suspend this account"
        const val LIFT_LABEL = "Lift the suspension"
        const val ONLY_ADMIN = "Only an ADMIN can suspend an account."
        const val CODE_NEEDED_FOR_ACT = "Enter a code from your authenticator app. Each suspension, and each lift, needs a new one."
        const val SUSPENDED = "Suspended. They are signed out everywhere and cannot sign in. Their bonds are paused."
        const val ALREADY_SUSPENDED = "Already suspended. Nothing changed."
        const val LIFTED = "Suspension lifted. They can sign in again; their bonds take entries again now."
        const val NOT_SUSPENDED = "This account is not suspended. Nothing changed."
        const val GONE = "This account was deleted. There is nothing to suspend."

        /** The lookup page for [id], as the act's `303` and the forms' way back name it. */
        fun lookupOf(id: UUID): String = "/admin/users?q=$id"

        /** The suspend form, for an `ACTIVE` or `PENDING_VERIFICATION` account; display only, the port decides. */
        fun suspendForm(id: UUID): DecisionForm = formFor(Act.SUSPEND, id)

        /** The lift form, for a `SUSPENDED` account; display only, the port decides. */
        fun liftForm(id: UUID): DecisionForm = formFor(Act.LIFT, id)

        /** The line the lookup shows for a pending deletion request, with the instant as the page prints its others. */
        fun deletionLine(dueAt: java.time.Instant): String = "A deletion this person asked for runs on $dueAt, suspended or not."

        private fun formFor(
            act: Act,
            id: UUID,
        ) = DecisionForm(
            id = act.route,
            back = lookupOf(id),
            action = "/admin/users/$id/${act.route}",
            label = act.label,
            gated = true,
            askCode = true,
            wording = DecisionForm.Wording.SUSPENSION,
        )

        /** Spec §5's table: each outcome's one line, or null for the one answered with the `404` page. */
        private fun flashOf(result: SuspensionResult): String? =
            when (result.outcome) {
                SuspensionOutcome.SUSPENDED -> listOfNotNull(SUSPENDED, result.deletionDueAt?.let(::deletionLine)).joinToString(" ")
                SuspensionOutcome.ALREADY_SUSPENDED -> ALREADY_SUSPENDED
                SuspensionOutcome.LIFTED -> LIFTED
                SuspensionOutcome.NOT_SUSPENDED -> NOT_SUSPENDED
                SuspensionOutcome.GONE -> GONE
                SuspensionOutcome.NO_SUCH_ACCOUNT -> null
            }
    }
}
