package com.moyi.admin.web

import com.moyi.common.security.ClientAddressResolver
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.util.matcher.IpAddressMatcher

/**
 * The first filter of the admin chain (spec §4): a request from outside
 * `moyi.admin.allowed-networks` gets the answer the API gives a request without a token, and
 * nothing else happens. It runs before the session is looked up and before CSRF, and it never
 * calls `getSession`, so a refused address never creates a session. An empty list admits
 * nobody.
 *
 * **Indistinguishable means the headers too.** The API's 401 is written by
 * [refusal]'s entry point (the API's own `ProblemAuthenticationEntryPoint`) inside the API
 * chain's `HeaderWriterFilter`, which adds Spring's default security headers. A refusal
 * written here without them would differ from the API's by its headers alone, so the same
 * header filter, taken from the API chain, wraps the same entry point. A bearer token, valid
 * or not, changes nothing: the answer is always the no-token one.
 *
 * The address is the one [ClientAddressResolver] resolves, so `X-Forwarded-For` is believed
 * only from the configured proxies (ADR-0023).
 */
internal class AdminAddressFilter(
    allowedNetworks: List<String>,
    private val addresses: ClientAddressResolver,
    private val refusal: AuthenticationEntryPoint,
    private val apiHeaders: Filter?,
) : Filter {
    private val allowed: List<IpAddressMatcher> = allowedNetworks.map(::IpAddressMatcher)

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        request as HttpServletRequest
        response as HttpServletResponse
        if (isAllowed(request)) {
            chain.doFilter(request, response)
        } else {
            refuse(request, response)
        }
    }

    private fun isAllowed(request: HttpServletRequest): Boolean {
        val address =
            try {
                addresses.resolve(request).hostAddress
            } catch (_: IllegalStateException) {
                return false
            }
        return allowed.any { it.matches(address) }
    }

    private fun refuse(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val answer =
            FilterChain { req, res ->
                refusal.commence(req as HttpServletRequest, res as HttpServletResponse, InsufficientAuthenticationException(REFUSED))
            }
        if (apiHeaders == null) answer.doFilter(request, response) else apiHeaders.doFilter(request, response, answer)
    }

    private companion object {
        // Never written to the response or the log: the entry point writes its own words.
        const val REFUSED = "The address is not on the admin allow-list"
    }
}
