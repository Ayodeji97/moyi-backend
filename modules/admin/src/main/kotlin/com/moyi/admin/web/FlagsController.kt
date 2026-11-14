package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.FlagChange
import com.moyi.admin.service.FlagConsole
import com.moyi.admin.service.InvalidModerationNote
import com.moyi.common.core.FeatureFlag
import com.moyi.common.security.ClientAddressResolver
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
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
import org.springframework.web.servlet.view.RedirectView

/**
 * The feature flags page (A4 spec §4, FR-115), an `ADMIN`'s alone. The POST, in order: the role;
 * the flag (not one of [FeatureFlag]: the `404` page); `enabled` (neither `true` nor `false`: the
 * `404` page); the justification (refused: the page again, `422`); the switch. Answered with a
 * `303` to the page and one line in the flash.
 */
@Controller
@ConditionalOnAdminConsole
@PreAuthorize("hasRole('ADMIN')")
internal class FlagsController(
    private val console: FlagConsole,
    private val addresses: ClientAddressResolver,
) {
    @GetMapping(PAGE)
    fun page(model: Model): ModelAndView = rendered(model, HttpStatus.OK)

    @PostMapping("$PAGE/{flag}")
    @Suppress("LongParameterList", "ReturnCount") // The path, the form's fields, who asked from where, the page; a return per refusal.
    fun set(
        @PathVariable flag: String,
        @RequestParam(defaultValue = "") enabled: String,
        @RequestParam(defaultValue = "") justification: String,
        authentication: Authentication,
        request: HttpServletRequest,
        redirect: RedirectAttributes,
        model: Model,
    ): ModelAndView {
        val known = FeatureFlag.entries.firstOrNull { it.name == flag } ?: return ModerationPages.notFound()
        val on =
            when (enabled) {
                "true" -> true
                "false" -> false
                else -> return ModerationPages.notFound()
            }
        return try {
            val change = console.set(known, on, justification, ModerationPages.adminId(authentication), addresses.orNull(request))
            redirect.addFlashAttribute(Decisions.RESULT, lineOf(change, on))
            ModelAndView(
                RedirectView(PAGE, true).apply {
                    setStatusCode(HttpStatus.SEE_OTHER)
                    setExposeModelAttributes(false)
                },
            )
        } catch (_: InvalidModerationNote) {
            model.addAttribute("refused", RefusedForm(known.name, justification, DecisionForm.Wording.JUSTIFICATION.noteRefused))
            rendered(model, HttpStatus.UNPROCESSABLE_CONTENT)
        }
    }

    private fun rendered(
        model: Model,
        status: HttpStatus,
    ): ModelAndView {
        model.addAttribute("flags", console.states())
        model.addAttribute("hint", DecisionForm.NOTE_HINT)
        return ModelAndView(VIEW, model.asMap(), status)
    }

    /** The one form refused, its justification kept. [toString] leaves the justification out. */
    class RefusedForm(
        val key: String,
        val justification: String,
        val error: String,
    ) {
        override fun toString(): String = "RefusedForm($key)"
    }

    companion object {
        const val PAGE = "/admin/flags"
        const val VIEW = "admin/flags"
        const val SWITCHED_OFF = "Switched off."
        const val SWITCHED_ON = "Switched on."
        const val ALREADY_OFF = "Already off. Nothing changed."
        const val ALREADY_ON = "Already on. Nothing changed."

        fun lineOf(
            change: FlagChange,
            on: Boolean,
        ): String =
            when (change) {
                FlagChange.CHANGED -> if (on) SWITCHED_ON else SWITCHED_OFF
                FlagChange.UNCHANGED -> if (on) ALREADY_ON else ALREADY_OFF
            }
    }
}
