package com.jorisjonkers.personalstack.knowledge.mcp

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Selects how much of the knowledge MCP surface is exposed.
 *
 * - [KnowledgeMode.FULL] (default): the complete curator surface — capture,
 *   read/recall, discovery, admin, digest, audit, review. No behaviour change
 *   from before this property existed.
 * - [KnowledgeMode.LITE]: retrieval + capture only. The curator-governance
 *   tools (discovery, admin, digest, audit, review) are not registered. This is
 *   the lightweight-memory target — heavy curation moves to PR review + file
 *   memory, leaving a thin recall+capture service that is cheap to maintain.
 *
 * Bound from `knowledge.mode` (env `KNOWLEDGE_MODE`); reversible by flipping
 * the value, so the cutover is a config change, not a code revert.
 */
@ConfigurationProperties("knowledge")
data class KnowledgeModeProperties(
    val mode: KnowledgeMode = KnowledgeMode.FULL,
)

enum class KnowledgeMode {
    FULL,
    LITE,
}
