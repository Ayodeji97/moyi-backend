package com.moyi.media.service

import com.moyi.media.api.VoiceViewStatus
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.MediaStatus
import com.moyi.media.domain.ModerationVerdict
import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.Waveform
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.UnavailableObjectStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Issue #165, ADR-0061: a voice note's waveform is given exactly when its URL is.
 * With no storage to sign a URL against, there is no URL, so there is no waveform
 * either, on a held-back note as on a ready one. The store is never asked: [PlayVoices.toOwner]
 * is given the asset.
 */
internal class PlayVoicesTest {
    private val voices =
        PlayVoices(
            MediaAssetStore(JdbcTemplate()),
            HourlyUrls(UnavailableObjectStore(), Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC)),
        )
    private val levels = Waveform.measure(ShortArray(6_400) { (it % 100 * 200).toShort() })

    @Test
    fun `a held-back voice note with no URL to give its owner has no waveform either`() {
        val view = voices.toOwner(voice(MediaStatus.QUARANTINED, ModerationVerdict.QUARANTINED))

        view.status shouldBe VoiceViewStatus.NOT_SHARED
        view.url shouldBe null
        view.durationMs shouldBe null
        view.waveform shouldBe null
    }

    @Test
    fun `a ready voice note with no URL to give is processing, with no waveform`() {
        val view = voices.toOwner(voice(MediaStatus.READY, ModerationVerdict.PASSED))

        view.status shouldBe VoiceViewStatus.PROCESSING
        view.waveform shouldBe null
    }

    private fun voice(
        status: MediaStatus,
        verdict: ModerationVerdict,
    ) = MediaAsset(
        id = UUID.randomUUID(),
        ownerUserId = UUID.randomUUID(),
        kind = MediaKind.VOICE,
        storageKey = StorageKey.forUpload(UUID.randomUUID()),
        contentType = "audio/mp4",
        sizeBytes = 1_000,
        checksumSha256 = "Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=",
        status = status,
        attachedEntryId = null,
        createdAt = Instant.parse("2026-11-10T08:00:00Z"),
        expiresAt = null,
        moderationVerdict = verdict,
        durationMs = 4_200,
        waveform = levels,
    )
}
