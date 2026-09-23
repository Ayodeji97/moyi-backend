package com.moyi.identity.infra.database

import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.TokenHash
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
internal class RefreshTokenStore(
    private val tokens: RefreshTokenRepository,
) {
    fun insert(token: RefreshToken) {
        tokens.save(token.toEntity())
    }

    fun findByHash(hash: TokenHash): RefreshToken? = tokens.findByTokenHash(hash.value)?.toDomain()

    fun rotate(
        id: UUID,
        replacementId: UUID,
        now: Instant,
    ): Boolean = tokens.rotate(id, replacementId, now) == 1

    fun revokeFamily(
        familyId: UUID,
        now: Instant,
    ) {
        tokens.revokeFamily(familyId, now)
    }
}
