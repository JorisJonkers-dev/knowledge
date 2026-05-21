package com.jorisjonkers.personalstack.knowledge.web

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.repo.AuditRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant

/**
 * REST-shim coverage. Seeds rows via the same repository paths the
 * MCP-flow tests use, then drives the new `/api/v1/knowledge/...`
 * endpoints through MockMvc and asserts on the JSON wire shape.
 *
 * The McpBearerFilter is intentionally not added here — the new REST
 * paths are gated by Traefik forward-auth in production, not by the
 * bearer filter (`McpBearerFilter.shouldNotFilter` returns true for
 * any path outside `/mcp`), so a MockMvc setup without the filter
 * mirrors the real reachability surface.
 */
class KnowledgeRestIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var noteRepository: NoteRepository

    @Autowired
    private lateinit var auditRepository: AuditRepository

    private lateinit var mockMvc: MockMvc
    private val mapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
    }

    private fun seed(
        title: String,
        scope: String,
        type: KbNoteType = KbNoteType.LESSON,
        tags: Set<String> = emptySet(),
    ): KbNote {
        val note =
            KbNote(
                id = Ulid.generate(),
                type = type,
                scope = scope,
                source = "test",
                capturedAt = Instant.now(),
                sessionId = null,
                confidence = 0.4,
                title = title,
                body = "body for $title",
                vaultPath = "$scope/${type.wire}/${title.replace(' ', '-')}.md",
                vaultCommit = null,
                tags = tags,
            )
        return noteRepository.create(note)
    }

    @Test
    fun `GET recent returns notes ordered most-recent-first with the snake_case wire shape`() {
        seed("oldest", scope = "personal")
        seed("middle", scope = "personal")
        seed("newest", scope = "personal")

        val raw =
            mockMvc
                .get("/api/v1/knowledge/recent?limit=10")
                .andReturn()
                .response
                .contentAsString
        val tree = mapper.readTree(raw)
        val titles = tree["notes"].map { it["title"].asText() }
        assertThat(titles).containsExactly("newest", "middle", "oldest")
        val first = tree["notes"][0]
        // snake_case keys preserved across the projection
        assertThat(first.has("captured_at")).isTrue()
        assertThat(first.has("vault_path")).isTrue()
        assertThat(first.has("session_id")).isTrue()
    }

    @Test
    fun `GET note by id returns 404 on miss and 200 with the note payload on hit`() {
        val note = seed("present", scope = "personal", tags = setOf("kotlin", "mcp"))

        mockMvc
            .get("/api/v1/knowledge/notes/missing-id")
            .andReturn()
            .response
            .status
            .let { assertThat(it).isEqualTo(404) }

        val raw =
            mockMvc
                .get("/api/v1/knowledge/notes/${note.id}")
                .andReturn()
                .response
                .contentAsString
        val tree = mapper.readTree(raw)
        assertThat(tree["id"].asText()).isEqualTo(note.id)
        assertThat(tree["tags"].map { it.asText() }).containsExactly("kotlin", "mcp")
    }

    @Test
    fun `GET recall surfaces FTS hits and reports the resolved mode`() {
        seed("rocket launch", scope = "personal")
        seed("kitchen tips", scope = "personal")

        val raw =
            mockMvc
                .get("/api/v1/knowledge/recall?query=rocket&limit=5")
                .andReturn()
                .response
                .contentAsString
        val tree = mapper.readTree(raw)
        assertThat(tree["mode"].asText()).isIn("fast", "hybrid", "deep")
        val titles = tree["hits"].map { it["title"].asText() }
        assertThat(titles).contains("rocket launch")
        assertThat(titles).doesNotContain("kitchen tips")
    }

    @Test
    fun `GET inbox surfaces unclassified notes only`() {
        seed("inboxed", scope = "_inbox")
        seed("promoted", scope = "topic:kotlin")

        val raw =
            mockMvc
                .get("/api/v1/knowledge/inbox?limit=10")
                .andReturn()
                .response
                .contentAsString
        val tree = mapper.readTree(raw)
        val titles = tree["notes"].map { it["title"].asText() }
        assertThat(titles).containsExactly("inboxed")
    }

    @Test
    fun `GET topics returns slugs in use with counts`() {
        seed("k1", scope = "topic:kotlin")
        seed("k2", scope = "topic:kotlin")
        seed("p1", scope = "topic:postgres")

        val raw =
            mockMvc
                .get("/api/v1/knowledge/topics?limit=10")
                .andReturn()
                .response
                .contentAsString
        val tree = mapper.readTree(raw)
        val slugs = tree["topics"].map { it["slug"].asText() }
        assertThat(slugs).contains("kotlin", "postgres")
    }

    @Test
    fun `GET audit lists rows with snake_case keys`() {
        auditRepository.record(
            actor = "kb-test",
            action = "test_action",
            targetId = "target-1",
            targetKind = "note",
            beforeJson = """{"a":1}""",
            afterJson = """{"a":2}""",
        )

        val raw =
            mockMvc
                .get("/api/v1/knowledge/audit?actor=kb-test&limit=10")
                .andReturn()
                .response
                .contentAsString
        val tree = mapper.readTree(raw)
        val rows = tree["rows"]
        assertThat(rows).isNotEmpty
        val row = rows[0]
        assertThat(row["actor"].asText()).isEqualTo("kb-test")
        assertThat(row["target_id"].asText()).isEqualTo("target-1")
        assertThat(row["target_kind"].asText()).isEqualTo("note")
        assertThat(row.has("before_json")).isTrue()
        assertThat(row.has("after_json")).isTrue()
    }
}
