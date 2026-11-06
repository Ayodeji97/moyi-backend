package com.moyi.admin.web

import com.moyi.admin.service.LiveAdminSessions
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.UUID

/**
 * Re-reads the account on every request of a signed-in admin session (spec §6), placed after
 * the security context is loaded from the session. If the account is no longer `ACTIVE`, its
 * credentials changed after the session captured them, or the session's absolute lifetime is
 * over, the session is invalidated, the context cleared, and the request sent to the sign-in
 * page. A session that cannot say which admin it belongs to is treated the same way.
 */
internal class AdminSessionGuardFilter(
    private val sessions: LiveAdminSessions,
) : OncePerRequestFilter() {
    private val contexts = SecurityContextHolder.getContextHolderStrategy()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val authentication = contexts.context.authentication
        if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
            chain.doFilter(request, response)
            return
        }
        val session = request.getSession(false)
        val adminId = authentication.principal as? UUID
        val live =
            session != null &&
                adminId != null &&
                sessions.isLive(
                    adminId,
                    session.getAttribute(AdminAuthorities.CREDENTIALS_CHANGED_AT) as? Instant,
                    session.getAttribute(AdminAuthorities.ABSOLUTE_DEADLINE) as? Instant,
                )
        if (live) {
            chain.doFilter(request, response)
        } else {
            session?.invalidate()
            contexts.clearContext()
            response.sendRedirect(request.contextPath + AdminSecurityConfiguration.LOGIN)
        }
    }
}
