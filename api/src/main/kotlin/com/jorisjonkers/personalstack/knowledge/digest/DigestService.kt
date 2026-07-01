package com.jorisjonkers.personalstack.knowledge.digest

import com.jorisjonkers.personalstack.knowledge.domain.DigestCandidate
import com.jorisjonkers.personalstack.knowledge.repo.DiscoveryRepository
import com.jorisjonkers.personalstack.knowledge.repo.TopicRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode

/**
 * Reflexion-style session-digest service. Given a session
 * transcript, asks an Ollama model to identify lesson-worthy
 * moments and return them as structured candidate captures.
 *
 * Anchors in the literature:
 *   - Reflexion (Shinn et al. 2023, arXiv:2303.11366) — verbal RL
 *     where the agent reflects on the just-finished episode and
 *     writes lessons to a long-term store. The digest pass IS the
 *     reflection step; the consumer's confidence floor + dedupe
 *     stand in for Reflexion's "actor critic" filter.
 *   - Generative Agents (Park et al. 2023, arXiv:2304.03442) —
 *     reflection at session boundaries (not per turn) synthesises
 *     higher-level abstractions over events. Same cadence here.
 *   - MemGPT (Packer et al. 2023, arXiv:2310.08560) — the agent
 *     decides when to push to long-term memory via reflection
 *     tokens. Self-rated `confidence` is the analog here.
 *
 * The service does NOT emit captures itself. It returns candidates;
 * the auto-MCP hook script applies the policy (confidence floor,
 * dedupe, rate limit, provenance) and calls `knowledge.capture_lesson`
 * for survivors. Splitting policy from inference keeps the service
 * stateless and the hook's safety surface auditable.
 */
@Service
class DigestService(
    private val ollama: OllamaDigestClient,
    private val topicRepository: TopicRepository,
    private val discoveryRepository: DiscoveryRepository,
) {
    private val log = LoggerFactory.getLogger(DigestService::class.java)

    fun digest(
        transcript: String,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    ): List<DigestCandidate> =
        if (transcript.isBlank()) {
            emptyList()
        } else {
            digestTranscript(transcript, maxCandidates, minConfidence)
        }

    private fun digestTranscript(
        transcript: String,
        maxCandidates: Int,
        minConfidence: Double,
    ): List<DigestCandidate> {
        val cappedMax = maxCandidates.coerceIn(1, ABSOLUTE_MAX_CANDIDATES)
        val topicSlugs = topicRepository.listActive().map { it.slug }
        val knownTags = discoveryRepository.listTags(scope = null, limit = TAG_VOCAB_FOR_PROMPT).map { it.tag }
        val parsed = requestDigest(transcript, topicSlugs, knownTags, cappedMax)?.candidateArray()
        return parsed
            ?.mapNotNull(::parseCandidate)
            ?.filter { it.confidence >= minConfidence }
            ?.take(cappedMax)
            .orEmpty()
    }

    private fun requestDigest(
        transcript: String,
        topicSlugs: List<String>,
        knownTags: List<String>,
        cappedMax: Int,
    ): JsonNode? =
        try {
            ollama.chatJson(
                systemPrompt = systemPrompt(topicSlugs, knownTags, cappedMax),
                userPrompt = userPrompt(transcript),
                responseSchema = responseSchema(cappedMax),
            )
        } catch (exc: RestClientException) {
            log.warn("digest.ollama_failed", exc)
            null
        } catch (exc: JacksonException) {
            log.warn("digest.ollama_invalid_json", exc)
            null
        } catch (exc: IllegalStateException) {
            log.warn("digest.ollama_incomplete_response", exc)
            null
        }

    private fun JsonNode.candidateArray(): JsonNode? =
        path("candidates").takeIf { it.isArray }
            ?: takeIf { it.isArray }

    private fun parseCandidate(node: JsonNode): DigestCandidate? {
        val kind =
            node
                .path("kind")
                .asString()
                .orEmpty()
                .lowercase()
        val title = node.path("title").asString().orEmpty()
        val body = node.path("body").asString().orEmpty()
        return if (kind in VALID_KINDS && title.length >= MIN_TITLE_LENGTH && body.length >= MIN_BODY_LENGTH) {
            DigestCandidate(
                kind = kind,
                title = title,
                body = body,
                suggestedTopic = node.path("suggested_topic").asString().takeUnless { it.isNullOrBlank() },
                suggestedTags = parseStringArray(node, "suggested_tags", MAX_TAGS_PER_CANDIDATE),
                confidence = node.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
                relevantExcerpts = parseStringArray(node, "relevant_excerpts", MAX_EXCERPTS_PER_CANDIDATE),
            )
        } else {
            null
        }
    }

    private fun parseStringArray(
        node: JsonNode,
        field: String,
        cap: Int,
    ): List<String> =
        node
            .path(field)
            .filter { it.isString }
            .map { it.asString() }
            .filter { it.isNotBlank() }
            .take(cap)

    companion object {
        const val DEFAULT_MAX_CANDIDATES: Int = 5
        const val DEFAULT_MIN_CONFIDENCE: Double = 0.65
        private const val ABSOLUTE_MAX_CANDIDATES = 10
        private const val MIN_TITLE_LENGTH = 4
        private const val MIN_BODY_LENGTH = 30
        private const val MAX_TAGS_PER_CANDIDATE = 8
        private const val MAX_EXCERPTS_PER_CANDIDATE = 3
        private const val TAG_VOCAB_FOR_PROMPT = 60
        private val VALID_KINDS = setOf("lesson", "decision", "note", "fact")

        fun systemPrompt(
            topicSlugs: List<String>,
            knownTags: List<String>,
            maxCandidates: Int,
        ): String =
            buildString {
                append("You are summarising an AI-coding session for lessons worth ")
                append("capturing in a knowledge base. Read the transcript and identify ")
                append("up to ").append(maxCandidates).append(" LESSON-WORTHY moments:\n")
                append("- a non-trivial problem was solved and the resolution mechanism generalises;\n")
                append("- a design decision was made with a clear rationale that survives context-switches;\n")
                append("- a gotcha or constraint was discovered that future sessions should know about.\n\n")
                append("Skip trivial moments (typos, formatting, throwaway experiments). Most sessions ")
                append("yield 0-2 candidates; do not pad the array to hit the limit.\n\n")
                append("Topic vocabulary (closed set, pick `suggested_topic` from these or null): ")
                append(topicSlugs.joinToString(", ")).append(".\n")
                if (knownTags.isNotEmpty()) {
                    append("Existing tags (prefer reuse over invention): ")
                    append(knownTags.take(TAG_VOCAB_FOR_PROMPT).joinToString(", ")).append(".\n")
                }
                append("\nReturn a JSON object `{\"candidates\": [...] }`. Each candidate:\n")
                append("- kind: \"lesson\" | \"decision\" | \"note\" | \"fact\"\n")
                append("- title: one line, declarative, present tense\n")
                append("- body: 2-4 paragraphs of markdown — situation, mechanism, resolution, generalisation\n")
                append("- suggested_topic: closed-vocab slug or null\n")
                append("- suggested_tags: 2-5 strings from the existing tags if any fit, otherwise new ones\n")
                append("- confidence: 0.0-1.0 self-rating of lesson-worthiness\n")
                append("- relevant_excerpts: 1-3 short quotes from the transcript supporting the lesson\n")
                append("\nIf nothing is lesson-worthy, return `{\"candidates\": []}`.\n")
            }

        fun userPrompt(transcript: String): String =
            buildString {
                append("<transcript>\n")
                // Trim long transcripts to keep the prompt within the
                // model's context window. Reflexion's original work
                // showed the head + tail of an episode carries most
                // of the learnable signal; the middle is usually
                // mechanical tool calls.
                val trimmed = trimTranscript(transcript)
                append(trimmed)
                append("\n</transcript>")
            }

        private const val MAX_TRANSCRIPT_CHARS = 60_000
        private const val HEAD_TAIL_SHARE = 0.45

        private fun trimTranscript(transcript: String): String {
            if (transcript.length <= MAX_TRANSCRIPT_CHARS) return transcript
            val edge = (MAX_TRANSCRIPT_CHARS * HEAD_TAIL_SHARE).toInt()
            val head = transcript.take(edge)
            val tail = transcript.takeLast(edge)
            return "$head\n\n... [transcript trimmed; middle elided] ...\n\n$tail"
        }

        fun responseSchema(maxCandidates: Int): Map<String, Any?> =
            mapOf(
                "name" to "session_digest",
                "schema" to
                    mapOf(
                        "type" to "object",
                        "required" to listOf("candidates"),
                        "additionalProperties" to false,
                        "properties" to
                            mapOf(
                                "candidates" to
                                    mapOf(
                                        "type" to "array",
                                        "maxItems" to maxCandidates,
                                        "items" to candidateSchema(),
                                    ),
                            ),
                    ),
                "strict" to true,
            )

        private fun candidateSchema(): Map<String, Any?> =
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to
                    listOf("kind", "title", "body", "suggested_tags", "confidence", "relevant_excerpts"),
                "properties" to
                    mapOf(
                        "kind" to mapOf("type" to "string", "enum" to VALID_KINDS.toList()),
                        "title" to mapOf("type" to "string", "minLength" to MIN_TITLE_LENGTH),
                        "body" to mapOf("type" to "string", "minLength" to MIN_BODY_LENGTH),
                        "suggested_topic" to mapOf("type" to listOf("string", "null")),
                        "suggested_tags" to
                            mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string"),
                                "maxItems" to MAX_TAGS_PER_CANDIDATE,
                            ),
                        "confidence" to mapOf("type" to "number", "minimum" to 0.0, "maximum" to 1.0),
                        "relevant_excerpts" to
                            mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string"),
                                "maxItems" to MAX_EXCERPTS_PER_CANDIDATE,
                            ),
                    ),
            )
    }
}
