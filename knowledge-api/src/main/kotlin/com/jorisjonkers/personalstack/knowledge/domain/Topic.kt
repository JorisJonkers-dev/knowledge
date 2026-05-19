package com.jorisjonkers.personalstack.knowledge.domain

import java.time.Instant

/**
 * A defined entry in the topic vocabulary. The closed list previously
 * lived in `topics-configmap.yaml`; the V2 schema lifts it into
 * Postgres so the curator + knowledge-api + MCP admin tools share one
 * source of truth.
 *
 * `aliases` are stored lowercase so the matcher in
 * [com.jorisjonkers.personalstack.knowledge.topic.TopicVocabulary]
 * works case-insensitively without a runtime UPPER(); a topic's slug
 * is implicitly its own alias, inserted as such by the seed.
 */
data class Topic(
    val slug: String,
    val description: String,
    val aliases: Set<String>,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
    val isActive: Boolean,
)
