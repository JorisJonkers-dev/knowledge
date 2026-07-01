package com.jorisjonkers.personalstack.knowledge.discovery

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
 * Exercises the SQL behind `knowledge.suggest_topic` and
 * `knowledge.find_duplicates` against a real pgvector instance.
 * Hand-crafted embeddings live in known scopes so the centroid +
 * threshold logic surfaces unambiguously.
 */
class SuggestionsRepositoryIntegrationTest
    @Autowired
    constructor(
        private val noteRepository: NoteRepository,
        private val embeddingRepository: EmbeddingRepository,
        private val dsl: DSLContext,
    ) : IntegrationTestBase() {
        @Test
        fun suggesttopicRanksCloserTopicsFirstByTheirCentroid() {
            // topic:kotlin = three notes clustered around the [1, 0, …] axis;
            // topic:postgres = three notes around the [0, 1, …] axis.
            seedEmbedded("k1", padded(KOTLIN_X, ZERO), scope = "topic:kotlin")
            seedEmbedded("k2", padded(KOTLIN_NEAR_X, KOTLIN_NEAR_Y), scope = "topic:kotlin")
            seedEmbedded("k3", padded(KOTLIN_MID_X, KOTLIN_MID_Y), scope = "topic:kotlin")
            seedEmbedded("p1", padded(ZERO, POSTGRES_Y), scope = "topic:postgres")
            seedEmbedded("p2", padded(POSTGRES_NEAR_X, POSTGRES_NEAR_Y), scope = "topic:postgres")

            val query = padded(KOTLIN_X, ZERO)
            val suggestions = embeddingRepository.suggestTopic(query, limit = 5)

            assertThat(suggestions.map { it.slug }).containsExactly("kotlin", "postgres")
            // Closer topic scores higher than the further one.
            assertThat(suggestions.first().score).isGreaterThan(suggestions.last().score)
        }

        @Test
        fun findduplicatesReturnsRowsAboveTheCosineThresholdAndExcludesTheSource() {
            val origin = seedEmbedded("source", padded(KOTLIN_X, ZERO), scope = "personal")
            seedEmbedded("near", padded(DUPE_NEAR_X, DUPE_NEAR_Y), scope = "personal")
            seedEmbedded("far", padded(ZERO, POSTGRES_Y), scope = "personal")

            // threshold=0.85 cosine → distance ≤ 0.15. The `near` row should
            // survive; `far` (orthogonal) and `origin` (excluded by id) drop.
            val dupes =
                embeddingRepository.findDuplicates(
                    queryEmbedding = padded(KOTLIN_X, ZERO),
                    threshold = DUPLICATE_THRESHOLD,
                    limit = 10,
                    excludeId = origin.id,
                )

            assertThat(dupes.map { it.title }).containsExactly("near")
            assertThat(dupes.first().score).isGreaterThanOrEqualTo(DUPLICATE_THRESHOLD)
        }

        @Test
        fun embeddingforRoundTripsAPersistedVector() {
            val note = seedEmbedded("round-trip", padded(HALF, HALF), scope = "personal")
            val vector = embeddingRepository.embeddingFor(note.id) ?: error("expected non-null")

            assertThat(vector.size).isEqualTo(EMBEDDING_DIM)
            assertThat(vector[0]).isCloseTo(HALF, within(EPSILON))
            assertThat(vector[1]).isCloseTo(HALF, within(EPSILON))
        }

        @Test
        fun embeddingforReturnsNullWhenTheRowHasNoEmbedding() {
            val note =
                noteRepository.create(
                    KbNote(
                        id = Ulid.generate(),
                        type = KbNoteType.LESSON,
                        scope = "personal",
                        source = "test",
                        capturedAt = Instant.now(),
                        sessionId = null,
                        confidence = 0.4,
                        title = "unembedded",
                        body = "no vector here",
                        vaultPath = "personal/lesson/unembedded.md",
                        vaultCommit = null,
                        tags = emptySet(),
                    ),
                )

            assertThat(embeddingRepository.embeddingFor(note.id)).isNull()
        }

        private fun padded(vararg head: Float): FloatArray {
            val out = FloatArray(EMBEDDING_DIM)
            head.forEachIndexed { i, v -> out[i] = v }
            return out
        }

        private fun seedEmbedded(
            title: String,
            embedding: FloatArray,
            scope: String,
        ): KbNote {
            val note =
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
            val literal = embedding.joinToString(",", "[", "]") { it.toString() }
            dsl.execute(
                "UPDATE kb_notes SET embedding = ?::vector, embedding_model = 'test', embedded_at = NOW() WHERE id = ?",
                literal,
                note.id,
            )
            return note
        }

        companion object {
            private const val EMBEDDING_DIM = 1024
            private const val ZERO = 0.0f
            private const val HALF = 0.5f
            private const val KOTLIN_X = 1.0f
            private const val KOTLIN_NEAR_X = 0.9f
            private const val KOTLIN_NEAR_Y = 0.1f
            private const val KOTLIN_MID_X = 0.95f
            private const val KOTLIN_MID_Y = 0.05f
            private const val POSTGRES_Y = 1.0f
            private const val POSTGRES_NEAR_X = 0.1f
            private const val POSTGRES_NEAR_Y = 0.9f
            private const val DUPE_NEAR_X = 0.95f
            private const val DUPE_NEAR_Y = 0.31f
            private const val DUPLICATE_THRESHOLD = 0.85
            private const val EPSILON = 1e-3f
        }
    }
