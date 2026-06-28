package com.jorisjonkers.personalstack.knowledge.ollama

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort selector between a heavy and light Ollama chat endpoint.
 *
 * The "heavy" endpoint is the host-native Ollama on
 * `enschede-rx7900xtx-1` (RX 7900 XTX, 24 GiB VRAM), exposed through
 * the `ollama-heavy.utility-system` selector-less Service. The
 * "light" endpoint is the in-cluster CPU Ollama in
 * `knowledge-system`. Whenever the heavy probe succeeds the digest
 * client routes to it with a larger model; otherwise it falls back to
 * the existing in-cluster pair.
 *
 * Caching: one probe at startup, then a [Scheduled] refresh on the
 * configured cadence (default 120 s). Per-call probing would add
 * detectable latency to every digest request.
 *
 * Embedding + reranker calls do NOT go through this resolver —
 * flipping embedding models per request would force the curator's
 * backfill CronJob into permanent re-embedding loops. Only the
 * chat-completion (digest) path is endpoint-aware.
 */
@Component
class OllamaChatEndpointResolver(
    @param:Value("\${knowledge.ollama.base-url:http://ollama.knowledge-system.svc.cluster.local:11434/v1}")
    private val lightBaseUrl: String,
    @param:Value("\${knowledge.ollama.chat-model:qwen2.5:7b-instruct}")
    private val lightChatModel: String,
    @param:Value("\${knowledge.ollama.heavy-base-url:}")
    private val heavyBaseUrl: String,
    @param:Value("\${knowledge.ollama.heavy-chat-model:qwen2.5:14b-instruct}")
    private val heavyChatModel: String,
    @param:Value("\${knowledge.ollama.heavy-probe-timeout-ms:2000}")
    private val heavyProbeTimeoutMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val probeClient: RestClient = buildProbeClient(Duration.ofMillis(heavyProbeTimeoutMs))
    private val light: Endpoint = Endpoint(lightBaseUrl, lightChatModel, Profile.LIGHT)
    private val current: AtomicReference<Endpoint> = AtomicReference(light)

    init {
        refresh()
    }

    fun current(): Endpoint = current.get()

    /**
     * Re-probe the heavy endpoint and update the cached choice.
     * Idempotent — safe to call from `@Scheduled` and from tests.
     * Picks up a node coming back online within one tick.
     */
    @Scheduled(
        fixedDelayString = "\${knowledge.ollama.heavy-probe-cache-seconds:120}000",
        initialDelayString = "\${knowledge.ollama.heavy-probe-cache-seconds:120}000",
    )
    fun refresh() {
        val heavyBase = heavyBaseUrl.trim()
        if (heavyBase.isEmpty()) {
            // Inert configuration — never probe; just stay on light.
            current.set(light)
            return
        }
        val resolved = probeHeavy(heavyBase)
        val previous = current.getAndSet(resolved)
        if (previous.profile != resolved.profile) {
            log.info(
                "ollama_resolver.endpoint_flipped from={} to={} base_url={} model={}",
                previous.profile,
                resolved.profile,
                resolved.baseUrl,
                resolved.model,
            )
        }
    }

    /**
     * Single-shot probe. Returns heavy on any 2xx; light on any
     * [RestClientException] subclass — that covers
     * [org.springframework.web.client.ResourceAccessException] for
     * transport errors (connect refused, timeout, DNS) and the 4xx /
     * 5xx response-status subclasses. A 3xx redirect is treated as
     * unreachable since the Ollama OpenAI-compat surface never
     * redirects in healthy operation.
     */
    private fun probeHeavy(heavyBase: String): Endpoint {
        val probeUrl = heavyBase.trimEnd('/') + "/models"
        return try {
            probeClient
                .get()
                .uri(probeUrl)
                .retrieve()
                .toBodilessEntity()
            Endpoint(heavyBase, heavyChatModel, Profile.HEAVY)
        } catch (ex: RestClientException) {
            log.warn(
                "ollama_resolver.probe_failed probe_url={} error={}: {}",
                probeUrl,
                ex.javaClass.simpleName,
                ex.message,
            )
            light
        }
    }

    data class Endpoint(
        val baseUrl: String,
        val model: String,
        val profile: Profile,
    )

    enum class Profile { HEAVY, LIGHT }

    private companion object {
        /**
         * Spring's default request factory doesn't bound connect + read
         * timeouts the way "best effort" wants — the cluster-default 30 s
         * on a TCP black-hole would freeze the scheduler thread for half a
         * minute on every missed probe. Wire the JDK HttpClient with the
         * configured timeout on both legs instead.
         */
        @JvmStatic
        fun buildProbeClient(timeout: Duration): RestClient {
            val httpClient =
                HttpClient
                    .newBuilder()
                    .connectTimeout(timeout)
                    .build()
            val factory = JdkClientHttpRequestFactory(httpClient)
            factory.setReadTimeout(timeout)
            return RestClient.builder().requestFactory(factory).build()
        }
    }
}
