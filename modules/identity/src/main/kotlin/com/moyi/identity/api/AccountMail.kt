package com.moyi.identity.api

import java.util.UUID

/**
 * Writes to an account holder at their address, for another module that must tell them something
 * by email and has no business knowing where it goes (ADR-0057 decision 6). [UserDirectory] says
 * "no email" on purpose, and this keeps it true: the caller names the account, identity finds the
 * address, and the address never crosses this boundary.
 *
 * **Only a confirmed address is written to.** Registration does not confirm an address (ADR-0015),
 * so an unconfirmed one may be somebody else's, and a message a stranger can make this system send
 * to it is the abuse that rule guards against. [AccountMailOutcome.NOT_VERIFIED] says so, and nothing
 * is sent.
 *
 * Synchronous, and reports rather than throws, as `notification.api.EmailSender` does: what a failed
 * send means is the caller's to decide.
 */
interface AccountMail {
    fun sendToAccountHolder(
        userId: UUID,
        message: AccountMessage,
    ): AccountMailOutcome
}

/**
 * What to write: no recipient (that is identity's), a subject, a plain-text body, an optional HTML
 * one, and a key a provider treats a second send under as the first. [toString] never prints the
 * text: a caller may one day put somebody's words in it.
 */
class AccountMessage(
    val subject: String,
    val text: String,
    val html: String?,
    val idempotencyKey: String,
) {
    override fun toString(): String = "AccountMessage(subject='$subject', idempotencyKey=$idempotencyKey)"
}

/** What became of a [AccountMail.sendToAccountHolder]. */
enum class AccountMailOutcome {
    /** The provider took it. Not "delivered": no provider can promise that at request time. */
    ACCEPTED,

    /** The account's address has never been confirmed, so nothing was sent. */
    NOT_VERIFIED,

    /** The id names no account, or one being deleted, so nothing was sent. */
    NO_ACCOUNT,

    /** The provider refused it or could not be reached; identity has logged which. */
    FAILED,
}
