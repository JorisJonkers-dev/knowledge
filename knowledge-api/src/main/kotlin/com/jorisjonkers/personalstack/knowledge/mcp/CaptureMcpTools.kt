@file:Suppress("DEPRECATION") // Jackson 3 deprecated asText()/isTextual.

package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.capture.CaptureRequest
import com.jorisjonkers.personalstack.knowledge.capture.CaptureService
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import org.springframework.stereotype.Component

/**
 * Write-path MCP tools (Phase 4c-1): `knowledge.capture_lesson`,
 * `knowledge.capture_decision`, `knowledge.ingest_note`. Each tool
 * mints a ULID, inserts the canonical row, and publishes a worker
 * job. The actual persistence + publish lives in `CaptureService`.
 */
@Component
class CaptureMcpTools(
    private val captureService: CaptureService,
) {
    fun tools(): List<McpTool> = listOf(captureLessonTool(), captureDecisionTool(), ingestNoteTool())

    private fun captureLessonTool() =
        McpTool(
            descriptor =
                captureDescriptor(
                    name = "knowledge.capture_lesson",
                    description =
                        "Persist a lesson learned to the knowledge base. " +
                            "Returns the assigned ULID id so follow-up captures can supersedes-chain.",
                    extra =
                        mapOf(
                            "tags" to
                                mapOf(
                                    "type" to "array",
                                    "items" to mapOf("type" to "string"),
                                    "description" to "Free-form tags for later recall.",
                                ),
                        ),
                ),
            handler = { args -> projectNote(captureService.captureLesson(parse(args, "claude-code"))) },
        )

    private fun captureDecisionTool() =
        McpTool(
            descriptor =
                captureDescriptor(
                    name = "knowledge.capture_decision",
                    description =
                        "Persist an architectural / process decision with its rationale. " +
                            "Same shape as capture_lesson; the type field flips to `decision`.",
                ),
            handler = { args -> projectNote(captureService.captureDecision(parse(args, "claude-code"))) },
        )

    private fun ingestNoteTool() =
        McpTool(
            descriptor =
                captureDescriptor(
                    name = "knowledge.ingest_note",
                    description =
                        "Persist a free-form note. Use for URLs, transcripts, screenshots, " +
                            "or anything that doesn't cleanly fit lesson/decision.",
                    extra =
                        mapOf(
                            "type" to
                                mapOf(
                                    "type" to "string",
                                    "enum" to KbNoteType.entries.map { it.wire },
                                    "default" to "note",
                                ),
                        ),
                ),
            handler = { args ->
                val type = JsonArguments.optionalString(args, "type")?.let(KbNoteType::fromWire) ?: KbNoteType.NOTE
                projectNote(captureService.captureGenericNote(parse(args, "manual", typeOverride = type)))
            },
        )

    private fun captureDescriptor(
        name: String,
        description: String,
        extra: Map<String, Any> = emptyMap(),
    ): Map<String, Any> =
        toolDescriptor(
            name = name,
            description = description,
            required = listOf("scope", "title", "body"),
            properties = baseProperties() + extra,
        )

    private fun baseProperties(): Map<String, Any> =
        mapOf(
            "scope" to
                mapOf(
                    "type" to "string",
                    "description" to "Access boundary: personal, work, agent:<name>, project:<repo>, …",
                ),
            "title" to mapOf("type" to "string"),
            "body" to mapOf("type" to "string"),
            "session_id" to
                mapOf(
                    "type" to "string",
                    "description" to "Originating Claude / Codex session id, if known.",
                ),
            "confidence" to
                mapOf(
                    "type" to "number",
                    "minimum" to 0.0,
                    "maximum" to 1.0,
                    "default" to CaptureRequest.DEFAULT_CONFIDENCE,
                ),
            "vault_path" to
                mapOf(
                    "type" to "string",
                    "description" to
                        "Path under the knowledge-vault repo. If unset the service mints a draft path.",
                ),
        )

    private fun parse(
        args: tools.jackson.databind.JsonNode,
        defaultSource: String,
        typeOverride: KbNoteType? = null,
    ): CaptureRequest =
        CaptureRequest(
            type = typeOverride ?: KbNoteType.LESSON,
            scope = JsonArguments.requireString(args, "scope"),
            source = JsonArguments.optionalString(args, "source") ?: defaultSource,
            title = JsonArguments.requireString(args, "title"),
            body = JsonArguments.requireString(args, "body"),
            sessionId = JsonArguments.optionalString(args, "session_id"),
            confidence = JsonArguments.optionalDouble(args, "confidence") ?: CaptureRequest.DEFAULT_CONFIDENCE,
            vaultPath = JsonArguments.optionalString(args, "vault_path"),
            tags = JsonArguments.optionalStringArray(args, "tags").toSet(),
        )

    private fun projectNote(note: KbNote): Map<String, Any?> =
        mapOf(
            "id" to note.id,
            "type" to note.type.wire,
            "scope" to note.scope,
            "title" to note.title,
            "captured_at" to note.capturedAt.toString(),
            "vault_path" to note.vaultPath,
        )
}
