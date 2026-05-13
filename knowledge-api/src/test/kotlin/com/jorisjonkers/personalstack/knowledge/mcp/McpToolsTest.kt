@file:Suppress("DEPRECATION") // Jackson 3 deprecated asText().

package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.capture.CaptureRequest
import com.jorisjonkers.personalstack.knowledge.capture.CaptureService
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
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
    private val tools = McpTools(captureService)
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
    fun `describe advertises three tools with the expected names`() {
        val names = tools.describe().map { it["name"] as String }
        assertThat(names).containsExactlyInAnyOrder(
            "knowledge.capture_lesson",
            "knowledge.capture_decision",
            "knowledge.ingest_note",
        )
    }

    @Test
    fun `descriptors are valid JSON Schema with required and properties`() {
        tools.describe().forEach { descriptor ->
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
