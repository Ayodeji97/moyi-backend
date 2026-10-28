package com.moyi.media.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** What completion and deletion may do with an asset, without a database or a storage. */
internal class MediaAssetTest {
    private val declared = "Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA="

    @Test
    fun `a pending upload becomes uploaded when the storage holds the declared length and digest`() {
        pending().completion { StoredObject(1234, declared) } shouldBe MediaAsset.Completion.Uploaded
    }

    @Test
    fun `a storage that reports no digest is judged by the length alone`() {
        pending().completion { StoredObject(1234, null) } shouldBe MediaAsset.Completion.Uploaded
    }

    @Test
    fun `nothing stored, another length or another digest is not uploaded`() {
        pending().completion { null } shouldBe MediaAsset.Completion.NotUploaded
        pending().completion { StoredObject(1233, declared) } shouldBe MediaAsset.Completion.NotUploaded
        pending().completion { StoredObject(1234, "b3RoZXJvdGhlcm90aGVyb3RoZXJvdGhlcm90aGVyMDA=") } shouldBe
            MediaAsset.Completion.NotUploaded
    }

    @Test
    fun `an asset past its upload is already complete, and the storage is not asked`() {
        for (status in MediaStatus.entries - MediaStatus.PENDING_UPLOAD) {
            pending(status).completion { error("the storage was asked about a $status asset") } shouldBe
                MediaAsset.Completion.AlreadyComplete
        }
    }

    @Test
    fun `an unattached asset may be deleted and an attached one may not`() {
        pending().isDeletable shouldBe true
        pending(attached = UUID.randomUUID()).isDeletable shouldBe false
    }

    @Test
    fun `the storage key is random under in, and never printed`() {
        val random = UUID.randomUUID()
        val key = StorageKey.forUpload(random)
        key.value shouldBe "in/$random"
        key.toString() shouldNotContain random.toString()
        pending().toString() shouldNotContain "in/"
    }

    private fun pending(
        status: MediaStatus = MediaStatus.PENDING_UPLOAD,
        attached: UUID? = null,
    ) = MediaAsset(
        id = UUID.randomUUID(),
        ownerUserId = UUID.randomUUID(),
        kind = MediaKind.IMAGE,
        storageKey = StorageKey.forUpload(UUID.randomUUID()),
        contentType = "image/png",
        sizeBytes = 1234,
        checksumSha256 = declared,
        status = status,
        attachedEntryId = attached,
        createdAt = Instant.parse("2026-10-28T09:00:00Z"),
        expiresAt = Instant.parse("2026-10-28T09:15:00Z"),
    )
}
