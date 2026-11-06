package com.moyi.admin.web

import com.moyi.admin.service.AdminProperties
import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.LiveAdminSessions
import com.moyi.common.security.ClientAddressResolver
import com.moyi.common.security.ProblemAuthenticationEntryPoint
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authorization.AuthorityAuthorizationManager
import org.springframework.security.authorization.AuthorizationManagerFactories
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.DelegatingMissingAuthorityAccessDeniedHandler
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.header.HeaderWriter
import org.springframework.security.web.header.HeaderWriterFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.session.DisableEncodeUrlFilter

/**
 * The admin console's own `SecurityFilterChain` (spec §3, §4, §6, §7), ordered ahead of the
 * API chain and matching only `/admin` and below. The API chain in `common:security` is not
 * touched: it has no order of its own, so it sorts last, where a chain matching every request
 * has to be.
 *
 * In order, a request meets: [AdminAddressFilter] (the allow-list, before anything reads a
 * session); Spring's context loading; the default headers and the spec's; then
 * [AdminSessionGuardFilter]; CSRF, with the token in the session; and the authorization rules.
 *
 * **Two factors, scoped to this chain.** `@EnableMultiFactorAuthentication` publishes one
 * global `AuthorizationManagerFactory`, which the API chain's `authenticated` would pick up,
 * and a bearer request carries only `FACTOR_BEARER`. So the factory requiring
 * [AdminAuthorities.FACTOR_PASSWORD] and [AdminAuthorities.FACTOR_TOTP] is built here and
 * used only here. A denial for a missing factor names it, and goes to that factor's page;
 * a request with no session at all goes to the sign-in page.
 *
 * **Sessions** are the servlet container's own (`IF_REQUIRED`); nothing here creates one
 * except a page that needs a CSRF token. The request cache is off, so an anonymous request
 * for a protected page does not create one to remember itself in.
 *
 * **htmx** (`/admin/static/htmx.min.js`) is vendored, never loaded from a CDN, so the CSP can
 * say `script-src 'self'`. Version 2.0.11, from https://unpkg.com/htmx.org@2.0.11/dist/htmx.min.js,
 * byte-identical to the v2.0.11 GitHub release asset, and matching the integrity htmx
 * publishes for it (htmx.org/docs, "Installing"):
 * `sha384-2OatzQy1H+Zd/IIrjr1TcuDGqLXeHhbooAyJY1KdQMKnr4LZ22k31GBLdYKHmVjg`.
 * Checked 2026-11-06 with `openssl dgst -sha384 -binary htmx.min.js | openssl base64 -A`.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminConsole
internal class AdminSecurityConfiguration {
    @Bean
    @Order(1)
    @Suppress("LongParameterList") // One chain, and each of these is a piece of it.
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        properties: AdminProperties,
        addresses: ClientAddressResolver,
        problems: ProblemAuthenticationEntryPoint,
        @Qualifier("securityFilterChain") apiChain: SecurityFilterChain,
        sessions: LiveAdminSessions,
        errors: AdminErrorPage,
    ): SecurityFilterChain {
        val factors =
            AuthorizationManagerFactories
                .multiFactor<RequestAuthorizationContext>()
                .requireFactors(AdminAuthorities.FACTOR_PASSWORD, AdminAuthorities.FACTOR_TOTP)
                .build()
        // Without it a refused address would differ from the API's 401 by its headers alone;
        // a missing filter is a wiring change to fail on at startup, not a quieter refusal.
        val apiHeaders =
            checkNotNull(apiChain.filters.filterIsInstance<HeaderWriterFilter>().singleOrNull()) {
                "The API chain (bean 'securityFilterChain') has no single HeaderWriterFilter for the admin allow-list to answer in"
            }

        http {
            securityMatcher("/admin/**")
            addFilterBefore<DisableEncodeUrlFilter>(AdminAddressFilter(properties.allowedNetworks, addresses, problems, apiHeaders))
            addFilterAfter<HeaderWriterFilter>(AdminSessionGuardFilter(sessions))
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.IF_REQUIRED }
            requestCache { requestCache = NullRequestCache() }
            csrf { csrfTokenRepository = HttpSessionCsrfTokenRepository() }
            headers {
                contentSecurityPolicy { policyDirectives = CONTENT_SECURITY_POLICY }
                referrerPolicy { policy = ReferrerPolicy.NO_REFERRER }
                // Spec §7 says `no-store`, exactly; Spring's writer adds three more words and
                // two more headers, and leaves alone a response that set its own.
                cacheControl { disable() }
                addHeaderWriter(NO_STORE)
            }
            formLogin { disable() }
            httpBasic { disable() }
            logout { disable() }
            authorizeHttpRequests {
                authorize("/admin/login", factors.permitAll())
                // The code page needs the password step, and only that: the factory's own
                // managers would demand the code before showing where to type it.
                authorize("/admin/login/totp", AuthorityAuthorizationManager.hasAuthority(AdminAuthorities.FACTOR_PASSWORD))
                authorize("/admin/enrol", factors.permitAll())
                authorize("/admin/logout", factors.permitAll())
                authorize("/admin/static/**", factors.permitAll())
                authorize(anyRequest, factors.authenticated())
            }
            exceptionHandling {
                authenticationEntryPoint = LoginUrlAuthenticationEntryPoint(LOGIN)
                accessDeniedHandler = missingFactor(errors)
            }
        }
        return http.build()
    }

    private fun missingFactor(errors: AdminErrorPage): AccessDeniedHandler =
        DelegatingMissingAuthorityAccessDeniedHandler
            .builder()
            .addEntryPointFor(LoginUrlAuthenticationEntryPoint(LOGIN), AdminAuthorities.FACTOR_PASSWORD)
            .addEntryPointFor(LoginUrlAuthenticationEntryPoint(TOTP), AdminAuthorities.FACTOR_TOTP)
            .build()
            .apply {
                // Anything else, a missing CSRF token among them: the admin error page, written
                // here. Not `sendError`, whose error dispatch would leave the admin chain for the API's.
                setDefaultAccessDeniedHandler { request, response, _ -> errors.write(request, response, HttpStatus.FORBIDDEN) }
            }

    companion object {
        const val LOGIN = "/admin/login"
        const val TOTP = "/admin/login/totp"

        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

        /** Written as the response commits, so it holds whatever a handler set before. */
        private val NO_STORE = HeaderWriter { _, response -> response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store") }
    }
}
