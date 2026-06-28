package com.jorisjonkers.personalstack.knowledge.domain

import java.time.Instant

/**
 * Single row returned by `knowledge.list_topics`. Today the slug list
 * is derived from the `kb_notes.scope` column (rows scoped
 * `topic:<slug>`) — the topic vocabulary ConfigMap is not yet the
 * source of truth in-DB. Once the dynamic-topic schema lands the
 * `description` field stops being null.
 */
data class TopicSummary(
    val slug: String,
    val noteCount: Int,
    val lastCapturedAt: Instant?,
    val description: String? = null,
)

/**
 * Single row returned by `knowledge.list_tags`. `lastUsedAt` is the
 * most recent `kb_notes.captured_at` over notes carrying this tag.
 * Tags live in the `kb_note_tags` join table (free-form text); the
 * count helps the caller spot drift between near-duplicate spellings.
 */
data class TagSummary(
    val tag: String,
    val count: Int,
    val lastUsedAt: Instant?,
)

/**
 * Single row returned by `knowledge.list_scopes`. Scopes are an open
 * set (`topic:<slug>` / `project:<repo>` / `agent:<name>` / `personal`
 * / `work` / `_inbox` / …) so the listing is a discovery aid rather
 * than a closed vocabulary.
 */
data class ScopeSummary(
    val scope: String,
    val noteCount: Int,
    val lastCapturedAt: Instant?,
)

/**
 * Single row returned by `knowledge.list_sources`. Source values are
 * provenance markers minted at capture time — `claude-code`, `codex`,
 * `manual`, `claude-code:auto-memory`, `url:<host>`, etc.
 */
data class SourceSummary(
    val source: String,
    val count: Int,
)

/**
 * Aggregate view over a single topic, returned by
 * `knowledge.topic_stats`. The breakdowns are bounded — top tags and
 * type counts only — so a hub topic with thousands of notes still
 * fits in the agent's context budget.
 */
data class TopicStats(
    val slug: String,
    val noteCount: Int,
    val firstCapturedAt: Instant?,
    val lastCapturedAt: Instant?,
    val typeBreakdown: Map<String, Int>,
    val topTags: List<TagSummary>,
)
