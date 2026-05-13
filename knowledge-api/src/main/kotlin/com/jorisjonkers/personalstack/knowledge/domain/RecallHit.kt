package com.jorisjonkers.personalstack.knowledge.domain

/**
 * One row returned by `knowledge.recall`. The body is truncated to a
 * snippet so the MCP transport doesn't ship megabytes when the worker
 * has indexed long URLs / transcripts.
 */
data class RecallHit(
    val id: String,
    val type: String,
    val scope: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val tags: Set<String> = emptySet(),
)
