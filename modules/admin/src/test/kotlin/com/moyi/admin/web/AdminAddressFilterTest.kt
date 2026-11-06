package com.moyi.admin.web

import com.moyi.common.security.ClientAddressProperties
import com.moyi.common.security.ClientAddressResolver
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.AuthenticationEntryPoint

/**
 * The allow-list on its own (spec §4): which addresses it admits, that an empty list admits
 * nobody, that a forged `X-Forwarded-For` is not believed, and that a refusal is the entry
 * point's answer and nothing else, with no session. `AdminChainTest` in `app` holds the same
 * filter inside the real chain, against the API's real 401.
 */
internal class AdminAddressFilterTest {
    private var refusals = 0
    private val entryPoint = AuthenticationEntryPoint { _, response, _ -> refusals++.also { response.status = UNAUTHORIZED } }
    private val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = listOf("172.16.0.0/12")))

    @Test
    fun `an address inside a listed network passes, IPv4 and IPv6`() {
        val filter = filter("10.9.0.0/16", "::1/128")
        passes(filter, request("10.9.200.4")) shouldBe true
        passes(filter, request("0:0:0:0:0:0:0:1")) shouldBe true
        refusals shouldBe 0
    }

    @Test
    fun `an address outside every listed network is refused by the entry point, with no session`() {
        val request = request("10.10.0.1")
        val response = MockHttpServletResponse()
        var passed = false
        filter("10.9.0.0/16").doFilter(request, response, FilterChain { _, _ -> passed = true })
        passed shouldBe false
        refusals shouldBe 1
        response.status shouldBe UNAUTHORIZED
        request.getSession(false).shouldBeNull()
    }

    @Test
    fun `an empty list admits nobody`() {
        passes(filter(), request("10.9.1.1")) shouldBe false
        passes(filter(), request("127.0.0.1")) shouldBe false
    }

    @Test
    fun `a forwarded address is believed from a trusted proxy only`() {
        val filter = filter("10.9.0.0/16")
        passes(filter, request("203.0.113.7").apply { addHeader("X-Forwarded-For", "10.9.1.1") }) shouldBe false
        passes(filter, request("172.16.0.2").apply { addHeader("X-Forwarded-For", "10.9.1.1") }) shouldBe true
    }

    @Test
    fun `a remote address that is not an address is refused, not an error`() {
        passes(filter("0.0.0.0/0"), request("not-an-address")) shouldBe false
    }

    private fun filter(vararg networks: String) = AdminAddressFilter(networks.toList(), resolver, entryPoint, apiHeaders = null)

    private fun passes(
        filter: AdminAddressFilter,
        request: MockHttpServletRequest,
    ): Boolean {
        var passed = false
        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> passed = true })
        return passed
    }

    private fun request(remote: String) = MockHttpServletRequest("GET", "/admin/login").apply { remoteAddr = remote }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
