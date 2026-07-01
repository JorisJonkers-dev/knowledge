package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
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
        McpTool(
            descriptor = findDuplicatesDescriptor(),
            handler = { args -> findDuplicatesHandler(args, discoveryService) },
        )

    private fun listTagCandidatesTool() =
        McpTool(
            descriptor = listTagCandidatesDescriptor(),
            handler = { args -> listTagCandidatesHandler(args, tagClusterService) },
        )
}
