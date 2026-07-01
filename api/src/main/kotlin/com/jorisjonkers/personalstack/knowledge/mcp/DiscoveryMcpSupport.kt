package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService

internal fun findDuplicatesDescriptor() =
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

internal fun findDuplicatesHandler(
    args: tools.jackson.databind.JsonNode,
    discoveryService: DiscoveryService,
): Map<String, Any?> {
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

internal fun listTagCandidatesDescriptor(): Map<String, Any> =
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

internal fun listTagCandidatesHandler(
    args: tools.jackson.databind.JsonNode,
    tagClusterService: TagClusterService,
): Map<String, Any?> {
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

internal fun limitSchema(default: Int) =
    mapOf(
        "type" to "integer",
        "minimum" to 1,
        "maximum" to DISCOVERY_MAX_LIMIT,
        "default" to default,
    )

internal const val DEFAULT_LIST_LIMIT = 50
internal const val DEFAULT_TAG_LIMIT = 100
internal const val DEFAULT_INBOX_LIMIT = 20
internal const val DEFAULT_TOP_TAGS = 10
internal const val MAX_TOP_TAGS = 50
internal const val DISCOVERY_MAX_LIMIT = 200
internal const val DEFAULT_SUGGEST_LIMIT = 5
internal const val DEFAULT_DUP_LIMIT = 5
internal const val DEFAULT_DUP_THRESHOLD = 0.85
internal const val DEFAULT_TAG_CANDIDATE_MIN_COUNT = 1
internal const val DEFAULT_TAG_CANDIDATE_THRESHOLD = 0.85
internal const val DEFAULT_TAG_CANDIDATE_MAX_TAGS = 200
internal const val MAX_TAG_CANDIDATE_LIMIT = 500
internal const val FIND_DUPLICATES_DESCRIPTION =
    "Vector-backed near-duplicate detection. Embeds `text` (or pulls the persisted " +
        "embedding when `id` is provided) and returns rows whose cosine similarity " +
        "is at or above `threshold` (default 0.85). Use pre-capture to avoid " +
        "re-stating an existing lesson, or post-capture to audit possible dupes."
internal const val LIST_TAG_CANDIDATES_DESCRIPTION =
    "Surface near-duplicate tag clusters for hygiene review. Each cluster contains " +
        "tags whose pairwise cosine similarity is at or above `threshold` (default " +
        "0.85), alongside the highest-count member as the suggested canonical. Use " +
        "before `knowledge.merge_tags` so the merge target is a deliberate operator " +
        "choice and the source list is complete. Embeds every distinct tag on the " +
        "fly — at the current scale (≤200 tags) this completes in seconds; degrades " +
        "to an empty list when the embedder is unreachable."
