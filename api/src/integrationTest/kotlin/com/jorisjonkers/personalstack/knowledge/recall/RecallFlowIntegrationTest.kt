
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
class RecallFlowIntegrationTest
    @Autowired
    constructor(
        private val context: WebApplicationContext,
        private val mcpBearerFilter: McpBearerFilter,
        private val noteRepository: NoteRepository,
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

        @Test
        fun getNoteReturnsTheSeededRowWithItsTags() {
            val note = noteRepository.seedRecallNote("title", "body", tags = setOf("kotlin", "mcp"))

            val result = recallCall(mockMvc, objectMapper, "knowledge.get_note", mapOf("id" to note.id))
            val returned = recallToolResult(objectMapper, result)["note"]

            assertThat(returned["id"].asText()).isEqualTo(note.id)
            assertThat(returned["title"].asText()).isEqualTo("title")
            val tags = returned["tags"].map { it.asText() }
            assertThat(tags).containsExactlyInAnyOrder("kotlin", "mcp")
        }

        @Test
        fun getNoteReturnsNullWhenNoRowMatches() {
            val result = recallCall(mockMvc, objectMapper, "knowledge.get_note", mapOf("id" to Ulid.generate()))
            val note = recallToolResult(objectMapper, result)["note"]
            assertThat(note.isNull).isTrue
        }

        @Test
        fun listRecentReturnsRowsInULIDDescendingOrder() {
            val a = noteRepository.seedRecallNote("oldest", "x")
            Thread.sleep(LIST_SLEEP_MS)
            val b = noteRepository.seedRecallNote("middle", "y")
            Thread.sleep(LIST_SLEEP_MS)
            val c = noteRepository.seedRecallNote("newest", "z")

            val result =
                recallCall(
                    mockMvc,
                    objectMapper,
                    "knowledge.list_recent",
                    mapOf("limit" to RECENT_LIMIT, "scope" to "personal"),
                )
            val ids = recallToolResult(objectMapper, result)["notes"].map { it["id"].asText() }

            assertThat(ids).containsExactly(c.id, b.id, a.id)
        }

        @Test
        fun listRecentHonoursTheTypeFilter() {
            noteRepository.seedRecallNote("lesson1", "body", type = KbNoteType.LESSON)
            noteRepository.seedRecallNote("decision1", "body", type = KbNoteType.DECISION)
            noteRepository.seedRecallNote("lesson2", "body", type = KbNoteType.LESSON)

            val result =
                recallCall(
                    mockMvc,
                    objectMapper,
                    "knowledge.list_recent",
                    mapOf("type" to "decision", "limit" to MAX_LIMIT),
                )
            val titles = recallToolResult(objectMapper, result)["notes"].map { it["title"].asText() }
            assertThat(titles).containsExactly("decision1")
        }

        @Test
        fun recallRanksTheTermRichRowHigherAndIncludesASnippet() {
            val rocket =
                noteRepository.seedRecallNote("rocket launch", "the rocket launched at dawn over the rocket field")
            val unrelated = noteRepository.seedRecallNote("kitchen tips", "boil water and add salt to taste")
            noteRepository.seedRecallNote("misc", "the dawn was beautiful, no rockets here") // sneaky single match

            val result =
                recallCall(
                    mockMvc,
                    objectMapper,
                    "knowledge.recall",
                    mapOf("query" to "rocket", "limit" to RECALL_LIMIT),
                )
            val hits = recallToolResult(objectMapper, result)["hits"]
            val ids = hits.map { it["id"].asText() }

            // The "rocket"-heavy row wins; the kitchen-tips row mustn't appear.
            assertThat(ids).contains(rocket.id)
            assertThat(ids).doesNotContain(unrelated.id)
            assertThat(hits[0]["id"].asText()).isEqualTo(rocket.id)
            assertThat(hits[0]["snippet"].asText()).contains("rocket")
            assertThat(hits[0]["score"].asDouble()).isGreaterThan(0.0)
        }

        @Test
        fun recallScopeFilterRestrictsResults() {
            noteRepository.seedRecallNote("personal note", "rocket science", scope = "personal")
            val work = noteRepository.seedRecallNote("work note", "rocket science", scope = "work")

            val result =
                recallCall(
                    mockMvc,
                    objectMapper,
                    "knowledge.recall",
                    mapOf("query" to "rocket", "scope" to "work"),
                )
            val ids = recallToolResult(objectMapper, result)["hits"].map { it["id"].asText() }
            assertThat(ids).containsExactly(work.id)
        }

        @Test
        fun recallWithABlankQueryReturnsNoHitsWithoutErroring() {
            noteRepository.seedRecallNote("anything", "rocket")
            val result = recallCall(mockMvc, objectMapper, "knowledge.recall", mapOf("query" to "  "))
            val hits = recallToolResult(objectMapper, result)["hits"]
            assertThat(hits.size()).isZero
        }

        @Test
        fun findConflictsReturnsSupersedesPlusContradictsButSkipsMentions() {
            val newer = noteRepository.seedRecallNote("v2", "v2 body")
            val older = noteRepository.seedRecallNote("v1", "v1 body")
            val unrelated = noteRepository.seedRecallNote("loose link", "body")
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
                    props = mapOf("confidence_delta" to CONFIDENCE_DELTA),
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

            val result = recallCall(mockMvc, objectMapper, "knowledge.find_conflicts", mapOf("id" to older.id))
            val rels = recallToolResult(objectMapper, result)["relations"]
            val predicates = rels.map { it["predicate"].asText() }.toSet()

            // older shows up twice — once as object of supersedes, once as
            // subject of contradicts. mentions is filtered out.
            assertThat(predicates).containsExactlyInAnyOrder("supersedes", "contradicts")
        }

        @Test
        fun toolsListNowAdvertisesTheFourReadToolsAlongsideTheCaptures() {
            // Exercise one read tool first to confirm Spring composed the
            // read-side registry; assert on tools/list separately.
            recallCall(mockMvc, objectMapper, "knowledge.recall", mapOf("query" to "x"))
            val names =
                objectMapper.readTree(recallRpcRaw(mockMvc, objectMapper, "tools/list", null))["result"]["tools"].map {
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
        fun relationsWalksTheKbRelationsGraphUpToTheRequestedDepth() {
            val a = noteRepository.seedRecallNote("root", "a")
            val b = noteRepository.seedRecallNote("hop1", "b")
            val c = noteRepository.seedRecallNote("hop2", "c")
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
                recallToolResult(
                    objectMapper,
                    recallCall(
                        mockMvc,
                        objectMapper,
                        "knowledge.relations",
                        mapOf(
                            "id" to a.id,
                            "depth" to 1,
                        ),
                    ),
                )
            val one = depth1["relations"].map { it["object_id"].asText() }.toSet()
            assertThat(one).containsExactly(b.id)

            // depth=2 sees the b → c hop too.
            val depth2 =
                recallToolResult(
                    objectMapper,
                    recallCall(
                        mockMvc,
                        objectMapper,
                        "knowledge.relations",
                        mapOf(
                            "id" to a.id,
                            "depth" to 2,
                        ),
                    ),
                )
            val two = depth2["relations"].map { it["object_id"].asText() }.toSet()
            assertThat(two).containsExactlyInAnyOrder(b.id, c.id)
        }

        companion object {
            private const val LIST_SLEEP_MS = 3L
            private const val RECENT_LIMIT = 3
            private const val MAX_LIMIT = 10
            private const val RECALL_LIMIT = 5
            private const val CONFIDENCE_DELTA = -0.3
        }
    }

private fun NoteRepository.seedRecallNote(
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
    return create(note)
}

private fun recallCall(
    mockMvc: MockMvc,
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    name: String,
    arguments: Map<String, Any?>,
): String = recallRpcRaw(mockMvc, objectMapper, "tools/call", mapOf("name" to name, "arguments" to arguments))

private fun recallToolResult(
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    rawJson: String,
) = objectMapper.readTree(rawJson)["result"]["structuredContent"]

private fun recallRpcRaw(
    mockMvc: MockMvc,
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    method: String,
    params: Map<String, Any?>?,
): String {
    val body =
        buildMap<String, Any?> {
            put("jsonrpc", "2.0")
            put("id", "test-request")
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
