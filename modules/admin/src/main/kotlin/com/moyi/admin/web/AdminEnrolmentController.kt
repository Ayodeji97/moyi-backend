package com.moyi.admin.web

import com.moyi.admin.domain.AdminPassword
import com.moyi.admin.service.AdminEnrolment
import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.EnrolmentResult
import com.moyi.common.security.ClientAddressResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The first visit, by invitation (spec §6): `GET` shows a fresh secret, `POST` checks the
 * password and one code. A refusal re-renders the same secret beside the field it concerns
 * (`422`); a link that is not valid, for any reason, gets one page that gives none (`404`).
 * Success sends the operator to sign in: enrolment signs nobody in.
 */
@Controller
@ConditionalOnAdminConsole
internal class AdminEnrolmentController(
    private val enrolment: AdminEnrolment,
    private val addresses: ClientAddressResolver,
) {
    @GetMapping("/admin/enrol")
    fun enrolPage(
        @RequestParam(defaultValue = "") token: String,
        model: Model,
        response: HttpServletResponse,
    ): String {
        val page = enrolment.begin(token) ?: return invalidLink(response)
        model.addAttribute("page", page)
        model.addAttribute("token", token)
        return ENROL
    }

    @Suppress("LongParameterList", "ReturnCount") // The form's four fields; one return per way out.
    @PostMapping("/admin/enrol")
    fun enrol(
        @RequestParam(defaultValue = "") token: String,
        @RequestParam(defaultValue = "") password: String,
        @RequestParam(defaultValue = "") confirm: String,
        @RequestParam(defaultValue = "") code: String,
        model: Model,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val result = enrolment.complete(token, password, confirm, code, addresses.orNull(request))
        val field =
            when (result) {
                EnrolmentResult.Done -> return "redirect:${AdminSecurityConfiguration.LOGIN}?enrolled"
                EnrolmentResult.InvalidLink -> return invalidLink(response)
                EnrolmentResult.PasswordMismatch -> "confirm" to PASSWORDS_DIFFER
                EnrolmentResult.PasswordTooShortOrLong -> "password" to PASSWORD_LENGTH
                EnrolmentResult.PasswordBreached -> "password" to PASSWORD_BREACHED
                EnrolmentResult.WrongCode -> "code" to WRONG_CODE
            }
        val page = enrolment.current(token) ?: return invalidLink(response)
        model.addAttribute("page", page)
        model.addAttribute("token", token)
        model.addAttribute("errorField", field.first)
        model.addAttribute("error", field.second)
        response.status = HttpStatus.UNPROCESSABLE_CONTENT.value()
        return ENROL
    }

    private fun invalidLink(response: HttpServletResponse): String {
        response.status = HttpStatus.NOT_FOUND.value()
        return "admin/enrol-invalid"
    }

    private companion object {
        const val ENROL = "admin/enrol"

        const val PASSWORDS_DIFFER = "The two passwords are not the same."
        const val PASSWORD_LENGTH =
            "Use a password of ${AdminPassword.MIN_LENGTH} to ${AdminPassword.MAX_LENGTH} characters."
        const val PASSWORD_BREACHED = "That password has appeared in a data breach. Choose another."
        const val WRONG_CODE = AdminAuthController.WRONG_CODE
    }
}
