package com.moyi.common.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component

/**
 * Where the request came from, through however many proxies we trust.
 *
 * Behind a reverse proxy the socket address is the proxy's, and the client's
 * is in `X-Forwarded-For` — a header any client can also write. The rule is
 * the standard one: walk the header **from the right**, past every address
 * that belongs to a proxy we configured, and the first address that does not
 * is the client. Anything to the left of it was written by someone we do not
 * trust and is ignored, which is what makes a forged header worthless.
 *
 * **No configured proxies means no header is believed**, and the socket
 * address is the client. That is the safe default in both directions: on a
 * developer's laptop the socket *is* the client, and in an environment where
 * someone forgot to configure the ranges, per-IP limiting collapses to
 * per-proxy limiting — over-strict, and visible — rather than to
 * per-whatever-the-client-claims, which is no limit at all. In production,
 * kamal-proxy sits in front (ADR-0011) and is trusted **by name**, in
 * `moyi.security.client-address.trusted-proxy-hosts` ([TrustedProxyHosts],
 * ADR-0062): its address on Kamal's Docker network is Docker's to assign, and
 * trusting the whole network would trust every container on it. Fixed ranges
 * (Cloudflare's, should the host ever be proxied) go in
 * `moyi.security.client-address.trusted-proxies`. A proxy is trusted when it
 * matches either list.
 *
 * Tomcat's `RemoteIpValve` does the same job with a regular expression over
 * the proxy addresses and rewrites `remoteAddr` for everyone; ADR-0023 says
 * why a CIDR list in a class the tests can hold was preferred.
 */
@Component
class ClientAddressResolver(
    properties: ClientAddressProperties,
    private val proxyHosts: TrustedProxyHosts,
) {
    @Autowired
    constructor(properties: ClientAddressProperties) : this(properties, TrustedProxyHosts(properties.trustedProxyHosts))

    private val trusted: List<IpAddressMatcher> = properties.trustedProxies.map(::IpAddressMatcher)

    fun resolve(request: HttpServletRequest): ClientAddress {
        val socket =
            ClientAddress.parse(request.remoteAddr)
                ?: error("The servlet container reported a remote address that is not an address")
        if (!socket.isTrustedProxy()) return socket

        // Right to left, and only as far as the entries parse: a hop that is
        // not an address ends the walk, because what lies beyond it was
        // written by something we cannot account for.
        val hops =
            request
                .getHeaders(FORWARDED_FOR)
                .toList()
                .flatMap { it.split(',') }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .asReversed()
                .asSequence()
                .map(ClientAddress::parse)
                .takeWhile { it != null }
                .filterNotNull()

        var client = socket
        for (address in hops) {
            client = address
            if (!address.isTrustedProxy()) break
        }
        return client
    }

    private fun ClientAddress.isTrustedProxy(): Boolean = trusted.any { it.matches(hostAddress) } || inet in proxyHosts

    private companion object {
        const val FORWARDED_FOR = "X-Forwarded-For"
    }
}

/**
 * The proxies whose `X-Forwarded-For` entries are believed: [trustedProxies] as CIDR ranges
 * (`10.0.0.0/8`, `fd00::/8`), [trustedProxyHosts] as names resolved to their current
 * addresses (`kamal-proxy`; [TrustedProxyHosts]). Both empty by default — see
 * [ClientAddressResolver] for why empty means "trust nobody" rather than "trust everybody".
 */
@ConfigurationProperties(prefix = "moyi.security.client-address")
data class ClientAddressProperties(
    val trustedProxies: List<String> = emptyList(),
    val trustedProxyHosts: List<String> = emptyList(),
)
