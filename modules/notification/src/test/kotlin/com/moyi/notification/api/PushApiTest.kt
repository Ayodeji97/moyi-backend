package com.moyi.notification.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The value rules of the push API: the one place a token's shape and a dedupe key's length are decided. */
internal class PushApiTest {
    @Test
    fun `a push token never prints itself, nor does a message or a registration that holds one`() {
        val token = PushToken("fcm-token-secret")
        token.toString() shouldBe "PushToken(<redacted>)"
        PushMessage(token, PushCategory.REVEAL, UUID.randomUUID()).toString() shouldNotContain "fcm-token-secret"
        PushTokenRegistration(UUID.randomUUID(), UUID.randomUUID(), PushPlatform.ANDROID, token).toString() shouldNotContain
            "fcm-token-secret"
    }

    @Test
    fun `a push token is 1 to 1024 characters with no whitespace`() {
        PushToken("a".repeat(PushToken.MAX_LENGTH)).value.length shouldBe PushToken.MAX_LENGTH
        shouldThrow<IllegalArgumentException> { PushToken("") }
        shouldThrow<IllegalArgumentException> { PushToken("a".repeat(PushToken.MAX_LENGTH + 1)) }
        shouldThrow<IllegalArgumentException> { PushToken("two words") }
        shouldThrow<IllegalArgumentException> { PushToken("line\nbreak") }
    }

    @Test
    fun `a dedupe key is 1 to 200 characters`() {
        val user = UUID.randomUUID()
        val bond = UUID.randomUUID()
        val at = Instant.parse("2026-10-28T00:00:00Z")
        PushRequest(user, bond, PushCategory.MILESTONE, "k".repeat(PushRequest.MAX_DEDUPE_KEY_LENGTH), at)
        shouldThrow<IllegalArgumentException> { PushRequest(user, bond, PushCategory.MILESTONE, "", at) }
        shouldThrow<IllegalArgumentException> {
            PushRequest(user, bond, PushCategory.MILESTONE, "k".repeat(PushRequest.MAX_DEDUPE_KEY_LENGTH + 1), at)
        }
    }
}
