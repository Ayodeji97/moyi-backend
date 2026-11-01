package com.moyi.app

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `media-worker` profile (ADR-0052) makes the application the media
 * worker: no web server, the media pipeline's three consumers and no other,
 * the outbox poller and no other timed job, and a ready file once started.
 *
 * Started as the worker is, with processing NATIVE (so libvips and ffmpeg must
 * be on PATH, as they are in CI and in the image) and the timer on, so that the
 * scheduled tasks are the ones the worker would run. Its context is closed
 * after the class: its poller must not go on ticking beside other tests.
 *
 * Each assertion was seen failing with its line in application-media-worker.yml
 * removed or changed (`servlet` for `none`; a consumer dropped from `only`; the
 * idempotency-key job's cron dropped).
 */
@SpringBootTest(
    properties = [
        "moyi.media.processing.provider=native",
        // macOS cannot enforce the sandbox's memory limit; Linux (CI) ignores this.
        "moyi.media.processing.allow-unlimited-memory=true",
        "moyi.scheduling.enabled=true",
        "moyi.ready-file=\${java.io.tmpdir}/moyi-media-worker-profile-test.ready",
    ],
)
@ActiveProfiles("test", "media-worker")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaWorkerProfileTest(
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val schedules: List<ScheduledTaskHolder>,
) : IntegrationTest() {
    /**
     * Read from the profile's file: a `@SpringBootTest` builds its own kind of
     * context whatever the property says (seen: with `servlet` here, the
     * context this class gets was no different). The image run in the pull
     * request is what shows the worker listening on no port.
     */
    @Test
    fun `the worker profile runs no web server`() {
        val yaml = YamlPropertySourceLoader().load("worker", ClassPathResource("application-media-worker.yml"))
        yaml.firstNotNullOf { it.getProperty("spring.main.web-application-type") }.toString() shouldBe "none"
    }

    /**
     * With no web server and virtual threads, every thread left is a daemon,
     * and the JVM exits once started (seen in the image: exit 0 a second after
     * "Started", its first deliveries interrupted). Only the image run shows
     * the effect; this holds the line.
     */
    @Test
    fun `the worker profile keeps the JVM alive without a web server`() {
        val yaml = YamlPropertySourceLoader().load("worker", ClassPathResource("application-media-worker.yml"))
        yaml.firstNotNullOf { it.getProperty("spring.main.keep-alive") }.toString() shouldBe "true"
    }

    /**
     * Review of #117: config/deploy.yml gives the API's secrets to the `web`
     * role alone, so the worker starts without them. The beans that would need
     * them are built anyway (one jar), so the profile gives them what needs no
     * secret. Read from the file, as above: the test profile already sets the
     * first two, so a started context could not tell. The production-like boot
     * in the pull request is what shows the worker starting without them.
     */
    @Test
    fun `the worker profile needs none of the API's secrets`() {
        val yaml = YamlPropertySourceLoader().load("worker", ClassPathResource("application-media-worker.yml"))

        fun value(name: String) = yaml.firstNotNullOfOrNull { it.getProperty(name) }?.toString()
        value("moyi.security.jwt.key-source") shouldBe "ephemeral"
        value("moyi.security.hashing.secret-source") shouldBe "ephemeral"
        value("moyi.notification.email.provider") shouldBe "log"
    }

    @Test
    fun `the worker registers the media pipeline's consumers and no other`() {
        // This process's consumers, not the database's: the backlog reads every consumer any context in
        // this JVM ever registered (seen: in the full suite it listed the API's too).
        dispatcher.consumerIds() shouldContainExactlyInAnyOrder listOf("media.process", "media.moderate-image", "media.moderate-voice")
    }

    @Test
    fun `the outbox poller is the one timed job the worker runs`() {
        val jobs =
            schedules
                .flatMap { it.scheduledTasks }
                // A scheduled method's task names itself by class and method.
                .map { it.task.runnable.toString() }

        jobs shouldContainExactly listOf("com.moyi.scheduling.service.OutboxJob.run")
    }

    @Test
    fun `the ready file exists once the worker has started`() {
        Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "moyi-media-worker-profile-test.ready")) shouldBe true
    }
}
