package com.moyi.identity.service

import com.moyi.identity.domain.SecurityNotice
import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import com.moyi.notification.api.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.util.HtmlUtils
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Emails the account holder after a security-relevant commit: a refresh-token
 * reuse (doc 09 §3) or a password change (T-17). Same `AFTER_COMMIT` +
 * `@Async` arrangement as `SendVerificationEmail`, for the same reasons, with
 * the same accepted gap: a process death between commit and send loses the
 * notice, and the *protection* — the revocation — has already happened.
 *
 * No link, no token, nothing to click: these emails inform. A person who did
 * not do the thing described has one recovery, which is the app's sign-in
 * and "I forgot my password", and the email says so in those words.
 */
@Component
internal class SendSecurityNotice(
    private val emails: EmailSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(notice: SecurityNotice) {
        val message = compose(notice)
        when (val delivery = emails.send(message)) {
            is EmailDelivery.Accepted -> {
                log.info(
                    "Security notice {} accepted for user {} (provider id {})",
                    notice.kind,
                    notice.userId.value,
                    delivery.providerMessageId,
                )
            }

            is EmailDelivery.Rejected -> {
                log.warn("Security notice {} REJECTED for user {}: {}", notice.kind, notice.userId.value, delivery.reason)
            }

            is EmailDelivery.Unavailable -> {
                log.warn(
                    "Security notice {} not sent for user {}, provider unavailable: {}",
                    notice.kind,
                    notice.userId.value,
                    delivery.reason,
                )
            }
        }
    }

    private val SecurityNotice.kind: String get() = javaClass.simpleName

    /** Plain and specific, no urgency (states.md's register). The display name is escaped in the HTML body, as always. */
    private fun compose(notice: SecurityNotice): EmailMessage {
        val name = notice.displayName.trim()
        val (subject, body) =
            when (notice) {
                is SecurityNotice.SessionReuseDetected -> {
                    "A sign-in on your Moyi account was ended" to
                        "A sign-in for your account was used from two places at once, which can mean another device has a " +
                        "copy of it. That sign-in has been ended and cannot be used again. Your other devices are not affected. " +
                        "If this was not you, change your password from the sign-in screen with \"I forgot my password\"; " +
                        "that signs every device out."
                }

                is SecurityNotice.DeletionRequested -> {
                    "Your Moyi account will be deleted" to
                        "You asked for your Moyi account to be deleted. It will be deleted after " +
                        "${DELETION_DATE.format(notice.cancellableUntil)}. Until then you can still sign in, and you can " +
                        "change your mind: open Moyi, go to your account, and cancel it under " +
                        "\"Delete my account\". After that date it cannot be undone. What you wrote becomes a blank " +
                        "placeholder in your shared archive, so the other person's days and streak stay as they were. " +
                        "If you did not ask for this, sign in, cancel it, and change your password with " +
                        "\"I forgot my password\"."
                }

                is SecurityNotice.PasswordChanged -> {
                    "Your Moyi password was changed" to
                        "Your password was changed just now, and every device has been signed out. If this was you, there is " +
                        "nothing more to do. If it was not, use \"I forgot my password\" on the sign-in screen straight away."
                }
            }
        return EmailMessage(
            to = notice.email.value,
            subject = subject,
            text = "Hi $name,\n\n$body",
            html = "<p>Hi ${HtmlUtils.htmlEscape(name)},</p><p>${HtmlUtils.htmlEscape(body)}</p>",
            // One notice per family, one per change; a retry cannot double up,
            // and a second change is a second notice — a key on the user id
            // alone would have made every later one look like a retry of the
            // first (Codex review of PR #32).
            idempotencyKey =
                when (notice) {
                    is SecurityNotice.SessionReuseDetected -> "reuse-${notice.familyId}"

                    is SecurityNotice.PasswordChanged -> "password-changed-${notice.userId.value}-${notice.changedAt.toEpochMilli()}"

                    // One per request: a repeated POST is the same request and must not mail twice.
                    is SecurityNotice.DeletionRequested -> "deletion-requested-${notice.requestId}"
                },
        )
    }
}

/**
 * "23 November 2026 at 14:05 UTC": the moment the window closes, to the minute, because it closes at
 * the request's time of day and not at the start or end of that date. UTC, and said so.
 */
private val DELETION_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.UK).withZone(ZoneOffset.UTC)
