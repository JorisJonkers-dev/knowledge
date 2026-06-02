package com.jorisjonkers.personalstack.knowledge.web

import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Browser-facing REST shim over [DiscoveryService]. Same wire shape
 * as the corresponding MCP tools in
 * [com.jorisjonkers.personalstack.knowledge.mcp.DiscoveryMcpTools].
 *
 * See [KnowledgeReadController] for the auth + design notes shared by
 * the REST surface.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
class KnowledgeDiscoveryController(
    private val discoveryService: DiscoveryService,
    private val tagClusterService: TagClusterService,
) {
    @GetMapping("/topics")
    fun listTopics(
        @RequestParam("limit", defaultValue = "$DEFAULT_LIST_LIMIT") limit: Int,
    ): TopicsResponse =
        TopicsResponse(
            topics = discoveryService.listTopics(limit.coerceIn(1, MAX_LIMIT)).map(TopicResponse::from),
        )

    @GetMapping("/tags")
    fun listTags(
        @RequestParam("scope", required = false) scope: String?,
        @RequestParam("limit", defaultValue = "$DEFAULT_TAG_LIMIT") limit: Int,
    ): TagsResponse =
        TagsResponse(
            tags =
                discoveryService
                    .listTags(scope, limit.coerceIn(1, MAX_LIMIT))
                    .map(TagResponse::from),
        )

    @GetMapping("/scopes")
    fun listScopes(
        @RequestParam("limit", defaultValue = "$DEFAULT_LIST_LIMIT") limit: Int,
    ): ScopesResponse =
        ScopesResponse(
            scopes = discoveryService.listScopes(limit.coerceIn(1, MAX_LIMIT)).map(ScopeResponse::from),
        )

    @GetMapping("/sources")
    fun listSources(
        @RequestParam("limit", defaultValue = "$DEFAULT_LIST_LIMIT") limit: Int,
    ): SourcesResponse =
        SourcesResponse(
            sources =
                discoveryService
                    .listSources(limit.coerceIn(1, MAX_LIMIT))
                    .map(SourceResponse::from),
        )

    @GetMapping("/topics/{slug}/stats")
    fun topicStats(
        @PathVariable("slug") slug: String,
        @RequestParam("top_tag_limit", defaultValue = "$DEFAULT_TOP_TAGS") topTagLimit: Int,
    ): ResponseEntity<TopicStatsResponse> {
        val stats =
            discoveryService.topicStats(slug, topTagLimit.coerceIn(1, MAX_TOP_TAGS))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TopicStatsResponse.from(stats))
    }

    @GetMapping("/inbox")
    fun listInbox(
        @RequestParam("limit", defaultValue = "$DEFAULT_INBOX_LIMIT") limit: Int,
    ): InboxResponse =
        InboxResponse(
            notes =
                discoveryService
                    .listInbox(limit.coerceIn(1, MAX_LIMIT))
                    .map(InboxNoteResponse::from),
        )

    @GetMapping("/suggest_topic")
    fun suggestTopic(
        @RequestParam("text") text: String,
        @RequestParam("limit", defaultValue = "$DEFAULT_SUGGEST_LIMIT") limit: Int,
    ): SuggestionsResponse =
        SuggestionsResponse(
            suggestions =
                discoveryService
                    .suggestTopic(text, limit.coerceIn(1, MAX_LIMIT))
                    .map(SuggestedTopicResponse::from),
        )

    @GetMapping("/find_duplicates")
    fun findDuplicates(
        @RequestParam("text", required = false) text: String?,
        @RequestParam("id", required = false) id: String?,
        @RequestParam("threshold", defaultValue = "$DEFAULT_DUP_THRESHOLD") threshold: Double,
        @RequestParam("limit", defaultValue = "$DEFAULT_DUP_LIMIT") limit: Int,
    ): DuplicatesResponse {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val matches =
            when {
                id != null -> discoveryService.findDuplicatesOf(id, threshold, capped)
                text != null -> discoveryService.findDuplicates(text, threshold, capped)
                else -> error("find_duplicates requires either `text` or `id` — neither was supplied")
            }
        return DuplicatesResponse(matches = matches.map(DuplicateMatchResponse::from))
    }

    @GetMapping("/tag_candidates")
    fun listTagCandidates(
        @RequestParam("min_count", defaultValue = "$DEFAULT_TAG_CANDIDATE_MIN_COUNT") minCount: Int,
        @RequestParam("threshold", defaultValue = "$DEFAULT_TAG_CANDIDATE_THRESHOLD") threshold: Double,
        @RequestParam("max_tags", defaultValue = "$DEFAULT_TAG_CANDIDATE_MAX_TAGS") maxTags: Int,
    ): TagCandidatesResponse {
        val clusters =
            tagClusterService.listTagCandidates(
                minCount = minCount,
                threshold = threshold,
                maxTags = maxTags.coerceIn(1, MAX_TAG_CANDIDATE_LIMIT),
            )
        return TagCandidatesResponse(clusters = clusters.map(TagCandidateClusterResponse::from))
    }

    companion object {
        private const val DEFAULT_LIST_LIMIT = 50
        private const val DEFAULT_TAG_LIMIT = 100
        private const val DEFAULT_INBOX_LIMIT = 20
        private const val DEFAULT_TOP_TAGS = 10
        private const val MAX_TOP_TAGS = 50
        private const val MAX_LIMIT = 200
        private const val DEFAULT_SUGGEST_LIMIT = 5
        private const val DEFAULT_DUP_LIMIT = 5
        private const val DEFAULT_DUP_THRESHOLD = 0.85
        private const val DEFAULT_TAG_CANDIDATE_MIN_COUNT = 1
        private const val DEFAULT_TAG_CANDIDATE_THRESHOLD = 0.85
        private const val DEFAULT_TAG_CANDIDATE_MAX_TAGS = 200
        private const val MAX_TAG_CANDIDATE_LIMIT = 500
    }
}

data class TopicsResponse(
    val topics: List<TopicResponse>,
)

data class TagsResponse(
    val tags: List<TagResponse>,
)

data class ScopesResponse(
    val scopes: List<ScopeResponse>,
)

data class SourcesResponse(
    val sources: List<SourceResponse>,
)

data class InboxResponse(
    val notes: List<InboxNoteResponse>,
)

data class SuggestionsResponse(
    val suggestions: List<SuggestedTopicResponse>,
)

data class DuplicatesResponse(
    val matches: List<DuplicateMatchResponse>,
)

data class TagCandidatesResponse(
    val clusters: List<TagCandidateClusterResponse>,
)
