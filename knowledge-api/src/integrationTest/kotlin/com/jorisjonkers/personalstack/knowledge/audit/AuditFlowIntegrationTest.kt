package com.jorisjonkers.personalstack.knowledge.audit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.repo.AuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant

/**
 * End-to-end coverage of the `kb_audit` log: write a few rows via
 * the repository (mirroring what the curator's future passes will
 * do), then drive `knowledge.list_audit` through MockMvc + the
 * `CallToolResult` envelope.
 *
 * Pins the filter composition (actor / action / target_id / since)
 * so a future refactor can't silently regress one of them.
 */
@TestPropertySource(
    properties = [
        "knowledge.mcp.tokens.ws=test-token-ws",
    ],
)
class AuditFlowIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var mcpBearerFilter: McpBearerFilter

    @Autowired
    private lateinit var auditRepository: AuditRepository

    private lateinit var mockMvc: MockMvc

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(mcpBearerFilter)
                .build()
    }

    private fun seed(
        actor: String,
        action: String,
        targetId: String? = null,
        targetKind: String? = null,
        beforeJson: String? = null,
        afterJson: String? = null,
        at: Instant = Instant.now(),
    ) = auditRepository.record(
        actor = actor,
        action = action,
        targetId = targetId,
        targetKind = targetKind,
        beforeJson = beforeJson,
        afterJson = afterJson,
        now = at,
    )

    @Test
    fun `list_audit returns most-recent-first by default`() {
        val older = seed(actor = "kb-curator-resolver", action = "drop_relation")
        Thread.sleep(LIST_SLEEP_MS)
        val newer = seed(actor = "kb-curator-resolver", action = "resolve_relation")

        val rows = toolResult(call("knowledge.list_audit", mapOf("limit" to 10)))["rows"]
        val ids = rows.map { it["id"].asText() }

        // ULIDs sort lex by time — `ORDER BY id DESC` is recency-first.
        assertThat(ids).containsExactly(newer.id, older.id)
    }

    @Test
    fun `list_audit filters by actor`() {
        val first = seed(actor = "mcp:workstation", action = "add_topic")
        seed(actor = "kb-renormalise-titles", action = "rename_title")

        val rows = toolResult(call("knowledge.list_audit", mapOf("actor" to "mcp:workstation")))["rows"]
        val ids = rows.map { it["id"].asText() }

        assertThat(ids).containsExactly(first.id)
    }

    @Test
    fun `list_audit filters by action`() {
        val first = seed(actor = "kb-curator-resolver", action = "resolve_relation")
        seed(actor = "kb-curator-resolver", action = "drop_relation")

        val rows = toolResult(call("knowledge.list_audit", mapOf("action" to "resolve_relation")))["rows"]
        val ids = rows.map { it["id"].asText() }

        assertThat(ids).containsExactly(first.id)
    }

    @Test
    fun `list_audit filters by target_id`() {
        seed(actor = "a", action = "rename_title", targetId = "01HXNOTE000000000000000001", targetKind = "note")
        val match =
            seed(actor = "a", action = "rename_title", targetId = "01HXNOTE000000000000000002", targetKind = "note")

        val rows =
            toolResult(call("knowledge.list_audit", mapOf("target_id" to "01HXNOTE000000000000000002")))["rows"]
        val ids = rows.map { it["id"].asText() }

        assertThat(ids).containsExactly(match.id)
    }

    @Test
    fun `list_audit clamps to a since window when an ISO-8601 timestamp is passed`() {
        val cutoff = Instant.parse("2026-05-19T12:00:00Z")
        seed(actor = "a", action = "drop_relation", at = Instant.parse("2026-05-19T11:00:00Z"))
        val newer = seed(actor = "a", action = "drop_relation", at = Instant.parse("2026-05-19T13:00:00Z"))

        val rows = toolResult(call("knowledge.list_audit", mapOf("since" to cutoff.toString())))["rows"]
        val ids = rows.map { it["id"].asText() }

        assertThat(ids).containsExactly(newer.id)
    }

    @Test
    fun `list_audit surfaces before_json and after_json verbatim`() {
        seed(
            actor = "kb-renormalise-titles",
            action = "rename_title",
            targetId = "01HXNOTE000000000000000099",
            targetKind = "note",
            beforeJson = """{"title":"a very long title that nobody can scan"}""",
            afterJson = """{"title":"scannable claim"}""",
        )

        val rows = toolResult(call("knowledge.list_audit", mapOf("limit" to 1)))["rows"]
        assertThat(rows[0]["before_json"].asText())
            .isEqualTo("""{"title":"a very long title that nobody can scan"}""")
        assertThat(rows[0]["after_json"].asText()).isEqualTo("""{"title":"scannable claim"}""")
    }

    private fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): String = rpcRaw("tools/call", mapOf("name" to name, "arguments" to arguments))

    private fun toolResult(rawJson: String) = objectMapper.readTree(rawJson)["result"]["structuredContent"]

    private fun rpcRaw(
        method: String,
        params: Map<String, Any?>?,
    ): String {
        val body =
            buildMap<String, Any?> {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", method)
                if (params != null) put("params", params)
            }
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content = objectMapper.writeValueAsBytes(body)
                }.andReturn()
        assertThat(result.response.status).isEqualTo(200)
        return result.response.contentAsString
    }

    companion object {
        // ULIDs sort lex by capture time; a small sleep separates
        // two consecutive seeds so the `most-recent-first` assertion
        // is deterministic.
        private const val LIST_SLEEP_MS = 3L
    }
}
