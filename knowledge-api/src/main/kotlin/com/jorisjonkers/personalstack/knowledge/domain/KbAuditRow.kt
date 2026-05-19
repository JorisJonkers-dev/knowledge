package com.jorisjonkers.personalstack.knowledge.domain

import java.time.Instant

/**
 * One row of the shared audit log. Lives in `kb_audit`; written by
 * the curator's retroactive passes (relation resolver, title
 * renormaliser, reclassifier) and by the admin-bearer-gated MCP
 * write tools (topic + tag mutations).
 *
 * `beforeJson` / `afterJson` are JSON strings the caller serialises
 * — the shape varies per `action` and the consumer parses on read.
 * Keeping them as raw text means the audit log is forward-
 * compatible without per-action schema migrations.
 */
data class KbAuditRow(
    val id: String,
    val actor: String,
    val action: String,
    val targetId: String?,
    val targetKind: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val at: Instant,
)
