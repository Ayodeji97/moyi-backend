package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.PasswordHashing
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(classes = [IdentityTestApplication::class])
internal class PasswordHashingAdapterTest(
    @Autowired private val hashing: PasswordHashing,
) : IntegrationTest() {
    @Test
    fun `a hash matches the password it was made from and no other`() {
        val raw = "a-long-unbreached-passphrase-42"
        val hash = hashing.hash(raw)

        hash.startsWith("\$argon2id\$") shouldBe true
        hashing.matches(raw, hash) shouldBe true
        hashing.matches("wrong", hash) shouldBe false
        hashing.matchesDummy("wrong") shouldBe false
    }

    @Test
    fun `a password hashed in full-width form verifies as its NFKC-equal ASCII text`() {
        val hash = hashing.hash("\uFF50\uFF41\uFF53\uFF53\uFF57\uFF4F\uFF52\uFF44-long-ok-1")

        hashing.matches("password-long-ok-1", hash) shouldBe true
        hashing.matches("\uFF50\uFF41\uFF53\uFF53\uFF57\uFF4F\uFF52\uFF44-long-ok-1", hash) shouldBe true
    }

    @Test
    fun `the breached corpus is asked, and a string that breaks the shape rules is not breached`() {
        hashing.isBreached("password") shouldBe true
        hashing.isBreached("a-long-unbreached-passphrase-42") shouldBe false
        hashing.isBreached("short") shouldBe false
    }

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
