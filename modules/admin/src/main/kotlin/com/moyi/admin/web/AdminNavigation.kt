package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * What every console page's navigation needs beyond its own model (A4 spec §4): whether to offer
 * the feature flags, which are an `ADMIN`'s. Display only: `FlagsController` and `FlagConsole`
 * decide. Scoped to this package's controllers, and false with no one signed in.
 */
@ControllerAdvice(basePackageClasses = [AdminNavigation::class])
@ConditionalOnAdminConsole
internal class AdminNavigation(
    private val secondFactor: SecondFactor,
) {
    @ModelAttribute(NAV_ADMIN)
    fun navAdmin(authentication: Authentication?): Boolean = authentication != null && secondFactor.isAdmin(authentication)

    companion object {
        const val NAV_ADMIN = "navAdmin"
    }
}
