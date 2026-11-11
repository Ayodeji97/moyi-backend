package com.moyi.common.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Production trusts kamal-proxy by name, not the Docker network it shares with every other
 * container (ADR-0062, the owner's ruling 5 on ADR-0055). On `kamal` the proxy is one address
 * and the media worker, Postgres and Valkey are its neighbours; only the proxy may say who the
 * client is.
 */
internal class TrustedProxyHostsTest {
    private val proxy = "172.18.0.2"
    private val neighbour = "172.18.0.7" // the media worker, say: same network, not the proxy

    /** Docker's embedded DNS, as the test drives it: the name's current addresses, or none. */
    private class Dns {
        var answers: MutableMap<String, List<String>> = mutableMapOf()
        var lookups = 0

        fun lookup(host: String): List<InetAddress> {
            lookups++
            val found = answers[host] ?: throw UnknownHostException(host)
            return found.map { ClientAddress.parse(it)!!.inet }
        }
    }

    private var now = 0L
    private val dns = Dns().apply { answers["kamal-proxy"] = listOf(proxy) }

    private fun resolver(hosts: List<String> = listOf("kamal-proxy")): ClientAddressResolver =
        ClientAddressResolver(
            ClientAddressProperties(trustedProxyHosts = hosts),
            TrustedProxyHosts(hosts, dns::lookup, { now }, TTL),
        )

    @Test
    fun `kamal-proxy, trusted by name, names the client`() {
        resolver().resolve(request(remote = proxy, forwardedFor = "198.51.100.1")).hostAddress shouldBe "198.51.100.1"
    }

    @Test
    fun `a blank name from a stray comma trusts nothing, least of all loopback`() {
        // `InetAddress.getAllByName("")` is loopback: "kamal-proxy, " must not make every
        // process in the app's own container a trusted proxy.
        dns.answers[""] = listOf("127.0.0.1")
        dns.answers[" "] = listOf("127.0.0.1")
        resolver(listOf("kamal-proxy", "", " ")).resolve(request(remote = "127.0.0.1", forwardedFor = "10.9.1.1")).hostAddress shouldBe
            "127.0.0.1"
    }

    @Test
    fun `a neighbour on the same Docker network is not believed, whatever it writes in the header`() {
        // The whole point of ruling 5: with 172.18.0.0/16 trusted this was "10.9.1.1", an
        // address the admin console's allow-list would admit.
        resolver().resolve(request(remote = neighbour, forwardedFor = "10.9.1.1")).hostAddress shouldBe neighbour
    }

    @Test
    fun `a forged hop behind the proxy is still not believed`() {
        // The neighbour talks to the app through kamal-proxy and pre-fills the header:
        // the proxy appends the neighbour's own address, which is where the walk stops.
        resolver().resolve(request(remote = proxy, forwardedFor = "10.9.1.1, $neighbour")).hostAddress shouldBe neighbour
    }

    @Test
    fun `a recreated proxy at a new address is believed once the answer is stale, and its old address no longer is`() {
        val resolver = resolver()
        resolver.resolve(request(remote = proxy, forwardedFor = "198.51.100.1")).hostAddress shouldBe "198.51.100.1"

        dns.answers["kamal-proxy"] = listOf("172.18.0.9")
        // Within the TTL the previous answer stands: the new address is not yet believed.
        resolver.resolve(request(remote = "172.18.0.9", forwardedFor = "198.51.100.1")).hostAddress shouldBe "172.18.0.9"

        now += TTL.toNanos()
        resolver.resolve(request(remote = "172.18.0.9", forwardedFor = "198.51.100.1")).hostAddress shouldBe "198.51.100.1"
        // The address it gave up is Docker's to hand to the next container started.
        resolver.resolve(request(remote = proxy, forwardedFor = "10.9.1.1")).hostAddress shouldBe proxy
    }

    @Test
    fun `a name that stops resolving is trusted nowhere, not at its last address`() {
        val resolver = resolver()
        resolver.resolve(request(remote = proxy, forwardedFor = "198.51.100.1")).hostAddress shouldBe "198.51.100.1"

        dns.answers.remove("kamal-proxy")
        now += TTL.toNanos()
        resolver.resolve(request(remote = proxy, forwardedFor = "198.51.100.1")).hostAddress shouldBe proxy

        dns.answers["kamal-proxy"] = listOf(proxy)
        now += TTL.toNanos()
        resolver.resolve(request(remote = proxy, forwardedFor = "198.51.100.1")).hostAddress shouldBe "198.51.100.1"
    }

    @Test
    fun `a name that never resolves, as on a laptop, believes no header`() {
        resolver(listOf("kamal-proxy.invalid")).resolve(request(remote = proxy, forwardedFor = "10.9.1.1")).hostAddress shouldBe proxy
    }

    @Test
    fun `the name is looked up once per TTL, not once per request`() {
        val resolver = resolver()
        repeat(50) { resolver.resolve(request(remote = proxy, forwardedFor = "198.51.100.1")) }
        dns.lookups shouldBe 1

        now += TTL.toNanos()
        repeat(50) { resolver.resolve(request(remote = proxy, forwardedFor = "198.51.100.1")) }
        dns.lookups shouldBe 2
    }

    @Test
    fun `with no names configured nothing is ever looked up`() {
        val hosts = TrustedProxyHosts(emptyList(), dns::lookup, { now }, TTL)
        (ClientAddress.parse(proxy)!!.inet in hosts) shouldBe false
        dns.lookups shouldBe 0
    }

    @Test
    fun `while one request refreshes a stale answer, the others use the previous one rather than wait`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockNext = false
        var releasedByTheOtherCaller = false
        val hosts =
            TrustedProxyHosts(
                listOf("kamal-proxy"),
                { host ->
                    if (blockNext) {
                        entered.countDown()
                        releasedByTheOtherCaller = release.await(10, TimeUnit.SECONDS)
                    }
                    dns.lookup(host)
                },
                { now },
                TTL,
            )
        val address = ClientAddress.parse(proxy)!!.inet
        now += TTL.toNanos()
        blockNext = true
        val refresher = thread { address in hosts }
        entered.await(10, TimeUnit.SECONDS) shouldBe true

        // The refresher is inside the lookup, holding the refresh; this caller must not join it.
        // Were it to wait, the refresher would be released only by its own timeout, not here.
        (address in hosts) shouldBe true

        release.countDown()
        refresher.join()
        releasedByTheOtherCaller shouldBe true
    }

    @Test
    fun `the system's own name service is what resolves a name in production`() {
        // The default lookup, unmocked: `localhost` resolves on every machine, as
        // `kamal-proxy` does through Docker's embedded DNS on Kamal's network.
        val resolver = ClientAddressResolver(ClientAddressProperties(trustedProxyHosts = listOf("localhost")))
        val loopback = InetAddress.getAllByName("localhost").first().hostAddress

        resolver.resolve(request(remote = loopback, forwardedFor = "198.51.100.1")).hostAddress shouldBe "198.51.100.1"
        resolver.resolve(request(remote = "10.0.0.2", forwardedFor = "198.51.100.1")).hostAddress shouldBe "10.0.0.2"
    }

    private fun request(
        remote: String,
        forwardedFor: String?,
    ): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            remoteAddr = remote
            forwardedFor?.let { addHeader("X-Forwarded-For", it) }
        }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(5)
    }
}
