package com.jorisjonkers.personalstack.knowledge.digest

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration

/**
 * OpenAI-compatible Ollama chat-completions client used by
 * [DigestService] to drive the transcript-digest call.
 *
 * Mirrors the curator's `OllamaClassifier` shape — one stateless
 * client per process, JSON-schema-constrained output via
 * `response_format: {type: "json_schema", json_schema: {...}}` so
 * Ollama's GBNF grammar enforces the structure at sampling time
 * rather than relying on the model to honour a prompt.
 *
 * The schema + system prompt live in [DigestService] (they're a
 * policy detail of the digest pass); this client only knows how to
 * call `/chat/completions` and unwrap the response envelope.
 */
@Component
class OllamaDigestClient(
    @param:Value("\${knowledge.ollama.base-url:http://ollama.knowledge-system.svc.cluster.local:11434/v1}")
    private val baseUrl: String,
    @param:Value("\${knowledge.ollama.chat-model:qwen2.5:7b-instruct}")
    private val chatModel: String,
    @param:Value("\${knowledge.ollama.timeout-seconds:180}")
    private val timeoutSeconds: Long,
) {
    private val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val client: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .build()

    fun chatJson(
        systemPrompt: String,
        userPrompt: String,
        responseSchema: Map<String, Any?>,
    ): JsonNode {
        val raw =
            client
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Timeout", Duration.ofSeconds(timeoutSeconds).toString())
                .body(mapper.writeValueAsString(buildPayload(systemPrompt, userPrompt, responseSchema)))
                .retrieve()
                .body<String>()
                ?: error("Ollama returned an empty body")
        return extractContent(raw)
    }

    private fun buildPayload(
        systemPrompt: String,
        userPrompt: String,
        responseSchema: Map<String, Any?>,
    ): Map<String, Any?> =
        mapOf(
            "model" to chatModel,
            "messages" to
                listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userPrompt),
                ),
            "temperature" to 0.0,
            "response_format" to mapOf("type" to "json_schema", "json_schema" to responseSchema),
            "max_tokens" to MAX_TOKENS,
        )

    private fun extractContent(raw: String): JsonNode {
        val parsed = mapper.readTree(raw)
        val content =
            parsed
                .path("choices")
                .firstOrNull()
                ?.path("message")
                ?.path("content")
                ?.asString()
                ?: error("Ollama response missing choices[0].message.content")
        return mapper.readTree(content)
    }

    companion object {
        // Headroom for the longest digest array we expect — 5
        // candidates × roughly 800 tokens of body + metadata.
        private const val MAX_TOKENS = 4096
    }
}
