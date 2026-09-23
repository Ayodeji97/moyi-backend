package com.moyi.notification.infra.email

import com.fasterxml.jackson.annotation.JsonInclude
import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import com.moyi.notification.api.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

/**
 * [EmailSender] over Resend's HTTP API — the one class in this codebase that
 * knows Resend exists (doc 25 D7).
 *
 * **`RestClient`, not the vendor SDK.** Resend's API for this is a single
 * `POST /emails` with five fields. An SDK would add a dependency to audit and
 * a set of vendor types to keep out of the rest of the module, in exchange
 * for saving the twenty lines below. `RestClient` is also the client Spring
 * has recommended since 6.1 — synchronous, fluent, and the one an interviewer
 * expects to hear about in place of `RestTemplate`.
 *
 * **Failures are classified, not propagated.** Every exception the client
 * can raise is mapped onto [EmailDelivery]'s two failure cases by the one
 * question a retrying caller has: will sending this again help? A 4xx says
 * no, permanently; a 5xx, a timeout or a 429 says try later. Nothing escapes
 * as an exception, because a caller cannot be expected to know Spring's
 * client exception hierarchy to use an email port.
 *
 * **What this logs.** Status codes and the provider's message id. Never the
 * recipient, never the body, never the provider's error text — Resend's
 * validation messages quote the field that failed, which for a bad `to` is
 * the address (doc 11 NFR-044).
 */
internal class ResendEmailSender(
    private val client: RestClient,
    private val from: String,
) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: EmailMessage): EmailDelivery =
        try {
            val response =
                client
                    .post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers { headers -> message.idempotencyKey?.let { headers.set(IDEMPOTENCY_KEY_HEADER, it) } }
                    .body(
                        SendEmailRequest(
                            from = from,
                            to = listOf(message.to),
                            subject = message.subject,
                            text = message.text,
                            html = message.html,
                        ),
                    ).retrieve()
                    .body(SendEmailResponse::class.java)

            log.debug("Resend accepted an email, provider id {}", response?.id)
            EmailDelivery.Accepted(response?.id)
        } catch (rateLimited: HttpClientErrorException.TooManyRequests) {
            // A 4xx by number and a transient failure by nature: the message is
            // fine, the moment is not. Classified before the general 4xx case
            // below so it does not get filed as permanent.
            unavailable("Resend rate-limited the request (HTTP ${rateLimited.statusCode.value()})")
        } catch (refused: HttpClientErrorException) {
            rejected("Resend refused the message (HTTP ${refused.statusCode.value()})")
        } catch (failed: HttpServerErrorException) {
            unavailable("Resend failed (HTTP ${failed.statusCode.value()})")
        } catch (unreachable: ResourceAccessException) {
            // Connection refused, DNS, timeout. The cause's class says which;
            // its message can name hosts and is left out.
            unavailable("Resend unreachable (${unreachable.cause?.javaClass?.simpleName ?: "I/O failure"})")
        }

    private fun rejected(reason: String): EmailDelivery.Rejected {
        log.warn("Email rejected by provider: {}", reason)
        return EmailDelivery.Rejected(reason)
    }

    private fun unavailable(reason: String): EmailDelivery.Unavailable {
        log.warn("Email provider unavailable: {}", reason)
        return EmailDelivery.Unavailable(reason)
    }

    /** The wire shape of `POST /emails`. `html` is omitted rather than sent as `null`, which Resend rejects. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    internal data class SendEmailRequest(
        val from: String,
        val to: List<String>,
        val subject: String,
        val text: String,
        val html: String?,
    )

    internal data class SendEmailResponse(
        val id: String?,
    )

    internal companion object {
        /** Resend's header; the same name Stripe uses, and the IETF draft standardises. */
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

        /**
         * Builds the sender from configuration, refusing to build one that
         * could not send. The checks are here rather than as `@NotBlank`
         * on the properties because they apply only when Resend is the
         * provider: the `local` profile has no key and must still start.
         */
        fun create(
            properties: EmailProperties,
            builder: RestClient.Builder,
        ): ResendEmailSender {
            val apiKey = properties.resend.apiKey
            check(!apiKey.isNullOrBlank()) {
                "moyi.notification.email.provider is RESEND (the default) but moyi.notification.email.resend.api-key " +
                    "is not set. Supply the key through the environment, or set the provider to LOG for local " +
                    "development. Refusing to start rather than run without a way to send email."
            }
            val from = properties.from
            check(!from.isNullOrBlank()) {
                "moyi.notification.email.from is not set. Resend needs a sender on a verified domain, " +
                    "e.g. `Moyi <hello@example.com>`."
            }

            // The builder arrives with its transport — request factory,
            // timeouts, observation — already configured by the caller. It is
            // not set here, and the reason is worth keeping: the first version
            // did, and it silently replaced the mock server the tests had bound
            // to the same builder, so every test went to the real network and
            // four of them proved only that api.resend.test does not resolve.
            val client =
                builder
                    .baseUrl(properties.resend.baseUrl)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
                    .build()
            return ResendEmailSender(client, from)
        }
    }
}
