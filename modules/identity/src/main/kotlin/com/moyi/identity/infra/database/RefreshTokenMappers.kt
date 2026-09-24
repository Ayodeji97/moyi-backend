package com.moyi.identity.infra.database

import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.TokenHash
import com.moyi.identity.domain.UserId

internal fun RefreshTokenEntity.toDomain(): RefreshToken =
    RefreshToken(
        id = getId(),
        userId = UserId(userId),
        familyId = familyId,
        tokenHash = TokenHash(tokenHash),
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        rotatedAt = rotatedAt,
        revokedAt = revokedAt,
        replacedBy = replacedBy,
        deviceId = deviceId,
    )

internal fun RefreshToken.toEntity(): RefreshTokenEntity =
    RefreshTokenEntity(
        id = id,
        userId = userId.value,
        familyId = familyId,
        tokenHash = tokenHash.value,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        rotatedAt = rotatedAt,
        revokedAt = revokedAt,
        replacedBy = replacedBy,
        deviceId = deviceId,
    )
