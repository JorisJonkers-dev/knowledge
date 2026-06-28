package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.audit.AuditService
import com.jorisjonkers.personalstack.knowledge.domain.KbAuditRow
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * Single read tool — `knowledge.list_audit` — that surfaces the
 * append-only `kb_audit` log to MCP clients. Optional filters by
 * `actor` / `action` / `target_id` and a `since` timestamp narrow
 * the result set; otherwise returns the most-recent rows.
 *
 * No write tool: writers are server-side (curator passes today,
 * admin MCP tools later) and call [com.jorisjonkers.personalstack.
 * knowledge.repo.AuditRepository.record] directly. Exposing a
 * `record_audit` MCP tool would just be a re-implementation of the
 * append insert with an extra hop.
 */
@Component
class AuditMcpTools(
    private val auditService: AuditService,
) {
    fun tools(): List<McpTool> = listOf(McpTool(listAuditDescriptor(), ::listAuditHandler))

    private fun listAuditDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.list_audit",
            description =
                "Most-recent-first listing of `kb_audit` rows. The log " +
                    "is populated by curator retroactive passes (relation " +
                    "resolver, title renormaliser, reclassifier) and by " +
                    "admin MCP writes (`add_topic` / `update_topic` / " +
                    "`merge_topics` / `rename_tag` / `merge_tags`). Filters compose: " +
                    "any combination of `actor` / `action` / `target_id` " +
                    "narrows the result set; `since` is an ISO-8601 " +
                    "timestamp clamping the window.",
            required = emptyList(),
            properties = listAuditProperties(),
        )

    private fun listAuditProperties(): Map<String, Any> =
        mapOf(
            "actor" to mapOf("type" to "string"),
            "action" to mapOf("type" to "string"),
            "target_id" to mapOf("type" to "string"),
            "since" to
                mapOf(
                    "type" to "string",
                    "description" to "ISO-8601 timestamp lower bound on `at`.",
                ),
            "limit" to
                mapOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to MAX_LIMIT,
                    "default" to DEFAULT_LIMIT,
                ),
        )

    private fun listAuditHandler(args: JsonNode): Map<String, Any?> {
        val actor = JsonArguments.optionalString(args, "actor")
        val action = JsonArguments.optionalString(args, "action")
        val targetId = JsonArguments.optionalString(args, "target_id")
        val since = JsonArguments.optionalString(args, "since")?.let(::parseInstantOrNull)
        val limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_LIMIT
        val rows =
            auditService.list(
                actor = actor,
                action = action,
                targetId = targetId,
                since = since,
                limit = limit.coerceAtMost(MAX_LIMIT),
            )
        return mapOf("rows" to rows.map(::projectRow))
    }

    private fun parseInstantOrNull(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    private fun projectRow(row: KbAuditRow): Map<String, Any?> =
        mapOf(
            "id" to row.id,
            "actor" to row.actor,
            "action" to row.action,
            "target_id" to row.targetId,
            "target_kind" to row.targetKind,
            "before_json" to row.beforeJson,
            "after_json" to row.afterJson,
            "at" to row.at.toString(),
        )

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}
