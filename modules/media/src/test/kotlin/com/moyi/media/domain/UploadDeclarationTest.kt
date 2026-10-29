package com.moyi.media.domain

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/** FR-042 and FR-043 at the intent, in the one place they are written. */
internal class UploadDeclarationTest {
    private val checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("fake photo bytes".toByteArray()))
    private val mebibyte = 1024L * 1024L

    @Test
    fun `an image of each accepted type is accepted, its type lower-cased and its checksum kept`() {
        for (type in listOf("image/jpeg", "image/png", "image/heic", "image/webp", " IMAGE/JPEG ")) {
            val accepted = UploadDeclaration.of("IMAGE", type, 1000, checksum).shouldBeInstanceOf<UploadDeclaration.Outcome.Accepted>()
            accepted.declaration.contentType shouldBe type.trim().lowercase()
            accepted.declaration.checksumSha256 shouldBe checksum
            accepted.declaration.kind shouldBe MediaKind.IMAGE
        }
    }

    @Test
    fun `a voice note in each recorder format is accepted`() {
        // D3 adds audio/3gpp and audio/aac, two of Android's recorder formats (ADR-0047 owed the check).
        val types =
            listOf(
                "audio/mp4",
                "audio/x-m4a",
                "audio/3gpp",
                "audio/ogg",
                "audio/amr",
                "audio/aac",
                "audio/webm",
                "audio/wav",
                "audio/x-wav",
            )
        for (type in types) {
            UploadDeclaration.of("VOICE", type, 1000, checksum).shouldBeInstanceOf<UploadDeclaration.Outcome.Accepted>()
        }
    }

    @Test
    fun `the size limit is inclusive, 10 MB of image and 5 MB of voice are accepted, one byte more is 413`() {
        UploadDeclaration.of("IMAGE", "image/png", 10 * mebibyte, checksum).shouldBeInstanceOf<UploadDeclaration.Outcome.Accepted>()
        refusalOf("IMAGE", "image/png", 10 * mebibyte + 1) shouldBe UploadDeclaration.Refusal.TooLarge(10 * mebibyte)
        UploadDeclaration.of("VOICE", "audio/ogg", 5 * mebibyte, checksum).shouldBeInstanceOf<UploadDeclaration.Outcome.Accepted>()
        refusalOf("VOICE", "audio/ogg", 5 * mebibyte + 1) shouldBe UploadDeclaration.Refusal.TooLarge(5 * mebibyte)
    }

    @Test
    fun `a type the kind does not take is unsupported, including another kind's type and a type with parameters`() {
        for ((kind, type) in listOf(
            "IMAGE" to "image/gif",
            "IMAGE" to "audio/ogg",
            "VOICE" to "image/jpeg",
            "VOICE" to "audio/webm;codecs=opus",
            "IMAGE" to "application/octet-stream",
        )) {
            refusalOf(kind, type, 1000) shouldBe UploadDeclaration.Refusal.UnsupportedType
        }
    }

    @Test
    fun `an unsupported type is answered before an oversized one`() {
        refusalOf("IMAGE", "image/gif", 100 * mebibyte) shouldBe UploadDeclaration.Refusal.UnsupportedType
    }

    @Test
    fun `every missing or malformed field is named at once, and nothing else is judged`() {
        val invalid = UploadDeclaration.of(null, " ", 0, "not-a-digest").refusal().shouldBeInstanceOf<UploadDeclaration.Refusal.Invalid>()
        invalid.violations.map { it.field to it.code } shouldContainExactlyInAnyOrder
            listOf("kind" to "NOT_NULL", "contentType" to "NOT_BLANK", "sizeBytes" to "POSITIVE", "checksum" to "SHA256_BASE64")
    }

    @Test
    fun `an unknown kind, a negative size and a missing checksum are each a field`() {
        val invalid = UploadDeclaration.of("image", "image/png", -5, null).refusal().shouldBeInstanceOf<UploadDeclaration.Refusal.Invalid>()
        invalid.violations.map { it.field to it.code } shouldContainExactlyInAnyOrder
            listOf("kind" to "VALID_KIND", "sizeBytes" to "POSITIVE", "checksum" to "NOT_NULL")
    }

    @Test
    fun `a checksum must be exactly 32 bytes of base64`() {
        val sha1 = Base64.getEncoder().encodeToString(ByteArray(20))
        val invalid = UploadDeclaration.of("IMAGE", "image/png", 10, sha1).refusal().shouldBeInstanceOf<UploadDeclaration.Refusal.Invalid>()
        invalid.violations.map { it.field } shouldBe listOf("checksum")
    }

    @Test
    fun `a missing size is a field too`() {
        val invalid =
            UploadDeclaration
                .of(
                    "IMAGE",
                    "image/png",
                    null,
                    checksum,
                ).refusal()
                .shouldBeInstanceOf<UploadDeclaration.Refusal.Invalid>()
        invalid.violations.map { it.field to it.code } shouldBe listOf("sizeBytes" to "NOT_NULL")
    }

    private fun refusalOf(
        kind: String,
        type: String,
        size: Long,
    ): UploadDeclaration.Refusal = UploadDeclaration.of(kind, type, size, checksum).refusal()

    private fun UploadDeclaration.Outcome.refusal(): UploadDeclaration.Refusal =
        this.shouldBeInstanceOf<UploadDeclaration.Outcome.Refused>().refusal
}
