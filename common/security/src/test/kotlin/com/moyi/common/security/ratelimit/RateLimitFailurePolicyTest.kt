package com.moyi.common.security.ratelimit

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * ADR-0023's failure policy, as the table states it: every bucket fails open but one.
 *
 * The owner's ruling of 2026-11-09 made `auth:code:email` fail closed, and only it. A
 * second bucket that started refusing requests while Redis is down would turn a cache
 * outage into the login outage ADR-0023 exists to prevent, so the set is pinned here
 * and a change to it is a change to this test, in review, beside an amended ADR.
 * What each consumer does with the flag is proved on the endpoints
 * (`TypedCodeRedisOutageTest` in identity: the code refused, the link and sign-in served).
 */
internal class RateLimitFailurePolicyTest {
    @Test
    fun `only the typed-code bucket fails closed, and every other bucket fails open`() {
        RateLimitBucket.entries.filter { it.failsClosed } shouldContainExactly listOf(RateLimitBucket.AUTH_CODE_EMAIL)
    }
}
