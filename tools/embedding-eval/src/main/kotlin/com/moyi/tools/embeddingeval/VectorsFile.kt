package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingSpace
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText

/**
 * `vectors-<provider>-<dimensions>.json`, beside `golden.json`: `model` (the [EmbeddingSpace]'s
 * name, which `RetrievalEvalTest` reads the space from, not the file name), `generatedAt`, and
 * `vectors`, every entry's and query's vector by its golden key.
 */
internal object VectorsFile {
    fun nameOf(space: EmbeddingSpace): String =
        when (space) {
            EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL -> "vectors-openai-1536.json"
            EmbeddingSpace.VOYAGE_3_5 -> "vectors-voyage-1024.json"
        }

    fun write(
        directory: Path,
        space: EmbeddingSpace,
        vectors: Map<String, FloatArray>,
        generatedAt: Instant,
    ): Path {
        val file = directory.resolve(nameOf(space))
        val document =
            linkedMapOf(
                "model" to space.name,
                "generatedAt" to generatedAt.toString(),
                "vectors" to vectors,
            )
        file.writeText(JsonMapper().writeValueAsString(document) + "\n")
        return file
    }
}
