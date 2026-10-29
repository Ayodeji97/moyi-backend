package com.moyi.media.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.media.domain.HeifItems
import com.moyi.media.domain.ImageFormat
import com.moyi.media.domain.ImageRefusal
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaStatus
import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.StoredVariant
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.processing.Encoding
import com.moyi.media.infra.processing.ImageProcessor
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * `media.process` (spec §4 D2): makes an uploaded photo into the images kept of
 * it, or fails it. It consumes `MediaUploaded`, which completion publishes
 * (ADR-0047), in the outbox's delivery transaction (ADR-0035), at least once.
 *
 * **It runs only where a processor does** ([ImageProcessor.processesHere]).
 * Where processing is off (production's API until the media worker exists,
 * ADR-0048), it declares no event type, so it subscribes to nothing and claims
 * nothing; the first process that processes subscribes it, and
 * [StartFrom.BEGINNING] hands it every upload published before then.
 *
 * In order:
 *
 * 1. **The row, read again** ([MediaAsset.processing]). The event may be older
 *    than the asset: one deleted since is skipped (ADR-0047 owed this), and so
 *    is one already ready or failed (the same event twice) and a voice note
 *    (D3's). A photo on which [MediaAsset.MAX_ATTEMPTS] decodes began and
 *    none finished is given up: `FAILED`, and the delivery succeeds.
 * 2. **The attempt is recorded in a transaction of its own**: `PROCESSING`,
 *    committed whatever becomes of this delivery, so a photo being worked on
 *    is seen to be (D5's alert on anything `PROCESSING` for 30 minutes reads
 *    it). One more `processing_attempts` is committed the same way, but only
 *    once the bytes are fetched and verified, just before the decode: a
 *    storage outage spends none of the cap, however long it lasts, and a
 *    decode that kills the process is counted.
 * 3. **The work, holding no lock on the asset.** The upload is read once,
 *    never past its declared length, and must have the declared SHA-256; its
 *    first bytes must be one of the four allowed formats and the one declared
 *    ([ImageFormat], T-07), and a HEIC must hold HEVC images only
 *    ([HeifItems]). Only then does the processor see it. Each variant
 *    is written under `v/{uuid}/…` ([StorageKey.forVariant]).
 * 4. **The decision, under the row lock**, on the status as it is then. Still
 *    `PROCESSING`: `READY` with the variants, or `FAILED`. Anything else means
 *    the owner deleted it meanwhile, and the variants just written are deleted
 *    again. A deletion therefore never waits on a photo being processed, and a
 *    photo processed never outlives its deletion.
 * 5. **After the commit, the upload itself is deleted** (spec §4 D2: "delete
 *    in/…"), ready or failed: the original bytes, metadata and all, are never
 *    kept. If the storage cannot be reached then, the bucket's lifecycle rule
 *    removes them within two days (ADR-0047).
 *
 * **What is final and what is not.** A photo the rules refuse ([ImageRefusal])
 * is `FAILED`, and the delivery succeeds: retrying would refuse it again. A
 * storage that cannot be reached is thrown, the delivery is rolled back, and
 * the outbox tries again later (spec §4 D2: "transient → throw and let the
 * outbox back off"); the asset stays `PROCESSING` and is taken up again.
 */
@Component
@Suppress("LongParameterList") // The pipeline's collaborators, each injected by Spring; D5 added the purge's.
internal class ProcessUploads(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val processor: ImageProcessor,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    private val meters: MeterRegistry,
    private val releases: MediaReleases,
) : EventConsumer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    override val id: String = ID
    override val eventTypes: Set<String> = if (processor.processesHere) setOf(CompleteUpload.MEDIA_UPLOADED) else emptySet()
    override val startFrom: StartFrom = StartFrom.BEGINNING

    override fun handle(event: ReceivedEvent) {
        val asset = event.references[CompleteUpload.MEDIA_ID]?.let(assets::findAny) ?: return
        when (asset.processing) {
            MediaAsset.Processing.GIVE_UP -> {
                giveUp(asset)
            }

            // Step 2: the attempt begins, unless it has nothing left to do by now.
            MediaAsset.Processing.PROCESS -> {
                if (ownTransaction.execute { assets.startProcessing(asset.id) } == true) attempt(asset)
            }

            MediaAsset.Processing.NOT_THIS_KIND, MediaAsset.Processing.NOTHING_TO_DO -> {
                Unit
            }
        }
    }

    /**
     * [MediaAsset.MAX_ATTEMPTS] decodes began and none finished: `FAILED`, in
     * this delivery's transaction, and the delivery succeeds, so the outbox
     * stops retrying. Whatever an attempt left in the storage goes after the
     * commit, as a failed photo's does.
     */
    private fun giveUp(asset: MediaAsset) {
        if (!assets.giveUp(asset.id)) return
        log.warn("Media {} failed: {}", asset.id, ImageRefusal.TOO_MANY_ATTEMPTS)
        afterCommit { asset.objectKeys.forEach { bestEffortDelete(it, asset) } }
    }

    /** Steps 3 to 5, timed. */
    private fun attempt(asset: MediaAsset) {
        val started = System.nanoTime()
        var outcome = OUTCOME_ERROR
        try {
            val result = workOn(asset)
            outcome = decide(asset, result)
        } finally {
            Timer
                .builder(DURATION)
                .description("Time to make an uploaded photo into its variants, or to fail it")
                .tag("kind", asset.kind.name)
                .tag("outcome", outcome)
                .register(meters)
                .record(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    /** Step 3: the upload, checked, and the variants, written. Holds no lock. */
    private fun workOn(asset: MediaAsset): Result {
        val workDir = workDirectory()
        try {
            val source = workDir.resolve("upload")
            val refusal = fetch(asset, source) ?: formatRefusal(asset, source)
            if (refusal != null) return Result.Refused(refusal)
            // The bytes are the declared format, so the declaration names it.
            val format = ImageFormat.entries.single { it.agreesWith(asset.contentType) }
            // The attempt counts from here, in a transaction of its own: the decode is where a photo can be the
            // cause, an outage before it never is (owner's ruling on #108).
            ownTransaction.executeWithoutResult { assets.countDecode(asset.id) }
            return when (val encoding = processor.encode(source, format, workDir)) {
                is Encoding.Refused -> {
                    Result.Refused(encoding.reason)
                }

                is Encoding.Encoded -> {
                    Result.Ready(
                        encoding.variants.map { encoded ->
                            val key = StorageKey.forVariant(asset.storageKey, encoded.variant)
                            storage.put(key, encoded.file, WEBP)
                            StoredVariant(encoded.variant, key, encoded.width, encoded.height, encoded.bytes)
                        },
                    )
                }
            }
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    /** T-07: the bytes are one of the four allowed formats, and the one declared. Null when they are. */
    private fun formatRefusal(
        asset: MediaAsset,
        source: Path,
    ): ImageRefusal? {
        val format = Files.newInputStream(source).use { ImageFormat.of(it.readNBytes(ImageFormat.SNIFF_BYTES)) }
        return when {
            format == null -> ImageRefusal.NOT_AN_ALLOWED_FORMAT

            !format.agreesWith(asset.contentType) -> ImageRefusal.NOT_THE_DECLARED_FORMAT

            // A HEIC's brands are a claim; its items are what is decoded (an AV1 under HEIC brands decodes as AV1).
            format == ImageFormat.HEIC && !HeifItems.isHevcOnly(Files.readAllBytes(source)) -> ImageRefusal.NOT_HEVC

            else -> null
        }
    }

    /**
     * Copies the upload to [target], reading at most one byte past its
     * declared length, and checks its SHA-256. Null when it is the upload
     * declared; otherwise why not.
     */
    private fun fetch(
        asset: MediaAsset,
        target: Path,
    ): ImageRefusal? {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = storage.open(asset.storageKey) ?: return ImageRefusal.SOURCE_MISSING
        val withinLength = stream.use { input -> Files.newOutputStream(target).use { copyAtMost(input, it, asset.sizeBytes, digest) } }
        val checksum = Base64.getEncoder().encodeToString(digest.digest())
        return when {
            !withinLength -> ImageRefusal.LONGER_THAN_DECLARED
            checksum != asset.checksumSha256 -> ImageRefusal.CHECKSUM_MISMATCH
            else -> null
        }
    }

    /** Step 4, in the delivery's transaction, and step 5 registered for after it. Returns the outcome's name for the timer. */
    private fun decide(
        asset: MediaAsset,
        result: Result,
    ): String {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val current = assets.lockAny(asset.id)
        if (current?.status != MediaStatus.PROCESSING) {
            // Deleted while it was worked on: nothing written may outlive that. At once if the storage
            // answers; and for certain by media.purge, whose run for the deletion may have come before
            // these variants were written (D5).
            if (result is Result.Ready) result.variants.forEach { bestEffortDelete(it.key, asset) }
            if (current?.isGone == true) releases.released(asset.id, now)
            log.info("Media {} was deleted while it was processed; what was made of it is removed", asset.id)
            return OUTCOME_GONE
        }
        val outcome =
            when (result) {
                is Result.Ready -> {
                    check(assets.markReady(asset.id, result.variants, now)) { "a photo under its row lock, PROCESSING, becomes READY" }
                    log.info("Media {} is ready ({} variants)", asset.id, result.variants.size)
                    OUTCOME_READY
                }

                is Result.Refused -> {
                    check(assets.markFailed(asset.id)) { "a photo under its row lock, PROCESSING, becomes FAILED" }
                    // The id and the rule, never the key or anything the file said.
                    log.warn("Media {} failed: {}", asset.id, result.reason)
                    OUTCOME_FAILED
                }
            }
        afterCommit { bestEffortDelete(asset.storageKey, asset) }
        return outcome
    }

    private fun afterCommit(action: () -> Unit) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) { "media.process runs in the delivery's transaction" }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    @Suppress("SwallowedException") // On purpose: the lifecycle rule (in/) and media.purge (D5) are the fallbacks.
    private fun bestEffortDelete(
        key: StorageKey,
        asset: MediaAsset,
    ) {
        try {
            storage.delete(key)
        } catch (unavailable: ObjectStoreUnavailableException) {
            log.warn("Media {}: an object could not be deleted and is left for the storage's own clean-up", asset.id)
        }
    }

    /** A directory of this job's own, readable by this user alone where the file system can say so. */
    private fun workDirectory(): Path =
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createTempDirectory(WORK_PREFIX, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            Files.createTempDirectory(WORK_PREFIX)
        }

    private sealed interface Result {
        data class Ready(
            val variants: List<StoredVariant>,
        ) : Result

        data class Refused(
            val reason: ImageRefusal,
        ) : Result
    }

    internal companion object {
        /** Stable for ever: delivery state is kept under it (ADR-0035). */
        const val ID = "media.process"

        /** Doc 11:49's `gratitude_media_processing_duration_seconds{kind}`, with the outcome beside the kind. */
        const val DURATION = "gratitude.media.processing.duration"

        private const val WORK_PREFIX = "moyi-media-"
        private const val WEBP = "image/webp"
        private const val OUTCOME_READY = "ready"
        private const val OUTCOME_FAILED = "failed"
        private const val OUTCOME_GONE = "deleted"
        private const val OUTCOME_ERROR = "error"
    }
}

/** Copies [input] to [output] through [digest]; false, and stops, as soon as more than [limit] bytes have been read. */
private fun copyAtMost(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    digest: MessageDigest,
): Boolean {
    val buffer = ByteArray(COPY_BUFFER)
    var length = 0L
    var read = input.read(buffer)
    while (read >= 0) {
        length += read
        if (length > limit) return false
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        read = input.read(buffer)
    }
    return true
}

private const val COPY_BUFFER = 64 * 1024
