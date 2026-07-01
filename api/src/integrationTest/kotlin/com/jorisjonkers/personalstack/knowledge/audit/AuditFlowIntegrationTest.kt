package com.jorisjonkers.personalstack.knowledge.audit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.repo.AuditRecordRequest
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
class AuditFlowIntegrationTest
    @Autowired
    constructor(
        private val context: WebApplicationContext,
        private val mcpBearerFilter: McpBearerFilter,
        private val auditRepository: AuditRepository,
    ) : IntegrationTestBase() {
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

        private fun seed(seed: AuditSeed) = auditRepository.record(seed.toRequest())

        @Test
        fun listAuditReturnsMostRecentFirstByDefault() {
            val older = seed(AuditSeed(actor = "kb-curator-resolver", action = "drop_relation"))
            Thread.sleep(LIST_SLEEP_MS)
            val newer = seed(AuditSeed(actor = "kb-curator-resolver", action = "resolve_relation"))

            val rows = toolResult(call("knowledge.list_audit", mapOf("limit" to 10)))["rows"]
            val ids = rows.map { it["id"].asText() }

            // ULIDs sort lex by time — `ORDER BY id DESC` is recency-first.
            assertThat(ids).containsExactly(newer.id, older.id)
        }

        @Test
        fun listAuditFiltersByActor() {
            val first = seed(AuditSeed(actor = "mcp:workstation", action = "add_topic"))
            seed(AuditSeed(actor = "kb-renormalise-titles", action = "rename_title"))

            val rows = toolResult(call("knowledge.list_audit", mapOf("actor" to "mcp:workstation")))["rows"]
            val ids = rows.map { it["id"].asText() }

            assertThat(ids).containsExactly(first.id)
        }

        @Test
        fun listAuditFiltersByAction() {
            val first = seed(AuditSeed(actor = "kb-curator-resolver", action = "resolve_relation"))
            seed(AuditSeed(actor = "kb-curator-resolver", action = "drop_relation"))

            val rows = toolResult(call("knowledge.list_audit", mapOf("action" to "resolve_relation")))["rows"]
            val ids = rows.map { it["id"].asText() }

            assertThat(ids).containsExactly(first.id)
        }

        @Test
        fun listAuditFiltersByTargetId() {
            seed(AuditSeed(actor = "a", action = "rename_title", targetId = "01HXNOTE000000000000000001"))
            val match =
                seed(AuditSeed(actor = "a", action = "rename_title", targetId = "01HXNOTE000000000000000002"))

            val rows =
                toolResult(call("knowledge.list_audit", mapOf("target_id" to "01HXNOTE000000000000000002")))["rows"]
            val ids = rows.map { it["id"].asText() }

            assertThat(ids).containsExactly(match.id)
        }

        @Test
        fun listAuditClampsToASinceWindowWhenAnIso8601TimestampIsPassed() {
            val cutoff = Instant.parse("2026-05-19T12:00:00Z")
            seed(AuditSeed(actor = "a", action = "drop_relation", at = Instant.parse("2026-05-19T11:00:00Z")))
            val newer =
                seed(AuditSeed(actor = "a", action = "drop_relation", at = Instant.parse("2026-05-19T13:00:00Z")))

            val rows = toolResult(call("knowledge.list_audit", mapOf("since" to cutoff.toString())))["rows"]
            val ids = rows.map { it["id"].asText() }

            assertThat(ids).containsExactly(newer.id)
        }

        @Test
        fun listAuditSurfacesBeforeJsonAndAfterJsonVerbatim() {
            seed(
                AuditSeed(
                    actor = "kb-renormalise-titles",
                    action = "rename_title",
                    targetId = "01HXNOTE000000000000000099",
                    beforeJson = """{"title":"a very long title that nobody can scan"}""",
                    afterJson = """{"title":"scannable claim"}""",
                ),
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
            assertThat(result.response.status).isEqualTo(
                org.springframework.http.HttpStatus.OK
                    .value(),
            )
            return result.response.contentAsString
        }

        companion object {
            // ULIDs sort lex by capture time; a small sleep separates
            // two consecutive seeds so the `most-recent-first` assertion
            // is deterministic.
            private const val LIST_SLEEP_MS = 3L
        }
    }

private data class AuditSeed(
    val actor: String,
    val action: String,
    val targetId: String? = null,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val at: Instant = Instant.now(),
) {
    fun toRequest(): AuditRecordRequest =
        AuditRecordRequest(
            actor = actor,
            action = action,
            targetId = targetId,
            targetKind = targetId?.let { "note" },
            beforeJson = beforeJson,
            afterJson = afterJson,
            now = at,
        )
}
