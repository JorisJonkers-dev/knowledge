package com.jorisjonkers.personalstack.knowledge.discovery

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.ScopeSummary
import com.jorisjonkers.personalstack.knowledge.domain.SourceSummary
import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.domain.TopicStats
import com.jorisjonkers.personalstack.knowledge.domain.TopicSummary
import com.jorisjonkers.personalstack.knowledge.repo.DiscoveryRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
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
) {
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

    private companion object {
        const val DEFAULT_TOPIC_STATS_TAG_LIMIT = 10
    }
}
