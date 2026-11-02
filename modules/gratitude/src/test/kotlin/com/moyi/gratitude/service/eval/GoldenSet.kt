package com.moyi.gratitude.service.eval

import com.moyi.ai.api.EmbeddingSpace
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.LocalDate

/**
 * `eval/golden.json` (Task 9, spec §4): synthetic bonds, their entries, and queries labelled with
 * the entries relevant to them. Each entry has a date of its own in its bond, so a day the API
 * answers with is exactly one golden entry ([GoldenSetTest] holds it to that).
 *
 * Texts are never printed: [Entry] and [Query] print their keys.
 */
internal class GoldenSet(
    val bonds: List<Bond>,
    val queries: List<Query>,
) {
    class Bond(
        val key: String,
        val entries: List<Entry>,
    ) {
        /** The bond's two writers, in the order they first write. */
        val authors: List<String> get() = entries.map { it.author }.distinct()
    }

    class Entry(
        val key: String,
        val author: String,
        val date: LocalDate,
        val text: String,
    ) {
        override fun toString() = "Entry($key)"
    }

    class Query(
        val key: String,
        val bond: String,
        val text: String,
        val relevant: Set<String>,
    ) {
        override fun toString() = "Query($key)"
    }

    companion object {
        private val json = JsonMapper()

        /** The eval's directory, as the test classpath holds it. */
        val directory: File = File(checkNotNull(GoldenSet::class.java.getResource("/eval/golden.json")).toURI()).parentFile

        fun load(): GoldenSet = parse(directory.resolve("golden.json").readText())

        fun parse(text: String): GoldenSet {
            val tree = json.readTree(text)
            return GoldenSet(
                tree["bonds"].toList().map { bond ->
                    Bond(
                        bond["key"].asString(),
                        bond["entries"].toList().map {
                            Entry(
                                it["key"].asString(),
                                it["author"].asString(),
                                LocalDate.parse(it["date"].asString()),
                                it["text"].asString(),
                            )
                        },
                    )
                },
                tree["queries"].toList().map { query ->
                    Query(
                        query["key"].asString(),
                        query["bond"].asString(),
                        query["q"].asString(),
                        query["relevant"].toList().map(JsonNode::asString).toSet(),
                    )
                },
            )
        }

        /**
         * Each space's stored vectors, by golden key, from the `vectors-*.json` files `tools/embedding-eval`
         * writes. The space is read from the file's `model`, not its name.
         */
        fun storedVectors(): Map<EmbeddingSpace, Map<String, FloatArray>> =
            directory
                .listFiles { file -> file.name.startsWith("vectors-") && file.name.endsWith(".json") }
                .orEmpty()
                .associate { file ->
                    val tree = json.readTree(file.readText())
                    EmbeddingSpace.valueOf(tree["model"].asString()) to
                        tree["vectors"].properties().associate { (key, vector) ->
                            key to vector.toList().map { it.floatValue() }.toFloatArray()
                        }
                }
    }
}
