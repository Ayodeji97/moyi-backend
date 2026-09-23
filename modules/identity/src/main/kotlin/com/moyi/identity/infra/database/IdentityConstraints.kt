package com.moyi.identity.infra.database

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException

/**
 * The database constraint names, and a way to ask which one a failure was.
 *
 * These exist because "attempt the insert and handle the conflict" is the
 * only correct way to write a uniqueness check under concurrency — and
 * handling a conflict means knowing *which* constraint broke. Matching on the
 * exception's message would work until someone upgrades Postgres and the
 * wording changes; the constraint name is part of the schema, so it is a
 * contract, and `UserPersistenceTest` asserts these constants against the
 * real database.
 *
 * Hibernate's exception type is unwrapped here, in `infra`, rather than in the
 * service that asks the question: that is where the persistence provider is
 * allowed to be visible.
 */
internal object IdentityConstraints {
    /** `users_email_key` — one mailbox, one account. */
    const val USERS_EMAIL_UNIQUE = "users_email_key"

    /** `consent_records_user_document_version_key` — the same version accepted twice. */
    const val CONSENT_RECORD_UNIQUE = "consent_records_user_document_version_key"

    /** `verification_tokens_token_hash_key` — two tokens with one digest, which only a broken generator can produce. */
    const val VERIFICATION_TOKEN_HASH_UNIQUE = "verification_tokens_token_hash_key"
}

/**
 * True when this failure was [constraint] and not some other one.
 *
 * Walks the cause chain rather than checking `cause` directly: Spring wraps
 * Hibernate, which wraps the driver, and the depth is not something to
 * hard-code. Treating *any* integrity violation as "email already taken"
 * would silently swallow a genuine bug — a null in a NOT NULL column would
 * be reported to the user as a successful registration.
 */
internal fun DataIntegrityViolationException.violates(constraint: String): Boolean =
    generateSequence(cause) { it.cause }
        .any { it is ConstraintViolationException && it.constraintName.equals(constraint, ignoreCase = true) }
