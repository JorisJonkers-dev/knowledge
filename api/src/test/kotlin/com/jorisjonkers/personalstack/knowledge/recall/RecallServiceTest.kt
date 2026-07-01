package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.repo.EmbeddingRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.RecallRepository
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test

class RecallServiceTest {
    private val noteRepository = mockk<NoteRepository>(relaxed = true)
    private val recallRepository = mockk<RecallRepository>()
    private val embeddingRepository = mockk<EmbeddingRepository>()
    private val queryEmbedder = mockk<QueryEmbedder>()
    private val graphRetriever = mockk<GraphRetriever>()
    private val reranker = mockk<Reranker>()

    private fun service(defaultModeWire: String = "fast"): RecallService {
        every { graphRetriever.retrieve(any(), any(), any()) } returns emptyList()
        return RecallService(
            stores = RecallStores(noteRepository, recallRepository, embeddingRepository),
            enhancers = RecallEnhancers(queryEmbedder, graphRetriever, reranker),
            observationRegistry = ObservationRegistry.NOOP,
            defaultModeWire = defaultModeWire,
            rrfK = 60,
        )
    }

    private fun hit(
        id: String,
        score: Double = 0.4,
    ) = RecallHit(
        id = id,
        type = "lesson",
        scope = "personal",
        title = "t-$id",
        snippet = "s",
        score = score,
    )

    @Test
    fun `fast mode delegates to the FTS repository only`() {
        every { recallRepository.recall("rockets", "personal", 5) } returns listOf(hit("01HXYZ"))

        val hits = service().recall("rockets", "personal", 5, RecallMode.FAST)

        assertThat(hits).extracting<String> { it.id }.containsExactly("01HXYZ")
        verify(exactly = 1) { recallRepository.recall("rockets", "personal", 5) }
        verify(exactly = 0) { queryEmbedder.embed(any()) }
    }

    @Test
    fun `default mode is read from config when no mode argument is supplied`() {
        every { recallRepository.recall("rockets", "personal", 5) } returns listOf(hit("01HXYZ"))

        // No `mode` argument — service falls back to its configured default.
        service(defaultModeWire = "fast").recall("rockets", "personal", 5)

        verify(exactly = 1) { recallRepository.recall("rockets", "personal", 5) }
        verify(exactly = 0) { queryEmbedder.embed(any()) }
    }

    @Test
    fun `hybrid mode runs both legs and RRF-fuses the result`() {
        // The service over-fetches by 3× from each leg to give RRF tail rows
        // to fuse — that constant is internal so the test pins it via the
        // requested limit pass-through rather than asserting the multiplier.
        every { recallRepository.recall("rockets", "personal", 15) } returns
            listOf(hit("ID-A"), hit("ID-B"), hit("ID-C"))
        every { queryEmbedder.embed("rockets") } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        every { embeddingRepository.recallVector(any(), "personal", 15) } returns
            listOf(hit("ID-B"), hit("ID-D"))

        val hits = service().recall("rockets", "personal", 5, RecallMode.HYBRID)

        // ID-B is in both legs → wins RRF; the other survivors come in by
        // single-leg rank (A and C from FTS, D from vector).
        assertThat(hits.first().id).isEqualTo("ID-B")
        assertThat(hits.map { it.id }).containsExactlyInAnyOrder("ID-B", "ID-A", "ID-C", "ID-D")
    }

    @Test
    fun `hybrid mode degrades gracefully when the embedder throws`() {
        every { recallRepository.recall("rockets", "personal", 15) } returns
            listOf(hit("ID-A"), hit("ID-B"))
        every { queryEmbedder.embed("rockets") } throws
            QueryEmbeddingException("ollama down", IllegalStateException("ollama down"))

        val hits = service().recall("rockets", "personal", 5, RecallMode.HYBRID)

        // Vector leg dropped; FTS hits flow through (after the single-leg
        // RRF fuse, which is a no-op rank-preserving transform).
        assertThat(hits.map { it.id }).containsExactly("ID-A", "ID-B")
        verify(exactly = 1) { queryEmbedder.embed("rockets") }
        verify(exactly = 0) { embeddingRepository.recallVector(any(), any(), any()) }
    }

    @Test
    fun `deep mode reranks the hybrid output and truncates to limit`() {
        // mode=deep over-fetches by an additional factor on top of the
        // 3x hybrid over-fetch so the reranker has tail rows. Per the
        // RERANK_OVERFETCH_FACTOR = 2 and limit=5, the hybrid call
        // sees limit=10 internally — which over-fetches by 3 again
        // for the FTS leg → 30.
        every { recallRepository.recall("rockets", "personal", 30) } returns
            listOf(hit("ID-A"), hit("ID-B"), hit("ID-C"))
        every { queryEmbedder.embed("rockets") } returns floatArrayOf(0.1f, 0.2f)
        every { embeddingRepository.recallVector(any(), "personal", 30) } returns
            listOf(hit("ID-B"), hit("ID-D"))
        // Reranker reorders: D leaps to top, A drops to last.
        every { reranker.rerank("rockets", any(), 5) } answers {
            val input = secondArg<List<RecallHit>>()
            listOf("ID-D", "ID-B", "ID-A", "ID-C").mapNotNull { id ->
                input.firstOrNull { it.id == id }
            }
        }

        val hits = service().recall("rockets", "personal", 5, RecallMode.DEEP)

        assertThat(hits.map { it.id }).containsExactly("ID-D", "ID-B", "ID-A", "ID-C")
        verify(exactly = 1) { reranker.rerank("rockets", any(), 5) }
    }

    @Test
    fun `deep mode fuses graph candidates before reranking`() {
        val recallService = service()
        every { recallRepository.recall("rockets", "personal", 30) } returns
            listOf(hit("ID-A"), hit("ID-B"))
        every { queryEmbedder.embed("rockets") } returns floatArrayOf(0.1f, 0.2f)
        every { embeddingRepository.recallVector(any(), "personal", 30) } returns listOf(hit("ID-B"))
        every { graphRetriever.retrieve("rockets", "personal", 5) } returns
            listOf(hit("GRAPH-A", score = 0.55))
        every { reranker.rerank("rockets", any(), 5) } answers {
            val input = secondArg<List<RecallHit>>()
            assertThat(input.map { hit -> hit.id }).contains("GRAPH-A")
            input.sortedByDescending { hit -> if (hit.id == "GRAPH-A") 1 else 0 }
        }

        val hits = recallService.recall("rockets", "personal", 5, RecallMode.DEEP)

        assertThat(hits.first().id).isEqualTo("GRAPH-A")
    }

    @Test
    fun `deep mode skips the reranker when only one candidate survives`() {
        every { recallRepository.recall("rockets", "personal", 30) } returns listOf(hit("ID-A"))
        every { queryEmbedder.embed("rockets") } returns floatArrayOf(0.1f)
        every { embeddingRepository.recallVector(any(), "personal", 30) } returns emptyList()

        val hits = service().recall("rockets", "personal", 5, RecallMode.DEEP)

        assertThat(hits.map { it.id }).containsExactly("ID-A")
        verify(exactly = 0) { reranker.rerank(any(), any(), any()) }
    }

    @Test
    fun `getNote delegates by id`() {
        every { noteRepository.findById("01HXYZ") } returns null
        assertThat(service().getNote("01HXYZ")).isNull()
        verify(exactly = 1) { noteRepository.findById("01HXYZ") }
    }

    @Test
    fun `listRecent passes scope, type, and limit through unchanged`() {
        every { noteRepository.listRecent("work", KbNoteType.DECISION, 7) } returns emptyList()
        service().listRecent("work", KbNoteType.DECISION, 7)
        verify(exactly = 1) { noteRepository.listRecent("work", KbNoteType.DECISION, 7) }
    }

    @Test
    fun `recall bumps usage stats for every returned hit`() {
        every { recallRepository.recall("rockets", "personal", 5) } returns
            listOf(hit("ID-A"), hit("ID-B"))

        service().recall("rockets", "personal", 5, RecallMode.FAST)

        verify(exactly = 1) { noteRepository.bumpRecallStats(listOf("ID-A", "ID-B")) }
    }

    @Test
    fun `recall swallows a usage-stats bump failure rather than 500ing`() {
        every { recallRepository.recall("rockets", "personal", 5) } returns listOf(hit("ID-A"))
        every { noteRepository.bumpRecallStats(any()) } throws DataAccessException("db down")

        val hits = service().recall("rockets", "personal", 5, RecallMode.FAST)

        assertThat(hits.map { it.id }).containsExactly("ID-A")
    }

    @Test
    fun `findConflicts delegates by id`() {
        every { noteRepository.findConflicts("01HXYZ") } returns emptyList()
        service().findConflicts("01HXYZ")
        verify(exactly = 1) { noteRepository.findConflicts("01HXYZ") }
    }
}
