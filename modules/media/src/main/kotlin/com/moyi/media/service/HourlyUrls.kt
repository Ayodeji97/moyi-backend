package com.moyi.media.service

import com.moyi.media.domain.StorageKey
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * **One URL an hour** (doc 05:169, ADR-0048 decision 6), for photos and voice
 * notes alike: every URL is signed as of the start of the current hour and
 * valid for 25 hours from then, so it works for at least 24 hours from any
 * moment it is handed out, it ends on the hour, and every request in the same
 * hour gets the same URL. A client can cache the bytes by URL, and the
 * archive's `ETag`, a digest of the body, changes at most once an hour on a
 * day with media. Signing needs no network.
 *
 * Written in `ShowImages` by D2; D3 moved it here so that a voice note's URL
 * is signed by the same rule, written once.
 */
@Component
internal class HourlyUrls(
    private val storage: ObjectStore,
    private val clock: Clock,
) {
    /** The instant every URL handed out now is signed as of: the start of this hour. Taken once per response. */
    fun signingInstant(): Instant = clock.instant().truncatedTo(ChronoUnit.HOURS)

    /**
     * A GET of [key], signed as of [signedAt]; null where no storage is
     * configured, since there is nothing a URL could reach (the caller shows
     * the asset as still processing rather than failing the response it is
     * part of).
     */
    @Suppress("SwallowedException") // On purpose: no storage means no URL, which "processing" already says.
    fun sign(
        key: StorageKey,
        signedAt: Instant,
    ): URI? =
        try {
            storage.presignDownload(key, signedAt, URL_LIFETIME)
        } catch (unavailable: ObjectStoreUnavailableException) {
            null
        }

    internal companion object {
        /** From the start of the hour: at least 24 hours from now, ending on the hour (doc 05:169). */
        val URL_LIFETIME: Duration = Duration.ofHours(25)
    }
}
