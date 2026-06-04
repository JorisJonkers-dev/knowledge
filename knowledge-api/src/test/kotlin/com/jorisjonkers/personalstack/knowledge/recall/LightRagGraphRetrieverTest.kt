package com.jorisjonkers.personalstack.knowledge.recall

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class LightRagGraphRetrieverTest {
    private lateinit var server: HttpServer
    private val handler = RecordingHandler()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/query", handler)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `disabled retriever returns empty without calling lightrag`() {
        val retriever = retriever(enabled = false)

        val hits = retriever.retrieve("vault cli", "project:personal-stack", 3)

        assertThat(hits).isEmpty()
        assertThat(handler.requestCount()).isZero()
    }

    @Test
    fun `enabled retriever maps lightrag context to a graph recall hit`() {
        handler.set(200, """{"response":"Use port-forward and VAULT_ADDR for CLI access."}""")
        val retriever = retriever()

        val hits = retriever.retrieve("vault cli", "project:personal-stack", 2)

        assertThat(hits).hasSize(1)
        val hit = hits.single()
        assertThat(hit.id).startsWith("lightrag:")
        assertThat(hit.type).isEqualTo("graph")
        assertThat(hit.scope).isEqualTo("project:personal-stack")
        assertThat(hit.title).isEqualTo("LightRAG graph context")
        assertThat(hit.snippet).contains("port-forward", "VAULT_ADDR")
        assertThat(hit.tags).containsExactlyInAnyOrder("lightrag", "graph")
        assertThat(handler.lastRequest()).contains(
            "\"query\":\"vault cli\"",
            "\"mode\":\"mix\"",
            "\"top_k\":2",
            "\"only_need_context\":true",
        )
    }

    @Test
    fun `lightrag failures degrade to empty graph hits`() {
        handler.set(503, "unavailable")
        val retriever = retriever()

        val hits = retriever.retrieve("vault cli", "project:personal-stack", 2)

        assertThat(hits).isEmpty()
    }

    private fun retriever(enabled: Boolean = true): LightRagGraphRetriever =
        LightRagGraphRetriever(
            enabled = enabled,
            lightragUrl = "http://127.0.0.1:${server.address.port}",
            timeoutSeconds = 1,
            maxSnippetChars = 500,
        )

    private class RecordingHandler : HttpHandler {
        private var status: Int = 200
        private var body: String = """{"response":""}"""
        private val requests = mutableListOf<String>()

        @Synchronized
        fun set(
            status: Int,
            body: String,
        ) {
            this.status = status
            this.body = body
        }

        @Synchronized
        fun requestCount(): Int = requests.size

        @Synchronized
        fun lastRequest(): String = requests.last()

        @Synchronized
        override fun handle(exchange: HttpExchange) {
            requests += exchange.requestBody.bufferedReader().use { it.readText() }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
