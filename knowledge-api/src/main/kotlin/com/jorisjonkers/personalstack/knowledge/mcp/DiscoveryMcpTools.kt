package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
import com.jorisjonkers.personalstack.knowledge.domain.DuplicateMatch
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.ScopeSummary
import com.jorisjonkers.personalstack.knowledge.domain.SourceSummary
import com.jorisjonkers.personalstack.knowledge.domain.SuggestedTopic
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateCluster
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateMember
import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.domain.TopicStats
import com.jorisjonkers.personalstack.knowledge.domain.TopicSummary
import org.springframework.stereotype.Component

/**
 * Discovery-side MCP tools. Aggregations over `kb_notes` /
 * `kb_note_tags` that let an agent enumerate topics, tags, scopes
 * and sources without first having to know them. The MCP write
 * tools (capture_*) gain a lot of accuracy when the agent can ask
 * "what topics already exist" before assigning a scope — see the
 * enrichment-plan note for the wider context.
 *
 * Tools (Phase 4d):
 *   - `knowledge.list_topics`     — slugs in use + counts.
 *   - `knowledge.list_tags`       — tag frequency, optional scope filter.
 *   - `knowledge.list_scopes`     — every distinct scope + counts.
 *   - `knowledge.list_sources`    — provenance roll-up.
 *   - `knowledge.topic_stats`     — per-topic aggregate.
 *   - `knowledge.list_inbox`      — notes pending curator promotion.
 *
 * `knowledge.suggest_topic`, `knowledge.related_topics` and
 * `knowledge.find_duplicates` are deliberately left out of this
 * slice — they depend on Ollama / graph traversal / vector search
 * and ship as follow-up PRs once the dynamic-topic schema is in
 * place.
 */
@Suppress("TooManyFunctions", "LargeClass") // Discovery surface lives here by design.
@Component
class DiscoveryMcpTools(
    private val discoveryService: DiscoveryService,
    private val tagClusterService: TagClusterService,
) {
    fun tools(): List<McpTool> =
        listOf(
            listTopicsTool(),
            listTagsTool(),
            listScopesTool(),
            listSourcesTool(),
            topicStatsTool(),
            listInboxTool(),
            suggestTopicTool(),
            findDuplicatesTool(),
            listTagCandidatesTool(),
        )

    // -------- tool definitions --------

    private fun listTopicsTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.list_topics",
                    description =
                        "Enumerate topic slugs currently in use across `kb_notes`. " +
                            "Returns `[{slug, note_count, last_captured_at}]` sorted by " +
                            "note_count descending. Use this before assigning a " +
                            "`topic:<slug>` scope on a new capture to match the existing " +
                            "vocabulary instead of inventing a near-duplicate.",
                    required = emptyList(),
                    properties =
                        mapOf(
                            "limit" to limitSchema(DEFAULT_LIST_LIMIT),
                        ),
                ),
            handler = { args ->
                val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_LIST_LIMIT
                mapOf("topics" to discoveryService.listTopics(limit).map(::projectTopic))
            },
        )

    private fun listTagsTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.list_tags",
                    description =
                        "Enumerate free-form tags with their note counts. Pass `scope` " +
                            "to scope the count to a single scope (useful for spotting " +
                            "per-project tag drift). Returns `[{tag, count, last_used_at}]` " +
                            "sorted by count descending. Use this before tagging a new " +
                            "capture to match existing spellings (`kotlin` vs `Kotlin` " +
                            "vs `kt`).",
                    required = emptyList(),
                    properties =
                        mapOf(
                            "scope" to mapOf("type" to "string"),
                            "limit" to limitSchema(DEFAULT_TAG_LIMIT),
                        ),
                ),
            handler = { args ->
                val scope = JsonArguments.optionalString(args, "scope")
                val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_TAG_LIMIT
                mapOf("tags" to discoveryService.listTags(scope, limit).map(::projectTag))
            },
        )

    private fun listScopesTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.list_scopes",
                    description =
                        "Enumerate every distinct `kb_notes.scope` value with its note " +
                            "count and most recent capture. Helps the agent discover " +
                            "project / agent / personal scopes it isn't aware of yet.",
                    required = emptyList(),
                    properties =
                        mapOf(
                            "limit" to limitSchema(DEFAULT_LIST_LIMIT),
                        ),
                ),
            handler = { args ->
                val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_LIST_LIMIT
                mapOf("scopes" to discoveryService.listScopes(limit).map(::projectScope))
            },
        )

    private fun listSourcesTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.list_sources",
                    description =
                        "Enumerate every distinct `kb_notes.source` value with its count. " +
                            "Source is provenance — `claude-code`, `codex`, `manual`, " +
                            "`claude-code:auto-memory`, `url:<host>`, etc. Use to spot " +
                            "an unexpected source or audit a bulk auto-capture run.",
                    required = emptyList(),
                    properties =
                        mapOf(
                            "limit" to limitSchema(DEFAULT_LIST_LIMIT),
                        ),
                ),
            handler = { args ->
                val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_LIST_LIMIT
                mapOf("sources" to discoveryService.listSources(limit).map(::projectSource))
            },
        )

    private fun topicStatsTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.topic_stats",
                    description =
                        "Per-topic aggregate: note count, capture window, type " +
                            "breakdown, top tags. `slug` is the bare topic slug (without " +
                            "the `topic:` prefix). Returns null when the slug has no " +
                            "notes — caller should fall back to `list_topics` for " +
                            "discovery rather than treat that as an error.",
                    required = listOf("slug"),
                    properties =
                        mapOf(
                            "slug" to mapOf("type" to "string"),
                            "top_tag_limit" to
                                mapOf(
                                    "type" to "integer",
                                    "minimum" to 1,
                                    "maximum" to MAX_TOP_TAGS,
                                    "default" to DEFAULT_TOP_TAGS,
                                ),
                        ),
                ),
            handler = { args ->
                val slug = JsonArguments.requireString(args, "slug")
                val topTags = JsonArguments.optionalInt(args, "top_tag_limit") ?: DEFAULT_TOP_TAGS
                mapOf("stats" to discoveryService.topicStats(slug, topTags)?.let(::projectTopicStats))
            },
        )

    private fun listInboxTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.list_inbox",
                    description =
                        "Notes still sitting under the `_inbox` sentinel scope — captured " +
                            "by an agent but not yet classified and promoted by the " +
                            "curator. Recent-first. Use to answer 'what hasn't the " +
                            "curator picked up yet'.",
                    required = emptyList(),
                    properties =
                        mapOf(
                            "limit" to limitSchema(DEFAULT_INBOX_LIMIT),
                        ),
                ),
            handler = { args ->
                val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_INBOX_LIMIT
                mapOf("notes" to discoveryService.listInbox(limit).map(::projectInboxNote))
            },
        )

    private fun suggestTopicTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.suggest_topic",
                    description =
                        "Vector-backed topic suggestion. Embeds `text` and compares it " +
                            "against each topic's centroid (mean of its members' embeddings). " +
                            "Returns `[{slug, score, note_count}]` ordered by descending " +
                            "cosine similarity. Use this when about to capture a note and " +
                            "unsure which `topic:<slug>` to scope to — feed in the draft " +
                            "title + body. Empty result means no embedded topics yet.",
                    required = listOf("text"),
                    properties =
                        mapOf(
                            "text" to mapOf("type" to "string"),
                            "limit" to limitSchema(DEFAULT_SUGGEST_LIMIT),
                        ),
                ),
            handler = { args ->
                val text = JsonArguments.requireString(args, "text")
                val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_SUGGEST_LIMIT
                mapOf("suggestions" to discoveryService.suggestTopic(text, limit).map(::projectSuggestedTopic))
            },
        )

    private fun findDuplicatesTool() =
        McpTool(descriptor = findDuplicatesDescriptor(), handler = ::findDuplicatesHandler)

    private fun findDuplicatesDescriptor() =
        toolDescriptor(
            name = "knowledge.find_duplicates",
            description = FIND_DUPLICATES_DESCRIPTION,
            required = emptyList(),
            properties =
                mapOf(
                    "text" to mapOf("type" to "string"),
                    "id" to mapOf("type" to "string"),
                    "threshold" to
                        mapOf(
                            "type" to "number",
                            "minimum" to 0.0,
                            "maximum" to 1.0,
                            "default" to DEFAULT_DUP_THRESHOLD,
                        ),
                    "limit" to limitSchema(DEFAULT_DUP_LIMIT),
                ),
        )

    private fun findDuplicatesHandler(args: tools.jackson.databind.JsonNode): Map<String, Any?> {
        val text = JsonArguments.optionalString(args, "text")
        val id = JsonArguments.optionalString(args, "id")
        val threshold = JsonArguments.optionalDouble(args, "threshold") ?: DEFAULT_DUP_THRESHOLD
        val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_DUP_LIMIT
        val matches =
            when {
                id != null -> discoveryService.findDuplicatesOf(id, threshold, limit)
                text != null -> discoveryService.findDuplicates(text, threshold, limit)
                else -> error("find_duplicates requires either `text` or `id` — neither was supplied")
            }
        return mapOf("matches" to matches.map(::projectDuplicate))
    }

    private fun listTagCandidatesTool() =
        McpTool(
            descriptor = listTagCandidatesDescriptor(),
            handler = ::listTagCandidatesHandler,
        )

    private fun listTagCandidatesDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.list_tag_candidates",
            description = LIST_TAG_CANDIDATES_DESCRIPTION,
            required = emptyList(),
            properties =
                mapOf(
                    "min_count" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "default" to DEFAULT_TAG_CANDIDATE_MIN_COUNT,
                        ),
                    "threshold" to
                        mapOf(
                            "type" to "number",
                            "minimum" to 0.0,
                            "maximum" to 1.0,
                            "default" to DEFAULT_TAG_CANDIDATE_THRESHOLD,
                        ),
                    "max_tags" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to MAX_TAG_CANDIDATE_LIMIT,
                            "default" to DEFAULT_TAG_CANDIDATE_MAX_TAGS,
                        ),
                ),
        )

    private fun listTagCandidatesHandler(args: tools.jackson.databind.JsonNode): Map<String, Any?> {
        val minCount =
            JsonArguments.optionalInt(args, "min_count")
                ?: DEFAULT_TAG_CANDIDATE_MIN_COUNT
        val threshold =
            JsonArguments.optionalDouble(args, "threshold")
                ?: DEFAULT_TAG_CANDIDATE_THRESHOLD
        val maxTags =
            (JsonArguments.optionalInt(args, "max_tags") ?: DEFAULT_TAG_CANDIDATE_MAX_TAGS)
                .coerceIn(1, MAX_TAG_CANDIDATE_LIMIT)
        val clusters = tagClusterService.listTagCandidates(minCount, threshold, maxTags)
        return mapOf("clusters" to clusters.map(::projectTagCluster))
    }

    // -------- projections --------

    private fun projectTopic(summary: TopicSummary): Map<String, Any?> =
        mapOf(
            "slug" to summary.slug,
            "note_count" to summary.noteCount,
            "last_captured_at" to summary.lastCapturedAt?.toString(),
            "description" to summary.description,
        )

    private fun projectTag(summary: TagSummary): Map<String, Any?> =
        mapOf(
            "tag" to summary.tag,
            "count" to summary.count,
            "last_used_at" to summary.lastUsedAt?.toString(),
        )

    private fun projectScope(summary: ScopeSummary): Map<String, Any?> =
        mapOf(
            "scope" to summary.scope,
            "note_count" to summary.noteCount,
            "last_captured_at" to summary.lastCapturedAt?.toString(),
        )

    private fun projectSource(summary: SourceSummary): Map<String, Any?> =
        mapOf(
            "source" to summary.source,
            "count" to summary.count,
        )

    private fun projectTopicStats(stats: TopicStats): Map<String, Any?> =
        mapOf(
            "slug" to stats.slug,
            "note_count" to stats.noteCount,
            "first_captured_at" to stats.firstCapturedAt?.toString(),
            "last_captured_at" to stats.lastCapturedAt?.toString(),
            "type_breakdown" to stats.typeBreakdown,
            "top_tags" to stats.topTags.map(::projectTag),
        )

    private fun projectInboxNote(note: KbNote): Map<String, Any?> =
        mapOf(
            "id" to note.id,
            "type" to note.type.wire,
            "scope" to note.scope,
            "source" to note.source,
            "captured_at" to note.capturedAt.toString(),
            "title" to note.title,
            "vault_path" to note.vaultPath,
            "tags" to note.tags.toList().sorted(),
        )

    private fun projectSuggestedTopic(suggestion: SuggestedTopic): Map<String, Any?> =
        mapOf(
            "slug" to suggestion.slug,
            "score" to suggestion.score,
            "note_count" to suggestion.noteCount,
        )

    private fun projectDuplicate(match: DuplicateMatch): Map<String, Any?> =
        mapOf(
            "id" to match.id,
            "title" to match.title,
            "scope" to match.scope,
            "score" to match.score,
        )

    private fun projectTagCluster(cluster: TagCandidateCluster): Map<String, Any?> =
        mapOf(
            "members" to cluster.members.map(::projectTagMember),
            "suggested_canonical" to cluster.suggestedCanonical,
            "average_similarity" to cluster.averageSimilarity,
        )

    private fun projectTagMember(member: TagCandidateMember): Map<String, Any?> =
        mapOf(
            "tag" to member.tag,
            "count" to member.count,
        )

    private fun limitSchema(default: Int) =
        mapOf(
            "type" to "integer",
            "minimum" to 1,
            "maximum" to MAX_LIMIT,
            "default" to default,
        )

    private companion object {
        const val DEFAULT_LIST_LIMIT = 50
        const val DEFAULT_TAG_LIMIT = 100
        const val DEFAULT_INBOX_LIMIT = 20
        const val DEFAULT_TOP_TAGS = 10
        const val MAX_TOP_TAGS = 50
        const val MAX_LIMIT = 200
        const val DEFAULT_SUGGEST_LIMIT = 5
        const val DEFAULT_DUP_LIMIT = 5
        const val DEFAULT_DUP_THRESHOLD = 0.85
        const val DEFAULT_TAG_CANDIDATE_MIN_COUNT = 1
        const val DEFAULT_TAG_CANDIDATE_THRESHOLD = 0.85
        const val DEFAULT_TAG_CANDIDATE_MAX_TAGS = 200
        const val MAX_TAG_CANDIDATE_LIMIT = 500
        const val FIND_DUPLICATES_DESCRIPTION =
            "Vector-backed near-duplicate detection. Embeds `text` (or pulls the persisted " +
                "embedding when `id` is provided) and returns rows whose cosine similarity " +
                "is at or above `threshold` (default 0.85). Use pre-capture to avoid " +
                "re-stating an existing lesson, or post-capture to audit possible dupes."
        const val LIST_TAG_CANDIDATES_DESCRIPTION =
            "Surface near-duplicate tag clusters for hygiene review. Each cluster contains " +
                "tags whose pairwise cosine similarity is at or above `threshold` (default " +
                "0.85), alongside the highest-count member as the suggested canonical. Use " +
                "before `knowledge.merge_tags` so the merge target is a deliberate operator " +
                "choice and the source list is complete. Embeds every distinct tag on the " +
                "fly — at the current scale (≤200 tags) this completes in seconds; degrades " +
                "to an empty list when the embedder is unreachable."
    }
}
