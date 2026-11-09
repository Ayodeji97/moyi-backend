package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.identity.domain.TokenGenerator
import com.moyi.identity.domain.User
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationRequested
import com.moyi.identity.domain.VerificationToken
import com.moyi.identity.infra.database.VerificationTokenStore
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Issues a verification token for a user and asks for it to be emailed.
 *
 * The one thing registration and resend have in common, in one place: mint a
 * secret, store its hash, publish the event that becomes the email. Both
 * callers invoke this **inside their own transaction** — the token row
 * commits or rolls back with whatever they were doing, and the event is bound
 * to that same transaction, so the email is sent only if the row exists.
 *
 * Not `@Transactional` itself, for that reason: it joins the caller's
 * boundary and must never open one of its own. Called outside any
 * transaction it would still insert the row, but the `AFTER_COMMIT` listener
 * would find no transaction to bind to and Spring would drop the event on
 * the floor — proven by moving the publish outside the boundary and watching
 * seven tests fail (docs/learning-log.md, 2026-09-23).
 */
@Component
internal class RequestVerification(
    private val tokens: VerificationTokenStore,
    private val generator: TokenGenerator,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
    private val codes: VerificationCodeIssuer,
) {
    fun request(
        user: User,
        purpose: VerificationPurpose,
    ) {
        val now = clock.instant()
        // A new email retires the code in the last one (#135); its link stays live
        // (ADR-0018 decision 6, amended 2026-11-08). Under a per-person lock, so two
        // emails asked for at once cannot both hold a code.
        tokens.retireCodes(user.id, purpose)
        // The secret and the code exist here, in the event, and in the email. Never in a row.
        val secret = generator.verificationSecret()
        val id = ids.timeOrdered()
        val code = codes.issue(id, purpose)
        val token =
            VerificationToken.issue(
                id = id,
                userId = user.id,
                purpose = purpose,
                secret = secret,
                codeHash = code.hash,
                codeTtl = code.ttl,
                now = now,
            )
        tokens.insert(token)

        events.publishEvent(
            VerificationRequested(
                userId = user.id,
                email = user.email,
                displayName = user.displayName,
                locale = user.locale,
                purpose = purpose,
                tokenId = token.id,
                secret = secret,
                expiresAt = token.expiresAt,
                code = code.code,
                codeExpiresAt = token.codeExpiresAt!!,
            ),
        )
    }
}
