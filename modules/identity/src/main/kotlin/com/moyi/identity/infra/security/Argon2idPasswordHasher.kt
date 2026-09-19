package com.moyi.identity.infra.security

import com.moyi.identity.domain.HashingCapacityExceededException
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHashAlgorithm
import com.moyi.identity.domain.PasswordHasher
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Argon2id, via Spring Security's encoder, under a fixed number of permits.
 *
 * **Nothing here is cryptography we wrote.** Doc 25 D5 draws that line
 * explicitly: the token *lifecycle* is ours to build, the primitives are
 * not. [Argon2PasswordEncoder] generates the salt, encodes the parameters
 * into the output string, and delegates the KDF to BouncyCastle.
 *
 * **What is ours is the admission control**, and it is the part that would be
 * missing from a tutorial. See [Argon2Properties.maxConcurrentHashes] for why
 * a semaphore and not a thread pool: with virtual threads enabled, the server
 * imposes no natural limit, and each hash holds ~19 MiB off-heap. The permit
 * is released in a `finally`, because a leaked permit is a slow, permanent
 * outage that begins with one unlucky exception.
 */
@Component
internal class Argon2idPasswordHasher(
    private val properties: Argon2Properties,
) : PasswordHasher {
    override val algorithm = PasswordHashAlgorithm.ARGON2ID

    private val encoder =
        Argon2PasswordEncoder(
            properties.saltLength,
            properties.hashLength,
            properties.parallelism,
            properties.memoryKib,
            properties.iterations,
        )

    private val permits = Semaphore(properties.maxConcurrentHashes, true)

    override fun hash(password: Password): PasswordHash {
        // Blocking here is cheap and correct: a virtual thread waiting on a
        // semaphore unmounts from its carrier, so the wait costs a
        // continuation rather than an OS thread. The bound being enforced is
        // memory, not concurrency, which is why the answer is a permit rather
        // than a smaller pool.
        if (!permits.tryAcquire(properties.acquireTimeoutMillis, TimeUnit.MILLISECONDS)) {
            throw HashingCapacityExceededException()
        }
        return try {
            // `encode` is declared nullable by Spring Security's JSpecify
            // annotations. `checkNotNull` rather than `!!` (doc 18 §3): if the
            // encoder ever returns nothing, that is genuinely exceptional and
            // the message should say which encoder, not just where the NPE was.
            PasswordHash(checkNotNull(encoder.encode(password.value)) { "Argon2id encoder returned no hash" })
        } finally {
            permits.release()
        }
    }
}
