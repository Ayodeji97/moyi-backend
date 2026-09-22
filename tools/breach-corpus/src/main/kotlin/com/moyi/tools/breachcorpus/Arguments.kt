package com.moyi.tools.breachcorpus

/**
 * Just enough argument parsing for a five-flag tool; a CLI library would be
 * more code than this.
 *
 * **An unrecognised flag is fatal.** The version before this silently ignored
 * one, which meant a typo in `--ranges` did not produce an error — it produced
 * a full 1,048,576-range run, an hour long and 70 GB, when a thirty-second
 * smoke test was asked for. A build tool that quietly does something far larger
 * than it was told to is the same class of problem as everything else this
 * corpus work keeps running into: the wrong outcome, reported as success.
 */
internal class Arguments(
    args: Array<String>,
) {
    private val values: Map<String, String>

    init {
        require(args.size % 2 == 0) {
            "arguments come in --flag value pairs; got ${args.size} of them: ${args.joinToString(" ")}"
        }
        val pairs = args.toList().chunked(2).map { it[0] to it[1] }
        val unknown = pairs.map { it.first }.filterNot { it in KNOWN }
        require(unknown.isEmpty()) { "unknown argument(s) ${unknown.joinToString()}; known flags are ${KNOWN.joinToString()}" }
        values = pairs.toMap()
    }

    fun require(flag: String): String = values[flag] ?: error("missing required argument $flag")

    fun int(
        flag: String,
        default: Int,
    ): Int = values[flag]?.toInt() ?: default

    fun double(
        flag: String,
        default: Double,
    ): Double = values[flag]?.toDouble() ?: default

    private companion object {
        val KNOWN = listOf("--output", "--min-count", "--fpr", "--concurrency", "--ranges")
    }
}
