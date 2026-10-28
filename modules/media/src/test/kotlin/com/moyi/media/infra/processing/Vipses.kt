package com.moyi.media.infra.processing

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/** The processors the tests use: the real libvips on `PATH`, or stand-in programs a test writes. */
internal object Vipses {
    /** Where `vips` and `vipsheader` are on this machine's `PATH`. */
    val binDir: Path by lazy {
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { Path.of(it) }
            .firstOrNull { Files.isExecutable(it.resolve("vips")) && Files.isExecutable(it.resolve("vipsheader")) }
            ?: error("libvips' command-line tools are not on PATH; install them (brew install vips, apt-get install libvips-tools)")
    }

    /** The memory limit production uses, where this OS can enforce one; none on macOS. */
    val sandbox: Sandbox get() = Sandbox(if (Sandbox.enforcesMemoryLimits) 1024L * 1024 * 1024 else null)

    fun onPath(timeout: Duration = Duration.ofSeconds(40)): VipsImageProcessor =
        VipsImageProcessor(binDir.resolve("vips"), binDir.resolve("vipsheader"), sandbox, timeout)

    /** Writes `vips` and `vipsheader` into [dir] as shell scripts running [vips] and [vipsheader]. */
    fun fake(
        dir: Path,
        vipsheader: String,
        vips: String,
    ): Path {
        Files.createDirectories(dir)
        script(dir.resolve("vipsheader"), vipsheader)
        script(dir.resolve("vips"), vips)
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
