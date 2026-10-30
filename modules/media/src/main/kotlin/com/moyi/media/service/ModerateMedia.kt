package com.moyi.media.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.media.domain.Decision
import com.moyi.media.domain.Findings
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.ModerationPolicy
import com.moyi.media.domain.ModerationVerdict
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.database.ModerationLog
import com.moyi.media.infra.moderation.ImageModerator
import com.moyi.media.infra.moderation.KnownImageCandidate
import com.moyi.media.infra.moderation.KnownImageMatcher
import com.moyi.media.infra.moderation.Moderated
import com.moyi.media.infra.moderation.VoiceModerator
import com.moyi.media.infra.processing.ImageProcessor
import com.moyi.media.infra.processing.VoiceProcessor
import com.moyi.media.infra.storage.ObjectStore
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * What moderating one asset takes, for both kinds (spec §4 D4, ADR-0051): the
 * row read again, the object read, the verdict decided by
 * [ModerationPolicy], and recorded under the row lock with its audit row and
 * its metric. The two consumers ([ModerateImages], [ModerateVoices]) differ
 * only in what they ask of which moderator.
 *
 * **Fail closed for delivery, open for the author.** An outage of the storage
 * or of a moderator is thrown: the delivery rolls back, the outbox backs off
 * (2 s doubling to 15 minutes) and retries **for as long as the outage
 * lasts**, spending nothing, so an outage can never fail an asset (D2's
 * attempt-cap rule: only what the asset itself could cause counts). The
 * author sees it `READY` all the while; the partner sees "processing". What
 * the asset could cause (a moderator refusing the bytes, the JPEG not being
 * made) is a refusal the same bytes would meet again: kept back for the
 * operator (`QUARANTINED`, `UNMODERATABLE`), never `FAILED`, never passed.
 *
 * **No lock on the asset is held while a moderator is asked**, as
 * `media.process` holds none while it decodes: the verdict is recorded under
 * the row lock afterwards, and only if the asset still needs one (deleted,
 * erased or moderated meanwhile: nothing is recorded). The delivery's own row
 * lock and its pooled connection *are* held throughout, as every consumer's
 * are (ADR-0035): the fetch (3 s at most), the conversion (10 s) and the
 * vendor's call (15 s) are bounded to 28 s of the delivery's 60, and
 * `ModerationCopyTest` holds the sum (review of #113).
 *
 * Logs the asset's id, the verdict and the reason; never a key, a URL, a
 * byte, or what a vendor named.
 */
@Component
internal class Moderation(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val auditLog: ModerationLog,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    /** The asset [event] names, if it is of [kind] and still needs a verdict. */
    fun due(
        event: ReceivedEvent,
        kind: MediaKind,
    ): MediaAsset? =
        event.references[CompleteUpload.MEDIA_ID]
            ?.let(assets::findAny)
            ?.takeIf { it.kind == kind && it.needsModeration }

    /**
     * The object moderation reads ([MediaAsset.moderatedKey]), copied to
     * [target] up to [MAX_BYTES]. False when the storage holds nothing there
     * (or more than any variant can be). Throws when it cannot be reached.
     */
    fun fetch(
        asset: MediaAsset,
        target: Path,
    ): Boolean {
        val bytes = storage.open(asset.moderatedKey)?.use { it.readNBytes(MAX_BYTES + 1) }
        val fits = bytes != null && bytes.size <= MAX_BYTES
        if (fits) Files.write(target, bytes)
        return fits
    }

    /** What [moderated] says, as a decision, given whether a known-image list matched. */
    fun decide(
        moderated: Moderated?,
        knownImage: Boolean,
        moderator: String,
        matcher: String,
    ): Pair<Decision, Findings?> =
        when {
            knownImage -> {
                val found = Findings(knownImage = true, findings = emptyList(), moderator = moderator, matcher = matcher)
                ModerationPolicy.decide(found) to found
            }

            moderated is Moderated.Found -> {
                val found =
                    Findings(
                        knownImage = false,
                        findings = moderated.findings,
                        moderator = moderator,
                        matcher = matcher,
                        modelVersion = moderated.modelVersion,
                    )
                ModerationPolicy.decide(found) to found
            }

            else -> {
                ModerationPolicy.unmoderatable() to null
            }
        }

    /** The verdict, under the row lock, in the delivery's transaction; nothing if the asset no longer needs one. */
    fun record(
        asset: MediaAsset,
        decision: Decision,
        found: Findings?,
        moderator: String,
        matcher: String,
    ) {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (assets.lockAny(asset.id)?.needsModeration != true) {
            log.info("Media {} no longer needs moderation; nothing recorded", asset.id)
            return
        }
        check(assets.recordVerdict(asset.id, decision.verdict, decision.sensitive, scores(decision, found, moderator, matcher), now)) {
            "a READY asset with no verdict, under its row lock, takes one"
        }
        auditLog.verdict(asset.id, decision.verdict, decision.reason, moderator, now)
        if (decision.verdict != ModerationVerdict.PASSED) {
            Counter
                .builder(QUARANTINED)
                .description("Photos and voice notes held back from the partner by moderation")
                .tag("kind", asset.kind.name)
                .tag("verdict", decision.verdict.name)
                .register(meters)
                .increment()
            log.warn("Media {} moderated: {} ({})", asset.id, decision.verdict, decision.reason)
        } else {
            log.info("Media {} moderated: {}{}", asset.id, decision.verdict, if (decision.sensitive) ", sensitive" else "")
        }
    }

    /**
     * `moderation_scores`: who looked, what was found (our categories and the
     * confidences), and why the verdict. Nothing a vendor named, nothing of the
     * asset.
     */
    private fun scores(
        decision: Decision,
        found: Findings?,
        moderator: String,
        matcher: String,
    ): String =
        json.writeValueAsString(
            mapOf(
                "moderator" to moderator,
                "knownImages" to matcher,
                "knownImage" to (found?.knownImage ?: false),
                "reason" to decision.reason.name,
                "modelVersion" to found?.modelVersion,
                "findings" to found?.findings.orEmpty().map { mapOf("category" to it.category.name, "confidence" to it.confidence) },
            ),
        )

    /** A directory of this job's own, readable by this user alone where the file system can say so; deleted after [work]. */
    fun <T> inWorkDirectory(work: (Path) -> T): T {
        val dir =
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createTempDirectory(WORK_PREFIX, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createTempDirectory(WORK_PREFIX)
            }
        try {
            return work(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    internal companion object {
        /** Doc 11:50's `gratitude_media_quarantined_total{kind,verdict}`: QUARANTINED and BLOCKED both counted. */
        const val QUARANTINED = "gratitude.media.quarantined"

        /** Well over a display variant (150 KiB) and a voice note (500 KiB); anything larger is not ours. */
        const val MAX_BYTES = 1024 * 1024

        private const val WORK_PREFIX = "moyi-moderation-"
    }
}

/**
 * `media.moderate-image`: a photo's verdict (spec §4 D4, Q1, Q16). On its
 * display variant, made into a JPEG here (Rekognition reads no WebP): the
 * known-image list first, and a match is `BLOCKED` with no classifier asked;
 * then the classifier.
 *
 * **It runs only where photos are processed and moderated**: the media worker,
 * with a moderator configured. Elsewhere it declares no event type and claims
 * nothing; the first process that moderates subscribes it, and
 * [StartFrom.BEGINNING] hands it every photo made before, so a photo made
 * while moderation was `none` is moderated once it is switched on, and no
 * partner saw it meanwhile.
 */
@Component
internal class ModerateImages(
    private val moderation: Moderation,
    private val images: ImageProcessor,
    private val moderator: ImageModerator,
    private val matcher: KnownImageMatcher,
) : EventConsumer {
    override val id: String = ID
    override val eventTypes: Set<String> =
        if (images.processesHere && moderator.moderatesHere) setOf(MediaReleases.MEDIA_READY) else emptySet()
    override val startFrom: StartFrom = StartFrom.BEGINNING

    override fun handle(event: ReceivedEvent) {
        val asset = moderation.due(event, MediaKind.IMAGE) ?: return
        val (decision, found) =
            moderation.inWorkDirectory { dir ->
                val display = dir.resolve("display.webp")
                val jpeg = if (moderation.fetch(asset, display)) images.jpegForModeration(display, dir) else null
                if (jpeg == null) {
                    moderation.decide(null, knownImage = false, moderator.name, matcher.name)
                } else {
                    val bytes = Files.readAllBytes(jpeg)
                    val known = matcher.matches(KnownImageCandidate(bytes, asset.checksumSha256))
                    moderation.decide(if (known) null else moderator.moderate(bytes), known, moderator.name, matcher.name)
                }
            }
        moderation.record(asset, decision, found, moderator.name, matcher.name)
    }

    internal companion object {
        /** Stable for ever: delivery state is kept under it (ADR-0035). */
        const val ID = "media.moderate-image"
    }
}

/**
 * `media.moderate-voice`: a voice note's verdict (the owner's ruling on
 * FR-061; ADR-0049 decision 7), on the Opus that is kept. Its own consumer, so
 * that switching voice moderation on later hands it every voice note made
 * before ([StartFrom.BEGINNING]) without photos being moderated twice.
 *
 * No vendor is wired (ADR-0051 decision 5): production names `none`, under
 * which this subscribes to nothing and a partner hears no voice note.
 */
@Component
internal class ModerateVoices(
    private val moderation: Moderation,
    private val voices: VoiceProcessor,
    private val moderator: VoiceModerator,
) : EventConsumer {
    override val id: String = ID
    override val eventTypes: Set<String> =
        if (voices.processesHere && moderator.moderatesHere) setOf(MediaReleases.MEDIA_READY) else emptySet()
    override val startFrom: StartFrom = StartFrom.BEGINNING

    override fun handle(event: ReceivedEvent) {
        val asset = moderation.due(event, MediaKind.VOICE) ?: return
        val (decision, found) =
            moderation.inWorkDirectory { dir ->
                val ogg = dir.resolve("voice.ogg")
                val durationMs = asset.durationMs
                val moderated =
                    if (durationMs != null &&
                        moderation.fetch(asset, ogg)
                    ) {
                        moderator.moderate(Files.readAllBytes(ogg), durationMs)
                    } else {
                        null
                    }
                moderation.decide(moderated, knownImage = false, moderator.name, NO_LIST)
            }
        moderation.record(asset, decision, found, moderator.name, NO_LIST)
    }

    internal companion object {
        /** Stable for ever: delivery state is kept under it (ADR-0035). */
        const val ID = "media.moderate-voice"

        /** A voice note is matched against no list of known images. */
        private const val NO_LIST = "none"
    }
}
