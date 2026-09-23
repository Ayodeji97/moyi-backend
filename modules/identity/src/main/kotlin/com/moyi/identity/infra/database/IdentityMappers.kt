package com.moyi.identity.infra.database

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
 * `INSERT`. Use [applyTo] to carry changes onto a row that already exists.
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
    )

/**
 * Carries a changed [User] onto the managed entity it came from.
 *
 * This is the update path, and it exists because the obvious alternative is
 * wrong in a way that does not fail: `save(user.toEntity())` on an existing
 * user hands Hibernate a *detached* object claiming to be new, and the
 * `isNew` flag means it will be persisted rather than merged.
 *
 * `id` and `createdAt` are not copied. They are not the caller's to change,
 * and the column is `updatable = false` so the database would refuse anyway.
 */
internal fun User.applyTo(entity: UserEntity) {
    require(entity.getId() == id.value) { "cannot apply a user onto a different user's row" }
    entity.email = email.value
    entity.emailVerifiedAt = emailVerifiedAt
    entity.displayName = displayName
    entity.avatarMediaId = avatarMediaId
    entity.locale = locale
    entity.status = status
    entity.updatedAt = updatedAt
    entity.deletedAt = deletedAt
}

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
    )
