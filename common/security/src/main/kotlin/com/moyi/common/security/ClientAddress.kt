package com.moyi.common.security

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The address a request came from, once the proxies in between have been
 * accounted for (see [ClientAddressResolver]).
 *
 * Wrapped rather than a bare [InetAddress] for the one derived value that
 * matters: [rateLimitKey]. An IPv4 address is its own key. An IPv6 address is
 * keyed on its first 64 bits, because an ordinary allocation is a /64 or
 * larger and a per-/128 bucket would give one connection 2^64 fresh keys —
 * a per-IP limit that can be sidestepped by incrementing a number is not a
 * limit.
 */
class ClientAddress private constructor(
    private val inet: InetAddress,
) {
    /** Java's canonical text form: dotted quad, or uncompressed colon-hex for IPv6. */
    val hostAddress: String get() = inet.hostAddress

    val rateLimitKey: String
        get() =
            when (inet) {
                is Inet6Address -> {
                    val prefix = inet.address.copyOf().also { it.fill(0, PREFIX_BYTES, it.size) }
                    "${InetAddress.getByAddress(prefix).hostAddress}/$PREFIX_BITS"
                }

                else -> {
                    inet.hostAddress
                }
            }

    override fun equals(other: Any?): Boolean = other is ClientAddress && other.inet == inet

    override fun hashCode(): Int = inet.hashCode()

    override fun toString(): String = "ClientAddress(redacted)"

    companion object {
        private const val PREFIX_BITS = 64
        private const val PREFIX_BYTES = PREFIX_BITS / Byte.SIZE_BITS
        private const val OCTET_MAX = 255
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        private val IPV6_CHARACTERS = Regex("""^[0-9A-Fa-f:.]+$""")
        private val BRACKETED_WITH_PORT = Regex("""^\[([^]]+)](?::\d+)?$""")
        private val IPV4_WITH_PORT = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):\d+$""")

        fun of(inet: InetAddress): ClientAddress = ClientAddress(inet)

        /**
         * A literal address or nothing — **never a name**. `InetAddress.getByName`
         * resolves anything that is not a literal through DNS, on the request
         * thread, and the string comes from a header the client wrote. So the
         * shape is checked here first, and only a shape that Java parses
         * without a lookup is handed to it.
         *
         * Tolerates the two port suffixes proxies are known to append:
         * `a.b.c.d:port` and `[v6]:port`.
         */
        fun parse(literal: String): ClientAddress? {
            val text =
                BRACKETED_WITH_PORT.matchEntire(literal.trim())?.groupValues?.get(1)
                    ?: IPV4_WITH_PORT.matchEntire(literal.trim())?.groupValues?.get(1)
                    ?: literal.trim()
            return when {
                IPV4.matches(text) -> ipv4(text)
                ':' in text && IPV6_CHARACTERS.matches(text) -> ipv6(text)
                else -> null
            }
        }

        private fun ipv4(text: String): ClientAddress? {
            val octets =
                IPV4
                    .matchEntire(text)!!
                    .groupValues
                    .drop(1)
                    .map(String::toInt)
            if (octets.any { it > OCTET_MAX }) return null
            return ClientAddress(InetAddress.getByAddress(ByteArray(octets.size) { octets[it].toByte() }))
        }

        @Suppress("SwallowedException")
        private fun ipv6(text: String): ClientAddress? =
            try {
                // Bracketed on purpose. The JDK treats an unbracketed string
                // that fails its IPv6 literal parse as a *hostname* and hands
                // it to the name service; "1:2:3:4:5:6:7:8:9" is all hex and
                // colons and is exactly that case. With brackets the contract
                // is literal-or-throw, and nothing here ever resolves a name.
                ClientAddress(InetAddress.getByName("[$text]"))
            } catch (notAnAddress: UnknownHostException) {
                null
            }
    }
}
