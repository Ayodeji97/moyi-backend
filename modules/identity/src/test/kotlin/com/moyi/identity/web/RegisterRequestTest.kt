package com.moyi.identity.web

import com.moyi.common.security.ClientContext
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

internal class RegisterRequestTest {
    @Test
    fun `the request never prints the password`() {
        // Spring prints this object without being asked: the argument resolver
        // logs the deserialised body at DEBUG, and
        // MethodArgumentNotValidException embeds the FieldError — whose
        // toString carries `rejected value [...]`, untruncated. A generated
        // data-class toString puts the plaintext in both.
        val printed = request().toString()

        printed shouldNotContain PASSWORD
        printed shouldContain "ada@example.com"
    }

    @Test
    fun `the command never prints the password either`() {
        // The second half: below the web layer the password is a `Password`,
        // whose own toString redacts, so nothing downstream can print it
        // however it is interpolated.
        val printed = request().toCommand(ClientContext(addressHash = "address-hash", userAgentHash = null)).toString()

        printed shouldNotContain PASSWORD
        printed shouldContain "Password(redacted)"
    }

    @Test
    fun `the command carries domain types, so the service cannot be handed something invalid`() {
        val command = request(email = "  Ada@Example.com  ").toCommand(ClientContext(addressHash = "address-hash", userAgentHash = null))

        command.email.value shouldBe "Ada@Example.com"
        command.password.value shouldBe PASSWORD
    }

    private fun request(
        email: String = "ada@example.com",
        password: String = PASSWORD,
    ) = RegisterRequest(
        email = email,
        password = password,
        displayName = "Ada",
        locale = "en",
        acceptedTermsVersion = "2026-09-01",
        over18 = true,
    )

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
