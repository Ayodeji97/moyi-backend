package com.moyi.notification.infra.email

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Which email provider is wired, and how to reach it.
 *
 * **The default is the real provider, and the real provider refuses to start
 * without its key.** The alternative ordering — default to a harmless logger
 * and opt *in* to Resend — means a production environment missing one
 * variable boots cleanly, passes its health check, and sends nothing, with
 * the only symptom being users who never receive a verification email. That
 * is the same fail-open shape [com.moyi.identity.infra.security.BreachCorpusProperties]
 * refuses for the breach corpus, for the same reason: a control that is not
 * running must not look like one that is. The logger has to be asked for by
 * name, which only the `local` profile does.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.notification.email")
internal data class EmailProperties(
    val provider: Provider = Provider.RESEND,
    /**
     * The `From` header, `Name <address@domain>`. The domain must be one the
     * provider has verified, which is why this is configuration and not
     * something a caller supplies per message.
     */
    val from: String? = null,
    val resend: Resend = Resend(),
) {
    internal enum class Provider {
        /** Resend's HTTP API (doc 25 D7). Requires [Resend.apiKey] and [from]. */
        RESEND,

        /**
         * Writes the message to the application log instead of sending it.
         * For local development only: it is how a developer reads the
         * verification link without a mailbox. Never the default.
         */
        LOG,
    }

    internal data class Resend(
        /** `re_…`. Supplied by the environment, never by a file in the repository. */
        val apiKey: String? = null,
        @field:NotBlank
        val baseUrl: String = DEFAULT_BASE_URL,
        /**
         * Short, because a send happens on a request path (the registration
         * response does not wait for it, but the thread does) and a provider
         * that is down should cost seconds, not the servlet timeout.
         */
        val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    ) {
        private companion object {
            const val DEFAULT_BASE_URL = "https://api.resend.com"
            val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
            val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(5)
        }
    }
}
