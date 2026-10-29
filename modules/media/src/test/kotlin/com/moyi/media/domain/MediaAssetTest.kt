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
    fun `media-process works on an uploaded photo or voice note, or one whose last attempt did not finish, and on nothing else`() {
        pending(MediaStatus.UPLOADED).processing shouldBe MediaAsset.Processing.PROCESS
        pending(MediaStatus.PROCESSING).processing shouldBe MediaAsset.Processing.PROCESS
        // ADR-0047's debt: an asset deleted after its MediaUploaded was published is skipped.
        for (status in MediaStatus.entries - MediaStatus.UPLOADED - MediaStatus.PROCESSING) {
            pending(status).processing shouldBe MediaAsset.Processing.NOTHING_TO_DO
        }
        // Since D3 a voice note is processed too, by the same rule.
        pending(MediaStatus.UPLOADED, kind = MediaKind.VOICE).processing shouldBe MediaAsset.Processing.PROCESS
        pending(MediaStatus.PROCESSING, kind = MediaKind.VOICE, attempts = 5).processing shouldBe MediaAsset.Processing.GIVE_UP
    }

    @Test
    fun `a photo on which five attempts began and none finished is given up, and one with four is tried again`() {
        pending(MediaStatus.PROCESSING, attempts = 4).processing shouldBe MediaAsset.Processing.PROCESS
        pending(MediaStatus.PROCESSING, attempts = 5).processing shouldBe MediaAsset.Processing.GIVE_UP
        pending(MediaStatus.UPLOADED, attempts = 5).processing shouldBe MediaAsset.Processing.GIVE_UP
        // Given up only while there is something to give up.
        pending(MediaStatus.READY, attempts = 5).processing shouldBe MediaAsset.Processing.NOTHING_TO_DO
        pending(MediaStatus.DELETED, attempts = 9).processing shouldBe MediaAsset.Processing.NOTHING_TO_DO
    }

    @Test
    fun `a photo's object keys are its upload and both variants', a voice note's its upload and its Opus, recorded or not`() {
        val photo = pending(MediaStatus.PROCESSING)
        photo.objectKeys shouldBe listOf(photo.storageKey) + ImageVariant.entries.map { StorageKey.forVariant(photo.storageKey, it) }
        val voice = pending(MediaStatus.UPLOADED, kind = MediaKind.VOICE)
        voice.objectKeys shouldBe listOf(voice.storageKey, StorageKey.forVoice(voice.storageKey))
        // Derived from the upload's random part, as a photo's variants are: nothing of the asset's id or owner.
        StorageKey.forVoice(StorageKey("in/0b6e6a37-1b1e-4c55-9d4e-8a1c7b0e2f11")).value shouldBe
            "v/0b6e6a37-1b1e-4c55-9d4e-8a1c7b0e2f11/voice.ogg"
    }

    /** Spec §4 D3: a voice note waits on no moderation verdict (ADR-0049). */
    @Test
    fun `a ready voice note is played to its owner and to anyone else alike, with its length`() {
        val voice = pending(MediaStatus.READY, kind = MediaKind.VOICE, durationMs = 4_200)
        voice.heard shouldBe MediaAsset.Heard.Ready(StorageKey.forVoice(voice.storageKey), 4_200)
        // A photo's rule is not a voice note's, nor the other way about.
        voice.shownTo(owner = true) shouldBe MediaAsset.Shown.Processing
        pending(MediaStatus.READY, variants = listOf(variant(ImageVariant.FULL))).heard shouldBe MediaAsset.Heard.Processing
    }

    @Test
    fun `a voice note that is not ready is processing to everyone`() {
        for (status in MediaStatus.entries - MediaStatus.READY) {
            pending(status, kind = MediaKind.VOICE, durationMs = 4_200).heard shouldBe MediaAsset.Heard.Processing
        }
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

    @Suppress("LongParameterList") // A fixture: every parameter defaults, and each test names the one it varies.
    private fun pending(
        status: MediaStatus = MediaStatus.PENDING_UPLOAD,
        attached: UUID? = null,
        kind: MediaKind = MediaKind.IMAGE,
        variants: List<StoredVariant> = emptyList(),
        verdict: String? = null,
        attempts: Int = 0,
        durationMs: Int? = null,
    ) = MediaAsset(
        processingAttempts = attempts,
        durationMs = durationMs,
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
