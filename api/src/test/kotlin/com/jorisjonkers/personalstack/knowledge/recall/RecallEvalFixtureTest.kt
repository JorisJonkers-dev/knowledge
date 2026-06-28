package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.repo.EmbeddingRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.RecallRepository
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class RecallEvalFixtureTest {
    private val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `historical mistake recall cases stay within rank budget`() {
        val suite = loadSuite()

        val scores =
            suite.cases.map { evalCase ->
                val hits = runRecall(evalCase)
                val score = RecallEvalScorer.score(evalCase, hits)

                assertThat(score.hitAtK)
                    .describedAs("${evalCase.name} should retrieve ${evalCase.expectedIds} by rank ${evalCase.maxRank}")
                    .isTrue()
                assertThat(score.missingTerms)
                    .describedAs("${evalCase.name} should surface trigger terms in the top ${evalCase.maxRank} hits")
                    .isEmpty()

                score
            }

        assertThat(scores).isNotEmpty
        assertThat(scores.map { it.reciprocalRank }.average()).isGreaterThanOrEqualTo(1.0 / 3.0)
    }

    @Test
    fun `historical mistake fixture cases are well formed`() {
        val suite = loadSuite()

        assertThat(suite.version).isEqualTo(1)
        assertThat(suite.cases).isNotEmpty
        assertThat(suite.cases.map { it.name }).doesNotHaveDuplicates()
        suite.cases.forEach { evalCase ->
            assertThat(evalCase.query).isNotBlank
            assertThat(evalCase.scope).startsWith("project:")
            assertThat(evalCase.limit).isBetween(1, 10)
            assertThat(evalCase.maxRank).isBetween(1, evalCase.limit)
            assertThat(evalCase.expectedIds).isNotEmpty
            assertThat(evalCase.expectedTerms).isNotEmpty
            assertThat(evalCase.seed.fts + evalCase.seed.vector)
                .extracting<String> { it.id }
                .containsAnyElementsOf(evalCase.expectedIds)
        }
    }

    private fun loadSuite(): RecallEvalSuite {
        val resource =
            javaClass.classLoader.getResourceAsStream("recall-eval/historical-mistakes.json")
                ?: error("recall-eval/historical-mistakes.json not found")
        resource.use {
            return mapper.readValue(it, RecallEvalSuite::class.java)
        }
    }

    private fun runRecall(evalCase: RecallEvalCase): List<RecallHit> {
        val noteRepository = mockk<NoteRepository>(relaxed = true)
        val recallRepository = mockk<RecallRepository>()
        val embeddingRepository = mockk<EmbeddingRepository>()
        val queryEmbedder = mockk<QueryEmbedder>()
        val graphRetriever = mockk<GraphRetriever>()
        val reranker = mockk<Reranker>()
        val mode = RecallMode.fromWire(evalCase.mode) ?: error("Unknown recall mode ${evalCase.mode}")

        every { recallRepository.recall(evalCase.query, evalCase.scope, any()) } returns
            evalCase.seed.fts.map { it.toHit(evalCase.scope) }
        every { queryEmbedder.embed(evalCase.query) } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        every { embeddingRepository.recallVector(any(), evalCase.scope, any()) } returns
            evalCase.seed.vector.map { it.toHit(evalCase.scope) }
        every { graphRetriever.retrieve(any(), any(), any()) } returns emptyList()
        every { reranker.rerank(evalCase.query, any(), evalCase.limit) } answers {
            val input = secondArg<List<RecallHit>>()
            if (evalCase.seed.rerankedIds.isEmpty()) {
                input.take(evalCase.limit)
            } else {
                evalCase.seed.rerankedIds.mapNotNull { id -> input.firstOrNull { it.id == id } }
            }
        }

        val service =
            RecallService(
                noteRepository = noteRepository,
                recallRepository = recallRepository,
                embeddingRepository = embeddingRepository,
                queryEmbedder = queryEmbedder,
                graphRetriever = graphRetriever,
                reranker = reranker,
                observationRegistry = ObservationRegistry.NOOP,
                defaultModeWire = evalCase.mode,
                rrfK = 60,
            )

        return service.recall(evalCase.query, evalCase.scope, evalCase.limit, mode)
    }
}

private data class RecallEvalSuite(
    val version: Int,
    val description: String,
    val cases: List<RecallEvalCase>,
)

private data class RecallEvalCase(
    val name: String,
    val query: String,
    val scope: String,
    val mode: String,
    val limit: Int,
    val maxRank: Int,
    val expectedIds: List<String>,
    val expectedTerms: List<String>,
    val seed: RecallEvalSeed,
)

private data class RecallEvalSeed(
    val fts: List<RecallEvalSeedHit>,
    val vector: List<RecallEvalSeedHit> = emptyList(),
    val rerankedIds: List<String> = emptyList(),
)

private data class RecallEvalSeedHit(
    val id: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val tags: Set<String> = emptySet(),
) {
    fun toHit(scope: String): RecallHit =
        RecallHit(
            id = id,
            type = "lesson",
            scope = scope,
            title = title,
            snippet = snippet,
            score = score,
            tags = tags,
        )
}

private data class RecallEvalScore(
    val rank: Int?,
    val reciprocalRank: Double,
    val missingTerms: List<String>,
) {
    val hitAtK: Boolean = rank != null
}

private object RecallEvalScorer {
    fun score(
        evalCase: RecallEvalCase,
        hits: List<RecallHit>,
    ): RecallEvalScore {
        val rank =
            hits
                .withIndex()
                .firstOrNull { (_, hit) -> hit.id in evalCase.expectedIds }
                ?.let { it.index + 1 }
                ?.takeIf { it <= evalCase.maxRank }
        val topText =
            hits
                .take(evalCase.maxRank)
                .joinToString(separator = "\n") { "${it.title}\n${it.snippet}\n${it.tags.joinToString(" ")}" }
                .lowercase()
        val missingTerms =
            evalCase.expectedTerms
                .map { it.lowercase() }
                .filterNot { topText.contains(it) }

        return RecallEvalScore(
            rank = rank,
            reciprocalRank = rank?.let { 1.0 / it } ?: 0.0,
            missingTerms = missingTerms,
        )
    }
}
