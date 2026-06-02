package com.jorisjonkers.personalstack.knowledge.discovery

import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.recall.QueryEmbedder
import com.jorisjonkers.personalstack.knowledge.repo.DiscoveryRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Focused tests for the clustering math. Each tag is given a hand-
 * picked embedding so the cosine math comes out where the test can
 * assert on cluster membership without depending on a live embedder.
 */
class TagClusterServiceTest {
    private val discoveryRepository = mockk<DiscoveryRepository>()

    private fun service(embeddings: Map<String, FloatArray>): TagClusterService {
        val embedder = QueryEmbedder { text -> embeddings[text] ?: error("no embedding for $text") }
        return TagClusterService(discoveryRepository, embedder)
    }

    private fun tag(
        name: String,
        count: Int = 1,
    ) = TagSummary(tag = name, count = count, lastUsedAt = Instant.now())

    @Test
    fun `clusters tags whose pairwise cosine is above the threshold`() {
        every { discoveryRepository.listTags(null, any()) } returns
            listOf(
                tag("kotlin", count = 10),
                tag("Kotlin", count = 3),
                tag("kt", count = 1),
                tag("postgres", count = 5),
            )
        val embeddings =
            mapOf(
                "kotlin" to floatArrayOf(1.0f, 0.0f),
                "Kotlin" to floatArrayOf(0.99f, 0.01f),
                "kt" to floatArrayOf(0.98f, 0.02f),
                "postgres" to floatArrayOf(0.0f, 1.0f),
            )

        val clusters = service(embeddings).listTagCandidates(minCount = 1, threshold = 0.95, maxTags = 100)

        assertThat(clusters).hasSize(1)
        val cluster = clusters.first()
        assertThat(cluster.suggestedCanonical).isEqualTo("kotlin")
        assertThat(cluster.members.map { it.tag }).containsExactlyInAnyOrder("kotlin", "Kotlin", "kt")
        // Single-link clustering on the kotlin-axis trio; pairwise
        // similarity is well above 0.95.
        assertThat(cluster.averageSimilarity).isGreaterThan(0.95)
    }

    @Test
    fun `respects the min_count filter and produces no cluster when fewer than two tags qualify`() {
        every { discoveryRepository.listTags(null, any()) } returns
            listOf(
                tag("rare", count = 1),
                tag("alsoRare", count = 1),
            )

        // The two embeddings are similar but min_count=5 excludes both.
        val embeddings =
            mapOf(
                "rare" to floatArrayOf(1.0f, 0.0f),
                "alsoRare" to floatArrayOf(0.99f, 0.01f),
            )
        val clusters = service(embeddings).listTagCandidates(minCount = 5, threshold = 0.5, maxTags = 100)

        assertThat(clusters).isEmpty()
    }

    @Test
    fun `degrades to empty list when the embedder fails`() {
        every { discoveryRepository.listTags(null, any()) } returns
            listOf(tag("kotlin", count = 10), tag("postgres", count = 5))
        val angryEmbedder = QueryEmbedder { _ -> throw IllegalStateException("ollama down") }
        val svc = TagClusterService(discoveryRepository, angryEmbedder)

        val clusters = svc.listTagCandidates(minCount = 1, threshold = 0.85, maxTags = 100)

        assertThat(clusters).isEmpty()
    }

    @Test
    fun `picks the highest-count member as the suggested canonical`() {
        every { discoveryRepository.listTags(null, any()) } returns
            listOf(
                tag("CI", count = 2),
                tag("ci", count = 12),
                tag("CICD", count = 1),
            )
        val embeddings =
            mapOf(
                "CI" to floatArrayOf(1.0f, 0.0f),
                "ci" to floatArrayOf(0.99f, 0.01f),
                "CICD" to floatArrayOf(0.97f, 0.03f),
            )

        val clusters = service(embeddings).listTagCandidates(minCount = 1, threshold = 0.9, maxTags = 100)

        assertThat(clusters).hasSize(1)
        assertThat(clusters.first().suggestedCanonical).isEqualTo("ci")
    }

    @Test
    fun `produces no cluster when no two tags are similar enough`() {
        every { discoveryRepository.listTags(null, any()) } returns
            listOf(
                tag("kotlin", count = 5),
                tag("postgres", count = 5),
                tag("kubernetes", count = 5),
            )
        val embeddings =
            mapOf(
                "kotlin" to floatArrayOf(1.0f, 0.0f, 0.0f),
                "postgres" to floatArrayOf(0.0f, 1.0f, 0.0f),
                "kubernetes" to floatArrayOf(0.0f, 0.0f, 1.0f),
            )

        val clusters = service(embeddings).listTagCandidates(minCount = 1, threshold = 0.9, maxTags = 100)

        assertThat(clusters).isEmpty()
    }
}
