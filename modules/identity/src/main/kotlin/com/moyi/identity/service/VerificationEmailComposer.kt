package com.moyi.identity.service

import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationRequested
import com.moyi.identity.infra.security.VerificationProperties
import com.moyi.notification.api.EmailMessage
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import org.springframework.web.util.UriComponentsBuilder

/**
 * Writes the verification email. Pure: an event in, a message out, nothing
 * read or sent, which is what makes its tests a list of strings.
 *
 * **English only, for now.** The user's locale travels in the event because
 * doc 16 §3 puts email localisation in Phase 1 and the message shape should
 * not need to change when it arrives; the copy itself is one language until
 * there is a second template to choose. Recorded as owed in ADR-0018.
 *
 * **The display name is HTML-escaped.** It is user input, and the HTML body
 * is the one place in this system where user input is rendered as markup by
 * somebody else's software. `<script>` as a display name must arrive as text.
 *
 * The copy follows `states.md`: plain, no urgency, and it says what the link
 * does before asking for the click. The idempotency key is the token id, so
 * the same token can never produce two emails at the provider.
 */
@Component
internal class VerificationEmailComposer(
    private val properties: VerificationProperties,
) {
    fun compose(event: VerificationRequested): EmailMessage {
        val link = linkFor(event)
        val name = event.displayName.trim()
        val reset = event.purpose == VerificationPurpose.PASSWORD_RESET

        return EmailMessage(
            to = event.email.value,
            subject = if (reset) RESET_SUBJECT else VERIFY_SUBJECT,
            text =
                """
                |Hi $name,
                |
                |${if (reset) "Reset your Moyi password by opening the link below. It works once and expires in 1 hour." else "Confirm this is your address by opening the link below. It works once and expires in 24 hours."}
                |
                |$link
                |
                |${if (reset) "If you did not request a password reset, you can ignore this email. Your password will not change without the link." else "If you did not create a Moyi account, you can ignore this email. Nothing happens without the link."}
                """.trimMargin(),
            html =
                """
                |<p>Hi ${HtmlUtils.htmlEscape(name)},</p>
                |<p>${if (reset) "Reset your Moyi password by opening the link below. It works once and expires in 1 hour." else "Confirm this is your address by opening the link below. It works once and expires in 24 hours."}</p>
                |<p><a href="${HtmlUtils.htmlEscape(link)}">${if (reset) "Reset my password" else "Confirm my email"}</a></p>
                |<p>${if (reset) "If you did not request a password reset, you can ignore this email. Your password will not change without the link." else "If you did not create a Moyi account, you can ignore this email. Nothing happens without the link."}</p>
                """.trimMargin(),
            idempotencyKey = event.tokenId.toString(),
        )
    }

    /**
     * `<link-base-url>?token=<secret>`. The secret is base64url, an alphabet
     * that needs no percent-encoding, but the builder encodes anyway so a
     * generator change cannot produce a link that breaks in a query string.
     */
    private fun linkFor(event: VerificationRequested): String =
        UriComponentsBuilder
            .fromUri(
                when (event.purpose) {
                    VerificationPurpose.EMAIL_VERIFICATION -> properties.linkBaseUrl
                    VerificationPurpose.PASSWORD_RESET -> properties.resetLinkBaseUrl
                },
            ).queryParam("token", event.secret.value)
            .encode()
            .build()
            .toUriString()

    private companion object {
        const val VERIFY_SUBJECT = "Confirm your email address"
        const val RESET_SUBJECT = "Reset your Moyi password"
    }
}
