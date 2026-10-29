package com.moyi.media.infra.moderation

import com.moyi.media.domain.Finding
import com.moyi.media.domain.ModerationCategory.ADULT_NUDITY
import com.moyi.media.domain.ModerationCategory.EXTREME_VIOLENCE
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Rekognition adapter against a server of the test's own that speaks
 * Rekognition's wire protocol (AWS JSON 1.1): what it sends, how it reads the
 * labels into Q16's categories, and which answers are an outage and which a
 * refusal (ADR-0051 decision 2). No AWS account is reached.
 */
@ExtendWith(OutputCaptureExtension::class)
internal class RekognitionImageModeratorTest {
    private val requests = CopyOnWriteArrayList<Pair<Map<String, String>, String>>()

    @Volatile private var status = 200

    @Volatile private var body = """{"ModerationLabels":[],"ModerationModelVersion":"7.0"}"""

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
                requests += headers to exchange.requestBody.readAllBytes().decodeToString()
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/x-amz-json-1.1")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private val moderator =
        RekognitionImageModerator.create(
            MediaModerationProperties.Rekognition(
                accessKeyId = "AKIAFAKEFAKEFAKE0000",
                secretAccessKey = "fake/secret/key/for/tests/only/000000000",
                endpoint = "http://127.0.0.1:${server.address.port}",
                apiCallTimeout = Duration.ofSeconds(5),
                apiCallAttemptTimeout = Duration.ofSeconds(2),
            ),
        )

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03)

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `it sends the bytes alone, signed for eu-central-1, asking from Rekognition's default confidence`() {
        moderator.moderate(jpeg) shouldBe Moderated.Found(emptyList(), modelVersion = "7.0")

        val (headers, sent) = requests.single()
        headers["x-amz-target"] shouldBe "RekognitionService.DetectModerationLabels"
        headers["authorization"]!! shouldContain "/eu-central-1/rekognition/aws4_request"
        sent shouldContain Base64.getEncoder().encodeToString(jpeg)
        sent shouldContain "\"MinConfidence\":50"
        sent shouldNotContain "ProjectVersion"
    }

    @Test
    fun `explicit nudity and graphic violence become Q16's categories, and every other label is dropped`() {
        body =
            labels(
                "Explicit" to null to 96.0,
                "Explicit Nudity" to "Explicit" to 95.0,
                "Exposed Female Nipple" to "Explicit Nudity" to 94.0,
                "Graphic Violence" to "Violence" to 88.0,
                "Blood & Gore" to "Graphic Violence" to 87.0,
                "Alcohol" to null to 99.0,
                "Weapons" to "Violence" to 99.0,
                "Kissing on the Lips" to "Non-Explicit Nudity of Intimate parts and Kissing" to 99.0,
                "Corpses" to "Death and Emaciation" to 81.0,
            )

        (moderator.moderate(jpeg) as Moderated.Found).findings shouldContainExactlyInAnyOrder
            listOf(
                Finding(ADULT_NUDITY, 96.0),
                Finding(ADULT_NUDITY, 95.0),
                Finding(ADULT_NUDITY, 94.0),
                Finding(EXTREME_VIOLENCE, 87.0),
                Finding(EXTREME_VIOLENCE, 81.0),
            )
    }

    @Test
    fun `self-harm or an explosion alone is not extreme violence, and graphic violence with no kind given is`() {
        body = labels("Graphic Violence" to "Violence" to 90.0, "Self-Harm" to "Graphic Violence" to 90.0)
        (moderator.moderate(jpeg) as Moderated.Found).findings.shouldBeEmpty()

        body = labels("Graphic Violence" to "Violence" to 90.0, "Explosions and Blasts" to "Graphic Violence" to 90.0)
        (moderator.moderate(jpeg) as Moderated.Found).findings.shouldBeEmpty()

        body = labels("Graphic Violence" to "Violence" to 90.0)
        (moderator.moderate(jpeg) as Moderated.Found).findings shouldBe listOf(Finding(EXTREME_VIOLENCE, 90.0))
    }

    @Test
    fun `a refusal of the image is final, and logged by its kind alone`(output: CapturedOutput) {
        for (type in listOf("InvalidImageFormatException", "ImageTooLargeException", "InvalidParameterException")) {
            status = 400
            body = """{"__type":"$type","Message":"fake-vendor-detail"}"""
            moderator.moderate(jpeg) shouldBe Moderated.Refused(type)
        }
        output.out shouldNotContain "fake-vendor-detail"
    }

    @Test
    fun `throttling, a 5xx and a refused credential are outages, retried later, never a refusal`() {
        for ((code, type) in listOf(400 to "ThrottlingException", 500 to "InternalServerError", 400 to "AccessDeniedException")) {
            status = code
            body = """{"__type":"$type","Message":"fake"}"""
            assertThrows<ModeratorUnavailableException> { moderator.moderate(jpeg) }
        }
    }

    /**
     * Review of #113: the labels read are version 7's. A taxonomy that is not
     * version 7 might name what matters otherwise, and read as version 7 would
     * pass it: an outage, retried and logged at ERROR, never a verdict.
     */
    @Test
    fun `a moderation model that is not version 7 is an outage, never a pass, and the version is kept when it is`() {
        body = """{"ModerationModelVersion":"8.0","ModerationLabels":[]}"""
        assertThrows<ModeratorUnavailableException> { moderator.moderate(jpeg) }
        body = """{"ModerationLabels":[]}"""
        assertThrows<ModeratorUnavailableException> { moderator.moderate(jpeg) }

        body = """{"ModerationModelVersion":"7.0","ModerationLabels":[]}"""
        moderator.moderate(jpeg) shouldBe Moderated.Found(emptyList(), modelVersion = "7.0")
    }

    /** Review of #113: what leaves the box goes to Frankfurt, and only a stand-in on this machine stands in for AWS. */
    @Test
    fun `another region, or an endpoint on another host, is refused at start`() {
        val key = MediaModerationProperties.Rekognition(accessKeyId = "AKIAFAKE", secretAccessKey = "fake-secret")
        assertThrows<IllegalArgumentException> { RekognitionImageModerator.create(key.copy(region = "us-east-1")) }
            .message!! shouldContain "eu-central-1"
        assertThrows<IllegalArgumentException> { RekognitionImageModerator.create(key.copy(endpoint = "https://rekognition.example.test")) }
        RekognitionImageModerator.create(key.copy(endpoint = "http://127.0.0.1:9")).shouldBeInstanceOf<RekognitionImageModerator>()
    }

    @Test
    fun `a server that does not answer is an outage`() {
        server.stop(0)
        assertThrows<ModeratorUnavailableException> { moderator.moderate(jpeg) }
    }

    @Test
    fun `it refuses to be made without both halves of its key, and naming none in the message as the way out`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                RekognitionImageModerator.create(MediaModerationProperties.Rekognition(accessKeyId = "AKIAFAKE"))
            }
        failure.message!! shouldContain "secret-access-key"
        failure.message!! shouldContain "none"
        MediaModerationProperties.Rekognition(secretAccessKey = "fake~secret").toString() shouldNotContain "fake~secret"
        moderator.shouldBeInstanceOf<ImageModerator>()
    }

    private fun labels(vararg labels: Pair<Pair<String, String?>, Double>): String =
        labels.joinToString(prefix = """{"ModerationModelVersion":"7.0","ModerationLabels":[""", postfix = "]}") { (named, confidence) ->
            val (name, parent) = named
            """{"Name":"$name","ParentName":"${parent.orEmpty()}","Confidence":$confidence}"""
        }
}
