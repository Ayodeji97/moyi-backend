package com.moyi.common.security

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proxies trusted by **name**, for a proxy whose address is not fixed (ADR-0062).
 *
 * In production the one proxy is `kamal-proxy`, a container on Kamal's Docker network whose
 * address Docker assigns and may assign differently when the container is recreated. Trusting
 * the whole network instead would trust every container on it (the media worker, Postgres,
 * Valkey), each of which could then name any client in `X-Forwarded-For`. So the name is
 * resolved through Docker's embedded DNS and only the addresses it names right now are
 * trusted, each one exactly.
 *
 * - **Resolved again every [ttl]**, by whichever request first finds the answer stale. Only
 *   that one request waits on the lookup; every other request meanwhile uses the previous
 *   answer. The JDK's own name cache (30 s for a positive answer, 10 s for a negative one, in
 *   the production image) sits beneath this one, so a recreated proxy is believed within about
 *   [ttl] plus 30 s.
 * - **Fails closed.** A name that does not resolve contributes no address, and its previous
 *   addresses are dropped rather than kept: an address kamal-proxy has given up can be handed
 *   to the next container Docker starts. Until the name resolves again its requests resolve to
 *   the proxy's own address — every client in one rate-limit bucket and the admin console
 *   refusing everyone — which is over-strict and visible, never over-trusting.
 * - **Never resolves anything a client sent.** The names come from configuration only; the
 *   header's entries are still parsed as literals ([ClientAddress.parse]).
 *
 * With no names configured (every profile but production) nothing is ever looked up.
 */
class TrustedProxyHosts(
    private val hosts: List<String>,
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttl: Duration = DEFAULT_TTL,
) {
    private class Answer(
        val addresses: Set<InetAddress>,
        val resolvedAt: Long,
    )

    private val refreshing = AtomicBoolean(false)
    private val unresolved: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var answer: Answer = if (hosts.isEmpty()) Answer(emptySet(), 0) else resolve()

    /** Whether [address] is, as of the latest answer, one of the trusted names' addresses. */
    operator fun contains(address: InetAddress): Boolean {
        if (hosts.isEmpty()) return false
        return address in current().addresses
    }

    private fun current(): Answer {
        val seen = answer
        // Fresh, or another request is already looking the name up: use what is there.
        if (nanoTime() - seen.resolvedAt < ttl.toNanos() || !refreshing.compareAndSet(false, true)) return seen
        return try {
            resolve().also { answer = it }
        } finally {
            refreshing.set(false)
        }
    }

    private fun resolve(): Answer {
        val addresses =
            hosts
                .flatMap { host ->
                    try {
                        lookup(host).also { if (unresolved.remove(host)) log.info("Trusted proxy {} resolves again", host) }
                    } catch (notResolved: UnknownHostException) {
                        if (unresolved.add(host)) {
                            log.warn(
                                "Trusted proxy {} does not resolve ({}); no X-Forwarded-For is believed from it until it does",
                                host,
                                notResolved.javaClass.simpleName,
                            )
                        }
                        emptyList()
                    }
                }.toSet()
        return Answer(addresses, nanoTime())
    }

    private companion object {
        val DEFAULT_TTL: Duration = Duration.ofSeconds(5)
        val log = LoggerFactory.getLogger(TrustedProxyHosts::class.java)
    }
}
