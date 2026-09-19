package com.moyi.identity.infra.security

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Argon2id's cost parameters, and the admission control around them.
 *
 * The defaults are NFR-046's values, not a library default — they are the
 * requirement, expressed where it is executable. `Argon2idPasswordHasherTest`
 * asserts them, so lowering a cost parameter is a failing test rather than a
 * quiet weakening that nothing notices.
 *
 * **These are the floor, not the tuned values, and they are currently well
 * under target.** NFR-046 states the parameters as a *minimum* and `09` §3
 * gives the intent as ~150 ms per hash. Measured on an Apple M5 (arm64) on
 * 2026-09-19, this configuration hashes in a **17.7 ms median** (p05 15.3,
 * p95 20.7, n=15) — roughly an eighth of the intended cost, which is an
 * eighth of the work an attacker has to do per guess against a leaked table.
 * The knob is [iterations]: cost scales close to linearly in `t`, so ~150 ms
 * is around `t=16` on this machine. It is deliberately **not** changed here,
 * because the number that matters is the one measured on the deployment
 * target (doc 15: "tuned and benchmarked on the ARM target"), that box does
 * not exist yet, and tuning against a developer laptop would bake in the
 * wrong answer while looking like diligence. This is owed before M1.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.security.argon2")
internal data class Argon2Properties(
    /** NFR-046: m=19456 KiB. This is the memory each concurrent hash allocates, **off-heap**. */
    @field:Min(MIN_MEMORY_KIB)
    val memoryKib: Int = DEFAULT_MEMORY_KIB,
    /** NFR-046: t=2. */
    @field:Min(1)
    val iterations: Int = DEFAULT_ITERATIONS,
    /** NFR-046: p=1. */
    @field:Min(1)
    val parallelism: Int = DEFAULT_PARALLELISM,
    /** 16 bytes — the length RFC 9106 recommends and Spring Security defaults to. */
    @field:Min(MIN_SALT_LENGTH)
    val saltLength: Int = DEFAULT_SALT_LENGTH,
    /** 32 bytes of output. */
    @field:Min(MIN_HASH_LENGTH)
    val hashLength: Int = DEFAULT_HASH_LENGTH,
    /**
     * NFR-005a: 4–8 permits.
     *
     * The number exists because every Argon2id hash holds ~19 MiB off-heap
     * for its duration, so peak memory is (concurrent hashes × 19 MiB) and
     * something has to bound the multiplier. The permit count is that bound,
     * and it is the one we control.
     *
     * **A correction to the obvious version of this argument.** It is tempting
     * to say that `spring.threads.virtual.enabled` removes every other limit,
     * so thousands of concurrent requests become thousands of concurrent
     * hashes. That is not true, and the test for this file found out the hard
     * way: a virtual thread unmounts from its carrier only when it *blocks*,
     * and Argon2id blocks on nothing — it is pure CPU and memory. Concurrent
     * hashes are therefore already capped by the carrier pool, which defaults
     * to the processor count. On a two-core container that is two hashes and
     * ~38 MiB, and this semaphore never binds at all.
     *
     * It stays, for three reasons that are smaller than the original claim but
     * real. NFR-005a requires it. It is the only bound that survives someone
     * raising `jdk.virtualThreadScheduler.parallelism`, moving to a larger
     * instance, or giving the hasher work that *does* block. And it converts
     * the failure from "the container is killed" to "a 503 with a
     * `Retry-After`", which is a choice worth having made in advance.
     */
    @field:Min(MIN_PERMITS)
    @field:Max(MAX_PERMITS)
    val maxConcurrentHashes: Int = DEFAULT_MAX_CONCURRENT_HASHES,
    /**
     * How long a request waits for a permit before being turned away.
     *
     * Not zero: hashing takes ~150 ms, so a caller arriving during a brief
     * burst is better served by waiting than by a 503 it will retry into the
     * same burst. Not long either — a queue that grows without bound is the
     * DoS wearing a different hat.
     */
    @field:Min(0)
    val acquireTimeoutMillis: Long = DEFAULT_ACQUIRE_TIMEOUT_MILLIS,
) {
    private companion object {
        // NFR-046's parameters, as defaults rather than as literals at the use
        // site, so `Argon2PropertiesTest` can assert them by name and a change
        // has exactly one place to happen.
        const val DEFAULT_MEMORY_KIB = 19456
        const val DEFAULT_ITERATIONS = 2
        const val DEFAULT_PARALLELISM = 1

        /** RFC 9106's recommended salt and tag lengths, and Spring Security's defaults. */
        const val DEFAULT_SALT_LENGTH = 16
        const val DEFAULT_HASH_LENGTH = 32

        /** Middle of NFR-005a's 4–8 band. */
        const val DEFAULT_MAX_CONCURRENT_HASHES = 6
        const val DEFAULT_ACQUIRE_TIMEOUT_MILLIS = 250L

        // Long, not Int: jakarta.validation's @Min takes a long, and an Int
        // constant will not compile into it.
        const val MIN_SALT_LENGTH = 16L
        const val MIN_HASH_LENGTH = 32L

        /** Below this, Argon2id stops being meaningfully expensive to attack. */
        const val MIN_MEMORY_KIB = 19456L
        const val MIN_PERMITS = 4L
        const val MAX_PERMITS = 8L
    }
}
