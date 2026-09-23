package com.moyi.notification.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

internal class EmailMessageTest {
    @Test
    fun `a message needs a recipient, a subject and a plain-text body`() {
        shouldThrow<IllegalArgumentException> { EmailMessage(to = " ", subject = "s", text = "t") }
        shouldThrow<IllegalArgumentException> { EmailMessage(to = "a@b.c", subject = "", text = "t") }
        shouldThrow<IllegalArgumentException> { EmailMessage(to = "a@b.c", subject = "s", text = "") }
    }

    @Test
    fun `an idempotency key longer than the provider accepts is refused up front`() {
        // Better here than as a 4xx from the provider after the message was
        // built — and the 4xx would be classified as a permanent rejection.
        EmailMessage(to = "a@b.c", subject = "s", text = "t", idempotencyKey = "k".repeat(256))
        shouldThrow<IllegalArgumentException> {
            EmailMessage(to = "a@b.c", subject = "s", text = "t", idempotencyKey = "k".repeat(257))
        }
    }

    @Test
    fun `toString does not reveal the recipient`() {
        // A data class prints every property. This one will be interpolated
        // into a log line by somebody, and an address is personal data.
        val message = EmailMessage(to = "ada@example.com", subject = "Verify", text = "body", idempotencyKey = "tok-1")

        val printed = message.toString()

        printed shouldNotContain "ada@example.com"
        printed shouldNotContain "ada"
        printed shouldContain "Verify"
        printed shouldContain "tok-1"
    }

    @Test
    fun `html is optional`() {
        EmailMessage(to = "a@b.c", subject = "s", text = "t").html shouldBe null
    }
}
