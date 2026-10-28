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

    @Test
    fun `a variant is kept under v, with the upload key's random part and never the asset's id`() {
        val random = UUID.randomUUID()
        val upload = StorageKey.forUpload(random)
        StorageKey.forVariant(upload, ImageVariant.FULL).value shouldBe "v/$random/full.webp"
        StorageKey.forVariant(upload, ImageVariant.DISPLAY).value shouldBe "v/$random/display.webp"
    }

    @Test
    fun `media-process works on an uploaded photo, or one whose last attempt did not finish, and on nothing else`() {
        pending(MediaStatus.UPLOADED).processing shouldBe MediaAsset.Processing.PROCESS
        pending(MediaStatus.PROCESSING).processing shouldBe MediaAsset.Processing.PROCESS
        // ADR-0047's debt: an asset deleted after its MediaUploaded was published is skipped.
        for (status in MediaStatus.entries - MediaStatus.UPLOADED - MediaStatus.PROCESSING) {
            pending(status).processing shouldBe MediaAsset.Processing.NOTHING_TO_DO
        }
        pending(MediaStatus.UPLOADED, kind = MediaKind.VOICE).processing shouldBe MediaAsset.Processing.NOT_THIS_KIND
    }

    @Test
    fun `its owner sees a ready photo, the full image while there is one`() {
        val full = variant(ImageVariant.FULL)
        val display = variant(ImageVariant.DISPLAY)
        pending(MediaStatus.READY, variants = listOf(display, full)).shownTo(owner = true) shouldBe MediaAsset.Shown.Ready(full)
        pending(MediaStatus.READY, variants = listOf(display)).shownTo(owner = true) shouldBe MediaAsset.Shown.Ready(display)
    }

    @Test
    fun `anyone else sees processing until the photo is ready and has passed moderation (FR-101)`() {
        val full = variant(ImageVariant.FULL)
        pending(MediaStatus.READY, variants = listOf(full)).shownTo(owner = false) shouldBe MediaAsset.Shown.Processing
        pending(MediaStatus.READY, variants = listOf(full), verdict = "QUARANTINED").shownTo(owner = false) shouldBe
            MediaAsset.Shown.Processing
        pending(MediaStatus.READY, variants = listOf(full), verdict = MediaAsset.PASSED).shownTo(owner = false) shouldBe
            MediaAsset.Shown.Ready(full)
    }

    @Test
    fun `a photo that is not ready is processing to everyone, its owner included`() {
        for (status in MediaStatus.entries - MediaStatus.READY) {
            pending(status, variants = listOf(variant(ImageVariant.FULL)), verdict = MediaAsset.PASSED).shownTo(owner = true) shouldBe
                MediaAsset.Shown.Processing
        }
    }

    private fun variant(which: ImageVariant) =
        StoredVariant(which, StorageKey.forVariant(StorageKey.forUpload(UUID.randomUUID()), which), 20, 10, 100)

    private fun pending(
        status: MediaStatus = MediaStatus.PENDING_UPLOAD,
        attached: UUID? = null,
        kind: MediaKind = MediaKind.IMAGE,
        variants: List<StoredVariant> = emptyList(),
        verdict: String? = null,
    ) = MediaAsset(
        id = UUID.randomUUID(),
        ownerUserId = UUID.randomUUID(),
        kind = kind,
        variants = variants,
        moderationVerdict = verdict,
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
