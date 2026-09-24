package com.moyi.common.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Who is calling, when a proxy may be in between and the header that says so
 * is written by whoever sent the request.
 */
internal class ClientAddressResolverTest {
    @Test
    fun `with no trusted proxy configured the socket address is the client, and the header is ignored`() {
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = emptyList()))

        val address = resolver.resolve(request(remote = "203.0.113.7", forwardedFor = "198.51.100.1"))

        address.hostAddress shouldBe "203.0.113.7"
    }

    @Test
    fun `behind a trusted proxy the client is the rightmost address the proxy did not add`() {
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = listOf("10.0.0.0/8")))

        // kamal-proxy (10.x) appended the edge it heard from; the edge (10.y)
        // appended the phone. Reading from the right, past everything we trust.
        resolver.resolve(request(remote = "10.0.0.2", forwardedFor = "198.51.100.1, 10.0.0.3")).hostAddress shouldBe "198.51.100.1"
    }

    @Test
    fun `a value the client wrote into the header is never the answer`() {
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = listOf("10.0.0.0/8")))

        // The phone sent "X-Forwarded-For: 1.2.3.4" itself; the trusted proxy
        // appended the phone's real address after it.
        resolver.resolve(request(remote = "10.0.0.2", forwardedFor = "1.2.3.4, 198.51.100.1")).hostAddress shouldBe "198.51.100.1"
    }

    @Test
    fun `when every hop is trusted the leftmost is the client`() {
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = listOf("10.0.0.0/8", "127.0.0.0/8")))

        resolver.resolve(request(remote = "127.0.0.1", forwardedFor = "10.0.0.9")).hostAddress shouldBe "10.0.0.9"
        resolver.resolve(request(remote = "127.0.0.1", forwardedFor = null)).hostAddress shouldBe "127.0.0.1"
    }

    @Test
    fun `an entry that is not an address stops the walk rather than being looked up`() {
        // `InetAddress.getByName("evil.example")` would do a DNS lookup on an
        // attacker's string, on the request thread. A literal parser or nothing.
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = listOf("10.0.0.0/8")))

        resolver.resolve(request(remote = "10.0.0.2", forwardedFor = "evil.example, 10.0.0.3")).hostAddress shouldBe "10.0.0.3"
        resolver.resolve(request(remote = "10.0.0.2", forwardedFor = "evil.example")).hostAddress shouldBe "10.0.0.2"
    }

    @Test
    fun `IPv6 is understood, in the header and in the trusted list`() {
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = listOf("fd00::/8")))

        resolver.resolve(request(remote = "fd00::1", forwardedFor = "2001:db8::42")).hostAddress shouldBe "2001:db8:0:0:0:0:0:42"
    }

    @Test
    fun `the rate-limit key is the whole IPv4 address but only the first 64 bits of an IPv6 one`() {
        // A home IPv6 allocation is a /64 or more; limiting per /128 hands an
        // attacker 2^64 fresh keys.
        ClientAddress.parse("203.0.113.7")!!.rateLimitKey shouldBe "203.0.113.7"
        ClientAddress.parse("2001:db8:1:2:3:4:5:6")!!.rateLimitKey shouldBe "2001:db8:1:2:0:0:0:0/64"
        ClientAddress.parse("2001:db8:1:2:ffff:ffff:ffff:ffff")!!.rateLimitKey shouldBe "2001:db8:1:2:0:0:0:0/64"
    }

    @Test
    fun `a port suffix in the header is tolerated`() {
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxies = listOf("10.0.0.0/8")))

        resolver.resolve(request(remote = "10.0.0.2", forwardedFor = "198.51.100.1:51234")).hostAddress shouldBe "198.51.100.1"
        resolver.resolve(request(remote = "10.0.0.2", forwardedFor = "[2001:db8::42]:51234")).hostAddress shouldBe "2001:db8:0:0:0:0:0:42"
    }

    private fun request(
        remote: String,
        forwardedFor: String?,
    ): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            remoteAddr = remote
            forwardedFor?.let { addHeader("X-Forwarded-For", it) }
        }
}
