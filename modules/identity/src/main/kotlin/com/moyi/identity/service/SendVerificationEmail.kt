package com.moyi.identity.service

import com.moyi.identity.domain.VerificationRequested
import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Turns a committed [VerificationRequested] into an email.
 *
 * **`@TransactionalEventListener(AFTER_COMMIT)`**, so this runs only once the
 * token row is durable. An ordinary `@EventListener` would fire at publish
 * time, inside the transaction, and send a link for a token that might roll
 * back a millisecond later. This is the Observer pattern with the
 * transaction as the subject.
 *
 * **`@Async`**, so the registration response does not wait for the provider.
 * That is a latency choice and a security one: the duplicate-registration
 * path publishes no event, so a synchronous send would make new accounts
 * measurably slower than duplicates — a timing oracle for "is this address
 * registered" (T-18), the very thing ADR-0015's identical response and
 * `RegisterUser`'s always-hash rule exist to close. Off the request thread,
 * both paths return in the same time.
 *
 * **What this is not: the outbox.** ADR-0008 names this exact arrangement as
 * acceptable for *non-critical* events, and defines the gap: if the process
 * dies after the commit and before this method runs, the email is lost and
 * nothing retries it. The verification email is non-critical under that
 * definition because the person has a "send it again" button (`states.md`
 * §1, screen 3) — the recovery path is designed in. A reveal notification
 * has no such button, which is why *that* goes through the outbox. When the
 * outbox lands, the event write replaces this listener and nothing upstream
 * changes.
 *
 * A failed send is a WARN, never an exception: there is no caller left to
 * throw to, and `EmailSender` reports rather than throws (ADR-0017).
 */
@Component
internal class SendVerificationEmail(
    private val emails: EmailSender,
    private val composer: VerificationEmailComposer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: VerificationRequested) {
        // User id and token id only: a token id is not the secret, and the
        // address is personal data (doc 11 NFR-044).
        when (val delivery = emails.send(composer.compose(event))) {
            is EmailDelivery.Accepted -> {
                log.info(
                    "Verification email accepted for user {} (token {}, provider id {})",
                    event.userId.value,
                    event.tokenId,
                    delivery.providerMessageId,
                )
            }

            is EmailDelivery.Rejected -> {
                log.warn(
                    "Verification email REJECTED for user {} (token {}): {}. The person can request another.",
                    event.userId.value,
                    event.tokenId,
                    delivery.reason,
                )
            }

            is EmailDelivery.Unavailable -> {
                log.warn(
                    "Verification email not sent for user {} (token {}), provider unavailable: {}. " +
                        "No retry until the outbox (ADR-0008) — the person can request another.",
                    event.userId.value,
                    event.tokenId,
                    delivery.reason,
                )
            }
        }
    }
}
