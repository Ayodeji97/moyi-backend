package com.moyi.media.infra.processing

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/**
 * What the sandbox around libvips holds a program to (T-07, spec §6 R-E): its
 * environment, its output, and on Linux its memory. The time limit is held in
 * `VipsImageProcessorTest`, through the processor that relies on it.
 */
internal class SandboxTest {
    @TempDir
    lateinit var dir: Path

    private val unlimited = Sandbox(null)

    @Test
    fun `a program sees only PATH and what it is given, nothing of this process's environment`() {
        val printEnv = script("env.sh", "env")

        val outcome = unlimited.run(listOf(printEnv.toString()), mapOf("VIPS_CONCURRENCY" to "1"), dir, deadline())

        val output = outcome.shouldBeInstanceOf<Sandbox.Outcome.Exited>().output
        output shouldContain "PATH="
        output shouldContain "VIPS_CONCURRENCY=1"
        // The JVM's own environment has HOME (and, in production, every secret); the program has none of it.
        System.getenv("HOME").isNullOrEmpty() shouldBe false
        output shouldNotContain "HOME="
    }

    @Test
    fun `a program that writes without end is read back only up to the limit`() {
        val chatty = script("chatty.sh", "head -c 200000 /dev/zero | tr '\\0' 'x'")

        val outcome = unlimited.run(listOf(chatty.toString()), emptyMap(), dir, deadline())

        outcome.shouldBeInstanceOf<Sandbox.Outcome.Exited>().output.length shouldBe Sandbox.OUTPUT_LIMIT
    }

    /**
     * The memory limit, enforced by the kernel: the same allocation succeeds
     * without the limit and fails under it. Python is the allocator because it
     * allocates exactly what it is told; libvips under the same limit was
     * measured by hand (`MediaProcessingProperties.Vips.memoryLimit`).
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on Linux a program cannot allocate past the memory limit`() {
        val allocate = script("allocate.sh", "exec python3 -c 'x = bytearray(300 * 1024 * 1024); print(len(x))'")

        val free = unlimited.run(listOf(allocate.toString()), emptyMap(), dir, deadline())
        free.shouldBeInstanceOf<Sandbox.Outcome.Exited>().code shouldBe 0

        val limited = Sandbox(200L * 1024 * 1024).run(listOf(allocate.toString()), emptyMap(), dir, deadline())
        limited.shouldBeInstanceOf<Sandbox.Outcome.Exited>().code shouldBeGreaterThan 0
    }

    /**
     * Review of #108: the CPU-time limit, the deadline's backstop, is set too:
     * the program sees its remaining seconds plus one, and the address-space
     * limit, as its own limits.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on Linux a program runs under the CPU-time limit and the address-space limit`() {
        val limits = script("limits.sh", "echo \"cpu=\$(ulimit -t) as=\$(ulimit -v)\"")

        val outcome =
            Sandbox(
                200L * 1024 * 1024,
            ).run(listOf(limits.toString()), emptyMap(), dir, Sandbox.Deadline.after(Duration.ofSeconds(9)))

        val output = outcome.shouldBeInstanceOf<Sandbox.Outcome.Exited>().output.trim()
        output shouldBe "cpu=9 as=${200 * 1024}"
    }

    /** Where the limit cannot be set (macOS refuses `ulimit -v`), the program does not run unlimited instead: it does not run. */
    @Test
    @EnabledOnOs(OS.MAC)
    fun `where the memory limit cannot be set, the program never starts`() {
        val marker = dir.resolve("ran")
        val touch = script("touch.sh", "touch \"$marker\"")

        val outcome = Sandbox(200L * 1024 * 1024).run(listOf(touch.toString()), emptyMap(), dir, deadline())

        outcome.shouldBeInstanceOf<Sandbox.Outcome.Exited>().code shouldBe 125
        Files.exists(marker) shouldBe false
    }

    private fun deadline() = Sandbox.Deadline.after(Duration.ofSeconds(20))

    private fun script(
        name: String,
        body: String,
    ): Path {
        val path = dir.resolve(name)
        Files.writeString(path, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}
