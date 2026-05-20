package com.jorisjonkers.personalstack.knowledge.recall

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Ollama implementation of [QueryEmbedder]. Calls
 * `POST /v1/embeddings` against the cluster Ollama instance.
 *
 * Same OpenAI-compat path the curator uses for note embeddings —
 * MUST stay aligned with `OLLAMA_EMBEDDING_MODEL` on the curator
 * side, otherwise the query vector lives in a different latent space
 * from the stored note vectors.
 *
 * Timeout is intentionally short (5s default): the recall hot path
 * is latency-sensitive and the FTS leg can carry the response on its
 * own if Ollama is slow. `RecallService.recallHybrid` catches
 * exceptions raised here and degrades gracefully.
 */
@Component
class OllamaQueryEmbedder(
    @param:Value("\${knowledge.ollama.base-url:http://ollama.knowledge-system.svc.cluster.local:11434/v1}")
    private val baseUrl: String,
    @param:Value("\${knowledge.ollama.embedding-model:qwen3-embedding:0.6b}")
    private val embeddingModel: String,
) : QueryEmbedder {
    private val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val client: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .build()

    override fun embed(query: String): FloatArray {
        val raw =
            client
                .post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(mapOf("model" to embeddingModel, "input" to query)))
                .retrieve()
                .body<String>()
                ?: error("Ollama /embeddings returned an empty body")
        val parsed = mapper.readTree(raw)
        val embedding =
            parsed
                .path("data")
                .firstOrNull()
                ?.path("embedding")
                ?: error("Ollama /embeddings response missing data[0].embedding")
        return FloatArray(embedding.size()) { i -> embedding.get(i).asDouble().toFloat() }
    }
}
