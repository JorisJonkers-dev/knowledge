package com.jorisjonkers.personalstack.knowledge.mcp

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

internal fun projectTopic(summary: TopicSummary): Map<String, Any?> =
    mapOf(
        "slug" to summary.slug,
        "note_count" to summary.noteCount,
        "last_captured_at" to summary.lastCapturedAt?.toString(),
        "description" to summary.description,
    )

internal fun projectTag(summary: TagSummary): Map<String, Any?> =
    mapOf(
        "tag" to summary.tag,
        "count" to summary.count,
        "last_used_at" to summary.lastUsedAt?.toString(),
    )

internal fun projectScope(summary: ScopeSummary): Map<String, Any?> =
    mapOf(
        "scope" to summary.scope,
        "note_count" to summary.noteCount,
        "last_captured_at" to summary.lastCapturedAt?.toString(),
    )

internal fun projectSource(summary: SourceSummary): Map<String, Any?> =
    mapOf(
        "source" to summary.source,
        "count" to summary.count,
    )

internal fun projectTopicStats(stats: TopicStats): Map<String, Any?> =
    mapOf(
        "slug" to stats.slug,
        "note_count" to stats.noteCount,
        "first_captured_at" to stats.firstCapturedAt?.toString(),
        "last_captured_at" to stats.lastCapturedAt?.toString(),
        "type_breakdown" to stats.typeBreakdown,
        "top_tags" to stats.topTags.map(::projectTag),
    )

internal fun projectInboxNote(note: KbNote): Map<String, Any?> =
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

internal fun projectSuggestedTopic(suggestion: SuggestedTopic): Map<String, Any?> =
    mapOf(
        "slug" to suggestion.slug,
        "score" to suggestion.score,
        "note_count" to suggestion.noteCount,
    )

internal fun projectDuplicate(match: DuplicateMatch): Map<String, Any?> =
    mapOf(
        "id" to match.id,
        "title" to match.title,
        "scope" to match.scope,
        "score" to match.score,
    )

internal fun projectTagCluster(cluster: TagCandidateCluster): Map<String, Any?> =
    mapOf(
        "members" to cluster.members.map(::projectTagMember),
        "suggested_canonical" to cluster.suggestedCanonical,
        "average_similarity" to cluster.averageSimilarity,
    )

internal fun projectTagMember(member: TagCandidateMember): Map<String, Any?> =
    mapOf(
        "tag" to member.tag,
        "count" to member.count,
    )
