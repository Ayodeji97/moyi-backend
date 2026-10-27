package com.moyi.notification.infra.push

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Which push provider is wired, and how to reach it. The posture is email's
 * (ADR-0017 decision 3): **the default is the real provider, and it refuses to
 * start without its credentials.** `LOG` has to be asked for by name; the
 * `local` profile and the test contexts ask, and so, until there is a Firebase
 * project, does production's `config/deploy.yml`, in a line that says why
 * (ADR-0044).
 *
 * Every value arrives from the environment, as `MOYI_NOTIFICATION_PUSH_…`.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.notification.push")
internal data class PushProperties(
    val provider: Provider = Provider.FCM,
    val fcm: Fcm = Fcm(),
) {
    internal enum class Provider {
        /** Firebase Cloud Messaging, HTTP v1 (doc 05:67, doc 25:133). Requires [Fcm]'s three credentials. */
        FCM,

        /** Writes that a push would have been sent, and sends nothing. Never the default. */
        LOG,
    }

    /**
     * A service account of the Firebase project, as three values of its JSON
     * key file: `project_id`, `client_email` and `private_key`. Three values
     * rather than the file, so that each is one field in the password manager
     * and none of the file's other fields travels anywhere.
     */
    internal data class Fcm(
        val projectId: String? = null,
        val clientEmail: String? = null,
        /**
         * `-----BEGIN PRIVATE KEY-----` …, PKCS#8. The key file writes its line
         * breaks as `\n`; either that or real line breaks is accepted.
         */
        val privateKeyPem: String? = null,
        /** The key file's `private_key_id`, sent as the assertion's `kid`. Optional. */
        val privateKeyId: String? = null,
        @field:NotBlank
        val baseUrl: String = DEFAULT_BASE_URL,
        @field:NotBlank
        val tokenUri: String = DEFAULT_TOKEN_URI,
        /**
         * Short, because a send happens inside an outbox delivery's transaction
         * and holds a pooled connection and the delivery's row lock while it
         * waits (spec §2.2 point 1). The read timeout is the spec's 5 s.
         */
        val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    ) {
        /** The generated one would print the private key. */
        override fun toString(): String =
            "Fcm(projectId=$projectId, clientEmail=$clientEmail, privateKeyPem=${if (privateKeyPem == null) null else "<redacted>"}, " +
                "baseUrl=$baseUrl, tokenUri=$tokenUri, connectTimeout=$connectTimeout, readTimeout=$readTimeout)"

        private companion object {
            const val DEFAULT_BASE_URL = "https://fcm.googleapis.com"
            const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
            val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
            val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(5)
        }
    }
}
