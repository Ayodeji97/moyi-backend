package com.moyi.identity.service

import com.moyi.identity.api.PasswordHashing
import com.moyi.identity.domain.BreachedPasswordCorpus
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHasher
import org.springframework.stereotype.Service

/** The [PasswordHashing] port over the account path's own hasher and corpus. */
@Service
internal class PasswordHashingAdapter(
    private val hasher: PasswordHasher,
    private val corpus: BreachedPasswordCorpus,
) : PasswordHashing {
    override fun hash(raw: String): String = hasher.hash(Password.of(raw)).value

    override fun matches(
        raw: String,
        hash: String,
    ): Boolean = hasher.matches(raw, PasswordHash(hash))

    override fun matchesDummy(raw: String): Boolean = hasher.matchesDummy(raw)

    override fun isBreached(raw: String): Boolean = runCatching { Password.of(raw) }.getOrNull()?.let(corpus::contains) ?: false
}
