package com.moyi.common.core

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.random.Random

internal class PemTest {
    // 300 bytes, so the base64 runs past one 64-column line and holds plenty of
    // `n` and `r`, the letters an escaped line break is spelt with.
    private val der = Random(7).nextBytes(300)
    private val written =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END PRIVATE KEY-----\n"

    @Test
    fun `every way a key's line breaks reach the container reads as the same bytes`() {
        val forms =
            mapOf(
                "real newlines, as openssl writes it" to written,
                "real CRLF" to written.replace("\n", "\r\n"),
                "`\\n` spelt out, as Google's JSON holds it" to written.replace("\n", "\\n"),
                "`\\\\n`, as Kamal's env file turns a vault's `\\n`" to written.replace("\n", "\\\\n"),
                "`\\r\\n` spelt out" to written.replace("\n", "\\r\\n"),
                "one line, no breaks at all" to written.replace("\n", ""),
                "breaks lost to spaces" to written.replace("\n", " "),
            )

        forms.forEach { (_, pem) -> Base64.getDecoder().decode(Pem.body(pem, "PRIVATE KEY")) shouldBe der }
    }

    @Test
    fun `a backslash left over on its own is not part of the key`() {
        Base64.getDecoder().decode(Pem.body(written.replace("\n", "\\\n"), "PRIVATE KEY")) shouldBe der
    }

    @Test
    fun `without both armour lines of the asked kind there is no body`() {
        Pem.body("not a key", "PRIVATE KEY").shouldBeNull()
        Pem.body(written, "PUBLIC KEY").shouldBeNull()
        Pem.body(written.substringBefore("-----END"), "PRIVATE KEY").shouldBeNull()
    }

    @Test
    fun `characters that are not base64 and not a line break are left for the decoder to refuse`() {
        Pem.body("-----BEGIN PRIVATE KEY-----\n%%%\n-----END PRIVATE KEY-----", "PRIVATE KEY") shouldBe "%%%"
    }
}
