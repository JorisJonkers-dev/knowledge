package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.repo.EmbeddingRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * End-to-end check that the V9 pgvector migration applied, the HNSW
 * index is queryable, and [EmbeddingRepository.recallVector] returns
 * cosine-ordered rows.
 *
 * We embed three tiny vectors by hand so the test doesn't need a
 * running Ollama — the JDBC path is the only thing under test. PR-2
 * adds the curator-side embed-on-promote integration test.
 */
class EmbeddingRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var noteRepository: NoteRepository

    @Autowired
    private lateinit var embeddingRepository: EmbeddingRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `recallVector orders rows by cosine similarity`() {
        // Three notes; the embedding column is 1024-dim per the V9 schema,
        // so we pad the meaningful prefix with zeros. The vectors differ
        // only on the first three dimensions, which is enough to drive
        // distinct cosine distances.
        seedEmbedded("near", padded(1.0f, 0.0f, 0.0f))
        seedEmbedded("middle", padded(0.6f, 0.6f, 0.0f))
        seedEmbedded("far", padded(0.0f, 0.0f, 1.0f))

        val query = padded(1.0f, 0.0f, 0.0f)
        val hits = embeddingRepository.recallVector(query, scope = "personal", limit = 3)

        assertThat(hits.map { it.title }).containsExactly("near", "middle", "far")
        // Score is `1 - cosine_distance`, so the perfect-match row scores 1.
        assertThat(hits.first().score).isCloseTo(1.0, within(1e-6))
    }

    @Test
    fun `recallVector skips rows whose embedding is NULL`() {
        // Real-world rows during the rollout window have no embedding
        // yet; the vector leg must treat them as invisible so the FTS
        // leg owns those hits via RRF.
        seedEmbedded("embedded", padded(1.0f, 0.0f, 0.0f))
        seedUnembedded("not-yet")

        val hits = embeddingRepository.recallVector(padded(1.0f, 0.0f, 0.0f), scope = "personal", limit = 10)

        assertThat(hits.map { it.title }).containsExactly("embedded")
    }

    @Test
    fun `recallVector scope=null applies the curated default filter`() {
        seedEmbedded("public", padded(1.0f, 0.0f, 0.0f), scope = "personal")
        seedEmbedded("inbox", padded(1.0f, 0.0f, 0.0f), scope = "_inbox")
        seedEmbedded("private-agent", padded(1.0f, 0.0f, 0.0f), scope = "agent:claude")
        seedEmbedded("shared-agent", padded(1.0f, 0.0f, 0.0f), scope = "agent:_shared")

        val hits = embeddingRepository.recallVector(padded(1.0f, 0.0f, 0.0f), scope = null, limit = 10)

        // _inbox and agent:<other> filtered out; agent:_shared stays.
        assertThat(hits.map { it.title }).containsExactlyInAnyOrder("public", "shared-agent")
    }

    private fun padded(vararg head: Float): FloatArray {
        val out = FloatArray(EMBEDDING_DIM)
        head.forEachIndexed { i, v -> out[i] = v }
        return out
    }

    private fun seedEmbedded(
        title: String,
        embedding: FloatArray,
        scope: String = "personal",
    ): KbNote {
        val note = seedRaw(title = title, scope = scope)
        val literal = embedding.joinToString(",", "[", "]") { it.toString() }
        dsl.execute(
            "UPDATE kb_notes SET embedding = ?::vector, embedding_model = 'test', embedded_at = NOW() WHERE id = ?",
            literal,
            note.id,
        )
        return note
    }

    private fun seedUnembedded(title: String): KbNote = seedRaw(title = title)

    private fun seedRaw(
        title: String,
        scope: String = "personal",
    ): KbNote =
        noteRepository.create(
            KbNote(
                id = Ulid.generate(),
                type = KbNoteType.LESSON,
                scope = scope,
                source = "test",
                capturedAt = Instant.now(),
                sessionId = null,
                confidence = 0.4,
                title = title,
                body = "body of $title",
                vaultPath = "$scope/lesson/${title.replace(' ', '-')}.md",
                vaultCommit = null,
                tags = emptySet(),
            ),
        )

    companion object {
        private const val EMBEDDING_DIM = 1024
    }
}
