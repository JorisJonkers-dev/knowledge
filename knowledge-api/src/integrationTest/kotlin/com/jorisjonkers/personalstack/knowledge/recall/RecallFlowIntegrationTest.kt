@file:Suppress("DEPRECATION") // Jackson 3 asText() — see McpTools file header.

package com.jorisjonkers.personalstack.knowledge.recall

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
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

@TestPropertySource(
    properties = [
        "knowledge.mcp.tokens.ws=test-token-ws",
    ],
)
class RecallFlowIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var mcpBearerFilter: McpBearerFilter

    @Autowired
    private lateinit var noteRepository: NoteRepository

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
        title: String,
        body: String,
        scope: String = "personal",
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
                body = body,
                vaultPath = "$scope/${type.wire}/draft.md",
                vaultCommit = null,
                tags = tags,
            )
        return noteRepository.create(note)
    }

    @Test
    fun `get_note returns the seeded row with its tags`() {
        val note = seed("title", "body", tags = setOf("kotlin", "mcp"))

        val result = call("knowledge.get_note", mapOf("id" to note.id))
        val returned = objectMapper.readTree(result)["result"]["note"]

        assertThat(returned["id"].asText()).isEqualTo(note.id)
        assertThat(returned["title"].asText()).isEqualTo("title")
        val tags = returned["tags"].map { it.asText() }
        assertThat(tags).containsExactlyInAnyOrder("kotlin", "mcp")
    }

    @Test
    fun `get_note returns null when no row matches`() {
        val result = call("knowledge.get_note", mapOf("id" to Ulid.generate()))
        val note = objectMapper.readTree(result)["result"]["note"]
        assertThat(note.isNull).isTrue
    }

    @Test
    fun `list_recent returns rows in ULID-descending order`() {
        val a = seed("oldest", "x")
        Thread.sleep(LIST_SLEEP_MS)
        val b = seed("middle", "y")
        Thread.sleep(LIST_SLEEP_MS)
        val c = seed("newest", "z")

        val result = call("knowledge.list_recent", mapOf("limit" to 3, "scope" to "personal"))
        val ids = objectMapper.readTree(result)["result"]["notes"].map { it["id"].asText() }

        assertThat(ids).containsExactly(c.id, b.id, a.id)
    }

    @Test
    fun `list_recent honours the type filter`() {
        seed("lesson1", "body", type = KbNoteType.LESSON)
        seed("decision1", "body", type = KbNoteType.DECISION)
        seed("lesson2", "body", type = KbNoteType.LESSON)

        val result = call("knowledge.list_recent", mapOf("type" to "decision", "limit" to 10))
        val titles = objectMapper.readTree(result)["result"]["notes"].map { it["title"].asText() }
        assertThat(titles).containsExactly("decision1")
    }

    @Test
    fun `recall ranks the term-rich row higher and includes a snippet`() {
        val rocket = seed("rocket launch", "the rocket launched at dawn over the rocket field")
        val unrelated = seed("kitchen tips", "boil water and add salt to taste")
        seed("misc", "the dawn was beautiful, no rockets here") // sneaky single match

        val result = call("knowledge.recall", mapOf("query" to "rocket", "limit" to 5))
        val hits = objectMapper.readTree(result)["result"]["hits"]
        val ids = hits.map { it["id"].asText() }

        // The "rocket"-heavy row wins; the kitchen-tips row mustn't appear.
        assertThat(ids).contains(rocket.id)
        assertThat(ids).doesNotContain(unrelated.id)
        assertThat(hits[0]["id"].asText()).isEqualTo(rocket.id)
        assertThat(hits[0]["snippet"].asText()).contains("rocket")
        assertThat(hits[0]["score"].asDouble()).isGreaterThan(0.0)
    }

    @Test
    fun `recall scope filter restricts results`() {
        seed("personal note", "rocket science", scope = "personal")
        val work = seed("work note", "rocket science", scope = "work")

        val result = call("knowledge.recall", mapOf("query" to "rocket", "scope" to "work"))
        val ids = objectMapper.readTree(result)["result"]["hits"].map { it["id"].asText() }
        assertThat(ids).containsExactly(work.id)
    }

    @Test
    fun `recall with a blank query returns no hits without erroring`() {
        seed("anything", "rocket")
        val result = call("knowledge.recall", mapOf("query" to "  "))
        val hits = objectMapper.readTree(result)["result"]["hits"]
        assertThat(hits.size()).isZero
    }

    @Test
    fun `find_conflicts returns supersedes plus contradicts but skips mentions`() {
        val newer = seed("v2", "v2 body")
        val older = seed("v1", "v1 body")
        val unrelated = seed("loose link", "body")
        noteRepository.insertRelation(
            KbRelation(
                subjectId = newer.id,
                predicate = "supersedes",
                objectId = older.id,
                props = emptyMap(),
                createdAt = Instant.now(),
            ),
        )
        noteRepository.insertRelation(
            KbRelation(
                subjectId = older.id,
                predicate = "contradicts",
                objectId = unrelated.id,
                props = mapOf("confidence_delta" to -0.3),
                createdAt = Instant.now(),
            ),
        )
        noteRepository.insertRelation(
            KbRelation(
                subjectId = newer.id,
                predicate = "mentions",
                objectId = unrelated.id,
                props = emptyMap(),
                createdAt = Instant.now(),
            ),
        )

        val result = call("knowledge.find_conflicts", mapOf("id" to older.id))
        val rels = objectMapper.readTree(result)["result"]["relations"]
        val predicates = rels.map { it["predicate"].asText() }.toSet()

        // older shows up twice — once as object of supersedes, once as
        // subject of contradicts. mentions is filtered out.
        assertThat(predicates).containsExactlyInAnyOrder("supersedes", "contradicts")
    }

    @Test
    fun `tools_list now advertises the four read tools alongside the captures`() {
        // Exercise one read tool first to confirm Spring composed the
        // read-side registry; assert on tools/list separately.
        call("knowledge.recall", mapOf("query" to "x"))
        val names =
            objectMapper.readTree(rpcRaw("tools/list", null))["result"]["tools"].map {
                it["name"].asText()
            }
        assertThat(names).contains(
            "knowledge.recall",
            "knowledge.get_note",
            "knowledge.list_recent",
            "knowledge.find_conflicts",
            "knowledge.relations",
        )
    }

    @Test
    fun `relations walks the kb_relations graph up to the requested depth`() {
        val a = seed("root", "a")
        val b = seed("hop1", "b")
        val c = seed("hop2", "c")
        noteRepository.insertRelation(
            KbRelation(
                subjectId = a.id,
                predicate = "see_also",
                objectId = b.id,
                props = emptyMap(),
                createdAt = Instant.now(),
            ),
        )
        noteRepository.insertRelation(
            KbRelation(
                subjectId = b.id,
                predicate = "supersedes",
                objectId = c.id,
                props = emptyMap(),
                createdAt = Instant.now(),
            ),
        )

        // depth=1 reaches the direct neighbour only.
        val depth1 =
            objectMapper.readTree(call("knowledge.relations", mapOf("id" to a.id, "depth" to 1)))
        val one = depth1["result"]["relations"].map { it["object_id"].asText() }.toSet()
        assertThat(one).containsExactly(b.id)

        // depth=2 sees the b → c hop too.
        val depth2 =
            objectMapper.readTree(call("knowledge.relations", mapOf("id" to a.id, "depth" to 2)))
        val two = depth2["result"]["relations"].map { it["object_id"].asText() }.toSet()
        assertThat(two).containsExactlyInAnyOrder(b.id, c.id)
    }

    private fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): String = rpcRaw("tools/call", mapOf("name" to name, "arguments" to arguments))

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
        private const val LIST_SLEEP_MS = 3L
    }
}
