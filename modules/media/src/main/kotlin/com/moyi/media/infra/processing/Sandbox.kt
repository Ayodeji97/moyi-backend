package com.moyi.media.infra.processing

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Runs a native program on bytes a user controls (T-07, spec §6 R-E), in a
 * process of its own, so that whatever the decoder does, it does there:
 *
 * - **A time limit, enforced by killing it.** The process, and anything it
 *   started, is killed when its [Deadline] passes; the caller hears
 *   [Outcome.TimedOut]. A decoder that loops for ever costs the time limit and
 *   no more, and no thread of this JVM is left waiting on it.
 * - **A memory limit, enforced by the kernel.** On Linux the program runs
 *   under `ulimit -v` ([memoryLimitBytes] of address space) and `ulimit -t`
 *   (CPU seconds, a backstop to the deadline): an allocation past the limit
 *   fails inside the decoder, which exits with an error, and the JVM's own
 *   memory is never at stake. On macOS `/bin/sh` refuses `ulimit -v`
 *   ("cannot modify limit: Invalid argument"), so a limited run never starts
 *   there (exit 125); [memoryLimitBytes] is `null` on macOS and nothing is limited
 *   ([MediaProcessingProperties.Vips.allowUnlimitedMemory] says when that is
 *   allowed; production is Linux).
 * - **No inherited environment.** The program sees `PATH` and what the caller
 *   passes, nothing of this process's secrets.
 * - **Its output goes to files**, never to a pipe this JVM must drain: a
 *   program that writes without end cannot block on a full pipe, and what it
 *   wrote is read back only up to [OUTPUT_LIMIT] bytes.
 *
 * Nothing it prints is logged: an error message may quote the file's own
 * metadata.
 */
internal class Sandbox(
    /** The address space allowed to each program, or `null` where the OS cannot enforce one. */
    val memoryLimitBytes: Long?,
) {
    /** What became of one program. */
    sealed interface Outcome {
        data class Exited(
            val code: Int,
            /** Its standard output, at most [OUTPUT_LIMIT] bytes of it. */
            val output: String,
        ) : Outcome

        /** The deadline passed first, and the program was killed. */
        data object TimedOut : Outcome
    }

    /**
     * Runs [command] (an absolute program path, then its arguments, passed as
     * arguments and never through a shell's parsing) in [workDir], with
     * [environment] and `PATH` as its whole environment, until it exits or
     * [deadline] passes.
     */
    fun run(
        command: List<String>,
        environment: Map<String, String>,
        workDir: Path,
        deadline: Deadline,
    ): Outcome {
        val remaining = deadline.remaining().coerceAtLeast(Duration.ZERO)
        val stdout = Files.createTempFile(workDir, ".out-", "")
        val stderr = Files.createTempFile(workDir, ".err-", "")
        val builder =
            ProcessBuilder(limited(command, remaining))
                .directory(workDir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE))
        builder.environment().apply {
            clear()
            put("PATH", SAFE_PATH)
            putAll(environment)
        }
        val process = builder.start()
        val exited = process.waitFor(remaining.toMillis(), TimeUnit.MILLISECONDS)
        if (!exited) {
            kill(process)
            return Outcome.TimedOut
        }
        return Outcome.Exited(process.exitValue(), readCapped(stdout))
    }

    /**
     * [command] behind `ulimit`, when there is a limit to set. The limits are
     * numbers this class computed, passed as positional parameters like the
     * command itself, so nothing a caller passes is ever parsed by the shell.
     */
    private fun limited(
        command: List<String>,
        remaining: Duration,
    ): List<String> {
        val limit = memoryLimitBytes ?: return command
        val kibibytes = limit / KIBIBYTE
        val cpuSeconds = remaining.toSeconds() + 1
        return listOf(SHELL, "-c", LIMITS_SCRIPT, "sandbox", kibibytes.toString(), cpuSeconds.toString()) + command
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun readCapped(file: Path): String = Files.newInputStream(file).use { String(it.readNBytes(OUTPUT_LIMIT), Charsets.UTF_8) }

    /** A point in time, by the monotonic clock, after which nothing more is started and what runs is killed. */
    class Deadline private constructor(
        private val endsAtNanos: Long,
    ) {
        fun remaining(): Duration = Duration.ofNanos(endsAtNanos - System.nanoTime())

        companion object {
            fun after(budget: Duration): Deadline = Deadline(System.nanoTime() + budget.toNanos())
        }
    }

    companion object {
        /** Whether this OS enforces `ulimit -v`. Linux does; on macOS `/bin/sh` refuses the call ("cannot modify limit"). */
        val enforcesMemoryLimits: Boolean =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("linux")

        const val OUTPUT_LIMIT = 64 * 1024
        private const val KIBIBYTE = 1024L
        private const val SHELL = "/bin/sh"
        private const val SAFE_PATH = "/usr/local/bin:/usr/bin:/bin"
        private val NULL_DEVICE = java.io.File("/dev/null")
        private val KILL_GRACE: Duration = Duration.ofSeconds(2)

        /**
         * `$1` the address space in KiB, `$2` the CPU seconds, then the command.
         * A limit that cannot be set ends the run with 125, before the program
         * starts: an unlimited decoder is never the fallback.
         */
        private const val LIMITS_SCRIPT = """ulimit -v "$1" || exit 125; ulimit -t "$2" || exit 125; shift 2; exec "$@""""
    }
}
