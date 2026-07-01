package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Listwise reranker backed by Ollama. Sends the query + an enumerated
 * candidate list to the chat-completions endpoint and asks the model
 * to return the ids in best-to-worst order. The output is validated
 * as a permutation of the input (subset, no novel ids) before being
 * returned.
 *
 * Default model is `qwen3-reranker:0.6b` — pinned cheap; Qwen3's
 * reranker family beats `bge-reranker-v2-m3` on MMTEB and runs in
 * ~1 GiB VRAM. Override via `knowledge.ollama.reranker-model`.
 *
 * Failure-by-fallback: any HTTP, parse, or validation issue returns
 * the input order unchanged. The caller treats "rerank applied"
 * vs "rerank fell through" as best-effort precision, never a
 * correctness boundary.
 */
@Component
class OllamaListwiseReranker(
    @param:Value("\${knowledge.ollama.base-url:http://ollama.knowledge-system.svc.cluster.local:11434/v1}")
    private val baseUrl: String,
    @param:Value("\${knowledge.ollama.reranker-model:qwen3-reranker:0.6b}")
    private val rerankerModel: String,
) : Reranker {
    private val log = LoggerFactory.getLogger(OllamaListwiseReranker::class.java)

    private val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val client: RestClient = RestClient.builder().baseUrl(baseUrl).build()

    override fun rerank(
        query: String,
        candidates: List<RecallHit>,
        keep: Int,
    ): List<RecallHit> =
        when {
            candidates.isEmpty() -> emptyList()
            candidates.size == 1 -> candidates.take(keep)
            else -> rerankOrNull(query, candidates)?.take(keep) ?: candidates.take(keep)
        }

    private fun rerankOrNull(
        query: String,
        candidates: List<RecallHit>,
    ): List<RecallHit>? {
        val raw =
            try {
                callOllama(query, candidates)
            } catch (ex: RestClientException) {
                log.warn("rerank: ollama call failed, falling back to pre-rerank order", ex)
                null
            } catch (ex: IllegalStateException) {
                log.warn("rerank: ollama response was incomplete, falling back to pre-rerank order", ex)
                null
            }
        val ordered = raw?.let { parseAndValidate(it, candidates) }
        if (raw != null && ordered == null) {
            log.warn("rerank: response failed validation, falling back to pre-rerank order")
        }
        return ordered
    }

    private fun callOllama(
        query: String,
        candidates: List<RecallHit>,
    ): String {
        val payload =
            mapOf(
                "model" to rerankerModel,
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                        mapOf("role" to "user", "content" to userPrompt(query, candidates)),
                    ),
                "temperature" to 0.0,
                "max_tokens" to (candidates.size * MAX_TOKENS_PER_ID + MAX_TOKENS_PADDING),
            )
        return client
            .post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(payload))
            .retrieve()
            .body<String>()
            ?: error("Ollama /chat/completions returned an empty body for rerank")
    }

    /**
     * Validate that the model returned a permutation (subset, no novel
     * ids) of the input candidates' ids. Returns the ordered hit list
     * or null on any validation failure.
     */
    private fun parseAndValidate(
        raw: String,
        candidates: List<RecallHit>,
    ): List<RecallHit>? =
        try {
            parseAndValidateJson(raw, candidates)
        } catch (ex: JacksonException) {
            log.warn("rerank: response was invalid JSON, falling back to pre-rerank order", ex)
            null
        }

    private fun parseAndValidateJson(
        raw: String,
        candidates: List<RecallHit>,
    ): List<RecallHit>? {
        val node = mapper.readTree(raw)
        val content =
            node
                .path("choices")
                .firstOrNull()
                ?.path("message")
                ?.path("content")
                ?.asString()
                .orEmpty()
        return if (content.isBlank()) {
            null
        } else {
            val tokenized =
                content
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.removeSuffix(",").removePrefix("-").trim() }
            val byId = candidates.associateBy { it.id }
            tokenized.mapNotNull { byId[it] }.takeIf { it.isNotEmpty() }
        }
    }

    private fun userPrompt(
        query: String,
        candidates: List<RecallHit>,
    ): String {
        val enumerated =
            candidates.joinToString(separator = "\n") { hit ->
                "${hit.id} | ${hit.title} — ${hit.snippet.take(SNIPPET_CHARS)}"
            }
        return "Query:\n$query\n\nCandidates (id | title — snippet):\n$enumerated\n\nReturn ids only, " +
            "best to worst, one per line."
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a precise listwise reranker. Given a query and a list of candidate notes, " +
                "return only the candidate ids in best-to-worst order, one per line. Do not invent " +
                "ids or add commentary."

        private const val MAX_TOKENS_PER_ID = 32
        private const val MAX_TOKENS_PADDING = 64
        private const val SNIPPET_CHARS = 200
    }
}
