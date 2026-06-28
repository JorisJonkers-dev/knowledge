package com.jorisjonkers.personalstack.knowledge.digest

import com.jorisjonkers.personalstack.knowledge.ollama.OllamaChatEndpointResolver
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
 * Endpoint selection is delegated to [OllamaChatEndpointResolver] —
 * the resolver probes the heavy host-native endpoint at startup +
 * on a fixed delay and picks heavy or light accordingly. Each
 * `chatJson` call asks the resolver for the current choice and
 * builds a per-call `RestClient` so a flip propagates without a pod
 * restart. The cost is one builder allocation per digest call; with
 * digests running on the order of seconds per call, the overhead is
 * irrelevant.
 *
 * The schema + system prompt live in [DigestService] (they're a
 * policy detail of the digest pass); this client only knows how to
 * call `/chat/completions` and unwrap the response envelope.
 */
@Component
class OllamaDigestClient(
    private val endpointResolver: OllamaChatEndpointResolver,
    @param:Value("\${knowledge.ollama.timeout-seconds:180}")
    private val timeoutSeconds: Long,
) {
    private val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun chatJson(
        systemPrompt: String,
        userPrompt: String,
        responseSchema: Map<String, Any?>,
    ): JsonNode {
        val endpoint = endpointResolver.current()
        val client =
            RestClient
                .builder()
                .baseUrl(endpoint.baseUrl)
                .build()
        val raw =
            client
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Timeout", Duration.ofSeconds(timeoutSeconds).toString())
                .body(
                    mapper.writeValueAsString(
                        buildPayload(
                            chatModel = endpoint.model,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            responseSchema = responseSchema,
                        ),
                    ),
                ).retrieve()
                .body<String>()
                ?: error("Ollama returned an empty body")
        return extractContent(raw)
    }

    private fun buildPayload(
        chatModel: String,
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
