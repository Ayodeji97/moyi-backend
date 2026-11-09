package com.moyi.identity.infra.security

import com.moyi.common.security.PersonalDataHasher
import com.moyi.identity.domain.CodeHash
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationCodeHasher
import com.moyi.identity.domain.VerificationPurpose
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * HMAC-SHA256 under `moyi.security.hashing.secret` (ADR-0023 decision 7), the same keyed
 * hash that keeps an address out of a Redis key. A code is six digits: unkeyed, all million
 * digests are a second's work and the column would hold the code. Keyed, a leaked table
 * says nothing without the secret.
 *
 * The MAC's input names the purpose and the row, so the same six digits issued to two
 * people, or twice to one, are two different hashes, and a hash copied from one row to
 * another matches nothing there.
 */
@Component
internal class HmacVerificationCodeHasher(
    private val hasher: PersonalDataHasher,
) : VerificationCodeHasher {
    override fun hash(
        tokenId: UUID,
        purpose: VerificationPurpose,
        code: VerificationCode,
    ): CodeHash = CodeHash(hasher.hash("verification-code:${purpose.name}:$tokenId:${code.value}"))
}
