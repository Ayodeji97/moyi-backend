package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingSpace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText

internal class VectorsFileTest {
    @Test
    fun `each space has its own file, named for its provider and size`() {
        VectorsFile.nameOf(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL) shouldBe "vectors-openai-1536.json"
        VectorsFile.nameOf(EmbeddingSpace.VOYAGE_3_5) shouldBe "vectors-voyage-1024.json"
    }

    @Test
    fun `the file holds the model, when it was made, and every vector by key`(
        @TempDir dir: Path,
    ) {
        val written =
            VectorsFile.write(
                dir,
                EmbeddingSpace.VOYAGE_3_5,
                mapOf("e1" to floatArrayOf(0.25f, -1f), "q1" to floatArrayOf(0.5f, 0.125f)),
                Instant.parse("2026-11-02T10:00:00Z"),
            )

        written shouldBe dir.resolve("vectors-voyage-1024.json")
        val tree = JsonMapper().readTree(written.readText())
        tree["model"].asString() shouldBe "VOYAGE_3_5"
        tree["generatedAt"].asString() shouldBe "2026-11-02T10:00:00Z"
        tree["vectors"]["e1"].toList().map { it.floatValue() } shouldBe listOf(0.25f, -1f)
        tree["vectors"]["q1"].toList().map { it.floatValue() } shouldBe listOf(0.5f, 0.125f)
    }
}
