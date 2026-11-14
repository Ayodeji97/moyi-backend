package com.moyi.identity.infra.database

import com.moyi.identity.domain.CodeHash
import com.moyi.identity.domain.ConsentRecord
import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.TokenHash
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationToken

/**
 * The translation between the domain model and the persistence model.
 *
 * Kept as plain functions rather than a mapping library: MapStruct does not
 * see Kotlin's value classes, and a reflective mapper would turn a rename
 * into a runtime surprise instead of a compile error. Two dozen lines of
 * explicit assignment is also the only version a reviewer can check against
 * the migration by reading it.
 *
 * The direction matters. [toDomain] is total — anything in the table can be
 * expressed. [toEntity] is **insert-only**: it builds a brand-new object
 * whose `isNew` flag is true, so handing its result to `save()` asks for an
 * `INSERT`. An existing `users` row is changed only by a targeted statement on
 * `UserRepository`, never by loading a user and saving it back: a load-and-save
 * writes every column it read, and twice in admin console A3 that wrote a stale
 * `status` and `tokens_invalid_before` over a suspension that committed in
 * between (verify, reset). There is deliberately no `User.applyTo`. Credentials
 * keep [Credentials.applyTo], which writes no status.
 */
internal fun UserEntity.toDomain(): User =
    User(
        id = UserId(getId()),
        email = Email(email),
        emailVerifiedAt = emailVerifiedAt,
        displayName = displayName,
        avatarMediaId = avatarMediaId,
        locale = locale,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        tokensInvalidBefore = tokensInvalidBefore,
    )

/** Builds a new row. See the note above on why this is not the update path. */
internal fun User.toEntity(): UserEntity =
    UserEntity(
        id = id.value,
        email = email.value,
        emailVerifiedAt = emailVerifiedAt,
        displayName = displayName,
        avatarMediaId = avatarMediaId,
        locale = locale,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        tokensInvalidBefore = tokensInvalidBefore,
    )

internal fun CredentialsEntity.toDomain(): Credentials =
    Credentials(
        userId = UserId(getId()),
        passwordHash = PasswordHash(passwordHash),
        algorithm = algorithm,
        passwordUpdatedAt = passwordUpdatedAt,
        failedAttempts = failedAttempts,
        lockedUntil = lockedUntil,
    )

/** Insert-only, for the same reason as [User.toEntity]. */
internal fun Credentials.toEntity(): CredentialsEntity =
    CredentialsEntity(
        id = userId.value,
        passwordHash = passwordHash.value,
        algorithm = algorithm,
        passwordUpdatedAt = passwordUpdatedAt,
        failedAttempts = failedAttempts,
        lockedUntil = lockedUntil,
    )

internal fun Credentials.applyTo(entity: CredentialsEntity) {
    require(entity.getId() == userId.value) { "cannot apply credentials onto a different user's row" }
    entity.passwordHash = passwordHash.value
    entity.algorithm = algorithm
    entity.passwordUpdatedAt = passwordUpdatedAt
    entity.failedAttempts = failedAttempts
    entity.lockedUntil = lockedUntil
}

internal fun ConsentRecordEntity.toDomain(): ConsentRecord =
    ConsentRecord(
        id = getId(),
        userId = UserId(userId),
        document = document,
        version = version,
        acceptedAt = acceptedAt,
        ipHash = ipHash,
        userAgentHash = userAgentHash,
    )

/** Insert-only. Consent records are never updated — a change of mind is a new row. */
internal fun ConsentRecord.toEntity(): ConsentRecordEntity =
    ConsentRecordEntity(
        id = id,
        userId = userId.value,
        document = document,
        version = version,
        acceptedAt = acceptedAt,
        ipHash = ipHash,
        userAgentHash = userAgentHash,
    )

internal fun VerificationTokenEntity.toDomain(): VerificationToken =
    VerificationToken(
        id = getId(),
        userId = UserId(userId),
        purpose = purpose,
        tokenHash = TokenHash(tokenHash),
        expiresAt = expiresAt,
        consumedAt = consumedAt,
        codeHash = codeHash?.let(::CodeHash),
        codeExpiresAt = codeExpiresAt,
        codeAttempts = codeAttempts,
    )

/**
 * Insert-only. A token's one state change — being consumed — is a conditional
 * `UPDATE` on the repository, never an `applyTo`, because it has to be atomic
 * with the check that it is still live.
 */
internal fun VerificationToken.toEntity(): VerificationTokenEntity =
    VerificationTokenEntity(
        id = id,
        userId = userId.value,
        purpose = purpose,
        tokenHash = tokenHash.value,
        expiresAt = expiresAt,
        consumedAt = consumedAt,
        codeHash = codeHash?.value,
        codeExpiresAt = codeExpiresAt,
        codeAttempts = codeAttempts,
    )
