package com.moyi.admin.web

import com.moyi.admin.service.AdminProperties
import com.moyi.admin.service.AdminSignIn
import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.PasswordStep
import com.moyi.admin.service.TotpStep
import com.moyi.common.security.ClientAddress
import com.moyi.common.security.ClientAddressResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.util.UUID

/**
 * The two sign-in steps, sign-out and the console's front page (spec §6).
 *
 * Each accepted step changes the session id (session fixation), drops the CSRF token so the
 * next page mints a new one, and stores a fresh `Authentication` through the
 * [HttpSessionSecurityContextRepository]: after the password, one holding only
 * [AdminAuthorities.FACTOR_PASSWORD] with the admin's id as principal, and the two session
 * attributes [AdminSessionGuardFilter] reads; after the code, one holding both factors and the
 * role. A password or a code never reaches a model or a log, and a refused sign-in does not
 * show the email back.
 */
@Controller
@ConditionalOnAdminConsole
@Suppress("FunctionOnlyReturningConstant") // A page's handler answers with its view's name.
internal class AdminAuthController(
    private val signIn: AdminSignIn,
    private val addresses: ClientAddressResolver,
    private val properties: AdminProperties,
    private val clock: Clock,
) {
    private val contexts = SecurityContextHolder.getContextHolderStrategy()
    private val contextRepository = HttpSessionSecurityContextRepository()
    private val csrfTokens = HttpSessionCsrfTokenRepository()

    @GetMapping("/admin/login")
    fun loginPage(): String = "admin/login"

    @PostMapping("/admin/login")
    fun login(
        @RequestParam(defaultValue = "") email: String,
        @RequestParam(defaultValue = "") password: String,
        model: Model,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String =
        when (val step = signIn.password(email, password, addresses.orNull(request))) {
            is PasswordStep.Accepted -> {
                val now = clock.instant()
                establish(
                    request,
                    response,
                    UsernamePasswordAuthenticationToken.authenticated(
                        step.adminId,
                        null,
                        listOf(FactorGrantedAuthority.fromAuthority(AdminAuthorities.FACTOR_PASSWORD)),
                    ),
                )
                request.session.setAttribute(AdminAuthorities.CREDENTIALS_CHANGED_AT, step.credentialsChangedAt)
                request.session.setAttribute(AdminAuthorities.ABSOLUTE_DEADLINE, now.plus(properties.absoluteTimeout))
                "redirect:$TOTP"
            }

            PasswordStep.Refused -> {
                model.addAttribute("error", SIGN_IN_REFUSED)
                response.status = HttpStatus.UNAUTHORIZED.value()
                "admin/login"
            }
        }

    @GetMapping("/admin/login/totp")
    fun totpPage(): String = "admin/totp"

    @PostMapping("/admin/login/totp")
    fun totp(
        @RequestParam(defaultValue = "") code: String,
        model: Model,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        // The chain admits only a session holding the password factor, whose principal is the id.
        val current = checkNotNull(contexts.context.authentication) { "no authentication on the code step" }
        val adminId = current.principal as UUID
        return when (val step = signIn.totp(adminId, code, addresses.orNull(request))) {
            is TotpStep.Accepted -> {
                val authorities: List<GrantedAuthority> =
                    current.authorities.toList() +
                        FactorGrantedAuthority.fromAuthority(AdminAuthorities.FACTOR_TOTP) +
                        SimpleGrantedAuthority("ROLE_${step.role.name}")
                establish(request, response, UsernamePasswordAuthenticationToken.authenticated(adminId, null, authorities))
                // The code just proven opens the step-up window (A2 spec §10.2): an irreversible
                // act within five minutes of signing in needs no second code.
                request.session.setAttribute(AdminAuthorities.SECOND_FACTOR_AT, clock.instant())
                "redirect:$HOME"
            }

            TotpStep.Refused -> {
                model.addAttribute("error", WRONG_CODE)
                response.status = HttpStatus.UNAUTHORIZED.value()
                "admin/totp"
            }
        }
    }

    @PostMapping("/admin/logout")
    fun logout(request: HttpServletRequest): String {
        val adminId = contexts.context.authentication?.principal as? UUID
        if (adminId != null) signIn.signOut(adminId, addresses.orNull(request))
        request.getSession(false)?.invalidate()
        contexts.clearContext()
        return "redirect:$LOGIN?signedOut"
    }

    @GetMapping("/admin")
    fun home(): String = "admin/home"

    private fun establish(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        request.getSession(true)
        request.changeSessionId()
        csrfTokens.saveToken(null, request, response)
        val context = contexts.createEmptyContext().apply { this.authentication = authentication }
        contexts.context = context
        contextRepository.saveContext(context, request, response)
    }

    companion object {
        private const val LOGIN = AdminSecurityConfiguration.LOGIN
        private const val TOTP = AdminSecurityConfiguration.TOTP
        private const val HOME = "/admin"

        // The copy is fixed by the plan (Task 5); the login one is the same for a wrong
        // password, an unknown email and a lock, so it confirms none of them.
        private const val SIGN_IN_REFUSED =
            "That email and password do not match an active account, or the account is locked for a few minutes."
        const val WRONG_CODE = "That code is not right. Codes change every 30 seconds."
    }
}

/** The audit row's address; a request whose address cannot be read is recorded without one. */
internal fun ClientAddressResolver.orNull(request: HttpServletRequest): ClientAddress? = runCatching { resolve(request) }.getOrNull()
