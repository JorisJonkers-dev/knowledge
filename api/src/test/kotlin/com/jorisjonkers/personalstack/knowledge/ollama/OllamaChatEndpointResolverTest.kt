package com.jorisjonkers.personalstack.knowledge.ollama

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Unit tests for [OllamaChatEndpointResolver].
 *
 * Uses the JDK's `com.sun.net.httpserver.HttpServer` instead of
 * pulling WireMock into the build — knowledge-api already mocks HTTP
 * with `mockk` everywhere else, and the resolver's only collaborator
 * is the network. The JDK server is light enough that the cost of
 * spinning one up per test is hidden by other test latency.
 *
 * Each test drives `refresh()` explicitly so behaviour doesn't race
 * the `@Scheduled` annotation.
 */
class OllamaChatEndpointResolverTest {
    private lateinit var server: HttpServer
    private val handler: AtomicHandler = AtomicHandler()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models", handler)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `returns light when heavy base URL is blank`() {
        val resolver =
            OllamaChatEndpointResolver(
                lightBaseUrl = "http://light.local/v1",
                lightChatModel = "qwen3:8b",
                heavyBaseUrl = "",
                heavyChatModel = "qwen2.5:14b-instruct",
                heavyProbeTimeoutMs = 500,
            )

        val endpoint = resolver.current()

        assertThat(endpoint.profile).isEqualTo(OllamaChatEndpointResolver.Profile.LIGHT)
        assertThat(endpoint.baseUrl).isEqualTo("http://light.local/v1")
        assertThat(endpoint.model).isEqualTo("qwen3:8b")
    }

    @Test
    fun `returns heavy when probe succeeds`() {
        handler.set(200, """{"object":"list","data":[{"id":"qwen2.5:14b-instruct","object":"model"}]}""")

        val resolver =
            OllamaChatEndpointResolver(
                lightBaseUrl = "http://light.local/v1",
                lightChatModel = "qwen3:8b",
                heavyBaseUrl = baseUrl(),
                heavyChatModel = "qwen2.5:14b-instruct",
                heavyProbeTimeoutMs = 1500,
            )

        val endpoint = resolver.current()

        assertThat(endpoint.profile).isEqualTo(OllamaChatEndpointResolver.Profile.HEAVY)
        assertThat(endpoint.baseUrl).isEqualTo(baseUrl())
        assertThat(endpoint.model).isEqualTo("qwen2.5:14b-instruct")
    }

    @Test
    fun `falls back to light when probe returns 5xx`() {
        handler.set(503, "service unavailable")

        val resolver =
            OllamaChatEndpointResolver(
                lightBaseUrl = "http://light.local/v1",
                lightChatModel = "qwen3:8b",
                heavyBaseUrl = baseUrl(),
                heavyChatModel = "qwen2.5:14b-instruct",
                heavyProbeTimeoutMs = 1500,
            )

        val endpoint = resolver.current()

        assertThat(endpoint.profile).isEqualTo(OllamaChatEndpointResolver.Profile.LIGHT)
    }

    @Test
    fun `falls back to light when heavy URL is unreachable`() {
        // Use the bound server's port but stop the server before
        // constructing the resolver so the connect attempt fails fast.
        val deadBase = baseUrl()
        server.stop(0)

        val resolver =
            OllamaChatEndpointResolver(
                lightBaseUrl = "http://light.local/v1",
                lightChatModel = "qwen3:8b",
                heavyBaseUrl = deadBase,
                heavyChatModel = "qwen2.5:14b-instruct",
                heavyProbeTimeoutMs = 500,
            )

        val endpoint = resolver.current()

        assertThat(endpoint.profile).isEqualTo(OllamaChatEndpointResolver.Profile.LIGHT)

        // Re-create a server for AfterEach to stop without surprises.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @Test
    fun `refresh picks up a freshly-up heavy endpoint`() {
        handler.set(503, "service unavailable")

        val resolver =
            OllamaChatEndpointResolver(
                lightBaseUrl = "http://light.local/v1",
                lightChatModel = "qwen3:8b",
                heavyBaseUrl = baseUrl(),
                heavyChatModel = "qwen2.5:14b-instruct",
                heavyProbeTimeoutMs = 1500,
            )

        assertThat(resolver.current().profile)
            .isEqualTo(OllamaChatEndpointResolver.Profile.LIGHT)

        handler.set(200, """{"object":"list","data":[]}""")
        resolver.refresh()

        assertThat(resolver.current().profile)
            .isEqualTo(OllamaChatEndpointResolver.Profile.HEAVY)
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}/v1"

    /**
     * Thread-safe stub handler. Tests set the response just before
     * calling `refresh()`; the HTTP server thread reads it under the
     * synchronized accessor.
     */
    private class AtomicHandler : HttpHandler {
        private var status: Int = 200
        private var body: String = "{}"

        @Synchronized
        fun set(
            status: Int,
            body: String,
        ) {
            this.status = status
            this.body = body
        }

        @Synchronized
        override fun handle(exchange: HttpExchange) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status.toLong().toInt(), bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
