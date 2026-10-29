package com.moyi.media.infra.processing

import com.moyi.media.domain.VoiceRules
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/** The voice processors the tests use: the real ffmpeg on `PATH`, or stand-in programs a test writes. As [Vipses] is for libvips. */
internal object Ffmpegs {
    /** Where `ffmpeg` and `ffprobe` are on this machine's `PATH`. */
    val binDir: Path by lazy {
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { Path.of(it) }
            .firstOrNull { Files.isExecutable(it.resolve("ffmpeg")) && Files.isExecutable(it.resolve("ffprobe")) }
            ?: error("ffmpeg and ffprobe are not on PATH; install them (brew install ffmpeg, apt-get install ffmpeg)")
    }

    /** The memory limit production uses for ffmpeg (512 MiB), where this OS can enforce one; none on macOS. */
    val sandbox: Sandbox get() = Sandbox(if (Sandbox.enforcesMemoryLimits) 512L * 1024 * 1024 else null)

    fun onPath(
        timeout: Duration = Duration.ofSeconds(30),
        maxKeptBytes: Long = VoiceRules.MAX_KEPT_BYTES,
    ): FfmpegVoiceProcessor = FfmpegVoiceProcessor(binDir.resolve("ffmpeg"), binDir.resolve("ffprobe"), sandbox, timeout, maxKeptBytes)

    /**
     * Runs `ffmpeg` (or `ffprobe`, with [program]) on files a test makes or
     * reads for itself, outside the code under test, and answers what it
     * printed. Fails the test if the program does.
     */
    fun tool(
        dir: Path,
        vararg args: String,
        program: String = "ffmpeg",
    ): String {
        val process =
            ProcessBuilder(listOf(binDir.resolve(program).toString(), "-hide_banner", "-v", "error") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "$program ${args.joinToString(" ")} failed: $output" }
        return output
    }

    /** What an independent `ffprobe` says of [file]: `-of flat` lines, keyed. */
    fun probe(
        file: Path,
        vararg entries: String,
    ): Map<String, String> =
        tool(file.parent, "-show_entries", entries.joinToString(":"), "-of", "flat", file.toString(), program = "ffprobe")
            .lineSequence()
            .filter { "=" in it }
            .associate { it.substringBefore("=") to it.substringAfter("=").removeSurrounding("\"") }

    /** Writes `ffprobe` and `ffmpeg` into [dir] as shell scripts running [ffprobe] and [ffmpeg]. */
    fun fake(
        dir: Path,
        ffprobe: String,
        ffmpeg: String,
    ): Path {
        Files.createDirectories(dir)
        script(dir.resolve("ffprobe"), ffprobe)
        script(dir.resolve("ffmpeg"), ffmpeg)
        return dir
    }

    private fun script(
        path: Path,
        body: String,
    ) {
        Files.writeString(path, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
}
