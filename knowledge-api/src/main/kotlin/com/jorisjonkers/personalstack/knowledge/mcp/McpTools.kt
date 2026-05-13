@file:Suppress("DEPRECATION") // Jackson 3 deprecated JsonNode.asText()/isTextual in favour of
// asString()/JsonNodeType — keeping JsonNode shape until a coordinated migration lands.

package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.capture.CaptureRequest
import com.jorisjonkers.personalstack.knowledge.capture.CaptureService
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * MCP `tools/list` + `tools/call` registry. Each `McpTool` owns its
 * own JSON-Schema descriptor (sent to clients verbatim) plus the
 * handler that turns a `params.arguments` object into a tool result.
 *
 * Phase 4c-1 ships only the three write-path capture tools. The
 * read-path tools (recall, get_note, list_recent, find_conflicts)
 * land in a follow-up alongside the hybrid pgvector+tsvector query.
 */
@Component
class McpTools(
    private val captureService: CaptureService,
) {
    private val tools: Map<String, McpTool> =
        listOf(
            captureLessonTool(),
            captureDecisionTool(),
            ingestNoteTool(),
        ).associateBy { it.descriptor["name"] as String }

    fun describe(): List<Map<String, Any?>> = tools.values.map { it.descriptor }

    fun call(
        name: String,
        arguments: JsonNode?,
    ): Map<String, Any?>? = tools[name]?.handler?.invoke(arguments ?: NULL_NODE)

    private fun captureLessonTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.capture_lesson",
                    description =
                        "Persist a lesson learned to the knowledge base. " +
                            "Returns the assigned ULID id so follow-up captures can supersedes-chain.",
                    required = listOf("scope", "title", "body"),
                    extraProperties =
                        mapOf(
                            "tags" to
                                mapOf(
                                    "type" to "array",
                                    "items" to mapOf("type" to "string"),
                                    "description" to "Free-form tags for later recall.",
                                ),
                        ),
                ),
            handler = { args -> captureResult(captureService.captureLesson(args.toCaptureRequest("claude-code"))) },
        )

    private fun captureDecisionTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.capture_decision",
                    description =
                        "Persist an architectural / process decision with its rationale. " +
                            "Same shape as capture_lesson; the type field flips to `decision`.",
                    required = listOf("scope", "title", "body"),
                ),
            handler = { args -> captureResult(captureService.captureDecision(args.toCaptureRequest("claude-code"))) },
        )

    private fun ingestNoteTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.ingest_note",
                    description =
                        "Persist a free-form note. Use for URLs, transcripts, screenshots, " +
                            "or anything that doesn't cleanly fit lesson/decision.",
                    required = listOf("scope", "title", "body"),
                    extraProperties =
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
                val type =
                    args.optionalString("type")?.let(KbNoteType::fromWire) ?: KbNoteType.NOTE
                captureResult(
                    captureService.captureGenericNote(args.toCaptureRequest("manual", typeOverride = type)),
                )
            },
        )

    private fun captureResult(note: com.jorisjonkers.personalstack.knowledge.domain.KbNote): Map<String, Any?> =
        mapOf(
            "id" to note.id,
            "type" to note.type.wire,
            "scope" to note.scope,
            "title" to note.title,
            "captured_at" to note.capturedAt.toString(),
            "vault_path" to note.vaultPath,
        )

    private fun toolDescriptor(
        name: String,
        description: String,
        required: List<String>,
        extraProperties: Map<String, Any> = emptyMap(),
    ): Map<String, Any> =
        mapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to
                mapOf(
                    "type" to "object",
                    "required" to required,
                    "properties" to baseProperties() + extraProperties,
                ),
        )

    private fun baseProperties(): Map<String, Any> =
        mapOf(
            "scope" to
                mapOf(
                    "type" to "string",
                    "description" to
                        "Access boundary. Examples: personal, work, agent:<name>, project:<repo>.",
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

    private fun JsonNode.toCaptureRequest(
        defaultSource: String,
        typeOverride: KbNoteType? = null,
    ): CaptureRequest {
        val required = listOf("scope", "title", "body").associateWith { requireString(it) }
        return CaptureRequest(
            type = typeOverride ?: KbNoteType.LESSON,
            scope = required.getValue("scope"),
            source = optionalString("source") ?: defaultSource,
            title = required.getValue("title"),
            body = required.getValue("body"),
            sessionId = optionalString("session_id"),
            confidence =
                optionalDouble("confidence") ?: CaptureRequest.DEFAULT_CONFIDENCE,
            vaultPath = optionalString("vault_path"),
            tags = optionalStringArray("tags").toSet(),
        )
    }

    private fun JsonNode.requireString(field: String): String =
        optionalString(field) ?: error("missing required field: $field")

    private fun JsonNode.optionalString(field: String): String? =
        get(field)
            ?.takeUnless { it.isNull }
            ?.takeIf { it.isTextual }
            ?.asText()
            ?.takeIf { it.isNotBlank() }

    private fun JsonNode.optionalDouble(field: String): Double? =
        get(field)?.takeUnless { it.isNull }?.takeIf { it.isNumber }?.asDouble()

    private fun JsonNode.optionalStringArray(field: String): List<String> {
        val node = get(field) ?: return emptyList()
        if (!node.isArray) return emptyList()
        return node.mapNotNull { if (it.isTextual) it.asText() else null }
    }

    companion object {
        private val NULL_NODE: JsonNode =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .createObjectNode()
    }
}

class McpTool(
    val descriptor: Map<String, Any?>,
    val handler: (JsonNode) -> Map<String, Any?>,
)
