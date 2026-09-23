package com.moyi.notification.api

/**
 * The one way anything in this system sends an email.
 *
 * This is the first `api` package in the repository, and it exists because of
 * doc 25 D7: every external provider sits behind an interface *we* define, in
 * the module that owns the capability, with the vendor confined to a single
 * `internal` class. Swapping Resend for Postmark is then a one-file change,
 * tests use a fake of this interface instead of a mock of somebody else's
 * SDK, and no vendor type is ever seen by a caller. `notification` owns email
 * (doc 05 §2), so the port lives here and `identity` calls it.
 *
 * **Sending is synchronous and reports, it does not throw.** A caller has to
 * decide what a failed send means for *its* operation — a verification email
 * that did not go out is a WARN and a resend button, a reveal notification
 * that did not go out is a P1 — and a sealed [EmailDelivery] forces that
 * decision to be written down at every call site. Doc 18 §3: failures are a
 * sealed result at module boundaries, and this is one.
 *
 * Whether a send is *deferred* — queued through the outbox, retried on
 * [EmailDelivery.Unavailable] — is not this interface's concern either. ADR-0008
 * puts that in the caller's write path, where the transaction is.
 */
interface EmailSender {
    fun send(message: EmailMessage): EmailDelivery
}

/**
 * One message to one recipient. The sender address is not here: it is the
 * provider's configuration, because it is a property of *this system*, not of
 * any one message, and letting a caller choose it is how a caller sends from
 * an address the provider has not verified.
 *
 * @property to The recipient. Redacted by [toString]: an address is personal
 *   data (doc 11 NFR-044) and this object will be interpolated into a log
 *   line by somebody, eventually.
 * @property text The plain-text body. Required — HTML is the enhancement, not
 *   the message, and a client that cannot render HTML still gets the link.
 * @property idempotencyKey When present, the provider treats a second send
 *   with the same key as the first, so a retry after an ambiguous failure
 *   cannot deliver twice. Callers should pass the id of the thing being
 *   sent about — a verification token, a notification — not a random value.
 */
data class EmailMessage(
    val to: String,
    val subject: String,
    val text: String,
    val html: String? = null,
    val idempotencyKey: String? = null,
) {
    init {
        require(to.isNotBlank()) { "an email needs a recipient" }
        require(subject.isNotBlank()) { "an email needs a subject" }
        require(text.isNotBlank()) { "an email needs a plain-text body" }
        require(idempotencyKey == null || idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            "an idempotency key must be at most $MAX_IDEMPOTENCY_KEY_LENGTH characters"
        }
    }

    override fun toString(): String = "EmailMessage(to=<redacted>, subject='$subject', idempotencyKey=$idempotencyKey)"

    private companion object {
        /** Resend's documented limit; other providers are looser. */
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 256
    }
}

/**
 * What became of a send. Three outcomes, because a caller that retries needs
 * to tell the two failures apart: retrying a [Rejected] send produces the
 * same rejection forever, retrying an [Unavailable] one is the whole point
 * of having a retry.
 */
sealed interface EmailDelivery {
    /**
     * The provider accepted the message for delivery. Not "delivered" — no
     * provider can promise that at request time — and [providerMessageId] is
     * what to search their dashboard for when a user says nothing arrived.
     */
    data class Accepted(
        val providerMessageId: String?,
    ) : EmailDelivery

    /**
     * The provider refused this message and will refuse it again: a
     * malformed address, an unverified sending domain, a body it will not
     * carry. Retrying is pointless; something about the message or the
     * configuration has to change.
     */
    data class Rejected(
        val reason: String,
    ) : EmailDelivery

    /**
     * The provider could not be reached or could not serve the request right
     * now: a timeout, a 5xx, a rate limit. The message was fine and may be
     * retried as-is.
     */
    data class Unavailable(
        val reason: String,
    ) : EmailDelivery
}
