package com.moyi.admin.web

import com.moyi.admin.domain.LookupPurpose
import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.InvalidModerationNote
import com.moyi.admin.service.LookupResult
import com.moyi.admin.service.ModerationNote
import com.moyi.admin.service.UserLookup
import com.moyi.common.security.ClientAddressResolver
import com.moyi.identity.api.AccountStatus
import com.moyi.identity.api.UserAdminView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The lookup page (A1 spec §9; A2 spec §9.3 and owner ruling 6).
 *
 * `GET /admin/users?q=` shows the form, with `q` filled in, and looks nothing up: a partner's
 * link lands here, and the admin still says why before anything is read.
 *
 * `POST /admin/users` looks up: the query, a [LookupPurpose], and for `SAFETY` or `LEGAL` a note.
 * A POST and not A1's GET, because the note is free text that may name a case, and a GET puts its
 * parameters in the URL, which a proxy's request log and the browser's history keep; the note may
 * be written only to the audit row's `justification`. A form with no purpose, an unknown one, no
 * query, or a note the console's rule refuses is the form again, `400`, and nothing is audited.
 *
 * The result shows the account's status and each bond's pause to every role, and the suspension's
 * forms to an `ADMIN` (A3 spec §5). The `GET` shows the one line a suspension's `303` left in the
 * flash (`admin/layout :: result`), and still looks nothing up.
 */
@Controller
@ConditionalOnAdminConsole
internal class UserLookupController(
    private val lookup: UserLookup,
    private val secondFactor: SecondFactor,
    private val addresses: ClientAddressResolver,
) {
    @GetMapping("/admin/users")
    fun form(
        @RequestParam(defaultValue = "") q: String,
        model: Model,
    ): String {
        model.addAttribute("q", q.trim())
        return form(model)
    }

    @PostMapping("/admin/users")
    @Suppress("LongParameterList") // The form's three fields, and who asked from where.
    fun users(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "") purpose: String,
        @RequestParam(defaultValue = "") note: String,
        authentication: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val chosen = LookupPurpose.named(purpose)
        model.addAttribute("q", q.trim())
        model.addAttribute("purpose", chosen?.name)
        val refusal =
            when {
                q.isBlank() -> QUERY_NEEDED
                chosen == null -> PURPOSE_NEEDED
                else -> found(q, chosen, note, authentication, request, model)
            }
        if (refusal != null) {
            // The note goes back into the form only: the page is `no-store`, and the note is never in a URL.
            model.addAttribute("note", note)
            model.addAttribute("error", refusal)
            response.status = HttpStatus.BAD_REQUEST.value()
        }
        return form(model)
    }

    /** The lookup, its answer put on [model]; the refusal's line when the note was refused, else null. */
    @Suppress("LongParameterList") // The form's fields, who asked from where, and the page they go on.
    private fun found(
        q: String,
        purpose: LookupPurpose,
        note: String,
        authentication: Authentication,
        request: HttpServletRequest,
        model: Model,
    ): String? =
        try {
            when (val result = lookup.find(q, purpose, note, authentication.principal as UUID, addresses.orNull(request))) {
                is LookupResult.Found -> {
                    model.addAttribute("user", result.user)
                    model.addAttribute("bonds", result.bonds)
                    suspension(result.user, authentication, model)
                }

                LookupResult.NotFound -> {
                    model.addAttribute("notFound", true)
                }
            }
            null
        } catch (_: InvalidModerationNote) {
            NOTE_NEEDED
        }

    /**
     * A3 spec §5: an `ADMIN` is offered the act the account's status allows (suspend an `ACTIVE` or
     * `PENDING_VERIFICATION` one, lift a `SUSPENDED` one, nothing for the others) and told of a
     * pending deletion request; anyone else is told only an `ADMIN` can. Display only: the console
     * and the port decide.
     */
    private fun suspension(
        user: UserAdminView,
        authentication: Authentication,
        model: Model,
    ) {
        if (!secondFactor.isAdmin(authentication)) {
            model.addAttribute("onlyAdmin", SuspensionController.ONLY_ADMIN)
            return
        }
        when (user.status) {
            AccountStatus.ACTIVE, AccountStatus.PENDING_VERIFICATION -> {
                model.addAttribute("suspendForm", SuspensionController.suspendForm(user.id))
            }

            AccountStatus.SUSPENDED -> {
                model.addAttribute("liftForm", SuspensionController.liftForm(user.id))
            }

            AccountStatus.PENDING_DELETION, AccountStatus.DELETED -> {
                Unit
            }
        }
        user.deletionDueAt?.let { model.addAttribute("deletionLine", SuspensionController.deletionLine(it)) }
    }

    private fun form(model: Model): String {
        model.addAttribute("purposes", LookupPurpose.entries.map { it.name })
        model.addAttribute("noteMax", ModerationNote.MAX_CODE_POINTS)
        return VIEW
    }

    private companion object {
        const val VIEW = "admin/users"
        const val QUERY_NEEDED = "Type a user id or an exact email."
        const val PURPOSE_NEEDED = "Choose why you are looking this account up."
        const val NOTE_NEEDED =
            "A lookup for SAFETY or LEGAL needs a note, 1 to ${ModerationNote.MAX_CODE_POINTS} characters: " +
                "the member's export will not list it, so the record must say why."
    }
}
