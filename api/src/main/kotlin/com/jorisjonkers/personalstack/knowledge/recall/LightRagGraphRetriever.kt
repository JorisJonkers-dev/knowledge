package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.http.HttpClient
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale

/**
 * LightRAG graph/context leg for `knowledge.recall(mode=deep)`.
 *
 * The graph store is intentionally kept separate from the pgvector note
 * store: LightRAG uses its own embedding model and tables, so this class
 * contributes a source-grounded graph/context hit that deep recall can fuse
 * at the hit level without mixing vector spaces.
 */
@Component
class LightRagGraphRetriever(
    @param:Value("\${knowledge.recall.graph.enabled:false}")
    private val enabled: Boolean,
    @param:Value("\${knowledge.recall.graph.lightrag-url:http://lightrag.knowledge-system.svc.cluster.local:9621}")
    private val lightragUrl: String,
    @Value("\${knowledge.recall.graph.query-timeout-seconds:5}")
    timeoutSeconds: Long,
    @param:Value("\${knowledge.recall.graph.max-snippet-chars:2000}")
    private val maxSnippetChars: Int,
) : GraphRetriever {
    private val log = LoggerFactory.getLogger(LightRagGraphRetriever::class.java)
    private val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val client: RestClient = buildClient(lightragUrl, Duration.ofSeconds(timeoutSeconds))

    override fun retrieve(
        query: String,
        scope: String?,
        limit: Int,
    ): List<RecallHit> {
        if (!shouldQuery(query)) return emptyList()
        val text = queryLightRagSafely(query, limit)
        return if (text.isBlank()) {
            emptyList()
        } else {
            listOf(
                RecallHit(
                    id = "lightrag:${sha256(query).take(ID_HASH_CHARS)}",
                    type = "graph",
                    scope = scope ?: "default",
                    title = "LightRAG graph context",
                    snippet = text.take(maxSnippetChars.coerceAtLeast(MIN_SNIPPET_CHARS)),
                    score = GRAPH_HIT_SCORE,
                    tags = setOf("lightrag", "graph"),
                ),
            )
        }
    }

    private fun shouldQuery(query: String): Boolean = enabled && lightragUrl.isNotBlank() && query.isNotBlank()

    private fun queryLightRagSafely(
        query: String,
        limit: Int,
    ): String =
        try {
            queryLightRag(query, limit)
        } catch (ex: RestClientException) {
            log.warn("lightrag graph recall failed; deep recall will use hybrid candidates only", ex)
            ""
        } catch (ex: JacksonException) {
            log.warn("lightrag graph recall returned invalid JSON; deep recall will use hybrid candidates only", ex)
            ""
        } catch (ex: IllegalStateException) {
            log.warn(
                "lightrag graph recall returned an incomplete response; deep recall will use hybrid candidates only",
                ex,
            )
            ""
        }

    private fun queryLightRag(
        query: String,
        limit: Int,
    ): String {
        val payload =
            mapOf(
                "query" to query,
                "mode" to "mix",
                "top_k" to limit.coerceAtLeast(1),
                // Supported by current LightRAG servers and ignored by
                // older ones: prefer context over a polished answer so the
                // recall façade stays source-grounded.
                "only_need_context" to true,
            )
        val raw =
            client
                .post()
                .uri("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(payload))
                .retrieve()
                .body<String>()
                ?: return ""
        return mapper.readTree(raw).path("response").asString()
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and BYTE_MASK) }

    private companion object {
        private const val ID_HASH_CHARS = 16
        private const val BYTE_MASK = 0xff
        private const val MIN_SNIPPET_CHARS = 200
        private const val GRAPH_HIT_SCORE = 0.55

        fun buildClient(
            baseUrl: String,
            timeout: Duration,
        ): RestClient {
            val httpClient =
                HttpClient
                    .newBuilder()
                    .connectTimeout(timeout)
                    .build()
            val factory = JdkClientHttpRequestFactory(httpClient)
            factory.setReadTimeout(timeout)
            return RestClient
                .builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build()
        }
    }
}
