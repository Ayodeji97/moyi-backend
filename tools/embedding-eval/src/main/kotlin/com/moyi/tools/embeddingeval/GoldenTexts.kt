package com.moyi.tools.embeddingeval

import tools.jackson.databind.json.JsonMapper

/**
 * The texts of `golden.json` to embed, each with the key its vector is stored under: every entry,
 * then every query. Only the texts and keys; which entry is relevant to which query is the eval's
 * to read (`RetrievalEvalTest`), not this tool's.
 */
internal class GoldenTexts(
    val entries: List<Keyed>,
    val queries: List<Keyed>,
) {
    /** A text and its key. Prints its key alone: synthetic or not, a text is not for a log. */
    class Keyed(
        val key: String,
        val text: String,
    ) {
        override fun toString() = "Keyed($key)"
    }

    companion object {
        fun read(json: String): GoldenTexts {
            val tree = JsonMapper().readTree(json)
            val entries =
                tree["bonds"].toList().flatMap { bond ->
                    bond["entries"].toList().map { Keyed(it["key"].asString(), it["text"].asString()) }
                }
            val queries = tree["queries"].toList().map { Keyed(it["key"].asString(), it["q"].asString()) }
            val twice = (entries + queries).groupBy { it.key }.filterValues { it.size > 1 }.keys
            require(twice.isEmpty()) { "golden.json uses a key twice: ${twice.joinToString()}" }
            return GoldenTexts(entries, queries)
        }
    }
}
