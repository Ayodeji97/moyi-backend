package com.moyi.media.infra.storage

import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.StoredObject
import com.moyi.media.infra.MediaIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * The S3 contract, against MinIO standing in for R2 (spec §4 D1): what the
 * upload URL signs, and that the storage then refuses any body but the one
 * declared. The PUTs are made with the JDK's own HTTP client, as a phone
 * would make them, never through the SDK, which would sign them itself.
 *
 * **[inferred], and not settled here: R2 honours a signed
 * `x-amz-checksum-sha256` as MinIO does.** Cloudflare's documents do not say
 * either way. What holds without it is that the length is signed, and that
 * completion compares the digest whenever the storage reports one. ADR-0047
 * owes one manual check against the real bucket.
 */
internal class R2ObjectStoreTest {
    private val store =
        R2ObjectStore.create(
            MediaStorageProperties.R2(
                endpoint = MediaIntegrationTest.minio.s3URL,
                bucket = MediaIntegrationTest.BUCKET.also { MediaIntegrationTest.admin },
                accessKeyId = MediaIntegrationTest.minio.userName,
                secretAccessKey = MediaIntegrationTest.minio.password,
            ),
        )
    private val http: HttpClient = HttpClient.newHttpClient()
    private val photo = "fake jpeg bytes for the contract test".toByteArray()

    @Test
    fun `the upload URL signs the type, the length and the digest, and tells the client to send each`() {
        val upload = presign(StorageKey.forUpload(UUID.randomUUID()), photo)

        upload.requiredHeaders.keys.map { it.lowercase() } shouldContainExactlyInAnyOrder
            listOf("content-type", "content-length", "x-amz-checksum-sha256")
        upload.requiredHeaders.entries
            .first { it.key.equals("content-type", true) }
            .value shouldBe "image/jpeg"
        upload.requiredHeaders.entries
            .first { it.key.equals("content-length", true) }
            .value shouldBe photo.size.toString()
        upload.requiredHeaders.entries
            .first { it.key.equals("x-amz-checksum-sha256", true) }
            .value shouldBe sha256(photo)
        val signed =
            upload.url.query
                .split('&')
                .first { it.startsWith("X-Amz-SignedHeaders=") }
        signed shouldContain "content-length"
        signed shouldContain "content-type"
        signed shouldContain "x-amz-checksum-sha256"
        upload.toString() shouldNotContain upload.url.toString()
    }

    @Test
    fun `the declared bytes go in, and HEAD reports their length and digest`() {
        val key = StorageKey.forUpload(UUID.randomUUID())
        put(presign(key, photo), photo) shouldBe OK

        store.head(key) shouldBe StoredObject(photo.size.toLong(), sha256(photo))
    }

    @Test
    fun `a body of another length is refused, and nothing is stored`() {
        val key = StorageKey.forUpload(UUID.randomUUID())
        val longer = photo + "!".toByteArray()

        (put(presign(key, photo), longer) in REFUSED) shouldBe true
        store.head(key).shouldBeNull()
    }

    @Test
    fun `a body of the same length with other bytes is refused by its digest, and nothing is stored`() {
        val key = StorageKey.forUpload(UUID.randomUUID())
        val forged = photo.copyOf().also { it[0] = 'F'.code.toByte() }

        (put(presign(key, photo), forged) in REFUSED) shouldBe true
        store.head(key).shouldBeNull()
    }

    @Test
    fun `another content type is refused, and nothing is stored`() {
        val key = StorageKey.forUpload(UUID.randomUUID())

        (put(presign(key, photo), photo, contentType = "image/png") in REFUSED) shouldBe true
        store.head(key).shouldBeNull()
    }

    @Test
    fun `a download URL and the stream both read back the bytes, and a delete removes them`() {
        val key = StorageKey.forUpload(UUID.randomUUID())
        put(presign(key, photo), photo) shouldBe OK

        val download = get(store.presignDownload(key, Instant.now(), Duration.ofMinutes(5)))
        download.statusCode() shouldBe OK
        download.body().toList() shouldBe photo.toList()
        store.open(key)!!.use { it.readAllBytes().toList() } shouldBe photo.toList()

        store.delete(key)
        store.head(key).shouldBeNull()
        store.open(key).shouldBeNull()
        store.delete(key) // removing what is not there is not an error
    }

    @Test
    fun `a download URL is signed as of the instant given, so the same instant gives the same URL, and it works`() {
        val key = StorageKey.forUpload(UUID.randomUUID())
        put(presign(key, photo), photo) shouldBe OK
        val hour = Instant.now().truncatedTo(ChronoUnit.HOURS)

        val first = store.presignDownload(key, hour, Duration.ofHours(25))
        Thread.sleep(1100) // a signer that read the wall clock would now sign another second
        val again = store.presignDownload(key, hour, Duration.ofHours(25))
        val nextHour = store.presignDownload(key, hour.plus(Duration.ofHours(1)), Duration.ofHours(25))

        again shouldBe first
        (nextHour == first) shouldBe false
        first.query shouldContain "X-Amz-Date=${DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(hour)}"
        first.query shouldContain "X-Amz-Expires=90000"
        // Signed up to an hour ago and still valid: MinIO serves it.
        get(first).statusCode() shouldBe OK
        get(first).body().toList() shouldBe photo.toList()
    }

    @Test
    fun `a download URL whose time has passed is refused`() {
        val key = StorageKey.forUpload(UUID.randomUUID())
        put(presign(key, photo), photo) shouldBe OK

        get(store.presignDownload(key, Instant.now().minus(Duration.ofHours(2)), Duration.ofHours(1))).statusCode() shouldBe FORBIDDEN
    }

    @Test
    fun `a variant is stored from a file, with its content type`() {
        val key = StorageKey("v/${UUID.randomUUID()}/full.webp")
        val file = Files.createTempFile("variant-", ".webp").also { Files.write(it, photo) }
        try {
            store.put(key, file, "image/webp")

            store.head(key) shouldBe StoredObject(photo.size.toLong(), null)
            val download = get(store.presignDownload(key, Instant.now(), Duration.ofMinutes(5)))
            download.headers().firstValue("Content-Type").orElse(null) shouldBe "image/webp"
            download.body().toList() shouldBe photo.toList()
        } finally {
            Files.deleteIfExists(file)
            store.delete(key)
        }
    }

    private fun get(url: URI): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `a storage that cannot be reached is unavailable, and the failure names no key`() {
        val unreachable =
            R2ObjectStore.create(
                MediaStorageProperties.R2(
                    endpoint = "http://127.0.0.1:1",
                    bucket = "nowhere",
                    accessKeyId = "fake-access-key",
                    secretAccessKey = "fake-secret-key",
                    connectTimeout = Duration.ofMillis(500),
                ),
            )
        val key = StorageKey.forUpload(UUID.randomUUID())

        val failure = shouldThrow<ObjectStoreUnavailableException> { unreachable.head(key) }
        failure.message!! shouldNotContain key.value
    }

    private fun presign(
        key: StorageKey,
        bytes: ByteArray,
    ): PresignedUpload = store.presignUpload(key, "image/jpeg", bytes.size.toLong(), sha256(bytes), Duration.ofMinutes(15))

    /**
     * PUTs [body] as a phone would: every required header but `Content-Length`,
     * which the JDK sets from the body itself and refuses to be told.
     */
    private fun put(
        upload: PresignedUpload,
        body: ByteArray,
        contentType: String? = null,
    ): Int {
        val request = HttpRequest.newBuilder(URI.create(upload.url.toString())).PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        upload.requiredHeaders
            .filterKeys { !it.equals("content-length", ignoreCase = true) }
            .forEach { (name, value) -> request.header(name, if (name.equals("content-type", true)) contentType ?: value else value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun sha256(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        const val OK = 200
        const val FORBIDDEN = 403
        val REFUSED = setOf(400, 403)
    }
}
