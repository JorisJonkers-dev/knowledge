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
class EmbeddingRepositoryIntegrationTest
    @Autowired
    constructor(
        private val noteRepository: NoteRepository,
        private val embeddingRepository: EmbeddingRepository,
        private val dsl: DSLContext,
    ) : IntegrationTestBase() {
        @Test
        fun recallvectorOrdersRowsByCosineSimilarity() {
            // Three notes; the embedding column is 1024-dim per the V9 schema,
            // so we pad the meaningful prefix with zeros. The vectors differ
            // only on the first three dimensions, which is enough to drive
            // distinct cosine distances.
            seedEmbedded("near", padded(AXIS_MATCH, ZERO, ZERO))
            seedEmbedded("middle", padded(MIDDLE_COMPONENT, MIDDLE_COMPONENT, ZERO))
            seedEmbedded("far", padded(ZERO, ZERO, AXIS_MATCH))

            val query = padded(AXIS_MATCH, ZERO, ZERO)
            val hits = embeddingRepository.recallVector(query, scope = "personal", limit = 3)

            assertThat(hits.map { it.title }).containsExactly("near", "middle", "far")
            // Score is `1 - cosine_distance`, so the perfect-match row scores 1.
            assertThat(hits.first().score).isCloseTo(PERFECT_SCORE, within(SCORE_EPSILON))
        }

        @Test
        fun recallvectorSkipsRowsWhoseEmbeddingIsNULL() {
            // Real-world rows during the rollout window have no embedding
            // yet; the vector leg must treat them as invisible so the FTS
            // leg owns those hits via RRF.
            seedEmbedded("embedded", padded(1.0f, 0.0f, 0.0f))
            seedUnembedded("not-yet")

            val hits = embeddingRepository.recallVector(padded(1.0f, 0.0f, 0.0f), scope = "personal", limit = 10)

            assertThat(hits.map { it.title }).containsExactly("embedded")
        }

        @Test
        fun recallvectorScopeNullAppliesTheCuratedDefaultFilter() {
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
            private const val ZERO = 0.0f
            private const val AXIS_MATCH = 1.0f
            private const val MIDDLE_COMPONENT = 0.6f
            private const val PERFECT_SCORE = 1.0
            private const val SCORE_EPSILON = 1e-6
        }
    }
