package com.moyi.app

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * A file that exists while the application is started and ready, for a process
 * with no HTTP port to be asked (ADR-0052): the media worker runs without a web
 * server, so Docker's health check, which Kamal waits on before it calls a
 * deploy of the worker done, tests for this file instead (`health-cmd` in
 * config/deploy.yml).
 *
 * **Written once the application is ready**, so after every bean exists, the
 * native programs passed their start-up checks in the sandbox (ADR-0048,
 * ADR-0049) and every event consumer is registered: a worker that would refuse
 * to start never reports healthy. **Removed when the context closes**, so a
 * worker that is stopping is not reported as ready. A process killed outright
 * leaves it behind, which is harmless: the worker's `/tmp` is a tmpfs that
 * dies with its container.
 *
 * Off unless `moyi.ready-file` names a path: the API has `/healthz` and needs none.
 * It says the process started and has not stopped; whether uploads are being
 * processed is `ops/monitoring/media-check.sh`'s to say.
 */
@Component
internal class ReadyFile(
    @Value("\${moyi.ready-file:}") private val path: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun ready() {
        if (path.isBlank()) return
        val file = Path.of(path)
        Files.writeString(file, "ready\n")
        log.info("Ready: wrote {}", file)
    }

    @EventListener(ContextClosedEvent::class)
    fun closing() {
        if (path.isBlank()) return
        Files.deleteIfExists(Path.of(path))
    }
}
