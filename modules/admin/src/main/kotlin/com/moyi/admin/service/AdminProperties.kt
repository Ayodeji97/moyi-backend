package com.moyi.admin.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.Base64
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The console's switches (spec §5, §6). Off by default: the console does not exist
 * unless [enabled]. When it is on, [validate] refuses to let the app start without
 * an allow-list and a 256-bit TOTP key. Messages name the property, never its value.
 */
@ConfigurationProperties("moyi.admin")
internal data class AdminProperties(
    val enabled: Boolean = false,
    val allowedNetworks: List<String> = emptyList(),
    /** Base64 of exactly 32 bytes: the AES-256 key that seals the TOTP secrets. */
    val totpKey: String? = null,
    val idleTimeout: Duration = 30.minutes.toJavaDuration(),
    val absoluteTimeout: Duration = 8.hours.toJavaDuration(),
    val issuer: String = "Moyi admin",
) {
    fun validate() {
        if (!enabled) return
        check(allowedNetworks.isNotEmpty()) { "moyi.admin.allowed-networks must list at least one network when the console is enabled" }
        check(totpKey != null) { "moyi.admin.totp-key must be set when the console is enabled" }
        check(decodedKey()?.size == KEY_BYTES) { "moyi.admin.totp-key must be base64 of exactly $KEY_BYTES bytes" }
    }

    /** The key as bytes, or null when it is not valid base64. */
    fun keyBytes(): ByteArray? = decodedKey()

    private fun decodedKey(): ByteArray? = totpKey?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** The generated one would print the key. */
    override fun toString() =
        "AdminProperties(enabled=$enabled, allowedNetworks=$allowedNetworks, totpKey=${totpKey?.let { "<redacted>" }}, " +
            "idleTimeout=$idleTimeout, absoluteTimeout=$absoluteTimeout, issuer=$issuer)"

    private companion object {
        const val KEY_BYTES = 32
    }
}
