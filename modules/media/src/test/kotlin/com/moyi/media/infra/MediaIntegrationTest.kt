package com.moyi.media.infra

import com.moyi.common.testing.IntegrationTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI

/**
 * Postgres and Valkey ([IntegrationTest]) and a MinIO standing in for R2 (spec
 * §4 D1), one per test JVM, with the bucket made before any context starts.
 * The storage properties point the real `R2ObjectStore` at it.
 *
 * **The image is `pgsty/minio`, not `minio/minio`.** MinIO stopped publishing
 * its community image (`minio/minio` no longer resolves on Docker Hub, and
 * quay.io's copy needs a login); `pgsty/minio` is a community build of the same
 * server from its source, pinned by its digest (ADR-0047).
 *
 * [admin] is a client of the test's own, outside the code under test, for what
 * a test must arrange or inspect behind the application's back.
 */
@Suppress("UtilityClassWithPublicConstructor")
internal abstract class MediaIntegrationTest : IntegrationTest() {
    companion object {
        const val BUCKET = "moyi-media-test"

        /**
         * `docker-compose.yml`'s MinIO, so the smoke run and the tests run one
         * server: release RELEASE.2026-08-04T00-00-00Z, pinned by its digest so a
         * re-pushed tag cannot change what runs.
         */
        const val MINIO_IMAGE = "pgsty/minio@sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372"

        @JvmStatic
        val minio: MinIOContainer =
            MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"))
                .also { it.start() }

        val admin: S3Client by lazy {
            S3Client
                .builder()
                .endpointOverride(URI.create(minio.s3URL))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.userName, minio.password)))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()
                .also { it.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            admin // the bucket exists before the first context asks for it
            registry.add("moyi.media.storage.provider") { "R2" }
            registry.add("moyi.media.storage.r2.endpoint") { minio.s3URL }
            registry.add("moyi.media.storage.r2.bucket") { BUCKET }
            registry.add("moyi.media.storage.r2.access-key-id") { minio.userName }
            registry.add("moyi.media.storage.r2.secret-access-key") { minio.password }
        }

        /** Puts [bytes] under [key] directly, as no client of the API can. */
        fun putBehindTheApi(
            key: String,
            bytes: ByteArray,
        ) {
            admin.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(key)
                    .build(),
                RequestBody.fromBytes(bytes),
            )
        }

        /** Whether MinIO holds anything under [key]. */
        @Suppress("SwallowedException") // absence is the answer
        fun stored(key: String): Boolean =
            try {
                admin.headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(BUCKET)
                        .key(key)
                        .build(),
                )
                true
            } catch (absent: NoSuchKeyException) {
                false
            } catch (refused: S3Exception) {
                if (refused.statusCode() == NOT_FOUND) false else throw refused
            }

        private const val NOT_FOUND = 404
    }
}
