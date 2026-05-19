package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.digest.DigestService
import com.jorisjonkers.personalstack.knowledge.domain.DigestCandidate
import org.springframework.stereotype.Component

/**
 * Read-side MCP tool exposing the Reflexion-style session-digest
 * pass. One tool, `knowledge.digest_transcript`, returns candidate
 * captures the client decides whether to emit.
 *
 * The split between this tool and the write tools (capture_lesson,
 * capture_decision, ingest_note) is deliberate: this tool is
 * stateless inference, the captures are stateful writes with their
 * own provenance + auth surface. The auto-MCP Stop hook calls this
 * tool, applies its safety policy (confidence floor, dedupe, rate
 * limit, path allowlist, panic switch), and only then calls a
 * capture tool. Same separation MemGPT uses between "should we
 * push to long-term memory?" and "the push itself".
 */
@Component
class DigestMcpTools(
    private val digestService: DigestService,
) {
    fun tools(): List<McpTool> = listOf(McpTool(digestDescriptor(), ::digestHandler))

    private fun digestDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.digest_transcript",
            description =
                "Reflexion-style digest of a session transcript. Returns up to " +
                    "`max_candidates` lesson-worthy moments with self-rated " +
                    "confidence, suggested topic, suggested tags, and a few " +
                    "supporting excerpts. The client decides which to capture; " +
                    "this tool emits nothing on its own.",
            required = listOf("transcript"),
            properties = digestProperties(),
        )

    private fun digestProperties(): Map<String, Any> =
        mapOf(
            "transcript" to
                mapOf(
                    "type" to "string",
                    "description" to
                        "Raw session transcript text. Long transcripts are trimmed " +
                        "head+tail by the server before the model sees them.",
                ),
            "max_candidates" to
                mapOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to MAX_CANDIDATES,
                    "default" to DigestService.DEFAULT_MAX_CANDIDATES,
                ),
            "min_confidence" to
                mapOf(
                    "type" to "number",
                    "minimum" to 0.0,
                    "maximum" to 1.0,
                    "default" to DigestService.DEFAULT_MIN_CONFIDENCE,
                    "description" to
                        "Server-side floor applied before returning candidates. " +
                        "The Stop hook applies a second client-side floor for the " +
                        "actual emit decision.",
                ),
        )

    private fun digestHandler(args: tools.jackson.databind.JsonNode): Map<String, Any?> {
        val transcript = JsonArguments.requireString(args, "transcript")
        val maxCandidates =
            JsonArguments.optionalInt(args, "max_candidates") ?: DigestService.DEFAULT_MAX_CANDIDATES
        val minConfidence =
            JsonArguments.optionalDouble(args, "min_confidence") ?: DigestService.DEFAULT_MIN_CONFIDENCE
        val candidates = digestService.digest(transcript, maxCandidates, minConfidence)
        return mapOf("candidates" to candidates.map(::projectCandidate))
    }

    private fun projectCandidate(candidate: DigestCandidate): Map<String, Any?> =
        mapOf(
            "kind" to candidate.kind,
            "title" to candidate.title,
            "body" to candidate.body,
            "suggested_topic" to candidate.suggestedTopic,
            "suggested_tags" to candidate.suggestedTags,
            "confidence" to candidate.confidence,
            "relevant_excerpts" to candidate.relevantExcerpts,
        )

    private companion object {
        const val MAX_CANDIDATES = 10
    }
}
