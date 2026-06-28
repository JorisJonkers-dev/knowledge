package com.jorisjonkers.personalstack.knowledge.discovery

import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateCluster
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateMember
import com.jorisjonkers.personalstack.knowledge.recall.QueryEmbedder
import com.jorisjonkers.personalstack.knowledge.repo.DiscoveryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.sqrt

/**
 * Surfaces near-duplicate tag clusters for the knowledge-ui hygiene
 * page. Embeds each distinct tag via [QueryEmbedder] on demand and
 * clusters by pairwise cosine similarity at or above a caller-set
 * threshold (default 0.85).
 *
 * Designed for the ≤200-tag scale this KB sees today: O(N²) pairwise
 * comparison stays sub-second once embeddings are in hand. Embedding
 * runs sequentially against Ollama — the wall time is dominated by
 * model latency, not by the clustering. A cache table
 * (`kb_tag_embeddings`) lands in a follow-up if telemetry shows the
 * re-embed cost matters; today the operator-facing call lives behind
 * the chat panel where a multi-second spinner is acceptable.
 *
 * Failure-by-fallback: if Ollama is unreachable, the tool returns an
 * empty cluster list — same posture `DiscoveryService.suggestTopic`
 * takes. The UI shows "no candidates" rather than 500ing.
 */
@Service
class TagClusterService(
    private val discoveryRepository: DiscoveryRepository,
    private val queryEmbedder: QueryEmbedder,
) {
    private val log = LoggerFactory.getLogger(TagClusterService::class.java)

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun listTagCandidates(
        minCount: Int,
        threshold: Double,
        maxTags: Int,
    ): List<TagCandidateCluster> {
        val rawTags =
            discoveryRepository
                .listTags(scope = null, limit = maxTags)
                .filter { it.count >= minCount }
        if (rawTags.size < 2) return emptyList()

        val embeddings: Map<String, FloatArray> =
            try {
                embedAll(rawTags.map { it.tag })
            } catch (ex: RuntimeException) {
                log.warn("list_tag_candidates degraded: embedder failed", ex)
                return emptyList()
            }
        val countByTag = rawTags.associate { it.tag to it.count }
        return cluster(embeddings, countByTag, threshold)
    }

    private fun embedAll(tags: List<String>): Map<String, FloatArray> {
        val out = LinkedHashMap<String, FloatArray>(tags.size)
        for (tag in tags) {
            out[tag] = queryEmbedder.embed(tag)
        }
        return out
    }

    /**
     * Single-link clustering: a tag joins a cluster if any current
     * member is within the cosine threshold. The naive O(N²) scan is
     * adequate at our scale; revisit when tag count crosses 10³.
     */
    private fun cluster(
        embeddings: Map<String, FloatArray>,
        countByTag: Map<String, Int>,
        threshold: Double,
    ): List<TagCandidateCluster> {
        val visited = mutableSetOf<String>()
        val out = mutableListOf<TagCandidateCluster>()
        val tags = embeddings.keys.toList()
        for (tag in tags) {
            if (tag !in visited) {
                val cluster = expandCluster(tag, embeddings, tags, threshold, visited)
                if (cluster.size >= MIN_CLUSTER_SIZE) {
                    out += projectCluster(cluster, embeddings, countByTag)
                }
            }
        }
        return out.sortedByDescending { it.averageSimilarity }
    }

    private fun expandCluster(
        seed: String,
        embeddings: Map<String, FloatArray>,
        allTags: List<String>,
        threshold: Double,
        visited: MutableSet<String>,
    ): MutableList<String> {
        val cluster = mutableListOf(seed)
        visited += seed
        var head = 0
        while (head < cluster.size) {
            val current = cluster[head++]
            val currentVec = embeddings.getValue(current)
            for (other in allTags) {
                if (other in visited) continue
                val sim = cosine(currentVec, embeddings.getValue(other))
                if (sim >= threshold) {
                    visited += other
                    cluster += other
                }
            }
        }
        return cluster
    }

    private fun projectCluster(
        cluster: List<String>,
        embeddings: Map<String, FloatArray>,
        countByTag: Map<String, Int>,
    ): TagCandidateCluster {
        val members = cluster.map { TagCandidateMember(tag = it, count = countByTag[it] ?: 0) }
        val canonical = members.maxBy { it.count }.tag
        val avg = averagePairwiseSimilarity(cluster, embeddings)
        return TagCandidateCluster(
            members = members.sortedByDescending { it.count },
            suggestedCanonical = canonical,
            averageSimilarity = avg,
        )
    }

    private fun averagePairwiseSimilarity(
        cluster: List<String>,
        embeddings: Map<String, FloatArray>,
    ): Double {
        if (cluster.size < 2) return 1.0
        var total = 0.0
        var pairs = 0
        for (i in cluster.indices) {
            for (j in i + 1 until cluster.size) {
                total += cosine(embeddings.getValue(cluster[i]), embeddings.getValue(cluster[j]))
                pairs++
            }
        }
        return if (pairs == 0) 1.0 else total / pairs
    }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 0.0) 0.0 else dot / denom
    }

    private companion object {
        // Two-member clusters are the smallest interesting result —
        // anything below that is just a single tag and doesn't help
        // the operator triage drift.
        const val MIN_CLUSTER_SIZE = 2
    }
}
