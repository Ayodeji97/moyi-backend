package com.moyi.media.infra.storage

import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.StoredObject
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.io.InputStream
import java.net.URI
import java.time.Duration

/**
 * The S3 API, for Cloudflare R2 (spec §4 D1, ADR-0047): the one class in the
 * codebase that names the AWS SDK (doc 25:118). MinIO speaks the same API and
 * stands in for R2 in the tests and the `local` profile.
 *
 * **What the upload URL signs.** `Content-Type`, `Content-Length` and
 * `x-amz-checksum-sha256` are set on the request before it is presigned, so
 * SigV4 lists them in `X-Amz-SignedHeaders` and the storage refuses a PUT whose
 * values differ: another type, another length, or bytes with another digest
 * (`R2ObjectStoreTest` sends each and watches the refusal on MinIO). R2 has no
 * presigned POST policy (Cloudflare's presigned-URL page: GET, HEAD, PUT and
 * DELETE only), so a range of sizes cannot be signed; the exact length can.
 *
 * **The SDK's own checksums are off unless required.** Since 2.30 the SDK adds
 * a CRC32 to every request that supports one, and R2's guides tell the Java
 * SDK to turn chunked encoding off; either would put a header in the
 * signature that a phone does not send. `WHEN_REQUIRED` and chunked encoding
 * off keep the signature to what [presignUpload] sets.
 *
 * Path-style addressing (`endpoint/bucket/key`), as Cloudflare's Java example
 * uses and MinIO needs.
 *
 * Every failure to reach the storage is an [ObjectStoreUnavailableException]
 * whose message carries no key and no URL; the log line carries the SDK's
 * exception class and the HTTP status, nothing else.
 */
internal class R2ObjectStore(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
) : ObjectStore,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun presignUpload(
        key: StorageKey,
        contentType: String,
        sizeBytes: Long,
        checksumSha256: String,
        expiresIn: Duration,
    ): PresignedUpload {
        val put =
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key.value)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .checksumSHA256(checksumSha256)
                .build()
        val presigned =
            reaching("presign an upload") {
                presigner.presignPutObject(
                    PutObjectPresignRequest
                        .builder()
                        .signatureDuration(expiresIn)
                        .putObjectRequest(put)
                        .build(),
                )
            }
        // Every signed header but Host, which an HTTP client sets from the URL
        // itself. Read from what the SDK signed, not restated, so the client
        // is told exactly what the signature covers.
        val headers =
            presigned
                .signedHeaders()
                .filterKeys { !it.equals(HOST, ignoreCase = true) }
                .mapValues { (_, values) -> values.joinToString(",") }
        return PresignedUpload(presigned.url().toURI(), headers)
    }

    override fun presignDownload(
        key: StorageKey,
        expiresIn: Duration,
    ): URI {
        val get =
            GetObjectRequest
                .builder()
                .bucket(bucket)
                .key(key.value)
                .build()
        return reaching("presign a download") {
            presigner
                .presignGetObject(
                    GetObjectPresignRequest
                        .builder()
                        .signatureDuration(expiresIn)
                        .getObjectRequest(get)
                        .build(),
                ).url()
                .toURI()
        }
    }

    @Suppress("SwallowedException") // A missing key is the answer "nothing stored", not a failure.
    override fun head(key: StorageKey): StoredObject? =
        reaching("read an object's length") {
            try {
                val head =
                    client.headObject(
                        HeadObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(key.value)
                            .checksumMode(ChecksumMode.ENABLED)
                            .build(),
                    )
                StoredObject(head.contentLength(), head.checksumSHA256())
            } catch (absent: NoSuchKeyException) {
                null
            } catch (refused: S3Exception) {
                // A HEAD has no body, so S3 reports a missing key as a bare 404
                // rather than as NoSuchKey.
                if (refused.statusCode() == NOT_FOUND) null else throw refused
            }
        }

    @Suppress("SwallowedException") // A missing key is the answer "nothing stored", not a failure.
    override fun open(key: StorageKey): InputStream? =
        reaching("read an object") {
            try {
                client.getObject(
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key.value)
                        .build(),
                )
            } catch (absent: NoSuchKeyException) {
                null
            }
        }

    override fun delete(key: StorageKey) {
        reaching("delete an object") {
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key.value)
                    .build(),
            )
        }
    }

    /** Spring calls it at shutdown: the client holds the HTTP connections, the presigner the credentials. */
    override fun close() {
        client.close()
        presigner.close()
    }

    private fun <T> reaching(
        what: String,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (failure: SdkException) {
            val status = (failure as? S3Exception)?.statusCode()
            log.warn("Media storage could not {}: {} (status {})", what, failure.javaClass.simpleName, status ?: "none")
            throw ObjectStoreUnavailableException("The media storage could not $what.", failure)
        }

    internal companion object {
        private const val HOST = "host"
        private const val NOT_FOUND = 404

        /**
         * Builds the store from configuration, or refuses to: the default
         * provider is the real one (ADR-0017 decision 3), so a missing value is
         * a failed start, not a deploy that boots and loses every upload.
         */
        fun create(r2: MediaStorageProperties.R2): R2ObjectStore {
            val endpoint = r2.endpoint
            val bucket = r2.bucket
            val accessKeyId = r2.accessKeyId
            val secret = r2.secretAccessKey
            check(!endpoint.isNullOrBlank() && !bucket.isNullOrBlank() && !accessKeyId.isNullOrBlank() && !secret.isNullOrBlank()) {
                "moyi.media.storage.provider is R2 (the default) but moyi.media.storage.r2.endpoint, " +
                    "moyi.media.storage.r2.bucket, moyi.media.storage.r2.access-key-id and " +
                    "moyi.media.storage.r2.secret-access-key are not all set. Supply the bucket's token through the " +
                    "environment, or set the provider to UNAVAILABLE where no media may be stored. Refusing to start " +
                    "rather than run without anywhere to put an upload."
            }
            val uri = URI.create(endpoint)
            val region = Region.of(r2.region)
            val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secret))
            val s3Configuration =
                S3Configuration
                    .builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build()
            val client =
                S3Client
                    .builder()
                    .endpointOverride(uri)
                    .region(region)
                    .credentialsProvider(credentials)
                    .serviceConfiguration(s3Configuration)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    // Bounded: with the SDK's defaults a storage that accepts and never
                    // answers held one call for about 20 s (ADR-0047 decision 2).
                    .overrideConfiguration { override ->
                        override
                            .apiCallTimeout(r2.apiCallTimeout)
                            .apiCallAttemptTimeout(r2.apiCallAttemptTimeout)
                            .retryStrategy { retry -> retry.maxAttempts(r2.maxAttempts) }
                    }.httpClientBuilder(
                        UrlConnectionHttpClient
                            .builder()
                            .connectionTimeout(r2.connectTimeout)
                            .socketTimeout(r2.readTimeout),
                    ).build()
            val presigner =
                S3Presigner
                    .builder()
                    .endpointOverride(uri)
                    .region(region)
                    .credentialsProvider(credentials)
                    .serviceConfiguration(s3Configuration)
                    .build()
            return R2ObjectStore(client, presigner, bucket)
        }
    }
}
