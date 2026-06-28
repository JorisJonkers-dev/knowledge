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
class SuggestionsRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var noteRepository: NoteRepository

    @Autowired
    private lateinit var embeddingRepository: EmbeddingRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun `suggestTopic ranks closer topics first by their centroid`() {
        // topic:kotlin = three notes clustered around the [1, 0, …] axis;
        // topic:postgres = three notes around the [0, 1, …] axis.
        seedEmbedded("k1", padded(1.0f, 0.0f), scope = "topic:kotlin")
        seedEmbedded("k2", padded(0.9f, 0.1f), scope = "topic:kotlin")
        seedEmbedded("k3", padded(0.95f, 0.05f), scope = "topic:kotlin")
        seedEmbedded("p1", padded(0.0f, 1.0f), scope = "topic:postgres")
        seedEmbedded("p2", padded(0.1f, 0.9f), scope = "topic:postgres")

        val query = padded(1.0f, 0.0f)
        val suggestions = embeddingRepository.suggestTopic(query, limit = 5)

        assertThat(suggestions.map { it.slug }).containsExactly("kotlin", "postgres")
        // Closer topic scores higher than the further one.
        assertThat(suggestions.first().score).isGreaterThan(suggestions.last().score)
    }

    @Test
    fun `findDuplicates returns rows above the cosine threshold and excludes the source`() {
        val origin = seedEmbedded("source", padded(1.0f, 0.0f), scope = "personal")
        seedEmbedded("near", padded(0.95f, 0.31f), scope = "personal")
        seedEmbedded("far", padded(0.0f, 1.0f), scope = "personal")

        // threshold=0.85 cosine → distance ≤ 0.15. The `near` row should
        // survive; `far` (orthogonal) and `origin` (excluded by id) drop.
        val dupes =
            embeddingRepository.findDuplicates(
                queryEmbedding = padded(1.0f, 0.0f),
                threshold = 0.85,
                limit = 10,
                excludeId = origin.id,
            )

        assertThat(dupes.map { it.title }).containsExactly("near")
        assertThat(dupes.first().score).isGreaterThanOrEqualTo(0.85)
    }

    @Test
    fun `embeddingFor round-trips a persisted vector`() {
        val note = seedEmbedded("round-trip", padded(0.5f, 0.5f), scope = "personal")
        val vector = embeddingRepository.embeddingFor(note.id) ?: error("expected non-null")

        assertThat(vector.size).isEqualTo(EMBEDDING_DIM)
        assertThat(vector[0]).isCloseTo(0.5f, within(1e-3f))
        assertThat(vector[1]).isCloseTo(0.5f, within(1e-3f))
    }

    @Test
    fun `embeddingFor returns null when the row has no embedding`() {
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
    }
}
