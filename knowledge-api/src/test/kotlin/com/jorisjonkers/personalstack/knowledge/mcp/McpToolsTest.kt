@file:Suppress("DEPRECATION") // Jackson 3 deprecated asText().

package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.capture.CaptureRequest
import com.jorisjonkers.personalstack.knowledge.capture.CaptureService
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.recall.RecallService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

class McpToolsTest {
    private val captureService = mockk<CaptureService>()
    private val recallService = mockk<RecallService>(relaxed = true)

    // Wire real CaptureMcpTools/ReadMcpTools around the mocked services
    // — that's what Spring does in production and what gives us coverage
    // of the descriptor builders + handler argument parsing.
    private val tools = McpTools(CaptureMcpTools(captureService), ReadMcpTools(recallService))
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val stubNote =
        KbNote(
            id = "01HXYZ00000000000000000000",
            type = KbNoteType.LESSON,
            scope = "personal",
            source = "claude-code",
            capturedAt = Instant.parse("2026-05-13T12:00:00Z"),
            sessionId = null,
            confidence = 0.4,
            title = "title",
            body = "body",
            vaultPath = "personal/lesson/draft.md",
            vaultCommit = null,
        )

    @Test
    fun `describe advertises capture and read tools by name`() {
        val names = tools.describe().map { it["name"] as String }
        assertThat(names).containsExactlyInAnyOrder(
            "knowledge.capture_lesson",
            "knowledge.capture_decision",
            "knowledge.ingest_note",
            "knowledge.recall",
            "knowledge.get_note",
            "knowledge.list_recent",
            "knowledge.find_conflicts",
        )
    }

    @Test
    fun `capture tool descriptors are valid JSON Schema with the shared required and properties`() {
        val captureNames =
            setOf("knowledge.capture_lesson", "knowledge.capture_decision", "knowledge.ingest_note")
        tools.describe().filter { it["name"] in captureNames }.forEach { descriptor ->
            val schema = descriptor["inputSchema"] as Map<*, *>
            assertThat(schema["type"]).isEqualTo("object")
            @Suppress("UNCHECKED_CAST")
            val required = schema["required"] as List<String>
            assertThat(required).containsExactlyInAnyOrder("scope", "title", "body")
            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as Map<String, Any>
            assertThat(properties.keys).contains("scope", "title", "body", "session_id", "confidence", "vault_path")
        }
    }

    @Test
    fun `read tool descriptors expose their own required and properties shape`() {
        val recall = tools.describe().single { it["name"] == "knowledge.recall" }

        @Suppress("UNCHECKED_CAST")
        val recallSchema = recall["inputSchema"] as Map<String, Any>
        assertThat(recallSchema["required"] as List<*>).containsExactly("query")
        assertThat((recallSchema["properties"] as Map<*, *>).keys).contains("query", "scope", "limit")

        val findConflicts = tools.describe().single { it["name"] == "knowledge.find_conflicts" }

        @Suppress("UNCHECKED_CAST")
        val fcSchema = findConflicts["inputSchema"] as Map<String, Any>
        assertThat(fcSchema["required"] as List<*>).containsExactly("id")
    }

    @Test
    fun `recall forwards parsed args and returns hits projection`() {
        every { recallService.recall("rockets", "personal", 5) } returns
            listOf(
                RecallHit(
                    id = "01HXY",
                    type = "lesson",
                    scope = "personal",
                    title = "t",
                    snippet = "s",
                    score = 0.42,
                ),
            )

        val out =
            tools.call(
                "knowledge.recall",
                mapper.readTree("""{"query":"rockets","scope":"personal","limit":5}"""),
            )!!

        @Suppress("UNCHECKED_CAST")
        val hits = out["hits"] as List<Map<String, Any?>>
        assertThat(hits).hasSize(1)
        assertThat(hits[0]["id"]).isEqualTo("01HXY")
        assertThat(hits[0]["score"]).isEqualTo(0.42)
    }

    @Test
    fun `get_note returns the wrapped note or a null marker`() {
        every { recallService.getNote("01HXY") } returns null
        val out = tools.call("knowledge.get_note", mapper.readTree("""{"id":"01HXY"}"""))!!
        assertThat(out["note"]).isNull()
    }

    @Test
    fun `list_recent parses optional type filter as a KbNoteType`() {
        every { recallService.listRecent(null, KbNoteType.DECISION, 7) } returns emptyList()
        tools.call(
            "knowledge.list_recent",
            mapper.readTree("""{"type":"decision","limit":7}"""),
        )
        verify(exactly = 1) { recallService.listRecent(null, KbNoteType.DECISION, 7) }
    }

    @Test
    fun `find_conflicts projects each relation to a flat map`() {
        every { recallService.findConflicts("01HXY") } returns
            listOf(
                KbRelation(
                    subjectId = "01HXY",
                    predicate = "supersedes",
                    objectId = "01ABC",
                    props = mapOf("note" to "rotation"),
                    createdAt = Instant.parse("2026-05-13T12:00:00Z"),
                ),
            )
        val out = tools.call("knowledge.find_conflicts", mapper.readTree("""{"id":"01HXY"}"""))!!

        @Suppress("UNCHECKED_CAST")
        val rels = out["relations"] as List<Map<String, Any?>>
        assertThat(rels).hasSize(1)
        assertThat(rels[0]["predicate"]).isEqualTo("supersedes")
        assertThat(rels[0]["object_id"]).isEqualTo("01ABC")
    }

    @Test
    fun `unknown tool name returns null so the controller can emit method_not_found`() {
        val args = mapper.createObjectNode()
        assertThat(tools.call("knowledge.no_such_tool", args)).isNull()
    }

    @Test
    fun `capture_lesson forwards parsed args to the service`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureLesson(capture(captured)) } returns stubNote
        val args =
            mapper.readTree(
                """
                {
                  "scope":"personal",
                  "title":"lesson title",
                  "body":"lesson body",
                  "tags":["kotlin","mcp"]
                }
                """.trimIndent(),
            )

        val result = tools.call("knowledge.capture_lesson", args)!!

        assertThat(captured.captured.scope).isEqualTo("personal")
        assertThat(captured.captured.title).isEqualTo("lesson title")
        assertThat(captured.captured.body).isEqualTo("lesson body")
        assertThat(captured.captured.tags).containsExactlyInAnyOrder("kotlin", "mcp")
        assertThat(captured.captured.source).isEqualTo("claude-code")
        assertThat(result["id"]).isEqualTo(stubNote.id)
        assertThat(result["type"]).isEqualTo("lesson")
    }

    @Test
    fun `capture_decision forwards via the decision branch of the service`() {
        every { captureService.captureDecision(any()) } returns stubNote.copy(type = KbNoteType.DECISION)
        val args =
            mapper.readTree(
                """{"scope":"work","title":"t","body":"b"}""",
            )

        tools.call("knowledge.capture_decision", args)

        verify(exactly = 1) { captureService.captureDecision(any()) }
    }

    @Test
    fun `ingest_note honours explicit type parameter`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureGenericNote(capture(captured)) } returns stubNote.copy(type = KbNoteType.FACT)

        tools.call(
            "knowledge.ingest_note",
            mapper.readTree("""{"scope":"agent:claude","title":"u","body":"v","type":"fact"}"""),
        )

        assertThat(captured.captured.type).isEqualTo(KbNoteType.FACT)
    }

    @Test
    fun `ingest_note defaults to NOTE when type is omitted`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureGenericNote(capture(captured)) } returns stubNote.copy(type = KbNoteType.NOTE)

        tools.call(
            "knowledge.ingest_note",
            mapper.readTree("""{"scope":"agent:claude","title":"u","body":"v"}"""),
        )

        assertThat(captured.captured.type).isEqualTo(KbNoteType.NOTE)
    }

    @Test
    fun `missing required field surfaces an error from the parser`() {
        val args = mapper.readTree("""{"title":"only title"}""")

        org.assertj.core.api.Assertions
            .assertThatThrownBy { tools.call("knowledge.capture_lesson", args) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("missing required field")
    }

    @Test
    fun `optional confidence is parsed when present`() {
        val captured = slot<CaptureRequest>()
        every { captureService.captureLesson(capture(captured)) } returns stubNote
        tools.call(
            "knowledge.capture_lesson",
            mapper.readTree("""{"scope":"p","title":"t","body":"b","confidence":0.85}"""),
        )
        assertThat(captured.captured.confidence).isEqualTo(0.85)
    }
}
