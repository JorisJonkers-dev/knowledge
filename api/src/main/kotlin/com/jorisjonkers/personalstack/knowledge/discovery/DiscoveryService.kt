package com.jorisjonkers.personalstack.knowledge.discovery

import com.jorisjonkers.personalstack.knowledge.domain.DuplicateMatch
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.ScopeSummary
import com.jorisjonkers.personalstack.knowledge.domain.SourceSummary
import com.jorisjonkers.personalstack.knowledge.domain.SuggestedTopic
import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.domain.TopicStats
import com.jorisjonkers.personalstack.knowledge.domain.TopicSummary
import com.jorisjonkers.personalstack.knowledge.recall.QueryEmbedder
import com.jorisjonkers.personalstack.knowledge.recall.QueryEmbeddingException
import com.jorisjonkers.personalstack.knowledge.repo.DiscoveryRepository
import com.jorisjonkers.personalstack.knowledge.repo.EmbeddingRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Read-side façade for the discovery MCP tools. Holding it in a
 * dedicated service (rather than overloading [com.jorisjonkers.
 * personalstack.knowledge.recall.RecallService]) keeps the recall
 * path's responsibilities narrow — recall is hot-path FTS, discovery
 * is GROUP BY for navigation — and lets each evolve independently.
 *
 * The service is intentionally thin: every method delegates to
 * either [DiscoveryRepository] (the aggregations) or [NoteRepository]
 * (the inbox listing, which reuses the existing recent-first lister).
 * That symmetry keeps both paths covered by one test fixture and
 * leaves room for a future caching layer without re-plumbing.
 */
@Service
class DiscoveryService(
    private val discoveryRepository: DiscoveryRepository,
    private val noteRepository: NoteRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val queryEmbedder: QueryEmbedder,
) {
    private val log = LoggerFactory.getLogger(DiscoveryService::class.java)

    fun listTopics(limit: Int): List<TopicSummary> = discoveryRepository.listTopics(limit)

    fun listTags(
        scope: String?,
        limit: Int,
    ): List<TagSummary> = discoveryRepository.listTags(scope, limit)

    fun listScopes(limit: Int): List<ScopeSummary> = discoveryRepository.listScopes(limit)

    fun listSources(limit: Int): List<SourceSummary> = discoveryRepository.listSources(limit)

    fun topicStats(
        slug: String,
        topTagLimit: Int = DEFAULT_TOPIC_STATS_TAG_LIMIT,
    ): TopicStats? = discoveryRepository.topicStats(slug, topTagLimit)

    /**
     * Notes still sitting under the `_inbox` sentinel scope — captured
     * by an agent but not yet promoted by the curator. Returns
     * recent-first via the existing list path so the MCP shape mirrors
     * `knowledge.list_recent`.
     */
    fun listInbox(limit: Int): List<KbNote> = noteRepository.listRecent(DiscoveryRepository.INBOX_SCOPE, null, limit)

    /**
     * Embeds [text] and matches it against each topic's centroid
     * (mean of the topic's note embeddings). Returns the top-N closest
     * topics. An embedder failure (Ollama down) bubbles up — there is
     * no graceful FTS fallback for this tool because the FTS path
     * doesn't compute topic centroids.
     */
    fun suggestTopic(
        text: String,
        limit: Int,
    ): List<SuggestedTopic> {
        if (text.isBlank()) return emptyList()
        return try {
            val embedding = queryEmbedder.embed(text)
            embeddingRepository.suggestTopic(embedding, limit)
        } catch (ex: QueryEmbeddingException) {
            log.warn(
                "suggest_topic degraded: embedder failed (text.len={})",
                text.length,
                ex,
            )
            emptyList()
        } catch (ex: DataAccessException) {
            log.warn(
                "suggest_topic degraded: repository failed (text.len={})",
                text.length,
                ex,
            )
            emptyList()
        }
    }

    /**
     * Find candidate near-duplicates of [text] within `threshold`
     * cosine similarity. The MCP tool surfaces this for either a
     * free-form query string (pre-capture dedup check) or a known
     * note id (post-capture audit) — the id path uses the row's
     * persisted embedding to skip re-embedding.
     */
    fun findDuplicates(
        text: String,
        threshold: Double,
        limit: Int,
    ): List<DuplicateMatch> {
        if (text.isBlank()) return emptyList()
        return try {
            val embedding = queryEmbedder.embed(text)
            embeddingRepository.findDuplicates(embedding, threshold, limit)
        } catch (ex: QueryEmbeddingException) {
            log.warn("find_duplicates degraded: embedder failed (text.len={})", text.length, ex)
            emptyList()
        } catch (ex: DataAccessException) {
            log.warn("find_duplicates degraded: repository failed (text.len={})", text.length, ex)
            emptyList()
        }
    }

    fun findDuplicatesOf(
        noteId: String,
        threshold: Double,
        limit: Int,
    ): List<DuplicateMatch> {
        val embedding = embeddingRepository.embeddingFor(noteId) ?: return emptyList()
        return embeddingRepository.findDuplicates(embedding, threshold, limit, excludeId = noteId)
    }

    private companion object {
        const val DEFAULT_TOPIC_STATS_TAG_LIMIT = 10
    }
}
