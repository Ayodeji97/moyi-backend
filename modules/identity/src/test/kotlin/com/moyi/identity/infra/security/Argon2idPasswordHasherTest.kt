package com.moyi.identity.infra.security

import com.moyi.identity.domain.HashingCapacityExceededException
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHashAlgorithm
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

internal class Argon2idPasswordHasherTest {
    @Test
    fun `the defaults are NFR-046's parameters`() {
        // The requirement expressed where it is executable. Argon2id's cost is
        // the only thing standing between a leaked hash table and the
        // plaintexts, and it is one number away from being nothing — dropping
        // `memoryKib` to a library default would leave every test green and
        // every hash cheap to attack.
        val defaults = Argon2Properties()

        defaults.memoryKib shouldBe 19456
        defaults.iterations shouldBe 2
        defaults.parallelism shouldBe 1
    }

    @Test
    fun `a hash is an argon2id digest carrying its own parameters, and verifies`() {
        val hash = hasher().hash(Password.of("correct horse battery"))

        hash.value shouldContain "\$argon2id\$"
        hash.value shouldContain "m=19456,t=2,p=1"
        // Verified with a separately constructed encoder, so this checks the
        // stored format rather than that one object agrees with itself.
        Argon2PasswordEncoder(16, 32, 1, 19456, 2)
            .matches("correct horse battery", hash.value) shouldBe true
    }

    @Test
    fun `it reports the algorithm it used, which is what gets stored`() {
        hasher().algorithm shouldBe PasswordHashAlgorithm.ARGON2ID
    }

    @Test
    fun `hashing beyond the permit count is refused rather than queued without bound`() {
        // NFR-005a. Each concurrent hash holds ~19 MiB off-heap, so the
        // permits are what bound peak memory rather than the request count.
        // The refusal is the feature: a fast 503 is survivable, an OOM is not.
        //
        // **Platform threads, deliberately, and this cost a red CI run.** The
        // first version used virtual threads, passed on a 10-core laptop and
        // failed on a 2-core runner with zero refusals. A virtual thread only
        // unmounts from its carrier when it *blocks*, and Argon2id blocks on
        // nothing — it is pure CPU and memory. So on a two-carrier machine at
        // most two hashes are ever in flight, the third and fourth permits are
        // never taken, and nothing is refused. Platform threads are scheduled
        // preemptively, so all forty reach `tryAcquire` whatever the core
        // count, which is the condition this test needs to exist at all.
        val permits = 4
        val hasher = hasher(Argon2Properties(maxConcurrentHashes = permits, acquireTimeoutMillis = 0))
        val attempts = 40
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val refused = AtomicInteger()

        val threads =
            List(attempts) {
                Thread.ofPlatform().unstarted {
                    start.await()
                    try {
                        hasher.hash(Password.of("correct horse battery"))
                        accepted.incrementAndGet()
                    } catch (expected: HashingCapacityExceededException) {
                        refused.incrementAndGet()
                    }
                }
            }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        refused.get() shouldBeGreaterThan 0
        accepted.get() shouldBeGreaterThan 0
        accepted.get() + refused.get() shouldBe attempts
    }

    @Test
    fun `permits are returned, so capacity is not consumed permanently`() {
        // The bug this guards against is a leaked permit: a slow, total outage
        // that starts with one exception on a path nobody watches and reads as
        // "the service degraded over the weekend".
        val hasher = hasher(Argon2Properties(maxConcurrentHashes = 4, acquireTimeoutMillis = 0))

        repeat(4 * 2 + 1) {
            hasher.hash(Password.of("correct horse battery"))
        }
    }

    private fun hasher(properties: Argon2Properties = Argon2Properties()) = Argon2idPasswordHasher(properties)
}
